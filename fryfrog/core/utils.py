from __future__ import annotations

import re
from pathlib import Path

# 常见噪声 token（扩展名外）。两侧用 ASCII 字母/数字边界夹住：
# 只删「独立成词的发布标记」，不碰 `Assassin's Creed`、`class`、`24K` 这类
# 标题里的合法子串。分支按「长词在前」排列，避免 `hdr10plus` 被 `hdr10` 截胡。
_NOISE = re.compile(
    r"(?i)(?<![a-z0-9])("
    # 分辨率
    r"4320p|2160p|1080p|720p|576p|480p|8k|4k|"
    # HDR / 动态范围
    r"hdr10plus|hdr10|dolbyvision|dolby|dovi|hdr|"
    # 视频编码
    r"h\.?265|h\.?264|x265|x264|hevc|avc|av1|vp9|vp8|xvid|divx|mpeg[24]|"
    # 音频
    r"truehd|dts-?x|dts|atmos|dd(?:\+|p)|eac3|ac3|aac|flac|alac|pcm|lpcm|opus|ogg|mp3|m4a|m4b|wav|"
    # 字幕
    r"subbed|dubbed|subs?|ass|srt|dual-?audio|multi-?audio|"
    # 来源
    r"web-?dl|webrip|hdtv|hdrip|bdrip|brrip|dvdrip|blu-?ray|remux|"
    # 版本标记
    r"openmatte|director'?s?[\s.\-]*cut|extended|theatrical|unrated|uncensored|censored|"
    r"complete|scalepack|scaled|remaster|proper|repack|internal|imax|ova|"
    # 平台
    r"crunchyroll|funimation|hidive|netflix|hmax|dsnp|atvp|itunes|amzn|"
    # 语言 / 中文字幕
    r"chs|cht|gb|big5|简体|繁体|中字|国语|台配|粤配|国配|内嵌|外挂|双音轨|双语|字幕|未删减|无删减|蓝光|高清|"
    # 分卷 / 色深 / 帧率 / 媒体格式
    r"cd\d+|disc\d+|part\s*[12]|10bit|12bit|8bit|ma10p|\d{2,3}fps|"
    r"epub|mobi|pdf|cbz|cbr|zip|rar"
    r")(?![a-z0-9])"
)

_BRACKET = re.compile(r"[\[\(【（][^\]\)】）]*[\]\)】）]")
_SEP = re.compile(r"[_\.]+")

# 集号标记：S01E01 / 1x01 / 第1集
_EPISODE_MARK = re.compile(
    r"(?i)S\d{1,2}\s*E\d{1,3}|(?<!\d)\d{1,2}x\d{2,3}(?!\d)|第\s*\d{1,3}\s*[集话話]"
)
# 发布年份：后面还跟着别的内容（如 .2025.S01E01）才认定是年份，避免误伤《Blade Runner 2049》
_RELEASE_YEAR = re.compile(r"[\.\s_\-](?:19|20)\d{2}(?=[\.\s_\-]\S)")
_CJK = re.compile(r"[\u3400-\u9fff\u3040-\u30ff]")


def title_head(name: str) -> str:
    """取发布名里集号标记之前的标题部分，并去掉末尾的发布年份。

    入参是文件名（含扩展名）或已去扩展名的名字，二者皆可。
    """
    stem = Path(name).stem
    years = list(_RELEASE_YEAR.finditer(stem))
    # 只截最后一处年份，保留标题里自带的年份（如《Blade Runner 2049》）
    head = stem[: years[-1].start()] if years else stem
    return _EPISODE_MARK.split(head)[0]


def title_parts(name: str) -> list[str]:
    """发布名的各标题段（已清洗），如「中文名.英文名」→ [中文名, 英文名]。"""
    # 先剥掉括号（含组名/集号/编码标记），否则 `[x265_flac_ass]` 会被
    # `[._]+` 拆成 `'][', 'flac', 'ass]'` 这类垃圾段。
    head = _BRACKET.sub(" ", title_head(name))
    parts = [clean_title(part) for part in re.split(r"[._]+", head)]
    return [part for part in parts if part]


def primary_title(name: str) -> str:
    """发布名的主标题：中文名.英文名.2025.S01E01 → 中文名；无中文段则取整名。"""
    parts = title_parts(name)
    for part in parts:
        if _CJK.search(part):
            return part
    return clean_title(title_head(name)) or (parts[0] if parts else "")


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
