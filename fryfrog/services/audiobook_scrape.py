"""有声书刮削：Bangumi / Open Library / Google Books / Audiobooker / 喜马拉雅。"""

from __future__ import annotations

import logging
import re
from html.parser import HTMLParser
from urllib.parse import urlsplit

import httpx

from sqlalchemy.orm import Session

from fryfrog.core.exceptions import BadRequestException, ResourceNotFoundException
from fryfrog.core.http import make_client
from fryfrog.models.audiobook import Audiobook
from fryfrog.services.bangumi import TYPE_BOOK, TYPE_REAL, BangumiClient

logger = logging.getLogger(__name__)

SOURCE_BANGUMI = "bangumi"
SOURCE_OPENLIB = "openlibrary"
SOURCE_GOOGLE = "googlebooks"
SOURCE_AUDIOBOOKER = "audiobooker"
SOURCE_XIMALAYA = "ximalaya"
SOURCES = (SOURCE_BANGUMI, SOURCE_OPENLIB, SOURCE_GOOGLE, SOURCE_AUDIOBOOKER, SOURCE_XIMALAYA)


def list_providers() -> list[dict]:
    return [
        {"source": SOURCE_BANGUMI, "displayName": "Bangumi", "bestFor": "中文/日文有声书、广播剧"},
        {"source": SOURCE_OPENLIB, "displayName": "Open Library", "bestFor": "英文图书元数据"},
        {"source": SOURCE_GOOGLE, "displayName": "Google Books", "bestFor": "全球图书与封面"},
        {"source": SOURCE_AUDIOBOOKER, "displayName": "Audiobooker", "bestFor": "LibriVox 等公版多源聚合"},
        {"source": SOURCE_XIMALAYA, "displayName": "喜马拉雅", "bestFor": "中文有声书；支持专辑链接或 ID"},
    ]


def _blank(source: str, source_id: str | None = None) -> dict:
    return {
        "source": source,
        "sourceId": source_id,
        "title": None,
        "author": None,
        "narrator": None,
        "overview": None,
        "coverUrl": None,
        "series": None,
        "seriesPart": None,
        "year": None,
        "rating": None,
    }


# -------------------- Bangumi --------------------


def _parse_bangumi(node: dict) -> dict | None:
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
    item = _blank(SOURCE_BANGUMI, str(node.get("id") or "") or None)
    item.update(
        {
            "title": title,
            "author": BangumiClient.infobox_value(node, "作者", "原作"),
            "narrator": BangumiClient.infobox_value(node, "朗读者", "演播", "主演"),
            "overview": summary,
            "coverUrl": cover,
            "series": BangumiClient.infobox_value(node, "系列", "系列名"),
            "year": year,
            "rating": rating,
        }
    )
    return item


def _search_bangumi(keyword: str) -> list[dict]:
    client = BangumiClient()
    out = []
    for node in client.search_subjects(keyword, [TYPE_REAL, TYPE_BOOK]):
        parsed = _parse_bangumi(node)
        if parsed:
            out.append(parsed)
    return out


def _fetch_bangumi(source_id: str) -> dict | None:
    node = BangumiClient().get_subject(source_id)
    return _parse_bangumi(node) if node else None


# -------------------- Open Library --------------------


def _parse_openlib(doc: dict) -> dict | None:
    title = (doc.get("title") or "").strip()
    if not title:
        return None
    authors = doc.get("author_name") or []
    cover = None
    cover_i = doc.get("cover_i")
    if cover_i:
        cover = f"https://covers.openlibrary.org/b/id/{cover_i}-L.jpg"
    year = doc.get("first_publish_year")
    item = _blank(SOURCE_OPENLIB, (doc.get("key") or "").split("/")[-1] or None)
    item.update(
        {
            "title": title,
            "author": "、".join(a for a in authors[:3] if a) or None,
            "overview": None,
            "coverUrl": cover,
            "year": int(year) if isinstance(year, int) or (str(year).isdigit()) else None,
        }
    )
    return item


def _search_openlib(keyword: str) -> list[dict]:
    try:
        with make_client() as client:
            resp = client.get(
                "https://openlibrary.org/search.json",
                params={"q": keyword, "limit": 20},
            )
            resp.raise_for_status()
            docs = resp.json().get("docs") or []
    except Exception:
        logger.exception("OpenLibrary search failed")
        return []
    out = []
    for doc in docs:
        parsed = _parse_openlib(doc)
        if parsed:
            out.append(parsed)
    return out


