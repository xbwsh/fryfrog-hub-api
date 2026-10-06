from __future__ import annotations

import re
from pathlib import Path

from fryfrog.core.natural_order import natural_compare
from fryfrog.core.utils import clean_title
from fryfrog.media_core import get_media_probe

VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".ts", ".m2ts", ".webm"}
MUSIC_EXTS = {".mp3", ".flac", ".m4a", ".wav", ".wma", ".aac", ".ogg", ".opus", ".ape"}
AUDIOBOOK_EXTS = MUSIC_EXTS | {".m4b"}
EBOOK_EXTS = {".epub", ".pdf", ".mobi", ".azw3", ".txt"}
COMIC_ARCHIVE_EXTS = {".zip", ".cbz", ".rar", ".cbr", ".7z", ".pdf"}
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


def iter_files(root: Path, exts: set[str]) -> list[Path]:
    if not root.exists():
        return []
    result = [p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in exts]
    result.sort(key=lambda p: natural_compare(p.name, p.name))
    return sorted(result, key=lambda p: (str(p.parent), p.name))


# `#12` / `＃12`（全角）形式的集号，JAV/同人动画常见。
#
# 为什么要加这条：实测库里 `Boin 巨乳教学 #1/#2`、`少女们的茶道ISM #1/#2`、
# `樱春女学院的男优 #1` 这批**完全解析不出集号**，于是被当单片入库
# （season_number/episode_number 都是 None），进不了正片季集体系。
# 对照 MHTI（xiyan520/MHTI）的解析器声明支持「裸数字与全角井号集数」。
#
# 两个负向断言（避免误判）：
#   (?<![\dA-Za-z])  前面不能紧跟字母/数字 —— 挡住 `FUCK#1` 这类无分隔的粘连，
#                    也避免把 `AB#12` 当集号；
#   (?!\d)           后面不能还有数字 —— 挡住 `#1234` 这种超长编号。
_HASH_EPISODE = re.compile(r"(?<![\dA-Za-z])[#＃]\s*(\d{1,3})(?!\d)")

# 从剧名里剥掉集号标记（与下面 SxxExx / 1x05 的处理保持一致）
_HASH_EPISODE_STRIP = re.compile(r"[#＃]\s*\d{1,3}(?!\d)")


def parse_episode(title: str) -> tuple[str, int | None, int | None]:
    """返回 (seriesName, season, episode) 的粗解析。

    按「越具体越优先」的顺序尝试：SxxExx → 1x05 → EP/第N话/第N集 → **#N**。
    """
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
            # 两个分支分开写很关键：
            #  - `第 N` **必须**带集数标记（集/话/話）才算，否则 "第 2 季" 的 2
            #    会被当成集号（实测 `某剧 第 2 季 #3` 曾解析出季=2 集=2；
            #    把标记写成可选 `[集话話]?` 也一样会误匹配 "第 2 "）。
            #  - `EP05` 这类前缀不需要额外标记。
            m = re.search(r"第\s*(\d{1,3})\s*[集话話]", title) or re.search(
                r"\bEP?\s*(\d{1,3})(?!\d)", title, re.I
            )
            if m:
                episode = int(m.group(1))
            else:
                # `#N` 放在最后：前面几条都更明确，命中就不用看这个
                m = _HASH_EPISODE.search(title)
                if m:
                    episode = int(m.group(1))
            m = re.search(r"第\s*(\d{1,2})\s*季", title, re.I)
            if m:
                season = int(m.group(1))
    name = re.sub(r"S\d{1,2}\s*E\d{1,3}", " ", title, flags=re.I)
    name = re.sub(r"\d{1,2}x\d{2,3}", " ", name, flags=re.I)
    name = _HASH_EPISODE_STRIP.sub(" ", name)
    return clean_title(name), season, episode


def probe_duration(path: Path) -> float | None:
    return get_media_probe().probe_video_duration(str(path)) if path.suffix.lower() in VIDEO_EXTS else None
