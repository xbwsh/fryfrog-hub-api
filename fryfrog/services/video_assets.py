from __future__ import annotations

import logging
import shutil
import subprocess
from pathlib import Path

import httpx
from sqlalchemy.orm import Session

from fryfrog.core.http import make_client
from fryfrog.core.signer import sign
from fryfrog.core.utils import placeholder_jpeg
from fryfrog.media_core import get_ffmpeg_runtime, get_media_probe
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services.video_service import (
    get_base_name,
    get_fanart_path,
    get_metadata_dir,
    get_nfo_path,
    get_poster_path,
    rename_show_dir,
    get_season_dir,
    get_series_root_dir,
    get_video_assets_dir,
)

logger = logging.getLogger(__name__)

IMAGE_CACHE = "public, max-age=604800, immutable"
FRAME_RATIOS = (0.12, 0.28, 0.44, 0.60, 0.76, 0.88)
SUBTITLE_EXTS = {".srt", ".ass", ".ssa", ".vtt", ".sub", ".sup", ".idx"}


def media_type_of(name: str) -> str:
    lower = name.lower()
    if lower.endswith(".svg"):
        return "image/svg+xml"
    if lower.endswith(".png"):
        return "image/png"
    if lower.endswith(".webp"):
        return "image/webp"
    if lower.endswith(".gif"):
        return "image/gif"
    return "image/jpeg"


def read_image(path: str | None, label: str = "", width: int = 300, height: int = 450) -> tuple[bytes, str]:
    if path:
        p = Path(path)
        if p.is_file():
            return p.read_bytes(), media_type_of(p.name)
    return placeholder_jpeg(width, height, label), "image/jpeg"


def download_image(url: str, target: Path, force: bool = False) -> bool:
    if not force and target.exists():
        return True
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        with make_client(timeout=30.0) as client:
            resp = client.get(url)
            if resp.status_code == 404:
                return False
            resp.raise_for_status()
            if not resp.content:
                return False
            target.write_bytes(resp.content)
            return True
    except Exception:
        logger.debug("下载图片失败: %s", url, exc_info=True)
        return False


def download_url_bytes(url: str) -> bytes | None:
    try:
        with make_client(timeout=15.0) as client:
            resp = client.get(url)
            resp.raise_for_status()
            return resp.content or None
    except Exception:
        return None


def logo_file_url(local_path: str | None, api_path: str) -> str | None:
    if local_path and Path(local_path).exists():
        return sign(api_path)
    return None


# -------------------- 季集号 --------------------

def season_of(video: Video) -> int:
    """季号，保留第 0 季（特别篇/OVA）。

    不能用 `video.season_number or 1`：`S00E01` → 0，而 `0 or 1` 得 1，
    会把特别篇错并进第 1 季。TMDB 约定特别篇就是 **Season 0**。
    """
    value = video.season_number
    return 1 if value is None else int(value)


def episode_of(video: Video) -> int:
    """集号，0 也保留（部分素材用 E00 表示整季/特典）。"""
    value = video.episode_number
    return 0 if value is None else int(value)


# Common logos users drop next to media (Jellyfin / Kodi / Ember style).
# 除固定名外还会匹配带序号/副本后缀的变体（如 `tvshow-logo (1).png`），
# 刮削工具改名或手动多存几份时都常见。
VIDEO_LOGO_FILENAMES = (
    "movie-logo.png",
    "movie-logo.jpg",
    "movie-logo.jpeg",
    "movie-logo.webp",
    "clearlogo.png",
    "clearlogo.jpg",
    "logo.png",
    "logo.jpg",
)
SERIES_LOGO_FILENAMES = (
    "tvshow-logo.png",
    "tvshow-logo.jpg",
    "clearlogo.png",
    "clearlogo.jpg",
    "logo.png",
    "logo.jpg",
)
_LOGO_EXTS = (".png", ".jpg", ".jpeg", ".webp", ".gif")


def _iter_logo_files(directory: Path, names: tuple[str, ...]) -> list[Path]:
    """目录下匹配候选名的 logo 文件（含 `xxx (1).png` 这类副本后缀）。

    固定名优先，保证同一目录有多份时先取标准命名。
    """
    found: list[Path] = []
    try:
        entries = {p.name.lower(): p for p in directory.iterdir() if p.is_file()}
    except OSError:
        return found
    for name in names:
        hit = entries.get(name)
        if hit is not None:
            found.append(hit)
    if found:
        return found
    # 无标准命名时退到“基名 + 可选空格/括号序号 + 扩展名”
    bases = {Path(n).stem.lower() for n in names}
    try:
        for entry in sorted(directory.iterdir(), key=lambda p: p.name.lower()):
            if not entry.is_file():
                continue
            lower = entry.name.lower()
            if not lower.endswith(_LOGO_EXTS):
                continue
            stem = entry.stem.lower()
            for base in bases:
                rest = stem[len(base):] if stem.startswith(base) else None
                if rest is None:
                    continue
                # 允许 " (1)" / "-1" / "_2" / " 副本" 之类的副本后缀
                if rest.strip(" ()-_0123456789") == "" or rest.strip() == "":
                    found.append(entry)
                    break
            if found:
                break
    except OSError:
        pass
    return found


def find_local_video_logo(video: Video) -> Path | None:
    """Side-by-side logo next to the media file (e.g. movie-logo.png)."""
    try:
        parent = Path(video.file_path).parent
    except Exception:
        return None
    found = _iter_logo_files(parent, VIDEO_LOGO_FILENAMES)
    return found[0] if found else None