def _fetch_openlib(source_id: str) -> dict | None:
    try:
        with make_client() as client:
            resp = client.get(f"https://openlibrary.org/works/OL{source_id}W.json")
            if resp.status_code == 404:
                resp = client.get(f"https://openlibrary.org{source_id}")
            if resp.status_code == 404:
                return None
            resp.raise_for_status()
            data = resp.json()
    except Exception:
        logger.exception("OpenLibrary fetch failed: %s", source_id)
        return None
    title = (data.get("title") or "").strip()
    if not title:
        return None
    desc = data.get("description")
    if isinstance(desc, dict):
        desc = desc.get("value")
    covers = data.get("covers") or []
    cover = f"https://covers.openlibrary.org/b/id/{covers[0]}-L.jpg" if covers else None
    item = _blank(SOURCE_OPENLIB, source_id)
    item.update({"title": title, "overview": (desc or "").strip() or None, "coverUrl": cover})
    return item


# -------------------- Google Books --------------------


def _parse_google(vol: dict) -> dict | None:
    info = vol.get("volumeInfo") or {}
    title = (info.get("title") or "").strip()
    if not title:
        return None
    images = info.get("imageLinks") or {}
    cover = images.get("thumbnail") or images.get("smallThumbnail")
    if cover:
        cover = cover.replace("http://", "https://")
    date = (info.get("publishedDate") or "").strip()
    year = int(date[:4]) if len(date) >= 4 and date[:4].isdigit() else None
    rating = info.get("averageRating")
    item = _blank(SOURCE_GOOGLE, vol.get("id"))
    item.update(
        {
            "title": title,
            "author": "、".join(info.get("authors") or []) or None,
            "overview": (info.get("description") or "").strip() or None,
            "coverUrl": cover,
            "year": year,
            "rating": round(float(rating), 1) if rating else None,
        }
    )
    return item


def _search_google(keyword: str) -> list[dict]:
    try:
        with make_client() as client:
            resp = client.get(
                "https://www.googleapis.com/books/v1/volumes",
                params={"q": keyword, "maxResults": 20},
            )
            resp.raise_for_status()
            items = resp.json().get("items") or []
    except Exception:
        logger.exception("Google Books search failed")
        return []
    out = []
    for vol in items:
        parsed = _parse_google(vol)
        if parsed:
            out.append(parsed)
    return out


def _fetch_google(source_id: str) -> dict | None:
    try:
        with make_client() as client:
            resp = client.get(f"https://www.googleapis.com/books/v1/volumes/{source_id}")
            if resp.status_code == 404:
                return None
            resp.raise_for_status()
            return _parse_google(resp.json())
    except Exception:
        logger.exception("Google Books fetch failed: %s", source_id)
        return None


# -------------------- Audiobooker（LibriVox 等公版聚合） --------------------


def _parse_audiobooker_book(book) -> dict | None:
    title = (getattr(book, "title", "") or "").strip()
    if not title:
        return None
    authors = []
    for a in getattr(book, "authors", None) or []:
        name = " ".join(
            x for x in (getattr(a, "first_name", ""), getattr(a, "last_name", "")) if x
        ).strip()
        if name:
            authors.append(name)
    narrator = getattr(book, "narrator", None)
    narrator_name = None
    if narrator is not None:
        narrator_name = " ".join(
            x
            for x in (getattr(narrator, "first_name", ""), getattr(narrator, "last_name", ""))
            if x
        ).strip() or None
    image = (getattr(book, "image", "") or "").strip() or None
    description = (getattr(book, "description", "") or "").strip() or None
    year = getattr(book, "year", 0) or None
    runtime = getattr(book, "runtime", 0) or None
    source_name = getattr(book, "source", "") or SOURCE_AUDIOBOOKER
    streams = list(getattr(book, "streams", None) or [])
    source_id = streams[0] if streams else f"{source_name}:{title}"
    item = _blank(SOURCE_AUDIOBOOKER, source_id)
    item.update(
        {
            "title": title,
            "author": "、".join(authors) if authors else None,
            "narrator": narrator_name,
            "overview": description,
            "coverUrl": image,
            "year": int(year) if year else None,
            "rating": None,
            "sourceDetail": source_name,
            "runtimeMinutes": int(runtime) if runtime else None,
            "streamUrl": streams[0] if streams else None,
        }
    )
    return item


def _search_audiobooker(keyword: str) -> list[dict]:
    out = _search_audiobooker_lib(keyword)
    return out


def _search_audiobooker_lib(keyword: str) -> list[dict]:
    """优先 LibriVox JSON（走 make_client 代理）；再尝试 audiobooker 聚合。"""
    out = _search_librivox(keyword)
    if out:
        return out
    try:
        import os

        from fryfrog.config import get_settings

        proxy = get_settings().scraper_proxy_url
        old_env = {}
        if proxy:
            for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
                old_env[k] = os.environ.get(k)
                os.environ[k] = proxy
        try:
            from audiobooker import search as ab_search

            for book in ab_search(keyword, max_per_source=5, timeout=12.0, deduplicate=True):
                parsed = _parse_audiobooker_book(book)
                if parsed:
                    out.append(parsed)
                if len(out) >= 20:
                    break
        finally:
            for k, v in old_env.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v
    except Exception:
        logger.exception("Audiobooker search failed")
    return out


