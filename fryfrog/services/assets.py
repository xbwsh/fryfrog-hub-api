from __future__ import annotations

from pathlib import Path

from fryfrog.core.signer import sign
from fryfrog.core.utils import placeholder_jpeg


def _cover_path(kind: str, item_id: int) -> Path:
    """返回持久化应用数据目录中的封面路径。"""
    from fryfrog.config import get_settings

    return get_settings().data_dir / "covers" / kind / f"{item_id}.jpg"


def comic_cover_path(comic_id: int) -> Path:
    """漫画封面统一放在应用数据区，避免污染媒体目录。"""
    return _cover_path("comic", comic_id)


def ebook_cover_path(book_id: int) -> Path:
    """电子书封面统一放在应用数据区，按 id 唯一命名。

    原先写 `<书目录>/cover.jpg`，同一目录下多本书会互相覆盖同一个文件
    （所有书的封面都指向最后写入者的那张），扫描还会污染用户媒体目录。
    与 comic_cover_path 同款处理。
    """
    return _cover_path("ebook", book_id)


def audiobook_cover_path(book_id: int) -> Path:
    """有声书封面统一放在应用数据区，理由同 ebook_cover_path。"""
    return _cover_path("audiobook", book_id)


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