def find_local_series_logo(db: Session, episodes: list[Video]) -> Path | None:
    """tvshow-logo / logo under episode folder or season metadata dir."""
    if not episodes:
        return None
    seen: set[str] = set()
    for ep in episodes:
        candidates: list[Path] = []
        try:
            parent = Path(ep.file_path).parent
            candidates.extend(_iter_logo_files(parent, SERIES_LOGO_FILENAMES))
        except Exception:
            pass
        season_dir = get_season_dir(db, ep)
        if season_dir:
            candidates.extend(_iter_logo_files(season_dir, SERIES_LOGO_FILENAMES))
        for p in candidates:
            key = str(p)
            if key in seen:
                continue
            seen.add(key)
            try:
                if p.is_file():
                    return p
            except Exception:
                continue
    return None



# -------------------- NFO --------------------

def find_nfo_path(db: Session, video: Video) -> Path | None:
    candidates = [
        get_nfo_path(db, video),
        Path(video.file_path).parent / f"{get_base_name(video.file_name)}.nfo",
        Path(video.file_path).with_suffix(".nfo"),
    ]
    for p in candidates:
        if p and p.is_file():
            return p
    return None


def parse_nfo(db: Session, video: Video) -> bool:
    """从已有 NFO 回填元数据（换库/重扫后恢复 tmdbId 等）。"""
    import xml.etree.ElementTree as ET

    nfo_path = find_nfo_path(db, video)
    if not nfo_path:
        return False
    try:
        root = ET.parse(nfo_path).getroot()
    except Exception:
        logger.debug("解析 NFO 失败: %s", nfo_path, exc_info=True)
        return False

    def text(tag: str) -> str | None:
        el = root.find(tag)
        if el is not None and el.text and el.text.strip():
            return el.text.strip()
        return None

    changed = False
    for tag, attr in (
        ("title", "title"),
        ("originaltitle", "original_title"),
        ("plot", "overview"),
        ("director", "director"),
        ("studio", "studio"),
        ("mpaa", None),
    ):
        if attr is None:
            continue
        val = text(tag)
        if val and not getattr(video, attr):
            setattr(video, attr, val)
            changed = True

    if not video.genre:
        genres = [g.text.strip() for g in root.findall("genre") if g.text and g.text.strip()]
        if genres:
            video.genre = ",".join(genres)
            changed = True

    year = text("year")
    if year and not video.year:
        try:
            video.year = int(year)
            changed = True
        except ValueError:
            pass

    premiered = text("premiered") or text("releasedate")
    if premiered and not video.release_date:
        video.release_date = premiered
        changed = True

    runtime = text("runtime")
    if runtime and not video.duration_minutes:
        try:
            video.duration_minutes = int(float(runtime))
            changed = True
        except ValueError:
            pass

    rating_el = root.find("ratings/rating/value")
    if rating_el is not None and rating_el.text and not video.rating:
        try:
            video.rating = float(rating_el.text)
            changed = True
        except ValueError:
            pass
    elif root.find("rating") is not None and root.find("rating").text and not video.rating:
        try:
            video.rating = float(root.find("rating").text)
            changed = True
        except ValueError:
            pass

    votes = text("votes")
    if votes and not video.vote_count:
        try:
            video.vote_count = int(votes)
            changed = True
        except ValueError:
            pass

    # <adult>true</adult> 只升不降：库里已有 18+ 标记时不被 NFO 的 false 摘掉。
    if not video.is_adult:
        adult = text("adult")
        if adult and adult.strip().lower() in ("true", "1", "yes"):
            video.is_adult = True
            changed = True

    actors = []
    for actor in root.findall("actor"):
        name = actor.findtext("name")
        if name and name.strip():
            actors.append(name.strip())
    if actors and not video.actors:
        video.actors = ",".join(actors[:12])
        changed = True

    for uid in root.findall("uniqueid"):
        uid_type = (uid.get("type") or "").lower()
        val = (uid.text or "").strip()
        if not val:
            continue
        if uid_type == "tmdb" and not video.tmdb_id:
            try:
                video.tmdb_id = int(val)
                changed = True
            except ValueError:
                pass
        elif uid_type == "imdb" and not video.imdb_id:
            video.imdb_id = val
            changed = True

    if video.tmdb_id and not video.metadata_source:
        video.metadata_source = "nfo"
        changed = True

    if changed:
        db.flush()
    return bool(video.tmdb_id or changed)


def find_series_nfo(video: Video) -> Path | None:
    """从分集目录向上找 tvshow.nfo（分集目录 → 季目录 → 剧目录）。"""
    d = Path(video.file_path).parent
    for _ in range(4):
        p = d / "tvshow.nfo"
        if p.is_file():
            return p
        parent = d.parent
        if parent == d:
            break
        d = parent
    return None


