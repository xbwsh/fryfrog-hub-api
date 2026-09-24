from __future__ import annotations

from pathlib import Path

from fryfrog.core.signer import sign
from fryfrog.core.utils import placeholder_jpeg


def comic_cover_path(comic_id: int) -> Path:
    """漫画封面统一放在应用数据区，避免污染媒体目录。"""
    return Path("data") / "covers" / "comic" / f"{comic_id}.jpg"


def signed_url(path: str) -> str:
    return sign(path)


def cover_bytes(path: str | None, label: str = "") -> tuple[bytes, str]:
    if path:
        from pathlib import Path

        p = Path(path)
        if p.is_file():
            data = p.read_bytes()
            suffix = p.suffix.lower()
            media = {
                ".jpg": "image/jpeg",
                ".jpeg": "image/jpeg",
                ".png": "image/png",
                ".webp": "image/webp",
                ".gif": "image/gif",
            }.get(suffix, "image/jpeg")
            return data, media
    return placeholder_jpeg(label=label), "image/jpeg"
