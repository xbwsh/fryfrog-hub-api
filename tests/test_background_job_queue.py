from threading import Event
from types import SimpleNamespace

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
    monkeypatch.setattr(background_jobs, "get_settings", lambda: SimpleNamespace(background_job_workers=1))
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
