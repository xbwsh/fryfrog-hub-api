"""素材域：封面、背景、logo、候选帧、NFO、TMDB 图片代理。"""
from __future__ import annotations

import logging
import shutil
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse, Response

from fryfrog.core.api_response import ApiResponse
from fryfrog.core.deps import DbSession
from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.core.utils import placeholder_jpeg
from fryfrog.media_core import get_media_probe
from fryfrog.schemas.video import FrameSelectRequest, LogoSelectRequest
from fryfrog.services import video_assets as assets
from fryfrog.services import video_service as vs

from ._common import _require_admin, _require_visible, _video_logo_url

logger = logging.getLogger(__name__)

router = APIRouter()
@router.get("/tmdb-image-proxy")
def tmdb_image_proxy(path: str, size: str = "w500"):
    if not _safe_tmdb_path(path) or size not in ALLOWED_SIZES:
        return Response(status_code=400)
    data = assets.download_url_bytes(f"{TMDB_CDN}/{size}{path}")
    if not data:
        return Response(status_code=502)
    return Response(
        content=data,
        media_type=assets.media_type_of(path),
        headers={"Cache-Control": assets.IMAGE_CACHE},
    )


@router.get("/{id:int}/nfo")
def get_nfo(db: DbSession, id: int):
    video = vs.get_video(db, id)
    nfo_path = vs.get_nfo_path(db, video)
    if not nfo_path.exists():
        alt = Path(video.file_path).parent / f"{vs.get_base_name(video.file_name)}.nfo"
        if alt.exists():
            nfo_path = alt
        else:
            raise ResourceNotFoundException("NFO", "videoId", id)
    return ApiResponse.ok(nfo_path.read_text(encoding="utf-8", errors="replace"))


@router.get("/{id:int}/cover")
def get_cover(db: DbSession, id: int):
    video = vs.get_video(db, id)
    # 分集竖屏封面共用季海报，避免每集各存一张（季海报缺失时回退下方分集图）
    if video.is_episode:
        season_dir = vs.get_season_dir(db, video)
        if season_dir:
            season_poster = season_dir / "tvshow-poster.jpg"
            if season_poster.is_file():
                return FileResponse(str(season_poster), media_type="image/jpeg")
    if video.cover_art_path and Path(video.cover_art_path).exists():
        return FileResponse(video.cover_art_path, media_type="image/jpeg")
    poster = vs.get_poster_path(db, video)
    if poster.exists():
        return FileResponse(str(poster), media_type="image/jpeg")
    alt = Path(video.file_path).parent / f"{vs.get_base_name(video.file_name)}-poster.jpg"
    if alt.exists():
        return FileResponse(str(alt), media_type="image/jpeg")
    frame = Path(video.file_path).parent / f"{vs.get_base_name(video.file_name)}-frame-v3.jpg"
    if not frame.exists():
        try:
            assets.capture_frame_at(video.file_path, str(frame), 300, 450, 30)
        except Exception:
            logger.debug("封面截帧失败 id=%s", id)
    if frame.exists():
        return FileResponse(str(frame), media_type="image/jpeg")
    return Response(
        content=placeholder_jpeg(300, 450, video.title or ""),
        media_type="image/jpeg",
    )


@router.get("/{id:int}/fanart")
def get_fanart(db: DbSession, id: int):
    video = vs.get_video(db, id)
    if video.backdrop_local_path and Path(video.backdrop_local_path).exists():
        return FileResponse(video.backdrop_local_path, media_type="image/jpeg")
    base = vs.get_base_name(video.file_name)
    video_dir = Path(video.file_path).parent
    for candidate in (
        video_dir / f"{base}-fanart.jpg",
        vs.get_fanart_path(db, video),
        video_dir / f"{base}-fanart-frame-v3.jpg",
    ):
        if candidate.exists():
            return FileResponse(str(candidate), media_type="image/jpeg")
    frame = video_dir / f"{base}-fanart-frame-v3.jpg"
    try:
        assets.capture_frame_at(video.file_path, str(frame), 1920, 1080, 45)
    except Exception:
        logger.debug("背景截帧失败 id=%s", id)
    if frame.exists():
        return FileResponse(str(frame), media_type="image/jpeg")
    return Response(
        content=placeholder_jpeg(1920, 400, video.title or ""),
        media_type="image/jpeg",
    )


@router.get("/{id:int}/logo")
def get_logo(db: DbSession, id: int):
    video = vs.get_video(db, id)
    if video.logo_local_path and Path(video.logo_local_path).exists():
        return FileResponse(
            video.logo_local_path, media_type=assets.media_type_of(video.logo_local_path)
        )
    # Side-by-side logo (e.g. …/喜剧之王/movie-logo.png).
    local = assets.find_local_video_logo(video)
    if local is not None:
        video.logo_local_path = str(local)
        db.flush()
        return FileResponse(str(local), media_type=assets.media_type_of(local.name))
    logo_url = video.logo_url
    if not logo_url and video.tmdb_id:
        logos = assets.movie_logo_options(video.tmdb_id)
        logo_url = logos[0]["filePath"] if logos else None
    if not logo_url:
        raise ResourceNotFoundException("Logo", "id", id)
    data = assets.fetch_tmdb_image(logo_url)
    if not data:
        raise ResourceNotFoundException("Logo", "id", id)
    return Response(content=data, media_type=assets.media_type_of(logo_url))


