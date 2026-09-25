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
    get_season_dir,
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


# Common logos users drop next to media (Jellyfin / Kodi / Ember style).
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


def find_local_video_logo(video: Video) -> Path | None:
    """Side-by-side logo next to the media file (e.g. movie-logo.png)."""
    try:
        parent = Path(video.file_path).parent
    except Exception:
        return None
    for name in VIDEO_LOGO_FILENAMES:
        p = parent / name
        try:
            if p.is_file():
                return p
        except Exception:
            continue
    return None


def find_local_series_logo(db: Session, episodes: list[Video]) -> Path | None:
    """tvshow-logo / logo under episode folder or season metadata dir."""
    if not episodes:
        return None
    seen: set[str] = set()
    for ep in episodes:
        candidates: list[Path] = []
        try:
            parent = Path(ep.file_path).parent
            for name in SERIES_LOGO_FILENAMES:
                candidates.append(parent / name)
            # Season folder: …/第 1 季/tvshow-logo.png
            for name in ("tvshow-logo.png", "tvshow-logo.jpg", "logo.png"):
                candidates.append(parent / name)
        except Exception:
            pass
        season_dir = get_season_dir(db, ep)
        if season_dir:
            for name in SERIES_LOGO_FILENAMES:
                candidates.append(season_dir / name)
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

def generate_nfo(db: Session, video: Video) -> str | None:
    try:
        metadata_dir = get_metadata_dir(db, video)
        metadata_dir.mkdir(parents=True, exist_ok=True)
        nfo_path = get_nfo_path(db, video)
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
    is_tv = (video.media_type or "").lower() == "tv"
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
        season = video.season_number or 1
        episode = video.episode_number or 1
        parts.append(_tag("season", season))
        parts.append(_tag("episode", episode))
        if video.series_name or (video.series and video.series.title):
            parts.append(_tag("showtitle", video.series_name or video.series.title))
    for actor_name in [a.strip() for a in (video.actors or "").split(",") if a.strip()]:
        parts.append(f"  <actor>\n    <name>{actor_name}</name>\n  </actor>\n")
    parts.append(f"</{root}>\n")
    return "".join(parts)


# -------------------- 封面 / Logo --------------------

def download_all_covers(db: Session, video: Video, force: bool = False) -> bool:
    poster_ok = False
    fanart_ok = False
    if video.poster_url:
        poster_ok = download_image(_full_image_url(video.poster_url), get_poster_path(db, video), force)
        if poster_ok:
            video.cover_art_path = str(get_poster_path(db, video))
    if video.backdrop_url:
        fanart_ok = download_image(_full_image_url(video.backdrop_url), get_fanart_path(db, video), force)
        if fanart_ok:
            video.backdrop_local_path = str(get_fanart_path(db, video))
    db.flush()
    return poster_ok or fanart_ok


def _full_image_url(url: str) -> str:
    if url.startswith("http"):
        return url
    from fryfrog.config import get_settings

    size = get_settings().tmdb_image_size or "original"
    return f"https://image.tmdb.org/t/{size}{url}"


def _tmdb_image_urls(path: str) -> list[str]:
    """TMDB 图片 URL 列表（按尺寸回退）。统一走 make_client：有代理走代理，否则直连。"""
    if path.startswith("http"):
        return [path]
    if not path.startswith("/"):
        path = "/" + path
    return [
        f"https://image.tmdb.org/t/original{path}",
        f"https://image.tmdb.org/t/w780{path}",
        f"https://image.tmdb.org/t/w500{path}",
        f"https://image.tmdb.org/t/w342{path}",
        f"https://image.tmdb.org/t/w300{path}",
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
    dest = get_metadata_dir(db, video) / f"{get_base_name(video.file_name)}-logo.png"
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


def _proxy_image_url(path: str | None) -> str | None:
    """走本站代理预览，避免浏览器直连 image.tmdb.org 失败。"""
    if not path:
        return None
    from urllib.parse import quote

    return f"/api/v1/video/tmdb-image-proxy?path={quote(path, safe='/')}&size=w500"


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
        key=lambda v: (v.season_number or 1, v.episode_number or 1),
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
                    if s_base == base or sibling.suffix.lower() in {".srt", ".ass", ".ssa", ".vtt"}:
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
