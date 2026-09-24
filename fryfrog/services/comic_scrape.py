"""漫画刮削 provider 调度：Bangumi（TYPE_BOOK=1）与 JM（可选，jmcomic 库）。"""

from __future__ import annotations

import logging

from sqlalchemy.orm import Session

from fryfrog.core.exceptions import BadRequestException, ResourceNotFoundException
from fryfrog.models.comic import Comic
from fryfrog.services import jm_scrape
from fryfrog.services.bangumi import TYPE_BOOK, BangumiClient

logger = logging.getLogger(__name__)

SOURCE = "bangumi"


def list_providers() -> list[dict]:
    providers = [{"source": SOURCE, "displayName": "Bangumi"}]
    if jm_scrape.is_available():
        providers.extend(jm_scrape.list_providers())
    return providers


def _parse_subject(node: dict) -> dict | None:
    title = (node.get("name_cn") or node.get("name") or "").strip()
    if not title:
        return None
    images = node.get("images") or {}
    cover = (images.get("large") or images.get("common") or "").strip() or None
    summary = (node.get("summary") or "").strip() or None
    score = float((node.get("rating") or {}).get("score") or 0)
    rating = round(score / 2, 1) if score > 0 else None
    year = None
    date = (node.get("date") or "").strip()
    if len(date) >= 4 and date[:4].isdigit():
        year = int(date[:4])
    return {
        "source": SOURCE,
        "sourceId": str(node.get("id") or "") or None,
        "title": title,
        "author": BangumiClient.infobox_value(node, "作者", "原作"),
        "overview": summary,
        "coverUrl": cover,
        "series": None,
        "seriesPart": None,
        "pubYear": year,
        "rating": rating,
    }


def _bangumi_search(keyword: str) -> list[dict]:
    client = BangumiClient()
    results = []
    for node in client.search_subjects(keyword, [TYPE_BOOK]):
        parsed = _parse_subject(node)
        if parsed:
            results.append(parsed)
    return results


def search(keyword: str, source: str | None = None) -> list[dict]:
    if not keyword or not keyword.strip():
        raise BadRequestException("搜索关键词不能为空")
    keyword = keyword.strip()
    if source == jm_scrape.SOURCE:
        return jm_scrape.search(keyword)
    if source and source != SOURCE:
        raise BadRequestException(f"未知数据源: {source}")
    results: list[dict] = []
    if source is None and jm_scrape.is_available():
        try:
            results.extend(jm_scrape.search(keyword))
        except Exception:
            logger.exception("JM search failed: %s", keyword)
    results.extend(_bangumi_search(keyword))
    return results


def _download_cover(comic: Comic, cover_url: str) -> None:
    if comic.id is None:
        return
    from fryfrog.services.assets import comic_cover_path

    target = comic_cover_path(comic.id)
    try:
        from fryfrog.core.http import make_client

        target.parent.mkdir(parents=True, exist_ok=True)
        with make_client(timeout=20.0) as client:
            resp = client.get(cover_url)
        if resp.status_code == 200 and resp.content:
            target.write_bytes(resp.content)
            comic.cover_art_path = str(target)
    except Exception:
        logger.warning("[ComicScrape] Cover download failed: %s", cover_url)


def _bangumi_detail(source_id: str) -> dict | None:
    node = BangumiClient().get_subject(source_id)
    return _parse_subject(node) if node else None


def _bind_bangumi(db: Session, comic_id: int, source_id: str) -> Comic:
    comic = db.get(Comic, comic_id)
    if comic is None:
        raise ResourceNotFoundException("Comic", "id", comic_id)
    detail = _bangumi_detail(source_id)
    if not detail:
        raise ResourceNotFoundException("ScrapeResult", "sourceId", source_id)
    for field in ("title", "author", "overview", "series"):
        val = detail.get(field)
        if val and str(val).strip():
            setattr(comic, field, val)
    if detail.get("seriesPart") is not None:
        comic.series_part = detail["seriesPart"]
    if detail.get("pubYear") is not None:
        comic.pub_year = detail["pubYear"]
    if detail.get("rating") is not None:
        comic.rating = detail["rating"]
    comic.source_id = detail.get("sourceId") or source_id
    comic.metadata_source = "scrape"
    if detail.get("coverUrl"):
        _download_cover(comic, detail["coverUrl"])
    db.flush()
    return comic


def bind(db: Session, comic_id: int, source: str, source_id: str) -> Comic:
    if source == jm_scrape.SOURCE:
        return jm_scrape.bind(db, comic_id, source_id)
    if source != SOURCE:
        raise BadRequestException(f"未知数据源: {source}")
    return _bind_bangumi(db, comic_id, source_id)


def unbind(db: Session, comic_id: int) -> None:
    comic = db.get(Comic, comic_id)
    if comic is None:
        raise ResourceNotFoundException("Comic", "id", comic_id)
    comic.source_id = None
    comic.metadata_source = "manual"
    db.flush()


def bind_summary(comic: Comic) -> dict:
    from fryfrog.services.assets import signed_url

    return {
        "id": comic.id,
        "title": comic.title,
        "author": comic.author,
        "pubYear": comic.pub_year,
        "rating": comic.rating,
        "overview": comic.overview,
        "series": comic.series,
        "coverUrl": signed_url(f"/api/v1/comics/{comic.id}/cover")
        if comic.cover_art_path
        else None,
    }
