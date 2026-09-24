"""有声书扫描：目录直接包含音频 = 一本书；单文件 SINGLE（M4B 内嵌章节），多文件 MULTI。"""

from __future__ import annotations

import json
import logging
import subprocess
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from fryfrog.core.natural_order import natural_key
from fryfrog.core.utils import clean_title
from fryfrog.media_core import get_media_probe
from fryfrog.models.audiobook import (
    Audiobook,
    AudiobookChapter,
    AudiobookProgress,
    AudiobookTrack,
)
from fryfrog.models.library import MediaLibrary
from fryfrog.services.fsutil import AUDIOBOOK_EXTS

logger = logging.getLogger(__name__)

COVER_NAMES = ("cover.jpg", "cover.png", "cover.webp", "folder.jpg", "folder.png")
CHAPTER_CAPABLE = {".m4b", ".m4a", ".mp4"}
AUDIO_EXTS = AUDIOBOOK_EXTS | {".m4r", ".oga", ".mp4", ".mka"}
TAG_TITLE_KEYS = ("album", "title", "ALBUM", "TITLE")
TAG_AUTHOR_KEYS = ("artist", "album_artist", "ARTIST", "ALBUM_ARTIST")
TAG_NARRATOR_KEYS = ("composer", "narrator", "description", "COMPOSER")
TAG_SERIES_KEYS = ("series", "show", "SERIES")


def _tags_of(probe: dict) -> dict:
    tags = probe.get("tags") or {}
    return {str(k).lower(): str(v) for k, v in tags.items() if v}


def _first_tag(tags: dict, *keys: str) -> str | None:
    for key in keys:
        val = tags.get(key.lower())
        if val and val.strip():
            return val.strip()
    return None


def probe_chapters(path: Path) -> list[dict]:
    """解析容器内嵌章节；优先走 MediaProbeService.probe_chapters（若已提供）。"""
    probe = get_media_probe()
    fn = getattr(probe, "probe_chapters", None)
    if callable(fn):
        try:
            rows = fn(str(path)) or []
            return [
                {
                    "title": r.get("title"),
                    "start": float(r.get("start") or 0),
                    "end": float(r.get("end") or 0),
                }
                for r in rows
            ]
        except Exception:
            logger.debug("probe_chapters failed: %s", path, exc_info=True)
    try:
        cmd = [
            probe.runtime.ffprobe_path,
            "-v",
            "error",
            "-print_format",
            "json",
            "-show_chapters",
            str(path),
        ]
        proc = subprocess.run(
            cmd,
            capture_output=True,
            timeout=15,
            check=False,
            env=probe.runtime.apply_library_env(),
        )
        if proc.returncode != 0 or not proc.stdout:
            return []
        data = json.loads(proc.stdout.decode("utf-8", errors="replace") or "{}")
        result = []
        for ch in data.get("chapters") or []:
            start = float(ch.get("start_time") or 0)
            end = float(ch.get("end_time") or 0)
            title = None
            tags = ch.get("tags") or {}
            for key in ("title", "TITLE"):
                if tags.get(key):
                    title = str(tags[key])
                    break
            result.append({"title": title, "start": start, "end": end})
        return result
    except Exception:
        logger.debug("ffprobe chapters failed: %s", path, exc_info=True)
        return []


def _audio_files_in(dir_path: Path) -> list[Path]:
    files = [
        p
        for p in dir_path.iterdir()
        if p.is_file() and p.suffix.lower() in AUDIO_EXTS and not p.name.startswith(".")
    ]
    files.sort(key=lambda p: natural_key(p.name))
    return files


def _format_of(path: Path) -> str:
    return path.suffix.lower().lstrip(".") or "mp3"


def scan_audiobook_library(db: Session, library: MediaLibrary) -> dict:
    root = Path(library.path)
    if not root.is_dir():
        logger.warning("[AudiobookScan] Not a directory: %s", root)
        return {"books": 0, "created": 0, "removed": 0}

    books: dict[str, list[Path]] = {}
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in AUDIO_EXTS:
            continue
        if any(part.startswith(".") for part in path.relative_to(root).parts):
            continue
        books.setdefault(str(path.parent), []).append(path)

    ordered: dict[str, list[Path]] = {}
    for key in sorted(books.keys()):
        ordered[key] = sorted(books[key], key=lambda p: natural_key(p.name))

    created = 0
    scanned_paths = set()
    for book_path, files in ordered.items():
        try:
            if _upsert_book(db, book_path, files, root, library.id):
                created += 1
            scanned_paths.add(book_path)
        except Exception:
            logger.exception("[AudiobookScan] Failed book %s", book_path)
    removed = _cleanup_missing(db, scanned_paths, library.id)
    return {"books": len(ordered), "created": created, "removed": removed}


