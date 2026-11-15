from __future__ import annotations

from threading import Lock
from time import monotonic
from typing import Any

from fryfrog.config import get_settings
from fryfrog.models.library import MediaLibrary

# 简单内存进度（与 Java ScrapeProgressService 对齐）
_store: dict[str, dict[str, Any]] = {}
_lock = Lock()


def _expire_locked(now: float) -> None:
    expired = [
        module for module, item in _store.items()
        if item.get("_expires_at", float("inf")) <= now
    ]
    for module in expired:
        del _store[module]


def cleanup_expired_progress() -> None:
    with _lock:
        _expire_locked(monotonic())


def update_progress(module: str, **kwargs: Any) -> None:
    with _lock:
        _update_locked(module, **kwargs)


def _update_locked(module: str, **kwargs: Any) -> None:
    item = _store.setdefault(
        module,
        {
            "module": module,
            "stage": "idle",
            "running": False,
            "total": 0,
            "completed": 0,
            "failed": 0,
            "skipped": 0,
            "startedAt": None,
            "updatedAt": None,
            "currentItem": None,
            "items": [],
        },
    )
    item.update(kwargs)
    if not item["running"] and item["stage"] in {"done", "error"}:
        if "_expires_at" not in item:
            item["_expires_at"] = monotonic() + get_settings().task_progress_retention_seconds
    else:
        item.pop("_expires_at", None)
    total = item.get("total") or 0
    completed = item.get("completed") or 0
    item["pending"] = max(total - completed, 0)
    item["percent"] = round((completed / total) * 100, 1) if total else 0.0


def get_scrape_progress(library_id: int | None = None) -> list[dict]:
    with _lock:
        _expire_locked(monotonic())
        return [
            {key: value for key, value in item.items() if key != "_expires_at"}
            for item in _store.values()
            if library_id is None or item.get("libraryId") in (None, library_id)
        ]


def get_pipeline_progress(library: MediaLibrary) -> dict:
    key = f"pipeline:{library.id}"
    with _lock:
        _expire_locked(monotonic())
        item = _store.get(key, {}).copy()
        scan = _store.get(f"scan:{library.type}:{library.id}", {}).copy()
        scrape = _store.get(f"scrape:{library.type}:{library.id}", {}).copy()
    scan_pct = 0.0 if item.get("queued") else scan.get("percent") or 0.0
    scrape_pct = 0.0 if item.get("queued") else scrape.get("percent") or 0.0
    result = {
        "libraryId": library.id,
        "stage": item.get("stage", "idle"),
        "running": bool(item.get("running") or scan.get("running") or scrape.get("running")),
        "percent": round((scan_pct + scrape_pct) / 2, 1) if library.enable_scraping else scan_pct,
        "currentItem": item.get("currentItem"),
        "scrapingEnabled": bool(library.enable_scraping),
        "scanPercent": scan_pct,
        "scrapePercent": scrape_pct,
    }
    if item.get("message"):
        result["message"] = item["message"]
    return result
