"""电子书扫描：一文件一书。EPUB 用 ebooklib，PDF/MOBI 文件名兜底。"""

from __future__ import annotations

import logging
from datetime import datetime
from pathlib import Path

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from fryfrog.core.natural_order import natural_key
from fryfrog.core.utils import clean_title
from fryfrog.models.ebook import Ebook, EbookProgress
from fryfrog.models.library import MediaLibrary
from fryfrog.services import ebook_text, scan_guard
from fryfrog.services.fsutil import EBOOK_EXTS

logger = logging.getLogger(__name__)

FORMAT_MAP = {"epub": "EPUB", "pdf": "PDF", "mobi": "MOBI", "azw3": "MOBI", "txt": "TXT"}


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
        if book is None:
            # 同路径可能被孤儿行占着（库删过/重建过）：file_path 是全局唯一，
            # 直接 INSERT 会撞 UNIQUE → 整个库的扫描永久失败。复用并改挂到本库。
            book = db.scalar(select(Ebook).where(Ebook.file_path == file_path))
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
            if book.format == "TXT":
                book.total_chapters = ebook_text.chapter_count(file)

    # 本轮没见到的行：先标记，宽限期满且过护栏才删（与视频/音乐同款保护）。
    # 挂载掉线（根目录空）时实见 0 条，护栏会整轮拦下并冻结基线，不会清库。
    now = datetime.now()
    threshold = scan_guard.grace_threshold()
    pending: list[Ebook] = []
    for book in existing.values():
        if book.file_path in scanned:
            book.missing_since = None
            continue
        if Path(book.file_path).exists():
            book.missing_since = None
            continue
        if book.missing_since is None:
            book.missing_since = now
        elif book.missing_since < threshold:
            pending.append(book)

    previous = scan_guard.read_last_count(db, "ebook_scan", library.id or 0)
    allowed = scan_guard.guard_allows(len(scanned), previous)
    removed = 0
    if pending:
        if allowed:
            for book in pending:
                # 子行用 core delete 立即执行（与 video/music 扫描同款）：
                # ORM 混合 flush 里父行可能先删，撞 FOREIGN KEY constraint failed
                db.execute(delete(EbookProgress).where(EbookProgress.ebook_id == book.id))
                db.delete(book)
                removed += 1
        else:
            logger.warning(
                "电子书库疑似磁盘异常（本轮 %d 条 / 上轮 %s 条），暂缓删除 %d 条: %s；"
                "如确认磁盘正常，可临时把 SCAN_GUARD_MIN_RATIO 设为 0 后重扫",
                len(scanned),
                previous,
                len(pending),
                library.name,
            )
    if allowed:
        scan_guard.write_last_count(db, "ebook_scan", library.id or 0, len(scanned))
    else:
        # 护栏拦截期间冻结基线：否则下一轮 previous 变小、护栏失效 → 整库被清空
        logger.warning("电子书库扫描基线保持不变（疑似磁盘异常）: %s", library.name)
    db.flush()
    return {"books": len(files), "created": created, "removed": removed}
