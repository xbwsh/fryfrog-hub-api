"""热监听：inotify 可用时不空转全树遍历，不可用时退回轮询且仍能发现增删。

背景：在 Docker（Linux）里 inotify 可用，若仍每秒 rglob+stat 一遍全库，
5000 文件的库就要白烧约 70ms/秒。
"""

from __future__ import annotations

import os
import time

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.services import scan as scan_svc, watcher
from fryfrog.services.media_library import MediaLibraryService


class _FakeService:
    def __init__(self, db, lib):
        self.db = db
        self.lib = lib

    def get_enabled_libraries(self, db):
        return [self.lib]

    def find_by_path(self, db, path):
        return self.lib if str(path) == self.lib.path else None


def _setup(tmp_path, monkeypatch):
    monkeypatch.setattr(watcher, "WATCH_EXTS", {"VIDEO": {".mp4"}})
    monkeypatch.setattr(watcher.time, "time", lambda: 1000.0)

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="剧集", path=str(tmp_path), type="VIDEO", enable_scraping=False)
    db.add(lib)
    db.flush()

    fired: list[str] = []
    monkeypatch.setattr("fryfrog.db.get_session_factory", lambda: sessionmaker(bind=engine))
    monkeypatch.setattr("fryfrog.core.security.UserService", lambda *a, **k: object())
    monkeypatch.setattr(
        "fryfrog.services.media_library.MediaLibraryService",
        lambda *a, **k: _FakeService(db, lib),
    )
    monkeypatch.setattr(
        "fryfrog.services.scan.scan_library", lambda db, library: fired.append(library.path)
    )
    assert MediaLibraryService  # 保持引用，避免被当成未使用导入
    return db, lib, fired


def test_inotify_mode_skips_full_tree_walk(tmp_path, monkeypatch):
    """inotify 可用：_tick 不做全树遍历，只同步监听目录。"""
    db, lib, _ = _setup(tmp_path, monkeypatch)
    (tmp_path / "a.mp4").write_bytes(b"x")

    walks: list[Path] = []
    worker = watcher.FileWatcher()
    monkeypatch.setattr(worker, "_scan_root", lambda root, exts: walks.append(root) or {})
    worker._event = (123, None)  # 假装 inotify 可用
    monkeypatch.setattr(worker, "_sync_event_roots", lambda roots: None)

    for _ in range(5):
        worker._tick()

    assert walks == [], "inotify 模式下不该再全树遍历"
    assert worker._inventory == {}, "也不该维护轮询清单"


def test_polling_mode_detects_added_and_removed(tmp_path, monkeypatch):
    """inotify 不可用（网络盘/Windows）：退回轮询，仍能发现新增与删除。"""
    db, lib, fired = _setup(tmp_path, monkeypatch)
    (tmp_path / "a.mp4").write_bytes(b"x")

    worker = watcher.FileWatcher()
    worker._event = None
    worker._tick()  # 首轮只建基线，不触发
    assert fired == []

    (tmp_path / "b.mp4").write_bytes(b"x")
    worker._tick()
    assert fired == [], "要等 debounce 时间到"

    monkeypatch.setattr(watcher.time, "time", lambda: 1000.0 + watcher.DEBOUNCE_SECONDS + 1)
    worker._tick()
    assert fired == [lib.path]

    fired.clear()
    (tmp_path / "b.mp4").unlink()
    worker._tick()
    monkeypatch.setattr(
        watcher.time, "time", lambda: 1000.0 + watcher.DEBOUNCE_SECONDS + watcher.DELETE_DELAY_SECONDS + 2
    )
    worker._tick()
    assert fired == [lib.path], "删除也要触发重扫（由扫描侧做延迟确认）"
    assert Path(tmp_path).is_dir()
    assert time.time() > 0
