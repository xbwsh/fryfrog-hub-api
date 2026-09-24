"""电子书扫描：一文件一书。EPUB 用 ebooklib，PDF/MOBI 文件名兜底。"""

from __future__ import annotations

import logging
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from fryfrog.core.natural_order import natural_key
from fryfrog.core.utils import clean_title
from fryfrog.models.ebook import Ebook, EbookProgress
from fryfrog.models.library import MediaLibrary
from fryfrog.services.fsutil import EBOOK_EXTS

logger = logging.getLogger(__name__)

FORMAT_MAP = {"epub": "EPUB", "pdf": "PDF", "mobi": "MOBI", "azw3": "MOBI"}


def _format_of(path: Path) -> str:
    return FORMAT_MAP.get(path.suffix.lower().lstrip("."), "MOBI")


def _filename_meta(book: Ebook, file: Path) -> None:
    stem = clean_title(file.stem)
    if " - " in stem:
        author, title = stem.split(" - ", 1)
        if author.strip() and title.strip():
            book.author = author.strip()
            book.title = title.strip()
            return
    book.title = file.stem.strip() or stem


def _epub_meta(book: Ebook, file: Path) -> None:
    try:
        from ebooklib import epub

        doc = epub.read_epub(str(file))

        def dc(key: str) -> str | None:
            vals = doc.get_metadata("DC", key) or []
            if not vals:
                return None
            first = vals[0]
            if isinstance(first, tuple) and first:
                text = first[0]
            else:
                text = first
            text = str(text or "").strip()
            return text or None

        title = dc("title")
        if title:
            book.title = title
        else:
            _filename_meta(book, file)
        book.author = dc("creator")
        book.publisher = dc("publisher")
        book.language = dc("language")
        date = dc("date")
        if date and len(date) >= 4 and date[:4].isdigit():
            book.pub_year = int(date[:4])
        book.overview = dc("description")
        spine = doc.spine or []
        book.total_chapters = len(spine) if spine else None

        # 封面：ITEM_COVER 或元数据 refiner
        cover_path = file.parent / "cover.jpg"
        try:
            from ebooklib import ITEM_COVER, ITEM_IMAGE

            for item in doc.get_items_of_type(ITEM_COVER) or []:
                data = item.get_content()
                if data:
                    cover_path.write_bytes(data)
                    book.cover_art_path = str(cover_path)
                    break
            else:
                for item in doc.get_items_of_type(ITEM_IMAGE) or []:
                    name = (item.get_name() or "").lower()
                    if "cover" in name:
                        data = item.get_content()
                        if data:
                            cover_path.write_bytes(data)
                            book.cover_art_path = str(cover_path)
                            break
        except Exception:
            logger.debug("EPUB cover extract failed: %s", file, exc_info=True)
    except Exception:
        logger.warning("[EbookScan] EPUB meta failed, fallback filename: %s", file)
        _filename_meta(book, file)


def scan_ebook_library(db: Session, library: MediaLibrary) -> dict:
    root = Path(library.path)
    if not root.is_dir():
        logger.warning("[EbookScan] Not a directory: %s", root)
        return {"books": 0, "created": 0, "removed": 0}

    files = sorted(
        [p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in EBOOK_EXTS],
        key=lambda p: (str(p.parent), natural_key(p.name)),
    )
    existing = {
        b.file_path: b
        for b in db.scalars(select(Ebook).where(Ebook.library_id == library.id)).all()
    }

    scanned: set[str] = set()
    created = 0
    for file in files:
        file_path = str(file)
        scanned.add(file_path)
        try:
            st = file.stat()
            mtime = int(st.st_mtime * 1000)
            size = st.st_size
        except OSError:
            continue
        book = existing.get(file_path)
        if book and book.file_mtime == mtime and book.file_size == size:
            continue
        is_new = book is None
        if book is None:
            book = Ebook(file_path=file_path, format="MOBI", title=file.stem)
            db.add(book)
            created += 1
        book.file_path = file_path
        book.file_mtime = mtime
        book.file_size = size
        book.format = _format_of(file)
        book.library_id = library.id
        # 扫描不覆盖已刮削字段
        if book.metadata_source != "scrape":
            book.metadata_source = "scan"
            if book.format == "EPUB":
                _epub_meta(book, file)
            else:
                _filename_meta(book, file)

    removed = 0
    for book in existing.values():
        if book.file_path in scanned:
            continue
        if Path(book.file_path).exists():
            continue
        for p in db.scalars(
            select(EbookProgress).where(EbookProgress.ebook_id == book.id)
        ).all():
            db.delete(p)
        db.delete(book)
        removed += 1
    db.flush()
    return {"books": len(files), "created": created, "removed": removed}
