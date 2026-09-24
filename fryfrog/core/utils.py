from __future__ import annotations

import re
from pathlib import Path

# 常见噪声 token（扩展名外）
_NOISE = re.compile(
    r"(?i)(1080p|720p|2160p|4k|x264|x265|h264|h265|hevc|aac|ac3|flac|web-?dl|"
    r"bluray|blu-ray|hdrip|webrip|hdtv|remux|proper|repack|internal|"
    r"chs|cht|gb|big5|简体|繁体|中字|双语|未删减|无删减|蓝光|高清|"
    r"cd1|cd2|disc1|disc2|part1|part2|mp3|flac|m4a|wav|epub|mobi|pdf|cbz|cbr|zip|rar)"
)

_BRACKET = re.compile(r"[\[\(【（][^\]\)】）]*[\]\)】）]")
_SEP = re.compile(r"[_\.]+")


def clean_title(name: str) -> str:
    """清洗文件/目录标题，尽量还原作品名。"""
    text = name
    # 去掉扩展名
    if "." in text and text.rsplit(".", 1)[-1].lower() in {
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mp3", "flac", "m4a",
        "wav", "wma", "aac", "ogg", "epub", "mobi", "azw3", "pdf", "cbz", "cbr", "zip", "rar", "m4b",
    }:
        text = text.rsplit(".", 1)[0]
    text = _BRACKET.sub(" ", text)
    text = _NOISE.sub(" ", text)
    text = _SEP.sub(" ", text)
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"[\s\-–—]+$", "", text)
    return text or name


def normalize_path(path: str | None) -> str | None:
    if path is None:
        return None
    try:
        return str(Path(path).resolve())
    except Exception:
        return path


def placeholder_jpeg(width: int = 400, height: int = 600, label: str = "") -> bytes:
    """生成灰底占位图 JPEG。"""
    from io import BytesIO

    from PIL import Image, ImageDraw

    img = Image.new("RGB", (width, height), (48, 48, 52))
    draw = ImageDraw.Draw(img)
    draw.rectangle([8, 8, width - 9, height - 9], outline=(90, 90, 96), width=2)
    if label:
        draw.text((20, height // 2), label[:40], fill=(160, 160, 168))
    buf = BytesIO()
    img.save(buf, format="JPEG", quality=85)
    return buf.getvalue()
