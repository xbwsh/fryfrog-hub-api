"""漫画扫描：作品目录聚合，子图片目录/zip·cbz·pdf 为卷；库根散图/压缩包按系列聚合。"""

from __future__ import annotations

import logging
import re
import shutil
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from fryfrog.core.natural_order import natural_key
from fryfrog.models.comic import Comic, ComicChapter, ComicProgress
from fryfrog.models.library import MediaLibrary
from fryfrog.services import comic_pages, scan_guard

logger = logging.getLogger(__name__)

ARCHIVE_EXTS = {".cbz", ".zip", ".cbr", ".rar", ".7z", ".pdf"}
UNSUPPORTED_EXTS: set[str] = set()
COVER_FILE = "cover.jpg"  # 仅历史参考；封面现存 data/covers
VOLUME_SUFFIX = re.compile(
    r"(?:[\s_-]*(?:第\s*\d+\s*[卷话集]|Vol\.?\s*\d+|v\d+|#\d+|\d{1,3}))+\s*$",
    re.I,
)


@dataclass
class ChapterDraft:
    file_path: str
    title: str
    type: str
    mtime: int = 0
    file_size: int = 0


@dataclass
class SeriesDraft:
    book_path: str
    title: str
    chapters: list[ChapterDraft] = field(default_factory=list)


def _stat(path: Path) -> tuple[int, int]:
    try:
        st = path.stat()
        return int(st.st_mtime * 1000), st.st_size
    except OSError:
        return 0, 0


def _strip_ext(path: Path) -> str:
    return path.stem


def series_title_of(name: str) -> str:
    stripped = name.strip()
    m = VOLUME_SUFFIX.search(stripped)
    if m and m.end() == len(stripped):
        result = stripped[: m.start()].strip()
        if result:
            return result
    return stripped


def _has_images(dir_path: Path) -> bool:
    try:
        return any(p.is_file() and comic_pages.is_image_name(p.name) for p in dir_path.iterdir())
    except OSError:
        return False


def _collect_directory_series(series_dir: Path, drafts: dict[str, SeriesDraft]) -> None:
    child_dirs: list[Path] = []
    child_archives: list[Path] = []
    direct_images = 0
    for child in sorted(series_dir.iterdir(), key=lambda p: natural_key(p.name)):
        if child.is_dir() and not child.name.startswith("."):
            child_dirs.append(child)
        elif child.is_file() and child.suffix.lower() in ARCHIVE_EXTS:
            child_archives.append(child)
        elif child.is_file() and child.suffix.lower() in UNSUPPORTED_EXTS:
            logger.info("[ComicScan] Skipping unsupported archive: %s", child.name)
        elif child.is_file() and comic_pages.is_image_name(child.name):
            direct_images += 1

    series_name = series_dir.name
    draft = SeriesDraft(str(series_dir), series_name)
    for d in sorted([d for d in child_dirs if _has_images(d)], key=lambda p: natural_key(p.name)):
        mtime, size = _stat(d)
        draft.chapters.append(ChapterDraft(str(d), d.name, "DIRECTORY", mtime, size))
    for archive in child_archives:
        mtime, size = _stat(archive)
        draft.chapters.append(
            ChapterDraft(str(archive), _strip_ext(archive), "ARCHIVE", mtime, size)
        )
    if not draft.chapters:
        if direct_images > 0:
            mtime, size = _stat(series_dir)
            draft.chapters.append(ChapterDraft(str(series_dir), series_name, "DIRECTORY", mtime, size))
        else:
            return
    drafts[draft.book_path] = draft


def _collect_root_archive(archive: Path, drafts: dict[str, SeriesDraft]) -> None:
    series_name = series_title_of(_strip_ext(archive))
    book_path = str(archive.parent / series_name)
    draft = drafts.setdefault(book_path, SeriesDraft(book_path, series_name))
    mtime, size = _stat(archive)
    draft.chapters.append(ChapterDraft(str(archive), _strip_ext(archive), "ARCHIVE", mtime, size))


def _resolve_cover(comic: Comic, draft: SeriesDraft) -> str | None:
    if not draft.chapters or comic.id is None:
        return comic.cover_art_path
    from fryfrog.services.assets import comic_cover_path

    target = comic_cover_path(comic.id)
    if comic.metadata_source == "scrape" and target.is_file():
        return str(target)
    first = draft.chapters[0]
    try:
        class _Probe:
            id = 0
            file_path = first.file_path
            type = first.type

        pages = comic_pages.list_page_names(_Probe())  # type: ignore[arg-type]
        if not pages:
            return comic.cover_art_path
        target.parent.mkdir(parents=True, exist_ok=True)
        if first.type == "ARCHIVE":
            data, _ = comic_pages.read_page(_Probe(), 0)  # type: ignore[arg-type]
            target.write_bytes(data)
        else:
            shutil.copy2(Path(first.file_path) / pages[0], target)
        return str(target)
    except Exception:
        logger.debug("[ComicScan] Cover extract failed: %s", draft.book_path, exc_info=True)
        return comic.cover_art_path


