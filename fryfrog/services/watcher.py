"""File watcher: Linux inotify with polling fallback."""

from __future__ import annotations

import ctypes
import logging
import os
import platform
import select
import struct
import threading
import time
from pathlib import Path

from fryfrog.services.fsutil import (
    AUDIOBOOK_EXTS,
    COMIC_ARCHIVE_EXTS,
    EBOOK_EXTS,
    IMAGE_EXTS,
    MUSIC_EXTS,
    VIDEO_EXTS,
)

logger = logging.getLogger(__name__)

DEBOUNCE_SECONDS = 5.0
FALLBACK_POLL_SECONDS = 60.0
DELETE_DELAY_SECONDS = 3.0
EVENT_WAIT_SECONDS = 1.0

WATCH_EXTS = {
    "VIDEO": VIDEO_EXTS,
    "MUSIC": MUSIC_EXTS,
    "AUDIOBOOK": AUDIOBOOK_EXTS,
    "EBOOK": EBOOK_EXTS,
    "COMIC": COMIC_ARCHIVE_EXTS | IMAGE_EXTS,
}

IN_MODIFY = 0x0000_0002
IN_CLOSE_WRITE = 0x0000_0008
IN_MOVED_FROM = 0x0000_0040
IN_MOVED_TO = 0x0000_0080
IN_CREATE = 0x0000_0100
IN_DELETE = 0x0000_0200
IN_DELETE_SELF = 0x0000_0400
IN_MOVE_SELF = 0x0000_0800
IN_Q_OVERFLOW = 0x0000_4000
IN_IGNORED = 0x0000_8000
IN_ISDIR = 0x4000_0000
IN_ONLYDIR = 0x0100_0000
IN_NONBLOCK = 0x0000_0800

_WATCH_MASK = (
    IN_MODIFY | IN_CLOSE_WRITE | IN_CREATE | IN_DELETE | IN_MOVED_FROM
    | IN_MOVED_TO | IN_DELETE_SELF | IN_MOVE_SELF | IN_ONLYDIR
)
_EVENT_HEADER = struct.Struct("iIII")
_EVENT_BUFFER_SIZE = 64 * 1024


def _inotify_init() -> tuple[int, object] | None:
    if platform.system() != "Linux":
        return None
    try:
        libc = ctypes.CDLL(None, use_errno=True)
        libc.inotify_init1.argtypes = [ctypes.c_int]
        libc.inotify_init1.restype = ctypes.c_int
        libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
        libc.inotify_add_watch.restype = ctypes.c_int
        libc.inotify_rm_watch.argtypes = [ctypes.c_int, ctypes.c_int]
        libc.inotify_rm_watch.restype = ctypes.c_int
        fd = libc.inotify_init1(IN_NONBLOCK)
        if fd < 0:
            raise OSError(ctypes.get_errno(), "inotify_init1 failed")
        os.set_inheritable(fd, False)
        return fd, libc
    except Exception:
        logger.exception("inotify init failed; falling back to polling")
        return None