def _search_librivox(keyword: str) -> list[dict]:
    try:
        with make_client(timeout=15.0) as client:
            resp = client.get(
                "https://librivox.org/api/feed/audiobooks/",
                params={"title": keyword, "limit": 20, "format": "json"},
            )
            resp.raise_for_status()
            books = resp.json().get("books") or []
    except Exception:
        logger.exception("LibriVox search failed")
        return []
    out = []
    for b in books:
        authors = [
            " ".join(x for x in (a.get("first_name") or "", a.get("last_name") or "") if x).strip()
            for a in (b.get("authors") or [])
        ]
        authors = [a for a in authors if a]
        year = b.get("copyright_year")
        item = _blank(SOURCE_AUDIOBOOKER, str(b.get("id") or ""))
        item.update(
            {
                "title": (b.get("title") or "").strip() or None,
                "author": "、".join(authors) if authors else None,
                "overview": (b.get("description") or "").strip() or None,
                "year": int(year) if year and str(year).isdigit() else None,
                "sourceDetail": "LibriVox",
                "runtimeMinutes": int((b.get("totaltimesecs") or 0) / 60) or None,
                "streamUrl": b.get("url_zip_file") or b.get("url_rss"),
            }
        )
        if item["title"]:
            out.append(item)
    return out


def _fetch_audiobooker(source_id: str) -> dict | None:
    # 无详情 API，用 source_id 反查（stream URL 或 title）
    title = source_id.rsplit(":", 1)[-1] if ":" in source_id and not source_id.startswith("http") else source_id
    for item in _search_audiobooker(title):
        if item.get("sourceId") == source_id:
            return item
    return None


# -------------------- 喜马拉雅公开网页数据 --------------------


