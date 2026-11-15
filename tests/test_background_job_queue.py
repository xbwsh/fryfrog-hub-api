from threading import Event
from types import SimpleNamespace

import pytest

from fryfrog.routers import media_libraries
from fryfrog.routers.video import _common
from fryfrog.services import background_jobs, progress, scan


class _Session:
    def get(self, model, id):
        return SimpleNamespace(id=id)

    def commit(self):
        pass

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def test_video_jobs_and_scans_share_worker_limit_and_deduplicate(monkeypatch):
    monkeypatch.setattr(background_jobs, "_executor", None)
    monkeypatch.setattr(background_jobs, "_pending", set())
    monkeypatch.setattr(background_jobs, "get_settings", lambda: SimpleNamespace(
        background_job_workers=1, background_job_queue_limit=4,
    ))
    monkeypatch.setattr(progress, "_store", {})
    monkeypatch.setattr("fryfrog.db.get_session_factory", lambda: _Session)
    started = Event()
    release = Event()
    calls = []

    def refresh(_):
        calls.append("rescrape:1")
        progress.update_progress("rescrape:1", completed=1)
        started.set()
        assert release.wait(5)

    def scan_one(_, library):
        calls.append(f"scan:{library.id}")
        progress.update_progress(f"pipeline:{library.id}", stage="done", running=False, queued=False)

    monkeypatch.setattr(scan, "scan_library", scan_one)
    try:
        assert _common.submit_job("rescrape:1", "rescrape", 2, refresh)
        assert started.wait(5)
        assert not _common.submit_job("rescrape:1", "rescrape", 9, refresh)
        assert progress._store["rescrape:1"]["completed"] == 1
        assert progress._store["rescrape:1"]["total"] == 2

        progress.update_progress("scan:VIDEO:2", stage="done", total=1, completed=1)
        scan.submit_scan_job([1, 2])
        scan.submit_scan_job([2, 3])
        assert _common.submit_job("nfo:1", "nfo", 1, lambda _: calls.append("nfo:1"))
        assert not _common.submit_job("nfo:1", "nfo", 1, lambda _: calls.append("duplicate"))
        assert calls == ["rescrape:1"]
        queued = progress.get_pipeline_progress(SimpleNamespace(id=2, type="VIDEO", enable_scraping=False))
        assert queued["running"]
        assert queued["percent"] == 0
    finally:
        release.set()
        if background_jobs._executor is not None:
            background_jobs._executor.shutdown(wait=True)

    assert calls == ["rescrape:1", "scan:1", "scan:2", "scan:3", "nfo:1"]
    assert background_jobs._pending == set()


def test_full_queue_rejects_entire_batch_and_accepts_retry_after_drain(monkeypatch):
    monkeypatch.setattr(background_jobs, "_executor", None)
    monkeypatch.setattr(background_jobs, "_pending", set())
    monkeypatch.setattr(background_jobs, "get_settings", lambda: SimpleNamespace(
        background_job_workers=1, background_job_queue_limit=1,
    ))
    monkeypatch.setattr(progress, "_store", {})
    monkeypatch.setattr("fryfrog.db.get_session_factory", lambda: _Session)
    monkeypatch.setattr(scan, "scan_library", lambda _, lib: progress.update_progress(
        f"pipeline:{lib.id}", stage="done", running=False, queued=False,
    ))
    started, release = Event(), Event()
    service = SimpleNamespace(get_enabled_libraries=lambda _: [SimpleNamespace(id=1), SimpleNamespace(id=2)])

    def block(_):
        started.set()
        assert release.wait(5)

    try:
        assert _common.submit_job("rescrape:3", "rescrape", 1, block)
        assert started.wait(5)
        with pytest.raises(background_jobs.JobQueueFull) as error:
            media_libraries.scan_all(None, service)
        assert error.value.status_code == 503
        assert error.value.headers == {"Retry-After": "5"}
        assert error.value.detail == "后台任务队列已满，请稍后重试"
        assert set(background_jobs._pending) == {"rescrape:3"}
        assert "pipeline:1" not in progress._store and "pipeline:2" not in progress._store

        assert scan.submit_scan_job([1, 2], allow_partial=True) == [1]
        assert scan.submit_scan_job([1]) == []
        with pytest.raises(background_jobs.JobQueueFull):
            _common.submit_job("nfo:1", "nfo", 1, lambda _: None)
        assert "nfo:1" not in progress._store

        release.set()
        # 单 worker 的屏障任务保证前面的任务已释放去重键和容量。
        background_jobs._executor.submit(lambda: None).result(timeout=5)
        with pytest.raises(background_jobs.JobQueueFull, match="请分批提交"):
            scan.submit_scan_job([1, 2, 3])
        assert background_jobs._pending == set()
        assert media_libraries.scan_all(None, service).success
    finally:
        release.set()
        if background_jobs._executor is not None:
            background_jobs._executor.shutdown(wait=True)


def test_saved_library_reports_that_auto_scan_could_not_be_queued(monkeypatch):
    operations = []
    monkeypatch.setattr("fryfrog.core.deps.commit_with_retry", lambda _: operations.append("commit"))

    def reject(ids):
        operations.append(ids)
        raise background_jobs.JobQueueFull()

    monkeypatch.setattr(scan, "submit_scan_job", reject)
    message = media_libraries._schedule_scan_after_commit(None, 1)
    assert operations == ["commit", [1]]
    assert message == "媒体库已保存，后台任务队列已满，请稍后手动扫描"