def parse_series_nfo(db: Session, video: Video) -> bool:
    """从 tvshow.nfo 回填系列行的简介/评分/年份等（字段为空才写）。

    详情页的「简介」和 ★ 评分读的是系列行；此前只有分集 NFO 解析器，
    系列级 tvshow.nfo 从未被读取——外部刮削的剧这两项一直是空的。
    """
    import xml.etree.ElementTree as ET

    series = video.series
    if series is None:
        return False
    nfo = find_series_nfo(video)
    if nfo is None:
        return False
    try:
        root = ET.parse(nfo).getroot()
    except Exception:
        logger.debug("解析 tvshow.nfo 失败: %s", nfo, exc_info=True)
        return False

    def text(tag: str) -> str | None:
        el = root.find(tag)
        if el is not None and el.text and el.text.strip():
            return el.text.strip()
        return None

    changed = False
    if not series.overview:
        plot = text("plot")
        if plot:
            series.overview = plot
            changed = True

    if not series.rating:
        rating_el = root.find("ratings/rating/value")
        raw = rating_el.text if rating_el is not None else None
        if not (raw and raw.strip()):
            flat = root.find("rating")
            raw = flat.text if flat is not None else raw
        if raw and raw.strip():
            try:
                series.rating = float(raw.strip())
                changed = True
            except ValueError:
                pass

    if not series.year:
        year = text("year")
        if year:
            try:
                series.year = int(year)
                changed = True
            except ValueError:
                pass

    if not series.original_title:
        original = text("originaltitle")
        if original:
            series.original_title = original
            changed = True

    if not series.release_date:
        premiered = text("premiered") or text("releasedate")
        if premiered:
            series.release_date = premiered
            changed = True

    if not series.status:
        status = text("status")
        if status:
            series.status = status
            changed = True

    if not series.is_adult:
        adult = text("adult")
        if adult and adult.strip().lower() in ("true", "1", "yes"):
            series.is_adult = True
            changed = True

    if changed:
        db.flush()
    return changed

def generate_nfo(db: Session, video: Video) -> str | None:
    try:
        nfo_path = get_nfo_path(db, video)
        # 目录可能已被清理（空壳素材目录回收、视频被搬移），写前先补建
        nfo_path.parent.mkdir(parents=True, exist_ok=True)
        nfo_path.write_text(_build_nfo(video), encoding="utf-8")
        return str(nfo_path)
    except Exception:
        logger.exception("生成 NFO 失败: %s", video.file_name)
        return None


def _tag(name: str, value) -> str:
    if value is None or value == "":
        return ""
    return f"  <{name}>{value}</{name}>\n"


def _build_nfo(video: Video) -> str:
    is_tv = video.is_episode
    root = "episodedetails" if is_tv else "movie"
    parts = [
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n',
        f"<{root}>\n",
        _tag("title", video.title),
        _tag("originaltitle", video.original_title),
        _tag("plot", video.overview),
        _tag("director", video.director),
        _tag("genre", video.genre),
        _tag("year", video.year),
        _tag("rating", video.rating),
        _tag("votes", video.vote_count),
        _tag("premiered", video.release_date),
        _tag("runtime", video.duration_minutes),
        _tag("mpaa", "NC-17" if video.is_adult else "PG"),
        _tag("studio", video.studio),
    ]
    if video.tmdb_id:
        parts.append(f'  <uniqueid type="tmdb" default="true">{video.tmdb_id}</uniqueid>\n')
    if video.imdb_id:
        parts.append(f'  <uniqueid type="imdb">{video.imdb_id}</uniqueid>\n')
    if is_tv:
        season = season_of(video)
        episode = episode_of(video)
        parts.append(_tag("season", season))
        parts.append(_tag("episode", episode))
        if video.series_name or (video.series and video.series.title):
            parts.append(_tag("showtitle", video.series_name or video.series.title))
    for actor_name in [a.strip() for a in (video.actors or "").split(",") if a.strip()]:
        parts.append(f"  <actor>\n    <name>{actor_name}</name>\n  </actor>\n")
    parts.append(f"</{root}>\n")
    return "".join(parts)


# -------------------- 封面 / Logo --------------------

def find_shared_vertical_poster(db: Session, video: Video) -> Path | None:
    """分集竖屏封面的共享来源：季海报 → 剧根目录总海报。

    与「刷新季海报」的既定策略一致：竖图整季共用，横屏 still 每集单独。
    """
    if not video.is_episode:
        return None
    season = get_season_dir(db, video)
    if season:
        p = season / "tvshow-poster.jpg"
        if p.is_file():
            return p
    root = get_series_root_dir(db, [video])
    if root:
        p = root / "tvshow-poster.jpg"
        if p.is_file():
            return p
    return None


def prune_private_vertical_cover(db: Session, video: Video) -> bool:
    """分集已有共享竖图（季/剧海报）时，删除其私有竖图副本并清空字段。

    与「刷新季海报」的清理一致；扫描时对已绑定的分集也生效
    （它们不会再走下载路径，不主动清就永远留着）。
    """
    if not video.is_episode or not video.cover_art_path:
        return False
    shared = find_shared_vertical_poster(db, video)
    if shared is None:
        return False
    stale = Path(video.cover_art_path)
    try:
        if stale.exists() and stale != shared:
            stale.unlink()
        video.cover_art_path = None
        db.flush()
        return True
    except OSError:
        logger.debug("清理分集私有竖图失败: %s", stale, exc_info=True)
        return False