def _upsert_book(
    db: Session, book_path: str, audio_files: list[Path], library_root: Path, library_id: int
) -> bool:
    book = db.scalar(select(Audiobook).where(Audiobook.book_path == book_path))
    is_new = book is None
    if book is None:
        book = Audiobook(book_path=book_path, play_type="MULTI", title=Path(book_path).name)
        db.add(book)

    single = len(audio_files) == 1
    book.play_type = "SINGLE" if single else "MULTI"
    book.library_id = library_id
    book.file_path = str(audio_files[0]) if single else None

    existing = (
        list(
            db.scalars(
                select(AudiobookTrack)
                .where(AudiobookTrack.audiobook_id == book.id)
                .order_by(AudiobookTrack.track_index)
            ).all()
        )
        if not is_new
        else []
    )
    unchanged = _tracks_unchanged(existing, audio_files)

    tracks: list[tuple[Path, dict, float | None, int]] = []
    if not unchanged:
        for file in audio_files:
            info = get_media_probe().probe_audio_info(str(file))
            tags = _tags_of(info)
            duration = info.get("duration")
            tracks.append((file, tags, float(duration) if duration else None, file.stat().st_size))

        book.total_duration_seconds = sum(d or 0 for _, _, d, _ in tracks) or None
        book.total_file_size = sum(s for _, _, _, s in tracks)
        book.track_count = len(tracks)
        # 扫描不覆盖已刮削字段
        if book.metadata_source != "scrape":
            first_tags = tracks[0][1] if tracks else {}
            book.title = _first_tag(first_tags, *TAG_TITLE_KEYS) or clean_title(
                Path(book_path).name
            )
            book.author = _first_tag(first_tags, *TAG_AUTHOR_KEYS) or _infer_author(
                Path(book_path), library_root
            )
            book.narrator = _first_tag(first_tags, *TAG_NARRATOR_KEYS)
            book.series = _first_tag(first_tags, *TAG_SERIES_KEYS)
            if not book.series:
                parent = Path(book_path).parent
                if parent != library_root and parent.is_dir() and not _dir_has_audio(parent):
                    part = _season_part_of(Path(book_path).name)
                    if part is not None:
                        book.series = parent.name
                        book.series_part = part

    saved_id = book.id
    db.flush()
    saved_id = book.id

    if not unchanged:
        for row in list(
            db.scalars(select(AudiobookTrack).where(AudiobookTrack.audiobook_id == saved_id)).all()
        ):
            db.delete(row)
        for ch in list(
            db.scalars(
                select(AudiobookChapter).where(AudiobookChapter.audiobook_id == saved_id)
            ).all()
        ):
            db.delete(ch)
        db.flush()

        for i, (file, tags, duration, size) in enumerate(tracks):
            db.add(
                AudiobookTrack(
                    audiobook_id=saved_id,
                    track_index=i,
                    title=_first_tag(tags, "title") or file.stem,
                    file_path=str(file),
                    format=_format_of(file),
                    duration_seconds=duration,
                    file_size=size,
                )
            )
        if single and audio_files[0].suffix.lower() in CHAPTER_CAPABLE:
            chapters = probe_chapters(audio_files[0])
            for i, ch in enumerate(chapters):
                db.add(
                    AudiobookChapter(
                        audiobook_id=saved_id,
                        chapter_index=i,
                        title=(ch.get("title") or "").strip() or f"Chapter {i + 1}",
                        start_seconds=ch.get("start") or 0,
                        end_seconds=ch.get("end") or 0,
                    )
                )
        db.flush()

    _ensure_cover(book, audio_files[0] if audio_files else None)
    db.flush()
    return is_new


def _tracks_unchanged(existing: list[AudiobookTrack], audio_files: list[Path]) -> bool:
    if not existing or len(existing) != len(audio_files):
        return False
    for i, file in enumerate(audio_files):
        t = existing[i]
        if t.track_index != i or t.file_path != str(file):
            return False
        try:
            if t.file_size != file.stat().st_size:
                return False
        except OSError:
            return False
        if not t.duration_seconds or t.duration_seconds <= 0:
            return False
    return True


def _dir_has_audio(dir_path: Path) -> bool:
    try:
        return any(p.is_file() and p.suffix.lower() in AUDIO_EXTS for p in dir_path.iterdir())
    except OSError:
        return True


def _infer_author(book_dir: Path, library_root: Path) -> str | None:
    parent = book_dir.parent
    if parent and parent != library_root and parent.is_dir() and not _dir_has_audio(parent):
        return parent.name
    return None


def _season_part_of(name: str) -> int | None:
    import re

    m = re.search(
        r"(?:第|Season\s*|S|卷|部|Part\s*|#)?\s*([0-9一二三四五六七八九十]+)\s*(?:季|部|卷|集)?$",
        name,
        re.I,
    )
    if not m:
        return None
    token = m.group(1)
    if token.isdigit():
        return int(token)
    cn = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
    return cn.get(token)


def _ensure_cover(book: Audiobook, first_audio: Path | None) -> None:
    if book.cover_art_path and Path(book.cover_art_path).is_file():
        return
    dir_path = Path(book.book_path)
    if not dir_path.is_dir():
        return
    for name in COVER_NAMES:
        candidate = dir_path / name
        if candidate.is_file():
            book.cover_art_path = str(candidate)
            return
    # 封面文件缺失时保持 cover_art_path 不变（占位图由 cover 端点生成）


def _cleanup_missing(db: Session, scanned_paths: set[str], library_id: int) -> int:
    removed = 0
    rows = list(db.scalars(select(Audiobook).where(Audiobook.library_id == library_id)).all())
    for book in rows:
        if book.book_path in scanned_paths:
            continue
        if _dir_has_audio(Path(book.book_path)):
            continue
        for p in db.scalars(
            select(AudiobookProgress).where(AudiobookProgress.audiobook_id == book.id)
        ).all():
            db.delete(p)
        for t in db.scalars(
            select(AudiobookTrack).where(AudiobookTrack.audiobook_id == book.id)
        ).all():
            db.delete(t)
        for c in db.scalars(
            select(AudiobookChapter).where(AudiobookChapter.audiobook_id == book.id)
        ).all():
            db.delete(c)
        db.delete(book)
        removed += 1
    db.flush()
    return removed
