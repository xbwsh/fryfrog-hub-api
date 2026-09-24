"""有声书整理：库根扁平文件（剑来001.mp3）→ 作品/第一季/001.mp3。"""

from __future__ import annotations

import logging
import re
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from fryfrog.core.exceptions import BadRequestException
from fryfrog.core.natural_order import natural_key
from fryfrog.models.audiobook import Audiobook, AudiobookChapter, AudiobookProgress, AudiobookTrack
from fryfrog.models.library import MediaLibrary
from fryfrog.services.fsutil import AUDIOBOOK_EXTS

logger = logging.getLogger(__name__)

TAIL_DIGITS = re.compile(r"^(.+?)?[-_\s#·]*(\d{2,4})\s*$")
TAIL_DIGIT_ONE = re.compile(r"^(.+)[-_\s#·]+(\d)\s*$")
SEASON_PART = re.compile(
    r"^.+?(?:第|Season\s*|S|卷|部|Part\s*|#)?\s*([0-9一二三四五六七八九十]+)\s*(?:季|部|卷|集)?$",
    re.I,
)


def season_part_of(name: str) -> int | None:
    m = SEASON_PART.match(name.strip())
    if not m:
        return None
    token = m.group(1)
    if token.isdigit():
        return int(token)
    cn = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
    return cn.get(token)


def _split_title_number(stem: str) -> tuple[str, int] | None:
    m = TAIL_DIGITS.match(stem)
    if m and (m.group(1) or "").strip():
        prefix = (m.group(1) or "").strip()
        return prefix, int(m.group(2))
    m = TAIL_DIGIT_ONE.match(stem)
    if m and (m.group(1) or "").strip():
        return m.group(1).strip(), int(m.group(2))
    return None


def organize(db: Session, library: MediaLibrary, dry_run: bool = True) -> dict:
    if (library.type or "").upper() != "AUDIOBOOK":
        raise BadRequestException("指定资源库不是 AUDIOBOOK 类型")
    root = Path(library.path)
    if not root.is_dir():
        raise BadRequestException(f"资源库路径不存在: {library.path}")

    book = db.scalar(select(Audiobook).where(Audiobook.book_path == str(root)))
    items: list[dict] = []
    if book is None:
        return {"dryRun": dry_run, "items": [], "movedOrPlanned": 0, "unchanged": 0}

    files = [
        p
        for p in root.iterdir()
        if p.is_file() and p.suffix.lower() in AUDIOBOOK_EXTS and not p.name.startswith(".")
    ]
    if not files:
        return {"dryRun": dry_run, "items": [], "movedOrPlanned": 0, "unchanged": 0}

    groups: dict[str, list[Path]] = {}
    for file in files:
        split = _split_title_number(file.stem)
        if not split:
            return {
                "dryRun": dry_run,
                "items": [{"file": file.name, "action": "skip", "reason": "无法解析作品前缀"}],
                "movedOrPlanned": 0,
                "unchanged": 0,
            }
        groups.setdefault(split[0], []).append(file)

    moved = 0
    for work, group in sorted(groups.items()):
        season_dir = root / work / f"{work}第一季"
        group = sorted(group, key=lambda p: natural_key(p.name))
        for i, file in enumerate(group):
            target = season_dir / f"{i + 1:03d}{file.suffix.lower()}"
            items.append(
                {
                    "from": str(file),
                    "to": str(target),
                    "action": "plan" if dry_run else "move",
                }
            )
            if not dry_run:
                target.parent.mkdir(parents=True, exist_ok=True)
                if file.resolve() != target.resolve():
                    file.replace(target)
            moved += 1

    if not dry_run and groups:
        # 简化：删除原扁平书，下次扫描重建结构
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
        db.flush()

    return {"dryRun": dry_run, "items": items, "movedOrPlanned": moved, "unchanged": 0}
