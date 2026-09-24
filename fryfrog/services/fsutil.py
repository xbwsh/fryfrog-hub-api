from __future__ import annotations

from pathlib import Path

from fryfrog.core.natural_order import natural_compare
from fryfrog.core.utils import clean_title
from fryfrog.media_core import get_media_probe

VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".ts", ".m2ts", ".webm"}
MUSIC_EXTS = {".mp3", ".flac", ".m4a", ".wav", ".wma", ".aac", ".ogg", ".opus", ".ape"}
AUDIOBOOK_EXTS = MUSIC_EXTS | {".m4b"}
EBOOK_EXTS = {".epub", ".pdf", ".mobi", ".azw3"}
COMIC_ARCHIVE_EXTS = {".zip", ".cbz", ".rar", ".cbr", ".7z"}
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


def iter_files(root: Path, exts: set[str]) -> list[Path]:
    if not root.exists():
        return []
    result = [p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in exts]
    result.sort(key=lambda p: natural_compare(p.name, p.name))
    return sorted(result, key=lambda p: (str(p.parent), p.name))


def parse_episode(title: str) -> tuple[str, int | None, int | None]:
    """返回 (seriesName, season, episode) 的粗解析。"""
    import re

    season = episode = None
    m = re.search(r"S(\d{1,2})\s*E(\d{1,3})", title, re.I)
    if m:
        season, episode = int(m.group(1)), int(m.group(2))
    else:
        m = re.search(r"(\d{1,2})x(\d{2,3})", title, re.I)
        if m:
            season, episode = int(m.group(1)), int(m.group(2))
        else:
            m = re.search(r"(?:EP?|第)\s*(\d{1,3})", title, re.I)
            if m:
                episode = int(m.group(1))
            m = re.search(r"第\s*(\d{1,2})\s*季", title, re.I)
            if m:
                season = int(m.group(1))
    name = re.sub(r"S\d{1,2}\s*E\d{1,3}", " ", title, flags=re.I)
    name = re.sub(r"\d{1,2}x\d{2,3}", " ", name, flags=re.I)
    return clean_title(name), season, episode


def probe_duration(path: Path) -> float | None:
    return get_media_probe().probe_video_duration(str(path)) if path.suffix.lower() in VIDEO_EXTS else None
