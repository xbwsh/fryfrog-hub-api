"""有声书刮削：Bangumi（TYPE_REAL=6 + TYPE_BOOK=1）。"""

from __future__ import annotations

import logging
from pathlib import Path

import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session

from fryfrog.core.exceptions import BadRequestException, ResourceNotFoundException
from fryfrog.models.audiobook import Audiobook
from fryfrog.services.bangumi import TYPE_BOOK, TYPE_REAL, BangumiClient

logger = logging.getLogger(__name__)

SOURCE = "bangumi"


def list_providers() -> list[dict]:
    return [{"source": SOURCE, "displayName": "Bangumi"}]


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
        "narrator": BangumiClient.infobox_value(node, "朗读者", "演播", "主演"),
        "overview": summary,
        "coverUrl": cover,
        "series": None,
        "seriesPart": None,
        "year": year,
        "rating": rating,
    }


def search(keyword: str, source: str | None = None) -> list[dict]:
    if not keyword or not keyword.strip():
        raise BadRequestException("搜索关键词不能为空")
    if source and source != SOURCE:
        raise BadRequestException(f"未知数据源: {source}")
    client = BangumiClient()
    results = []
    for node in client.search_subjects(keyword.strip(), [TYPE_REAL, TYPE_BOOK]):
        parsed = _parse_subject(node)
        if parsed:
            results.append(parsed)
    return results


def fetch_detail(source_id: str) -> dict | None:
    client = BangumiClient()
    node = client.get_subject(source_id)
    return _parse_subject(node) if node else None


def _download_cover(book: Audiobook, cover_url: str) -> None:
    book_dir = Path(book.book_path)
    if not book_dir.is_dir():
        return
    target = book_dir / "cover.jpg"
    try:
        resp = httpx.get(cover_url, timeout=20, follow_redirects=True)
        if resp.status_code == 200 and resp.content:
            target.write_bytes(resp.content)
            book.cover_art_path = str(target)
    except Exception:
        logger.warning("[AudiobookScrape] Cover download failed: %s", cover_url)


def bind(db: Session, book_id: int, source: str, source_id: str) -> Audiobook:
    if source != SOURCE:
        raise BadRequestException(f"未知数据源: {source}")
    book = db.get(Audiobook, book_id)
    if book is None:
        raise ResourceNotFoundException("Audiobook", "id", book_id)
    detail = fetch_detail(source_id)
    if not detail:
        raise ResourceNotFoundException("ScrapeResult", "sourceId", source_id)

    for field in ("title", "author", "narrator", "overview", "series"):
        val = detail.get(field)
        if val and str(val).strip():
            setattr(book, field, val)
    if detail.get("seriesPart") is not None:
        book.series_part = detail["seriesPart"]
    if detail.get("year") is not None:
        book.pub_year = detail["year"]
    if detail.get("rating") is not None:
        book.rating = detail["rating"]
    book.source_id = detail.get("sourceId") or source_id
    book.metadata_source = "scrape"
    if detail.get("coverUrl"):
        _download_cover(book, detail["coverUrl"])
    db.flush()
    return book


def unbind(db: Session, book_id: int) -> None:
    book = db.get(Audiobook, book_id)
    if book is None:
        raise ResourceNotFoundException("Audiobook", "id", book_id)
    book.source_id = None
    book.metadata_source = "manual"
    db.flush()


def bind_summary(book: Audiobook) -> dict:
    from fryfrog.services.assets import signed_url

    return {
        "id": book.id,
        "title": book.title,
        "author": book.author,
        "narrator": book.narrator,
        "overview": book.overview,
        "series": book.series,
        "pubYear": book.pub_year,
        "rating": book.rating,
        "coverUrl": signed_url(f"/api/v1/audiobooks/{book.id}/cover")
        if book.cover_art_path
        else None,
    }
