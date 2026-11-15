from types import SimpleNamespace

from fryfrog.services import progress


def test_finished_progress_expires_and_active_progress_survives(monkeypatch):
    now = 100.0
    monkeypatch.setattr(progress, "monotonic", lambda: now)
    monkeypatch.setattr(progress, "get_settings", lambda: SimpleNamespace(task_progress_retention_seconds=10))
    monkeypatch.setattr(progress, "_store", {})
    library = SimpleNamespace(id=1, type="VIDEO", enable_scraping=False)
    progress.update_progress("pipeline:1", stage="error", running=False, message="扫描失败")
    progress.update_progress("scan:VIDEO:1", stage="error", running=False, total=1, failed=1)
    progress.update_progress("bind:10", stage="done", running=False)
    progress.update_progress("scan:VIDEO:2", stage="scan", running=True)
    progress.update_progress("pipeline:3", stage="scan", running=True, queued=True)

    now = 109
    assert progress.get_pipeline_progress(library)["message"] == "扫描失败"
    items = {item["module"]: item for item in progress.get_scrape_progress()}
    assert items["bind:10"]["stage"] == "done"
    assert "_expires_at" not in items["bind:10"]

    progress.update_progress("bind:10", stage="bind", running=True)
    now = 110
    progress.cleanup_expired_progress()
    assert set(progress._store) == {"bind:10", "scan:VIDEO:2", "pipeline:3"}
    assert progress.get_pipeline_progress(library)["stage"] == "idle"

    progress.update_progress("bind:10", stage="done", running=False)
    now = 120
    assert {item["module"] for item in progress.get_scrape_progress()} == {"scan:VIDEO:2", "pipeline:3"}