def download_all_covers(
    db: Session, video: Video, force: bool = False, poster: bool = True
) -> bool:
    poster_ok = False
    fanart_ok = False
    # 分集竖屏共用季/剧海报：共享图已存在时不再为分集落竖图，
    # 并清掉历史遗留的私有副本（横屏 still 每集照旧保留）。
    if poster and video.is_episode and find_shared_vertical_poster(db, video):
        poster = False
        prune_private_vertical_cover(db, video)
    if poster and video.poster_url:
        poster_ok = download_image(_full_image_url(video.poster_url), get_poster_path(db, video), force)
        if poster_ok:
            video.cover_art_path = str(get_poster_path(db, video))
    # 分集横屏是 TMDB 单集 still：分集恒定 force 下载，覆盖历史「剧 backdrop 拷贝」文件
    if video.backdrop_url:
        fanart_ok = download_image(
            _full_image_url(video.backdrop_url),
            get_fanart_path(db, video),
            True if video.is_episode else force,
        )
        if fanart_ok:
            video.backdrop_local_path = str(get_fanart_path(db, video))
    db.flush()
    return poster_ok or fanart_ok


def download_series_root_art(
    db: Session, series: VideoSeries, episodes: list[Video], detail: dict | None = None
) -> dict:
    """总封面落地到剧名根目录（与季文件夹同级）：tvshow-poster.jpg + tvshow-fanart.jpg。

    季横屏复用总横屏，不再逐季写入。detail 可传入已取好的 TMDB tv 详情避免重复请求。
    """
    from fryfrog.services.tmdb import TmdbClient

    result = {"poster": False, "fanart": False}
    root = get_series_root_dir(db, episodes)
    if root is None or not series.tmdb_id:
        return result
    client = TmdbClient()
    if detail is None:
        detail = client.get_tv(series.tmdb_id) or {}
    root.mkdir(parents=True, exist_ok=True)

    poster_url = client.image_url(detail.get("poster_path")) or series.poster_url
    if poster_url:
        target = root / "tvshow-poster.jpg"
        if download_image(_full_image_url(poster_url), target, force=True):
            result["poster"] = True
            series.poster_local_path = str(target)

    backdrop_url = (
        client.image_url(detail.get("backdrop_path"), "original") or series.backdrop_url
    )
    if backdrop_url:
        target = root / "tvshow-fanart.jpg"
        if download_image(_full_image_url(backdrop_url), target, force=True):
            result["fanart"] = True

    db.flush()
    return result


def _repair_tmdb_url(url: str) -> str:
    """修正历史数据里缺 /p/ 的图片 URL（image.tmdb.org/t/{size} → /t/p/{size}）。"""
    if "image.tmdb.org/t/p/" in url:
        return url
    return url.replace("image.tmdb.org/t/", "image.tmdb.org/t/p/", 1) if url.startswith("http") else url


def _full_image_url(url: str) -> str:
    if url.startswith("http"):
        return _repair_tmdb_url(url)
    from fryfrog.config import get_settings

    size = get_settings().tmdb_image_size or "original"
    return f"https://image.tmdb.org/t/p/{size}{url}"


def _tmdb_image_urls(path: str) -> list[str]:
    """TMDB 图片 URL 列表（按尺寸回退）。统一走 make_client：有代理走代理，否则直连。"""
    if path.startswith("http"):
        return [_repair_tmdb_url(path)]
    if not path.startswith("/"):
        path = "/" + path
    return [
        f"https://image.tmdb.org/t/p/original{path}",
        f"https://image.tmdb.org/t/p/w780{path}",
        f"https://image.tmdb.org/t/p/w500{path}",
        f"https://image.tmdb.org/t/p/w342{path}",
        f"https://image.tmdb.org/t/p/w300{path}",
    ]


def fetch_tmdb_image(path_or_url: str | None) -> bytes | None:
    """按尺寸回退拉取 TMDB 图（API/CDN 均经 make_client，遵守 PROXY_HOST）。"""
    if not path_or_url:
        return None
    for url in _tmdb_image_urls(path_or_url):
        data = download_url_bytes(url)
        if data:
            return data
    return None


def download_movie_logo(db: Session, video: Video, file_path: str | None = None, force: bool = False) -> bool:
    from fryfrog.services.tmdb import TmdbClient

    if video.logo_local_path and Path(video.logo_local_path).exists() and not force and not file_path:
        return True
    target_path = file_path or video.logo_url
    if not target_path and video.tmdb_id:
        client = TmdbClient()
        logos = _movie_logos(client, video.tmdb_id)
        target_path = logos[0].get("file_path") if logos else None
    if not target_path:
        return False
    dest = get_video_assets_dir(video) / f"{get_base_name(video.file_name)}-logo.png"
    dest.parent.mkdir(parents=True, exist_ok=True)
    ok = False
    for url in _tmdb_image_urls(target_path):
        ok = download_image(url, dest, force=True)
        if ok:
            break
    if ok:
        video.logo_local_path = str(dest)
        video.logo_url = target_path if not target_path.startswith("http") else video.logo_url
        db.flush()
    return ok


def download_series_logo(db: Session, series: VideoSeries, file_path: str | None = None) -> bool:
    from fryfrog.services.tmdb import TmdbClient

    if file_path:
        urls = _tmdb_image_urls(file_path)
    elif series.logo_url:
        urls = _tmdb_image_urls(series.logo_url)
    elif series.tmdb_id:
        client = TmdbClient()
        logos = _tv_logos(client, series.tmdb_id)
        if not logos:
            return False
        file_path = logos[0].get("file_path")
        if not file_path:
            return False
        urls = _tmdb_image_urls(file_path)
    else:
        return False
    dest_dir = Path(series.metadata_dir) if series.metadata_dir else Path("data/series") / str(series.id)
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / "logo.png"
    ok = False
    for url in urls:
        ok = download_image(url, dest, force=True)
        if ok:
            break
    if ok:
        series.logo_local_path = str(dest)
        if file_path:
            series.logo_url = file_path
        db.flush()
    return ok