def scan_comic_library(db: Session, library: MediaLibrary) -> dict:
    root = Path(library.path)
    if not root.is_dir():
        logger.warning("[ComicScan] Not a directory: %s", root)
        return {"series": 0, "chapters": 0}

    existing = {
        c.book_path: c
        for c in db.scalars(select(Comic).where(Comic.library_id == library.id)).all()
    }
    existing_chapters: dict[int, dict[str, ComicChapter]] = {}
    for comic in existing.values():
        existing_chapters[comic.id] = {
            ch.file_path: ch
            for ch in db.scalars(
                select(ComicChapter)
                .where(ComicChapter.comic_id == comic.id)
                .order_by(ComicChapter.chapter_index)
            ).all()
        }

    drafts: dict[str, SeriesDraft] = {}
    for entry in sorted(root.iterdir(), key=lambda p: natural_key(p.name)):
        if entry.name.startswith("."):
            continue
        if entry.is_dir():
            _collect_directory_series(entry, drafts)
        elif entry.is_file() and entry.suffix.lower() in ARCHIVE_EXTS:
            _collect_root_archive(entry, drafts)

    chapter_total = 0
    for draft in drafts.values():
        comic = existing.get(draft.book_path)
        if comic is None:
            # book_path 全局唯一：孤儿行占着同一路径时复用（否则 INSERT 撞 UNIQUE）
            comic = db.scalar(select(Comic).where(Comic.book_path == draft.book_path))
        if comic is None:
            comic = Comic(book_path=draft.book_path, title=draft.title, metadata_source="scan")
            db.add(comic)
        # 扫描不覆盖已刮削字段
        if comic.metadata_source != "scrape":
            comic.title = draft.title
            comic.metadata_source = "scan"
        comic.book_path = draft.book_path
        comic.library_id = library.id
        comic.total_chapters = len(draft.chapters)
        comic.total_size = sum(ch.file_size for ch in draft.chapters)
        db.flush()
        comic.cover_art_path = _resolve_cover(comic, draft)
        db.flush()

        existing_map = existing_chapters.get(comic.id) or {}
        for i, cd in enumerate(draft.chapters):
            chapter = existing_map.get(cd.file_path)
            recalc = chapter is None or chapter.file_mtime != cd.mtime
            if chapter is None:
                chapter = ComicChapter(comic_id=comic.id)
                db.add(chapter)
            chapter.comic_id = comic.id
            chapter.chapter_index = i
            chapter.title = cd.title
            chapter.file_path = cd.file_path
            chapter.type = cd.type
            chapter.file_mtime = cd.mtime
            chapter.file_size = cd.file_size
            if recalc or chapter.page_count is None:
                chapter.page_count = comic_pages.page_count(chapter)
            chapter_total += 1

    # 本轮没见到的书：先标记，宽限期满且过护栏才删（与视频/音乐同款保护）。
    # 章节清理只对「书本轮仍在盘上」的做——挂载掉线时书目录整体消失，
    # 章节跟着书一起走宽限期，不单独提前删（免得半删半留）。
    scanned = set(drafts.keys())
    now = datetime.now()
    threshold = scan_guard.grace_threshold()
    pending: list[Comic] = []
    for comic in existing.values():
        if comic.book_path in scanned or Path(comic.book_path).exists():
            comic.missing_since = None
            for ch in db.scalars(
                select(ComicChapter).where(ComicChapter.comic_id == comic.id)
            ).all():
                if not Path(ch.file_path).exists():
                    db.delete(ch)
            continue
        if comic.missing_since is None:
            comic.missing_since = now
        elif comic.missing_since < threshold:
            pending.append(comic)

    previous = scan_guard.read_last_count(db, "comic_scan", library.id or 0)
    allowed = scan_guard.guard_allows(len(drafts), previous)
    removed = 0
    if pending:
        if allowed:
            for comic in pending:
                # 子行用 core delete 立即执行（与 video/music 扫描同款）：
                # ORM 混合 flush 里父行可能先删，撞 FOREIGN KEY constraint failed
                db.execute(delete(ComicProgress).where(ComicProgress.comic_id == comic.id))
                db.execute(delete(ComicChapter).where(ComicChapter.comic_id == comic.id))
                db.delete(comic)
                removed += 1
        else:
            logger.warning(
                "漫画库疑似磁盘异常（本轮 %d 条 / 上轮 %s 条），暂缓删除 %d 条: %s；"
                "如确认磁盘正常，可临时把 SCAN_GUARD_MIN_RATIO 设为 0 后重扫",
                len(drafts),
                previous,
                len(pending),
                library.name,
            )
    if allowed:
        scan_guard.write_last_count(db, "comic_scan", library.id or 0, len(drafts))
    else:
        # 护栏拦截期间冻结基线：否则下一轮 previous 变小、护栏失效 → 整库被清空
        logger.warning("漫画库扫描基线保持不变（疑似磁盘异常）: %s", library.name)
    db.flush()
    return {"series": len(drafts), "chapters": chapter_total, "removed": removed}
