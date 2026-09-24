from __future__ import annotations

from typing import Any

from sqlalchemy.orm import Session

from fryfrog.models.library import MediaLibrary

# 简单内存进度（与 Java ScrapeProgressService 对齐）
_store: dict[str, dict[str, Any]] = {}


def update_progress(module: str, **kwargs: Any) -> None:
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
    total = item.get("total") or 0
    completed = item.get("completed") or 0
    item["pending"] = max(total - completed, 0)
    item["percent"] = round((completed / total) * 100, 1) if total else 0.0


def get_scrape_progress(library_id: int | None = None) -> list[dict]:
    items = list(_store.values())
    if library_id is not None:
        items = [i for i in items if i.get("libraryId") in (None, library_id)]
    return items


def get_pipeline_progress(library: MediaLibrary) -> dict:
    key = f"pipeline:{library.id}"
    item = _store.get(key, {})
    scan = _store.get(f"scan:{library.type}:{library.id}", {})
    scrape = _store.get(f"scrape:{library.type}:{library.id}", {})
    scan_pct = scan.get("percent") or 0.0
    scrape_pct = scrape.get("percent") or 0.0
    return {
        "libraryId": library.id,
        "stage": item.get("stage", "idle"),
        "running": bool(item.get("running") or scan.get("running") or scrape.get("running")),
        "percent": round((scan_pct + scrape_pct) / 2, 1) if library.enable_scraping else scan_pct,
        "currentItem": item.get("currentItem"),
        "scrapingEnabled": bool(library.enable_scraping),
        "scanPercent": scan_pct,
        "scrapePercent": scrape_pct,
    }