def _movie_logos(client, tmdb_id: int) -> list[dict]:
    images = client._get(
        f"/movie/{tmdb_id}/images",
        {"include_image_language": "zh-CN,zh,en,null,ja"},
    ) or {}
    logos = images.get("logos") or []
    logos.sort(key=lambda x: x.get("vote_count") or 0, reverse=True)
    return logos


def _tv_logos(client, tmdb_id: int) -> list[dict]:
    images = client._get(
        f"/tv/{tmdb_id}/images",
        {"include_image_language": "zh-CN,zh,en,null,ja"},
    ) or {}
    logos = images.get("logos") or []
    logos.sort(key=lambda x: x.get("vote_count") or 0, reverse=True)
    return logos


def _proxy_image_url(path: str | None, size: str = "w500") -> str | None:
    """走本站代理预览，避免浏览器直连 image.tmdb.org 失败。"""
    if not path:
        return None
    from urllib.parse import quote

    return f"/api/v1/video/tmdb-image-proxy?path={quote(path, safe='/')}&size={size}"


def movie_logo_options(tmdb_id: int) -> list[dict]:
    from fryfrog.services.tmdb import TmdbClient

    client = TmdbClient()
    result = []
    for logo in _movie_logos(client, tmdb_id):
        path = logo.get("file_path")
        result.append(
            {
                "filePath": path,
                "iso6391": logo.get("iso_639_1"),
                "width": logo.get("width"),
                "height": logo.get("height"),
                "voteCount": logo.get("vote_count"),
                "url": _proxy_image_url(path),
            }
        )
    return result


def tv_logo_options(tmdb_id: int) -> list[dict]:
    from fryfrog.services.tmdb import TmdbClient

    client = TmdbClient()
    result = []
    for logo in _tv_logos(client, tmdb_id):
        path = logo.get("file_path")
        result.append(
            {
                "filePath": path,
                "iso6391": logo.get("iso_639_1"),
                "width": logo.get("width"),
                "height": logo.get("height"),
                "voteCount": logo.get("vote_count"),
                "url": _proxy_image_url(path),
            }
        )
    return result


def episode_still_options(
    db: Session, video: Video, series_tmdb_id: int | None = None
) -> list[dict]:
    """某分集在 TMDB 上的候选横屏图（本集剧照 still）。

    分集横屏在本项目里就是「本集 still」（见 video_scrape._apply_episode_detail），
    但那是自动取一张。这里把 TMDB 的候选列出来让用户挑。
    `/tv/{id}/season/{s}/episode/{e}?append_to_response=images` 会带 images.stills，
    一次请求即可；没有候选时退回单集自带的 still_path。
    """
    from fryfrog.services.tmdb import TmdbClient

    tmdb_id = series_tmdb_id
    if tmdb_id is None:
        from fryfrog.models.video import VideoSeries

        series = db.get(VideoSeries, video.series_id) if video.series_id else None
        tmdb_id = series.tmdb_id if series else None
    season = season_of(video)
    episode = episode_of(video)
    if not tmdb_id:
        return []

    client = TmdbClient()
    detail = client.get_episode_images(tmdb_id, season, episode) or {}
    stills = [
        s
        for s in ((detail.get("images") or {}).get("stills") or [])
        if s.get("file_path")
    ]
    if not stills and detail.get("still_path"):
        stills = [{"file_path": detail["still_path"]}]

    result: list[dict] = []
    for still in stills:
        path = still.get("file_path")
        result.append(
            {
                "filePath": path,
                # 预览用 w780：still 是 16:9，w500 在高分屏上偏糊
                "url": _proxy_image_url(path, size="w780"),
                "width": still.get("width"),
                "height": still.get("height"),
                "voteCount": still.get("vote_count"),
                "iso6391": still.get("iso_639_1"),
            }
        )
    return result


def apply_video_backdrop(db: Session, video: Video, file_path: str) -> bool:
    """把选定的 TMDB 图落成本集横屏（fanart.jpg 固定命名，覆盖旧图）。"""
    if not file_path:
        return False
    target = get_fanart_path(db, video)
    ok = download_image(_full_image_url(file_path), target, force=True)
    if ok:
        video.backdrop_url = file_path
        video.backdrop_local_path = str(target)
        db.flush()
    return ok


# -------------------- TMDB 分层图片（总览 / 季 / 单集 × 海报 / 背景图 / 剧照） --------------------

# TMDB 的图片分类与本站资产的对应关系：
#   posters   → 竖版海报（总览=剧集海报，季=季海报）
#   backdrops → 横版背景图（总览/季）
#   stills    → 单集剧照（本集的横版图，即本站「分集横屏」）
IMAGE_LEVELS = ("series", "season", "episode")
IMAGE_KINDS = ("poster", "backdrop", "still")

# 预览尺寸：竖图用 w342、横图用 w780（比例不同，同一个尺寸名观感差很多）
_PREVIEW_SIZE = {"poster": "w342", "backdrop": "w780", "still": "w780"}


