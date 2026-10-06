"""客户端扫描进度契约。

Flutter 客户端（fryfrog-hub-android）这样用扫描接口：
- `POST /media-libraries/scan` → 每 1 秒轮询 `GET /media-libraries/scan/progress`，
  连续 15 次拿到空列表就报「扫描状态获取超时」并停止轮询；
- `POST /media-libraries/{id}/scan` → 每秒轮询 `GET /{id}/pipeline-progress`，
  `running == false` 即判定完成。

所以后端必须：立即返回（客户端超时 15s）、并且真的写进度。
"""

from __future__ import annotations

import os
import time

os.environ.setdefault("AUTH_ENABLED", "false")

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from fryfrog.core.security import UserService
from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.services import progress as progress_svc
from fryfrog.services import scan as scan_svc
from fryfrog.services.media_library import MediaLibraryService

progress_svc._store.clear()
# 扫描占位是进程级全局状态，测试之间必须清掉（每例都建 id 相同的库）
scan_svc._running_scans.clear()


class _FakeProbe:
    def probe_video_duration(self, path):
        return None

    def probe_video_resolution(self, path):
        return None


def _setup(tmp_path, monkeypatch):
    progress_svc._store.clear()
    scan_svc._running_scans.clear()
    monkeypatch.setattr("fryfrog.services.video_scan.get_media_probe", lambda: _FakeProbe())
    media = tmp_path / "media"
    media.mkdir()
    (media / "a.mp4").write_bytes(b"x")

    # StaticPool：让后台线程里的 session 共用同一个内存库（默认每线程一个空库）
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine)
    # 后台任务的线程走全局 session 工厂，指向同一个内存库
    monkeypatch.setattr("fryfrog.db.get_session_factory", lambda: factory)

    db = factory()
    lib = MediaLibrary(
        name="剧集",
        path=str(media),
        type="VIDEO",
        enabled=True,
        enable_scraping=False,
    )
    db.add(lib)
    db.commit()
    return db, lib


def _join_scan_threads(timeout: float = 10.0) -> None:
    import threading

    for thread in threading.enumerate():
        if thread.name.startswith("Thread-"):
            thread.join(timeout=timeout)


def test_scan_all_reports_progress_and_finishes(tmp_path, monkeypatch):
    db, lib = _setup(tmp_path, monkeypatch)

    scan_svc.submit_scan_job([lib.id])  # 对应 POST /media-libraries/scan

    # 客户端第一秒就能拿到非空列表（否则 15 秒后报超时）
    items = progress_svc.get_scrape_progress()
    assert items, "扫描开始后 /scan/progress 不能是空列表"
    assert {"running", "total", "completed", "failed", "skipped", "currentItem"} <= set(items[0])

    _join_scan_threads()

    items = progress_svc.get_scrape_progress()
    assert items and items[0]["running"] is False, "扫完必须收尾，否则客户端一直显示扫描中"
    assert items[0]["stage"] == "done"


def test_scan_one_pipeline_progress_reports_running_then_done(tmp_path, monkeypatch):
    db, lib = _setup(tmp_path, monkeypatch)
    service = MediaLibraryService(UserService())
    library = service.get_library_by_id(db, lib.id)

    scan_svc.scan_library(db, library)  # 对应 POST /media-libraries/{id}/scan

    pipeline = progress_svc.get_pipeline_progress(library)
    assert pipeline["running"] is False
    assert pipeline["stage"] == "done", "客户端靠 stage/running 判断扫描结束"
    assert pipeline["scanPercent"] == 100.0


class _SettingsOverride:
    """把真实 settings 包一层，只覆盖指定字段（避免替换整个 get_settings）。"""

    def __init__(self, **overrides):
        self._overrides = overrides

    def __getattr__(self, name):
        if name in self._overrides:
            return self._overrides[name]
        from fryfrog.config import get_settings

        return getattr(get_settings(), name)


def test_stuck_scan_does_not_block_forever(tmp_path, monkeypatch):
    """卡死的扫描不能让该库永久不可扫（网络卡死自愈）。"""
    db, lib = _setup(tmp_path, monkeypatch)
    service = MediaLibraryService(UserService())
    library = service.get_library_by_id(db, lib.id)

    monkeypatch.setattr(
        "fryfrog.config.get_settings", lambda: _SettingsOverride(scan_stale_after_seconds=60)
    )
    monkeypatch.setattr(scan_svc, "_scan_library", lambda session, target: None)
    # 模拟一个卡住很久的占位（超过 scan_stale_after_seconds）
    scan_svc._running_scans[lib.id] = time.monotonic() - 7200

    scan_svc.scan_library(db, library)

    assert progress_svc.get_pipeline_progress(library)["stage"] == "done", (
        "超时后必须能重新扫描，而不是被静默跳过"
    )
    assert lib.id not in scan_svc._running_scans, "跑完要释放占位"


def test_running_scan_makes_new_trigger_skip(tmp_path, monkeypatch):
    """扫描进行中：同一库的新触发被护栏挡掉。"""
    db, lib = _setup(tmp_path, monkeypatch)
    service = MediaLibraryService(UserService())
    library = service.get_library_by_id(db, lib.id)

    assert scan_svc._claim_library_scan(library) is True
    assert scan_svc._claim_library_scan(library) is False, "未超时的占位应拒绝重复触发"
    assert lib.id in scan_svc._running_scans

    scan_svc._running_scans.pop(lib.id, None)
    assert scan_svc._claim_library_scan(library) is True, "释放后应能再次触发"


def test_scan_progress_is_visible_to_client_polling(tmp_path, monkeypatch):
    """客户端轮询期间的中间态：running=True、stage=scan。"""
    db, lib = _setup(tmp_path, monkeypatch)
    service = MediaLibraryService(UserService())
    library = service.get_library_by_id(db, lib.id)

    seen: list[dict] = []
    original = scan_svc._scan_library

    def spy(session, target):
        seen.append(dict(progress_svc._store[scan_svc.scan_progress_module(target)]))
        return original(session, target)

    monkeypatch.setattr(scan_svc, "_scan_library", spy)
    scan_svc.scan_library(db, library)

    assert seen, "扫描过程中没有任何进度写入"
    mid = seen[0]
    assert mid["running"] is True
    assert mid["stage"] == "scan"
    assert mid["total"] == 1
