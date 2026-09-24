"""文件热监听：轮询检测媒体库新/删文件，debounce 后触发目录扫描。"""

from __future__ import annotations

import logging
import threading
import time
from pathlib import Path

from fryfrog.core.natural_order import natural_compare
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
POLL_SECONDS = 3.0
DELETE_DELAY_SECONDS = 3.0

WATCH_EXTS = {
    "VIDEO": VIDEO_EXTS,
    "MUSIC": MUSIC_EXTS,
    "AUDIOBOOK": AUDIOBOOK_EXTS,
    "EBOOK": EBOOK_EXTS,
    "COMIC": COMIC_ARCHIVE_EXTS | IMAGE_EXTS,
}


class FileWatcher:
    def __init__(self) -> None:
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._inventory: dict[str, dict[str, float]] = {}  # root -> path -> mtime
        self._pending: dict[str, float] = {}  # root -> ready_at
        self._pending_delete: dict[str, float] = {}

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True, name="file-watcher")
        self._thread.start()
        logger.info("文件热监听已启动")

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self._tick()
            except Exception:
                logger.exception("文件监听轮询失败")
            self._stop.wait(POLL_SECONDS)

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
                kind = (lib.type or "").upper()
                exts = WATCH_EXTS.get(kind)
                if not exts or not Path(root).is_dir():
                    continue
                current = self._scan_root(Path(root), exts)
                prev = self._inventory.get(root, {})
                added = [p for p in current if p not in prev]
                removed = [p for p in prev if p not in current]
                self._inventory[root] = current
                if added:
                    self._pending[root] = now + DEBOUNCE_SECONDS
                if removed:
                    self._pending_delete[root] = now + DELETE_DELAY_SECONDS

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
        except Exception:
            session.rollback()
        finally:
            session.close()

    def _scan_root(self, root: Path, exts: set[str]) -> dict[str, float]:
        result: dict[str, float] = {}
        try:
            for p in root.rglob("*"):
                if not p.is_file() or p.name.startswith("."):
                    continue
                if p.suffix.lower() not in exts:
                    continue
                try:
                    result[str(p)] = p.stat().st_mtime
                except OSError:
                    continue
        except OSError:
            return result
        return result

    def _fire_scan(self, session, root: str) -> None:
        from fryfrog.services.media_library import MediaLibraryService
        from fryfrog.core.security import UserService
        from fryfrog.services.scan import scan_library

        service = MediaLibraryService(UserService())
        lib = service.find_by_path(session, root)
        if lib is None:
            return
        logger.info("[FileWatcher] 触发扫描: %s (%s)", lib.name, lib.type)
        try:
            scan_library(session, lib)
        except Exception:
            logger.exception("热监听扫描失败: %s", root)


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