def _image_options(client, payload: dict | None, kind: str) -> list[dict]:
    """把 TMDB images 响应里的某类图整理成候选列表（按票数降序）。"""
    key = {"poster": "posters", "backdrop": "backdrops", "still": "stills"}[kind]
    raw = [x for x in ((payload or {}).get("images") or {}).get(key) or [] if x.get("file_path")]
    raw.sort(key=lambda x: x.get("vote_count") or 0, reverse=True)
    out = []
    for item in raw:
        path = item["file_path"]
        out.append(
            {
                "filePath": path,
                "url": _proxy_image_url(path, size=_PREVIEW_SIZE[kind]),
                "width": item.get("width"),
                "height": item.get("height"),
                "voteCount": item.get("vote_count"),
                "iso6391": item.get("iso_639_1"),
                "kind": kind,
            }
        )
    return out


def tmdb_image_options(
    series_tmdb_id: int,
    level: str,
    season: int | None = None,
    episode: int | None = None,
    media_type: str | None = None,
) -> dict:
    """某一层级的 TMDB 图片候选，返回 {poster: [...], backdrop: [...]}。

    - series：`/tv/{id}/images`（电影走 `/movie/{id}/images`）→ posters + backdrops
    - season：`/tv/{id}/season/{s}/images` → posters
    - episode：单集详情 append images → stills（单集的横版图就是剧照）
    一次请求同时给出该层级所有可用类型，避免客户端多次往返。
    """
    from fryfrog.services.tmdb import TmdbClient

    if level not in IMAGE_LEVELS or not series_tmdb_id:
        return {}
    client = TmdbClient()
    is_movie = (media_type or "").lower() == "movie"

    if level == "series":
        if is_movie:
            payload = client.get_movie_images(series_tmdb_id)
        else:
            payload = client.get_tv_images(series_tmdb_id)
        return {
            "poster": _image_options(client, payload, "poster"),
            "backdrop": _image_options(client, payload, "backdrop"),
        }

    if level == "season":
        if season is None:
            return {}
        payload = client.get_season_images(series_tmdb_id, season)
        return {"poster": _image_options(client, payload, "poster")}

    if level == "episode":
        if season is None or episode is None:
            return {}
        payload = client.get_episode_images(series_tmdb_id, season, episode)
        return {"still": _image_options(client, payload, "still")}

    return {}


def _series_local_roots(episodes: list[Video], library_path: str | None) -> list[Path]:
    """剧名级目录候选（真实媒体布局优先，其次按库根重建）。

    实际目录是 `<lib>/<剧名>/第 N 季/…`，而 get_metadata_dir 重建的是
    `<lib>/<剧名>/第 N 季/第 M 集/`——两者层级不同，所以这里从文件路径推。
    """
    roots: list[Path] = []
    if episodes:
        try:
            roots.append(Path(episodes[0].file_path).parent.parent)
        except Exception:
            pass
    if library_path:
        roots.append(Path(library_path))
    seen: set[str] = set()
    return [r for r in roots if not (str(r) in seen or seen.add(str(r)))]


def apply_tmdb_image(
    db: Session,
    video: Video,
    episodes: list[Video],
    level: str,
    kind: str,
    file_path: str,
) -> Path | None:
    """把选定的 TMDB 图落到对应层级的本地位置，返回落地路径。

    落盘命名沿用既有约定（见 find_shared_vertical_poster / download_series_root_art）：
      总览 → `<剧名根>/tvshow-poster.jpg` · `tvshow-fanart.jpg`
      季   → `<季目录>/tvshow-poster.jpg`
      单集 → `<分集目录>/poster.jpg` · `fanart.jpg`
    """
    if not file_path or level not in IMAGE_LEVELS or kind not in IMAGE_KINDS:
        return None

    from fryfrog.models.library import MediaLibrary

    library_path = None
    if video.library_id is not None:
        lib = db.get(MediaLibrary, video.library_id)
        library_path = lib.path if lib else None

    target: Path | None = None
    if level == "episode":
        target = get_fanart_path(db, video) if kind == "backdrop" else get_poster_path(db, video)
    elif level == "season":
        season_dir = get_season_dir(db, video)
        if season_dir:
            target = season_dir / (
                "tvshow-fanart.jpg" if kind == "backdrop" else "tvshow-poster.jpg"
            )
    else:  # series
        roots = _series_local_roots(episodes or [video], library_path)
        if roots:
            name = "tvshow-fanart.jpg" if kind == "backdrop" else "tvshow-poster.jpg"
            target = roots[0] / name
            for root in roots:
                if (root / name).is_file():
                    target = root / name
                    break

    if target is None:
        return None
    target.parent.mkdir(parents=True, exist_ok=True)
    if not download_image(_full_image_url(file_path), target, force=True):
        return None

    # 回填 DB 字段，让接口立刻返回新图（签名 URL 由 DTO 层生成）
    if level == "episode" and kind == "backdrop":
        video.backdrop_url = file_path
        video.backdrop_local_path = str(target)
    elif level == "episode":
        video.poster_url = file_path
        video.cover_art_path = str(target)
    elif level == "season":
        # 季海报全季共用：分集竖图指向它，避免各集留私有副本
        for ep in episodes or [video]:
            ep.poster_url = file_path
    else:
        series = db.get(VideoSeries, video.series_id) if video.series_id else None
        if series is not None:
            if kind == "backdrop":
                series.backdrop_local_path = str(target)
            else:
                series.poster_local_path = str(target)
        for ep in episodes or [video]:
            if kind == "backdrop":
                ep.backdrop_url = ep.backdrop_url or file_path
    db.flush()
    return target


