"""TXT 在线阅读：编码探测、章节切分、正文读取（按 path+mtime 内存缓存）。"""

from __future__ import annotations

import re
from functools import lru_cache
from pathlib import Path

from fryfrog.core.exceptions import ResourceNotFoundException

# 行首章节标记：第X章/卷/回/节…、Chapter N、序幕/楔子/尾声等；限短行避免误伤正文
_CHAPTER_LINE = re.compile(
    r"^\s*(?:"
    r"第\s*[0-9０-９一二三四五六七八九十百千零〇两]+\s*[章卷回节集部篇](?:\s*[:：].*|[^\n]{0,50})?"
    r"|Chapter\s+\d+(?:\b.*)?"
    r"|序幕|楔子|尾声|后记|前言|引子|番外\s*\d*"
    r")\s*$",
    re.IGNORECASE,
)
MAX_TITLE_LEN = 60


def decode_text(data: bytes) -> str:
    """中文 TXT 常见编码：UTF-8（含 BOM）→ GB18030 → BIG5，最后宽松解码。"""
    if data.startswith(b"\xef\xbb\xbf"):
        return data.decode("utf-8-sig", errors="replace")
    if data.startswith(b"\xff\xfe") or data.startswith(b"\xfe\xff"):
        return data.decode("utf-16", errors="replace")
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        pass
    for enc in ("gb18030", "big5"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def split_chapters(text: str) -> list[dict]:
    """按行首章节标记切分；无标记则整本为一章。返回 [{index, title, start, end}]（字符偏移）。"""
    matches: list[tuple[int, str]] = []
    offset = 0
    for line in text.split("\n"):
        stripped = line.strip()
        if stripped and len(stripped) <= MAX_TITLE_LEN and _CHAPTER_LINE.match(stripped):
            matches.append((offset + (len(line) - len(line.lstrip())), stripped))
        offset += len(line) + 1

    chapters: list[dict] = []
    if not matches:
        return [{"index": 0, "title": "全文", "start": 0, "end": len(text)}]
    if matches[0][0] > 0:
        chapters.append({"index": 0, "title": "开头", "start": 0, "end": matches[0][0]})
    for i, (start, title) in enumerate(matches):
        end = matches[i + 1][0] if i + 1 < len(matches) else len(text)
        chapters.append(
            {"index": len(chapters), "title": title, "start": start, "end": end}
        )
    return chapters


@lru_cache(maxsize=8)
def _load(path_str: str, mtime: int) -> tuple[str, tuple[dict, ...]]:
    path = Path(path_str)
    if not path.is_file():
        raise ResourceNotFoundException("File", "path", path_str)
    text = decode_text(path.read_bytes())
    return text, tuple(split_chapters(text))


def _snapshot(path: Path) -> tuple[str, int]:
    try:
        return str(path), int(path.stat().st_mtime)
    except OSError:
        raise ResourceNotFoundException("File", "path", str(path))


def chapters_of(path: Path) -> list[dict]:
    path_str, mtime = _snapshot(path)
    return list(_load(path_str, mtime)[1])


def chapter_page(path: Path, page: int, size: int) -> dict:
    """分页章节目录：只返回当前页（大书全量返回会让前端滚动卡顿）。

    返回 {content, page, size, totalElements, totalPages}，与 PageResponse 对齐。
    """
    path_str, mtime = _snapshot(path)
    text, chapters = _load(path_str, mtime)
    total = len(chapters)
    size = max(1, size)
    start = max(0, page) * size
    content = [dict(c) for c in chapters[start : start + size]]
    total_pages = (total + size - 1) // size
    return {
        "content": content,
        "page": page,
        "size": size,
        "totalElements": total,
        "totalPages": total_pages,
    }


def chapter_count(path: Path) -> int:
    return len(chapters_of(path))


def chapter_content(path: Path, index: int) -> dict:
    path_str, mtime = _snapshot(path)
    text, chapters = _load(path_str, mtime)
    if index < 0 or index >= len(chapters):
        raise ResourceNotFoundException("EbookChapter", "index", index)
    ch = chapters[index]
    return {
        "index": ch["index"],
        "title": ch["title"],
        "text": text[ch["start"] : ch["end"]],
        "chapterCount": len(chapters),
        "charCount": len(text),
    }