class FileWatcher:
    def __init__(self) -> None:
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._inventory: dict[str, dict[str, float]] = {}
        self._primed = False
        self._pending: dict[str, float] = {}
        self._pending_delete: dict[str, float] = {}
        self._event: tuple[int, object] | None = _inotify_init()
        self._watch_paths: dict[int, str] = {}
        self._event_roots: set[str] = set()
        self._next_poll = 0.0
        # 正在执行的扫描数：>0 期间的文件事件视为扫描自身写入，忽略
        self._scanning = 0
        # watch 注册失败只告警一次：配额耗尽时每个目录都会失败，逐条 warning
        # 会刷爆日志；但不能降到 debug——inotify 模式没有轮询兜底，
        # 配额耗尽意味着超出部分从此失明，运维必须能在日志里看到原因
        self._watch_fail_warned = False

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True, name="file-watcher")
        self._thread.start()
        mode = "inotify + polling fallback" if self._event else "polling"
        logger.info("文件热监听已启动（%s）", mode)

    def stop(self) -> None:
        self._stop.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3.0)
        self._close_event()

    def _close_event(self) -> None:
        if self._event is not None:
            fd, _ = self._event
            try:
                os.close(fd)
            except OSError:
                pass
        self._event = None
        self._watch_paths.clear()
        self._event_roots.clear()

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self._tick()
                self._wait_for_events(EVENT_WAIT_SECONDS)
            except Exception:
                logger.exception("file watcher failed")
                self._stop.wait(EVENT_WAIT_SECONDS)

    def begin_scan(self) -> None:
        """扫描开始：这期间产生的文件事件多半是扫描自己写封面/NFO 造成的。

        不做这个隔离会形成自触发死循环——扫描写素材 → inotify 事件 → 去抖后
        再扫，实测每 ~54 秒就跑一轮全库（105 轮/2 小时、4500+ 次 TMDB 请求）。
        """
        self._scanning += 1

    def end_scan(self) -> None:
        """扫描结束：丢弃扫描期间的事件积压，只对之后的新事件做去抖。"""
        self._scanning = max(0, self._scanning - 1)
        if self._scanning == 0 and self._event is not None:
            self._drain_events()

    def _drain_events(self) -> None:
        if self._event is None:
            return
        fd, _ = self._event
        dropped = 0
        while True:
            try:
                data = os.read(fd, _EVENT_BUFFER_SIZE)
            except (BlockingIOError, OSError):
                break
            if not data:
                break
            dropped += len(data)
        if dropped:
            logger.info("丢弃扫描自身产生的事件 %d 字节", dropped)

    def _wait_for_events(self, timeout: float) -> None:
        if self._event is None:
            self._stop.wait(timeout)
            return
        fd, _ = self._event
        ready, _, _ = select.select([fd], [], [], timeout)
        if not ready:
            return
        try:
            data = os.read(fd, _EVENT_BUFFER_SIZE)
        except BlockingIOError:
            return
        if not data:
            return
        offset = 0
        while offset + _EVENT_HEADER.size <= len(data):
            wd, mask, _cookie, name_len = _EVENT_HEADER.unpack_from(data, offset)
            offset += _EVENT_HEADER.size
            name_bytes = data[offset : offset + name_len]
            offset += name_len
            name = name_bytes.split(b"\0", 1)[0].decode("utf-8", errors="replace")
            self._handle_event(wd, mask, name)

    def _tick(self) -> None:
        from fryfrog.db import get_session_factory
        from fryfrog.core.security import UserService
        from fryfrog.services.media_library import MediaLibraryService

        session = get_session_factory()()
        try:
            service = MediaLibraryService(UserService())
            libs = service.get_enabled_libraries(session)
            now = time.time()
            seen_roots: set[str] = set()
            for lib in libs:
                root = lib.path
                seen_roots.add(root)
                # inotify 可用时不做全树遍历：事件已经覆盖增/改/删/移动，
                # 再每秒 rglob+stat 一遍纯属白烧 CPU（5000 文件约 70ms/次）
                if self._event is not None:
                    continue
                exts = WATCH_EXTS.get((lib.type or "").upper())
                if not exts or not Path(root).is_dir():
                    continue
                current = self._scan_root(Path(root), exts)
                prev = self._inventory.get(root, {})
                if not self._primed:
                    self._inventory[root] = current
                    continue
                added = [p for p in current if p not in prev]
                removed = [p for p in prev if p not in current]
                self._inventory[root] = current
                if added:
                    self._pending[root] = now + DEBOUNCE_SECONDS
                if removed:
                    self._pending_delete[root] = now + DELETE_DELAY_SECONDS

            if self._event is not None:
                self._sync_event_roots(seen_roots)
            for root, ready in list(self._pending.items()):
                if now >= ready:
                    self._pending.pop(root, None)
                    self._fire_scan(session, root)
            for root, ready in list(self._pending_delete.items()):
                if now >= ready:
                    self._pending_delete.pop(root, None)
                    self._fire_scan(session, root)

            for root in list(self._inventory):
                if root not in seen_roots:
                    self._inventory.pop(root, None)
            session.commit()
            self._primed = True
        except Exception:
            session.rollback()
            raise
        finally:
            session.close()

    def _scan_root(self, root: Path, exts: set[str]) -> dict[str, float]:
        result: dict[str, float] = {}
        try:
            for path in root.rglob("*"):
                if not path.is_file() or path.name.startswith("."):
                    continue
                if path.suffix.lower() not in exts:
                    continue
                try:
                    result[str(path)] = path.stat().st_mtime
                except OSError:
                    continue
        except OSError:
            return result
        return result

    def _sync_event_roots(self, roots: set[str]) -> None:
        if self._event is None:
            return
        for root in roots - self._event_roots:
            self._add_tree(Path(root))
        for root in self._event_roots - roots:
            self._remove_tree(Path(root))
        self._event_roots = set(roots)

    def _add_watch(self, path: Path) -> None:
        if self._event is None:
            return
        fd, libc = self._event
        try:
            wd = libc.inotify_add_watch(fd, os.fsencode(str(path)), _WATCH_MASK)
            if wd < 0:
                raise OSError(ctypes.get_errno(), f"inotify_add_watch failed: {path}")
            self._watch_paths[wd] = str(path)
            self._watch_fail_warned = False
        except Exception:
            if not self._watch_fail_warned:
                self._watch_fail_warned = True
                logger.warning(
                    "inotify watch 注册失败: %s（常见原因：fs.inotify.max_user_watches "
                    "配额耗尽，超出部分目录将不再触发热扫描，且 inotify 模式无轮询兜底；"
                    "可调大内核配额或设 WATCHER_ENABLED=false 改用周期扫描）",
                    path,
                    exc_info=True,
                )
            else:
                logger.debug("inotify watch failed: %s", path, exc_info=True)

    def _remove_watch(self, wd: int) -> None:
        if self._event is None:
            return
        fd, libc = self._event
        path = self._watch_paths.pop(wd, None)
        try:
            libc.inotify_rm_watch(fd, wd)
        except Exception:
            logger.debug("inotify rm watch failed: %s", path, exc_info=True)

    def _add_tree(self, root: Path) -> None:
        self._add_watch(root)
        try:
            for parent, dirs, _files in os.walk(root, topdown=True, followlinks=False):
                for name in list(dirs):
                    if name.startswith("."):
                        dirs.remove(name)
                for name in dirs:
                    self._add_watch(Path(parent) / name)
        except OSError:
            logger.debug("inotify tree walk failed: %s", root, exc_info=True)

    def _remove_tree(self, root: Path) -> None:
        prefix = str(root).rstrip(os.sep) + os.sep
        for wd, path in list(self._watch_paths.items()):
            if path == str(root) or path.startswith(prefix):
                self._remove_watch(wd)

    def _handle_event(self, wd: int, mask: int, name: str) -> None:
        now = time.time()
        if self._scanning:
            # 扫描期间的写入是扫描自己造成的，直接忽略（否则自触发死循环）
            return
        if mask & IN_Q_OVERFLOW:
            for root in self._event_roots:
                self._pending[root] = now + DEBOUNCE_SECONDS
            return
        watch_path = self._watch_paths.get(wd)
        if mask & IN_IGNORED:
            # wd 已失效。不能无条件 _remove_watch：inotify_rm_watch 产生的
            # IN_IGNORED 排在事件队列尾部，期间 wd 可能已被内核复用指向新
            # 目录（把目录移出再移入即可复现）——无条件删除会误杀新 watch，
            # 新目录子树从此失明。只在路径已不存在（目录被删而我们没处理到
            # delete 事件，如扫描期间被丢弃）时清理这条映射。
            if watch_path and not Path(watch_path).exists():
                self._remove_watch(wd)
            return
        if not watch_path:
            return
        root = next(
            (
                candidate
                for candidate in self._event_roots
                if watch_path == candidate or watch_path.startswith(candidate.rstrip(os.sep) + os.sep)
            ),
            None,
        )
        if root is None:
            return
        path = Path(watch_path) / name if name else Path(watch_path)
        if mask & IN_ISDIR:
            if mask & (IN_CREATE | IN_MOVED_TO):
                self._add_tree(path)
            if mask & (IN_DELETE | IN_MOVED_FROM | IN_DELETE_SELF | IN_MOVE_SELF):
                self._remove_tree(path)
        if mask & (IN_MODIFY | IN_CLOSE_WRITE | IN_CREATE | IN_MOVED_TO):
            self._pending[root] = now + DEBOUNCE_SECONDS
        if mask & (IN_DELETE | IN_MOVED_FROM | IN_DELETE_SELF | IN_MOVE_SELF):
            self._pending_delete[root] = now + DELETE_DELAY_SECONDS

    def _fire_scan(self, session, root: str) -> None:
        from fryfrog.services.media_library import MediaLibraryService
        from fryfrog.core.security import UserService
        from fryfrog.services.scan import scan_library

        service = MediaLibraryService(UserService())
        lib = service.find_by_path(session, root)
        if lib is None:
            return
        logger.info("[FileWatcher] 触发扫描: %s (%s)", lib.name, lib.type)
        self.begin_scan()
        try:
            scan_library(session, lib)
        except Exception:
            logger.exception("热监听扫描失败: %s", root)
        finally:
            self.end_scan()


_watcher: FileWatcher | None = None


def get_file_watcher() -> FileWatcher:
    global _watcher
    if _watcher is None:
        _watcher = FileWatcher()
    return _watcher


def start_file_watcher() -> None:
    get_file_watcher().start()


def stop_file_watcher() -> None:
    get_file_watcher().stop()
