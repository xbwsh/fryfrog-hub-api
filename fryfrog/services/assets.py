from __future__ import annotations

from fryfrog.core.signer import sign
from fryfrog.core.utils import placeholder_jpeg


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