@router.get("/{id:int}/logo-options")
def get_logo_options(db: DbSession, id: int):
    video = vs.get_video(db, id)
    if not video.tmdb_id:
        assets.parse_nfo(db, video)
    if not video.tmdb_id:
        return ApiResponse.error("视频没有 TMDB ID，无法查询 logo")
    return ApiResponse.ok(assets.movie_logo_options(video.tmdb_id))


@router.post("/{id:int}/logo")
def set_logo(db: DbSession, id: int, body: LogoSelectRequest):
    _require_admin(db)
    if not body.filePath:
        return ApiResponse.error("filePath 不能为空")
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    if not video.tmdb_id:
        return ApiResponse.error("视频没有 TMDB ID，无法设置 logo")
    ok = assets.download_movie_logo(db, video, file_path=body.filePath, force=True)
    return ApiResponse.ok(
        {
            "videoId": id,
            "title": video.title,
            "downloaded": ok,
            "logoUrl": _video_logo_url(video),
        }
    )


@router.post("/{id:int}/frames")
def generate_frames(db: DbSession, id: int):
    _require_admin(db)
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    candidates = assets.generate_frame_candidates(video)
    return ApiResponse.ok({"videoId": id, "total": len(candidates), "candidates": candidates})


@router.get("/{id:int}/frames/{index:int}")
def get_frame(db: DbSession, id: int, index: int):
    video = vs.get_video(db, id)
    frame_path = assets.frames_cache_dir(video) / f"frame-{index}.jpg"
    if not frame_path.exists():
        raise ResourceNotFoundException("Frame", "index", index)
    return FileResponse(str(frame_path), media_type="image/jpeg")


@router.post("/{id:int}/frames/select")
def select_frame(db: DbSession, id: int, body: FrameSelectRequest):
    _require_admin(db)
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    frame_path = assets.frames_cache_dir(video) / f"frame-{body.index}.jpg"
    if not frame_path.exists():
        return ApiResponse.error("候选帧不存在，请先调用生成接口")
    is_poster = (body.type or "").lower() == "poster"
    is_series_fanart = (body.type or "").lower() == "series_fanart"
    series = video.series if is_series_fanart else None
    if is_series_fanart and series is None:
        return ApiResponse.error("该视频不属于任何系列，无法设置为系列背景图")

    video_dir = Path(video.file_path).parent
    base = vs.get_base_name(video.file_name)
    if is_poster:
        output_name = f"{base}-frame-v3.jpg"
    elif is_series_fanart:
        output_name = f"{base}-series-fanart.jpg"
    else:
        output_name = f"{base}-fanart-frame-v3.jpg"
    output_path = video_dir / output_name

    duration = get_media_probe().probe_video_duration(video.file_path) or 0
    ratios = assets.FRAME_RATIOS
    pos = duration * ratios[body.index] if duration > 0 else 30 + body.index * 30
    ok = assets.capture_frame_at(
        video.file_path,
        str(output_path),
        300 if is_poster else 1920,
        450 if is_poster else 1080,
        pos,
    )
    if not ok:
        shutil.copyfile(frame_path, output_path)

    if is_poster:
        video.cover_art_path = str(output_path)
    elif is_series_fanart and series is not None:
        series.backdrop_local_path = str(output_path)
    else:
        video.backdrop_local_path = str(output_path)
    db.flush()
    return ApiResponse.ok({"videoId": id, "type": body.type, "path": str(output_path)})


@router.post("/{id:int}/refresh-logo")
def refresh_movie_logo(db: DbSession, id: int):
    _require_admin(db)
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    if not video.tmdb_id:
        return ApiResponse.error("视频没有 TMDB ID，无法获取 logo")
    ok = assets.download_movie_logo(db, video)
    return ApiResponse.ok(
        {
            "videoId": id,
            "title": video.title,
            "downloaded": ok,
            "logoUrl": _video_logo_url(video),
        }
    )


@router.post("/{id:int}/nfo")
def generate_nfo_endpoint(db: DbSession, id: int):
    _require_admin(db)
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    nfo_path = assets.generate_nfo(db, video)
    return ApiResponse.ok({"videoId": str(id), "nfoPath": nfo_path or "null"})


@router.post("/{id:int}/covers")
def download_covers(db: DbSession, id: int):
    _require_admin(db)
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    success = assets.download_all_covers(db, video, force=True)
    return ApiResponse.ok({"videoId": str(id), "success": str(success).lower()})


ALLOWED_SIZES = {"w92", "w154", "w185", "w342", "w500", "w780", "original"}
TMDB_CDN = "https://image.tmdb.org/t/p"


def _safe_tmdb_path(path: str) -> bool:
    if not path or not path.startswith("/"):
        return False
    if ".." in path or "://" in path or len(path) > 512:
        return False
    return True
