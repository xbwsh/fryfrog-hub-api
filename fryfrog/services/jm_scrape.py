"""漫画刮削：JM（18comic）provider，依赖可选库 jmcomic（pip install jmcomic）。"""

from __future__ import annotations

import logging
from typing import Any

from sqlalchemy.orm import Session

from fryfrog.core.exceptions import BadRequestException, ResourceNotFoundException
from fryfrog.models.comic import Comic

logger = logging.getLogger(__name__)

SOURCE = "jm"


def _load():
    try:
        import jmcomic  # noqa: PLC0415

        return jmcomic
    except ImportError:
        return None


def is_available() -> bool:
    return _load() is not None


def _client():
    jm = _load()
    if jm is None:
        raise BadRequestException("未安装 jmcomic：pip install jmcomic")
    option = jm.JmOption.default()
    return option.new_jm_client()


def list_providers() -> list[dict]:
    return [{"source": SOURCE, "displayName": "JM (18+)"}]


def _as_text(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, (list, tuple, set)):
        parts = [str(v).strip() for v in value if str(v).strip()]
        return "、".join(parts) or None
    text = str(value).strip()
    return text or None


def _parse_album(album: Any, source_id: str) -> dict:
    title = _as_text(
        getattr(album, "title", None) or getattr(album, "name", None)
    )
    return {
        "source": SOURCE,
        "sourceId": source_id,
        "title": title,
        "author": _as_text(getattr(album, "author", None)),
        "overview": _as_text(getattr(album, "description", None)),
        # 封面不走 URL，bind 时用 download_album_cover 直接落盘
        "coverUrl": None,
        "series": _as_text(getattr(album, "series", None)),
        "seriesPart": None,
        "pubYear": None,
        "rating": None,
    }


def search(keyword: str) -> list[dict]:
    if not keyword or not keyword.strip():
        raise BadRequestException("搜索关键词不能为空")
    client = _client()
    results: list[dict] = []
    try:
        page = client.search_site(search_query=keyword.strip(), page=1)
        for album_id, title in page:
            title_text = str(title).strip()
            if not title_text:
                continue
            results.append(
                {
                    "source": SOURCE,
                    "sourceId": str(album_id),
                    "title": title_text,
                    "author": None,
                    "overview": None,
                    "coverUrl": None,
                    "series": None,
                    "seriesPart": None,
                    "pubYear": None,
                    "rating": None,
                }
            )
    except Exception:
        logger.exception("JM search failed: %s", keyword)
    return results


def fetch_detail(source_id: str) -> dict | None:
    client = _client()
    try:
        album = client.get_album_detail(str(source_id))
    except Exception:
        logger.exception("JM album detail failed: %s", source_id)
        return None
    if album is None:
        return None
    return _parse_album(album, str(source_id))


def download_cover(comic: Comic, source_id: str) -> None:
    if comic.id is None:
        return
    from fryfrog.services.assets import comic_cover_path  # noqa: PLC0415

    target = comic_cover_path(comic.id)
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        client = _client()
        client.download_album_cover(str(source_id), str(target))
        if target.is_file():
            comic.cover_art_path = str(target)
    except Exception:
        logger.warning("[JmScrape] Cover download failed: %s", source_id)


def bind(db: Session, comic_id: int, source_id: str) -> Comic:
    comic = db.get(Comic, comic_id)
    if comic is None:
        raise ResourceNotFoundException("Comic", "id", comic_id)
    detail = fetch_detail(source_id)
    if not detail:
        raise ResourceNotFoundException("ScrapeResult", "sourceId", source_id)
    for field in ("title", "author", "overview", "series"):
        val = detail.get(field)
        if val and str(val).strip():
            setattr(comic, field, val)
    comic.source_id = detail["sourceId"]
    comic.metadata_source = "scrape"
    download_cover(comic, detail["sourceId"])
    db.flush()
    return comic