# -------------------- 截帧 --------------------

def frames_cache_dir(video: Video) -> Path:
    return Path(video.file_path).parent / f".frames-{video.id}"


def capture_frame_at(src: str, dest: str, width: int, height: int, position: float) -> bool:
    runtime = get_ffmpeg_runtime()
    if not runtime.is_available():
        return False
    dest_path = Path(dest)
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        runtime.ffmpeg_path,
        "-ss",
        str(max(position, 0)),
        "-i",
        src,
        "-frames:v",
        "1",
        "-vf",
        f"scale={width}:{height}",
        "-y",
        dest,
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, timeout=30, check=False)
        return proc.returncode == 0 and dest_path.exists()
    except Exception:
        logger.debug("截帧失败: %s", src, exc_info=True)
        return False


def generate_frame_candidates(video: Video) -> list[dict]:
    cache_dir = frames_cache_dir(video)
    if cache_dir.exists():
        shutil.rmtree(cache_dir, ignore_errors=True)
    cache_dir.mkdir(parents=True, exist_ok=True)
    duration = get_media_probe().probe_video_duration(video.file_path) or 0
    candidates = []
    for i, ratio in enumerate(FRAME_RATIOS):
        pos = duration * ratio if duration > 0 else 30 + i * 30
        frame_path = cache_dir / f"frame-{i}.jpg"
        if capture_frame_at(video.file_path, str(frame_path), 640, 360, pos):
            candidates.append(
                {
                    "index": i,
                    "position": round(pos),
                    "url": f"/api/v1/video/{video.id}/frames/{i}",
                }
            )
    return candidates


# -------------------- 整理（含季目录 + 空目录清理） --------------------


def _prune_empty_dirs(start: Path, stop_at: Path) -> int:
    """自 start 向上删除空目录，不越过 stop_at。返回删除数量。"""
    removed = 0
    try:
        stop_resolved = stop_at.resolve()
    except OSError:
        return 0
    current = start
    while current.is_dir():
        try:
            resolved = current.resolve()
        except OSError:
            break
        if resolved == stop_resolved:
            break
        try:
            if any(current.iterdir()):
                break
        except OSError:
            break
        try:
            current.rmdir()
            removed += 1
        except OSError:
            break
        parent = current.parent
        if parent == current:
            break
        current = parent
    return removed


def organize_videos(db: Session, videos: list[Video]) -> dict:
    moved = skipped = failed = 0
    cleaned_dirs = 0
    old_parents: set[Path] = set()
    for video in sorted(
        videos,
        key=lambda v: (season_of(v), episode_of(v)),
    ):
        try:
            target_dir = get_metadata_dir(db, video)
            if target_dir is None:
                skipped += 1
                continue
            old_path = Path(video.file_path)
            if not old_path.exists():
                skipped += 1
                continue
            target_dir.mkdir(parents=True, exist_ok=True)
            # 季目录规范：TV 落在「剧名/第 N 季/」下（get_metadata_dir 已含季）
            new_path = target_dir / video.file_name
            if old_path.resolve() != new_path.resolve():
                old_dir = old_path.parent
                base = get_base_name(video.file_name)
                for sibling in list(old_dir.iterdir()):
                    if not sibling.is_file():
                        continue
                    s_base = get_base_name(sibling.name)
                    fixed = sibling.name in {"poster.jpg", "fanart.jpg", "folder.jpg", "thumb.jpg"}
                    if (
                        s_base == base
                        or (base and s_base.startswith(base))
                        or fixed
                        or sibling.suffix.lower() in {".srt", ".ass", ".ssa", ".vtt"}
                    ):
                        if sibling.suffix.lower() in SUBTITLE_EXTS or sibling.suffix.lower() in {
                            ".nfo",
                            ".jpg",
                            ".jpeg",
                            ".png",
                        }:
                            dest = target_dir / sibling.name.replace(get_base_name(old_path.name), base)
                            if sibling.resolve() != dest.resolve():
                                shutil.move(str(sibling), str(dest))
                shutil.move(str(old_path), str(new_path))
                video.file_path = str(new_path)
                video.file_name = new_path.name
                old_parents.add(old_dir)
                moved += 1
            else:
                skipped += 1
            db.flush()
        except Exception:
            failed += 1
            logger.exception("整理失败: %s", video.file_name)

    # 清理搬空后的目录（不越过库根）
    for old_dir in old_parents:
        root = None
        if videos and videos[0].library_id is not None:
            from fryfrog.models.library import MediaLibrary

            lib = db.get(MediaLibrary, videos[0].library_id)
            if lib and lib.path:
                root = Path(lib.path)
        if root is None:
            root = old_dir
        cleaned_dirs += _prune_empty_dirs(old_dir, root)

    return {
        "moved": moved,
        "skipped": skipped,
        "failed": failed,
        "total": len(videos),
        "cleanedEmptyDirs": cleaned_dirs,
    }