class _XimalayaText(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag in {"br", "p", "div", "li"}:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in {"p", "div", "li"}:
            self.parts.append("\n")

    def handle_data(self, data):
        self.parts.append(data)


def _ximalaya_text(value: str | None) -> str | None:
    if not isinstance(value, str):
        return None
    parser = _XimalayaText()
    parser.feed(value or "")
    return "\n".join(line.strip() for line in "".join(parser.parts).splitlines() if line.strip()) or None


def _ximalaya_album_id(value: str) -> str | None:
    value = value.strip()
    if value.isascii() and value.isdigit():
        return str(int(value)) if len(value) <= 20 and int(value) > 0 else None
    try:
        url = urlsplit(value if "://" in value else f"https://{value}")
    except ValueError:
        return None
    if url.scheme not in {"http", "https"} or url.hostname not in {
        "www.ximalaya.com", "m.ximalaya.com", "ximalaya.com"
    }:
        return None
    match = re.fullmatch(r"/(?:album|youshengshu)/([0-9]+)(?:\.html)?/?", url.path)
    return _ximalaya_album_id(match[1]) if match else None


def _ximalaya_get(path: str, params: dict) -> dict | None:
    try:
        with make_client(timeout=12.0) as client:
            resp = client.get(
                f"https://www.ximalaya.com/revision/{path}",
                params=params,
                headers={"User-Agent": "Mozilla/5.0", "Referer": "https://www.ximalaya.com/"},
            )
            if resp.status_code == 404:
                return None
            resp.raise_for_status()
            payload = resp.json()
    except (httpx.HTTPError, ValueError) as exc:
        logger.warning("[Ximalaya] 网页请求失败: %s", exc)
        raise BadRequestException("喜马拉雅暂时无法访问，请稍后重试") from exc
    data = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(data, dict) or payload.get("ret") != 200 or data.get("reason"):
        logger.warning("[Ximalaya] 网页请求受限或返回异常")
        raise BadRequestException("喜马拉雅暂时限制访问或返回异常；关键词搜索受限时，可尝试粘贴专辑链接或 ID")
    return data


def _parse_ximalaya(node: dict, source_id: str | None = None) -> dict | None:
    title = _ximalaya_text(node.get("albumTitle") or node.get("title"))
    album_id = _ximalaya_album_id(str(node.get("albumId") or node.get("id") or source_id or ""))
    if not title or album_id is None:
        return None
    overview = _ximalaya_text(
        node.get("detailRichIntro") or node.get("richIntro") or node.get("intro") or node.get("shortIntro")
    )
    cover = node.get("cover") or node.get("coverPath") or node.get("cover_path") or ""
    cover = cover.strip() if isinstance(cover, str) else ""
    if cover.startswith("//"):
        cover = f"https:{cover}"
    if not cover.startswith(("http://", "https://")):
        cover = None
    item = _blank(SOURCE_XIMALAYA, album_id)
    item.update({"title": title, "overview": overview, "coverUrl": cover})
    # ponytail: 只取简介中独立标注的人名，复杂排版留空，交由用户手动补充。
    for field, labels in (("author", "作者|原著|原作"), ("narrator", "演播|演播者|播讲|朗读|朗读者|主播")):
        match = re.search(rf"(?:^|\n)\s*(?:{labels})\s*[：:]\s*([^\n，,。；;]+)", overview or "")
        if match:
            item[field] = match[1].strip() or None
    return item


def _fetch_ximalaya(source_id: str) -> dict | None:
    album_id = _ximalaya_album_id(source_id)
    if album_id is None:
        raise BadRequestException("请输入有效的喜马拉雅专辑链接或数字 ID")
    data = _ximalaya_get("album/v1/simple", {"albumId": album_id})
    main = data.get("albumPageMainInfo") if data else None
    return _parse_ximalaya(main, album_id) if isinstance(main, dict) else None


def _search_ximalaya(keyword: str) -> list[dict]:
    album_id = _ximalaya_album_id(keyword)
    if album_id is not None:
        item = _fetch_ximalaya(album_id)
        return [item] if item else []
    data = _ximalaya_get("search/main", {"core": "album", "kw": keyword, "page": 1, "rows": 20})
    album = (data or {}).get("album")
    docs = album.get("docs") if isinstance(album, dict) else None
    if not isinstance(docs, list):
        raise BadRequestException("喜马拉雅搜索暂不可用，请粘贴专辑链接或 ID")
    return [item for node in docs[:20] if isinstance(node, dict) and (item := _parse_ximalaya(node))]


# -------------------- 统一入口 --------------------


def search(keyword: str, source: str | None = None) -> list[dict]:
    if not keyword or not keyword.strip():
        raise BadRequestException("搜索关键词不能为空")
    key = keyword.strip()
    if source is None and _ximalaya_album_id(key) is not None and not key.isdigit():
        return _search_ximalaya(key)
    if source:
        if source not in SOURCES:
            raise BadRequestException(f"未知数据源: {source}")
        return {
            SOURCE_BANGUMI: _search_bangumi,
            SOURCE_OPENLIB: _search_openlib,
            SOURCE_GOOGLE: _search_google,
            SOURCE_AUDIOBOOKER: _search_audiobooker,
            SOURCE_XIMALAYA: _search_ximalaya,
        }[source](key)

    # 未指定源：各源限量聚合
    results: list[dict] = []
    results.extend(_search_bangumi(key)[:8])
    results.extend(_search_openlib(key)[:8])
    results.extend(_search_google(key)[:8])
    results.extend(_search_audiobooker(key)[:8])
    try:
        results.extend(_search_ximalaya(key)[:8])
    except BadRequestException:
        # 喜马拉雅风控或临时故障不影响其余来源的聚合结果。
        pass
    return results


def fetch_detail(source: str, source_id: str) -> dict | None:
    if source == SOURCE_BANGUMI:
        return _fetch_bangumi(source_id)
    if source == SOURCE_OPENLIB:
        return _fetch_openlib(source_id)
    if source == SOURCE_GOOGLE:
        return _fetch_google(source_id)
    if source == SOURCE_AUDIOBOOKER:
        return _fetch_audiobooker(source_id)
    if source == SOURCE_XIMALAYA:
        return _fetch_ximalaya(source_id)
    raise BadRequestException(f"未知数据源: {source}")


def _download_cover(book: Audiobook, cover_url: str) -> None:
    from fryfrog.services.assets import audiobook_cover_path

    # 按 id 唯一命名并放在应用数据区：原先写 <书目录>/cover.jpg，同目录多本书
    # 会互相覆盖同一个文件，且污染用户媒体目录。
    target = audiobook_cover_path(book.id)
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        with make_client(timeout=20.0) as client:
            resp = client.get(cover_url)
        if resp.status_code == 200 and resp.content:
            target.write_bytes(resp.content)
            book.cover_art_path = str(target)
    except Exception:
        logger.warning("[AudiobookScrape] Cover download failed: %s", cover_url)


def bind(db: Session, book_id: int, source: str, source_id: str) -> Audiobook:
    if source not in SOURCES:
        raise BadRequestException(f"未知数据源: {source}")
    book = db.get(Audiobook, book_id)
    if book is None:
        raise ResourceNotFoundException("Audiobook", "id", book_id)
    detail = fetch_detail(source, source_id)
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
    book.metadata_source = f"scrape:{source}"
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
        "metadataSource": book.metadata_source,
        "coverUrl": signed_url(f"/api/v1/audiobooks/{book.id}/cover")
        if book.cover_art_path
        else None,
    }
