"""漫画整理：库根散包归入 作品名/ 目录（多卷同目录，自然序）。"""

from __future__ import annotations

import logging
import re
import shutil
from pathlib import Path

from sqlalchemy.orm import Session

from fryfrog.core.exceptions import BadRequestException
from fryfrog.core.natural_order import natural_key
from fryfrog.models.library import MediaLibrary
from fryfrog.services.comic_scan import ARCHIVE_EXTS, series_title_of

logger = logging.getLogger(__name__)


def _safe_name(name: str) -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")
    return cleaned or "book"


def organize_comics(db: Session, library: MediaLibrary, dry_run: bool = True) -> dict:
    if (library.type or "").upper() != "COMIC":
        raise BadRequestException("指定资源库不是 COMIC 类型")
    root = Path(library.path)
    if not root.is_dir():
        raise BadRequestException(f"资源库路径不存在: {library.path}")

    loose = sorted(
        (
            p
            for p in root.iterdir()
            if p.is_file()
            and p.suffix.lower() in ARCHIVE_EXTS
            and not p.name.startswith(".")
        ),
        key=lambda p: natural_key(p.name),
    )
    groups: dict[str, list[Path]] = {}
    for file in loose:
        groups.setdefault(series_title_of(file.stem), []).append(file)

    items: list[dict] = []
    moved = 0
    for series, files in sorted(groups.items(), key=lambda kv: natural_key(kv[0])):
        dest_dir = root / _safe_name(series)
        for file in files:
            dest = dest_dir / file.name
            if dest_dir.is_dir() and file.parent == dest_dir:
                items.append({"file": file.name, "action": "skip", "reason": "已在作品目录"})
                continue
            items.append(
                {
                    "file": str(file),
                    "action": "plan" if dry_run else "move",
                    "to": str(dest),
                    "series": series,
                }
            )
            if not dry_run:
                dest_dir.mkdir(parents=True, exist_ok=True)
                if dest.exists():
                    items[-1]["action"] = "skip"
                    items[-1]["reason"] = "目标已存在"
                    continue
                shutil.move(str(file), str(dest))
                moved += 1

    # 清理搬空后的空目录（库根下仅一层）
    if not dry_run:
        for child in list(root.iterdir()):
            if child.is_dir() and not any(child.iterdir()):
                try:
                    child.rmdir()
                except OSError:
                    pass

    return {
        "libraryId": library.id,
        "dryRun": dry_run,
        "items": items,
        "movedOrPlanned": sum(1 for i in items if i["action"] in ("plan", "move")),
        "moved": moved,
        "unchanged": sum(1 for i in items if i["action"] == "skip"),
    }