def upgrade_legacy_assets(db: Session, video: Video) -> None:
    """把老方案的素材整理成新结构：素材贴视频、固定命名、散放归置、清理空壳。

    老方案（升级前）把 nfo/封面写到 get_metadata_dir（库根/剧名/第N季/第N集/），
    命名带 -poster/-fanart 前缀；重新刮削（bind/refresh/rescrape-library）时
        调用本函数：素材迁到视频同目录的固定命名（poster.jpg/fanart.jpg/{base}.nfo），
        删除旧变体与 -frame-v3 截帧，修正 DB 素材字段；散放在库根的视频
        归置进「剧名/第N季/第N集」规范目录，并在搬移后清理空目录。
    """
    try:
        rename_show_dir(db, video)
        video_dir = get_video_assets_dir(video)
        base = get_base_name(video.file_name)
        legacy = get_metadata_dir(db, video)
        if legacy == video_dir:
            legacy = None
        if legacy is not None:
            _migrate_legacy_files(legacy, video_dir, base)
        _collect_stray_assets(video)
        _converge_asset_variants(video_dir, base)
        _repair_asset_paths(db, video)
        if legacy is not None:
            root = None
            if video.library_id is not None:
                from fryfrog.models.library import MediaLibrary

                lib = db.get(MediaLibrary, video.library_id)
                if lib and lib.path:
                    root = Path(lib.path)
            _prune_empty_dirs(legacy, root or legacy.parent)
        # 散放/非规范位置：归置进规范目录（视频与素材一起搬，不洒在库根）
        if get_metadata_dir(db, video) != get_video_assets_dir(video):
            organize_videos(db, [video])
    except Exception:
        logger.exception("升级素材整理失败: %s", video.file_name)


def _migrate_legacy_files(legacy: Path, video_dir: Path, base: str) -> None:
    """老位置素材按固定命名规则搬到视频目录；目标已存在时删除旧副本。"""
    if not video_dir.is_dir():
        # 视频被归置到新目录后可能还没建出来，先补建再搬
        video_dir.mkdir(parents=True, exist_ok=True)
    for src_name, dst_name in (
        (f"{base}.nfo", f"{base}.nfo"),
        (f"{base}-poster.jpg", "poster.jpg"),
        (f"{base}-fanart.jpg", "fanart.jpg"),
        (f"{base}-logo.png", f"{base}-logo.png"),
        ("poster.jpg", "poster.jpg"),
        ("fanart.jpg", "fanart.jpg"),
        ("folder.jpg", "folder.jpg"),
        ("thumb.jpg", "thumb.jpg"),
    ):
        src = legacy / src_name
        if not src.is_file():
            continue
        dst = video_dir / dst_name
        if dst.exists():
            if dst.resolve() == src.resolve():
                continue
            src.unlink()
        else:
            shutil.move(str(src), str(dst))


def _converge_asset_variants(video_dir: Path, base: str) -> None:
    """视频目录内旧变体收敛到固定命名；清理未被 DB 引用的兜底截帧。"""
    for old, new in (
        (video_dir / f"{base}-poster.jpg", video_dir / "poster.jpg"),
        (video_dir / f"{base}-fanart.jpg", video_dir / "fanart.jpg"),
    ):
        if not old.exists() or old.resolve() == new.resolve():
            continue
        if new.exists():
            if new.is_file():
                old.unlink()
        else:
            old.rename(new)


def _collect_stray_assets(video: Video) -> None:
    """回收其他目录里遗留的同名素材（老方案名称变化后剩余的），搬到视频目录。

    覆盖「视频已被旧 organize 搬去新名目录、素材留在旧名目录」的场景：
    只移动与视频 base 同名（或同 base 的 poster/fanart 变体）的素材文件，
    收敛为固定命名；搬空的源目录顺带尝试删除。
    """
    video_dir = get_video_assets_dir(video)
    base = get_base_name(video.file_name)
    wanted = {
        f"{base}.nfo": f"{base}.nfo",
        f"{base}-poster.jpg": "poster.jpg",
        f"{base}-fanart.jpg": "fanart.jpg",
        f"{base}-logo.png": f"{base}-logo.png",
    }
    search_root = video_dir.parent
    try:
        if not search_root.is_dir():
            return
        for p in search_root.rglob("*"):
            if not p.is_file() or p.name not in wanted:
                continue
            if p.parent.resolve() == video_dir.resolve():
                continue
            dst = video_dir / wanted[p.name]
            if dst.exists():
                continue
            try:
                shutil.move(str(p), str(dst))
                try:
                    p.parent.rmdir()
                except OSError:
                    pass
            except OSError:
                logger.debug("回收散落素材失败: %s", p)
    except OSError:
        logger.debug("扫描散落素材失败: %s", search_root, exc_info=True)


def _repair_asset_paths(db: Session, video: Video) -> None:
    """cover_art_path/backdrop_local_path 指向已迁移/收敛后的素材（或置空）。"""
    video_dir = get_video_assets_dir(video)
    base = get_base_name(video.file_name)
    if video.cover_art_path:
        p = Path(video.cover_art_path)
        if not p.exists():
            poster = video_dir / "poster.jpg"
            video.cover_art_path = str(poster) if poster.exists() else None
        elif p.name == f"{base}-poster.jpg":
            poster = video_dir / "poster.jpg"
            if poster.exists() and poster.resolve() != p.resolve():
                video.cover_art_path = str(poster)
    if video.backdrop_local_path:
        p = Path(video.backdrop_local_path)
        if not p.exists():
            fanart = video_dir / "fanart.jpg"
            video.backdrop_local_path = str(fanart) if fanart.exists() else None
        elif p.name == f"{base}-fanart.jpg":
            fanart = video_dir / "fanart.jpg"
            if fanart.exists() and fanart.resolve() != p.resolve():
                video.backdrop_local_path = str(fanart)
    db.flush()
