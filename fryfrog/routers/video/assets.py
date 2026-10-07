"""素材域：封面、背景、logo、候选帧、NFO、TMDB 图片代理。"""
from __future__ import annotations

import logging
import shutil
from pathlib import Path

from fastapi import APIRouter, File, Form, UploadFile
from fastapi.responses import FileResponse, Response

from fryfrog.core.api_response import ApiResponse
from fryfrog.core.deps import DbSession
from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.core.utils import placeholder_jpeg
from fryfrog.media_core import get_media_probe
from fryfrog.models.video import VideoSeries
from fryfrog.schemas.video import (
    CoverSelectRequest,
    FrameSelectRequest,
    LogoSelectRequest,
    TmdbImageSelectRequest,
)
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
    # 分集竖屏封面共用季海报，避免每集各存一张（季海报缺失时回退剧根
    # 目录总海报，最后才落到分集私有图）
    if video.is_episode:
        season_dir = vs.get_season_dir(db, video)
        if season_dir:
            season_poster = season_dir / "tvshow-poster.jpg"
            if season_poster.is_file():
                return FileResponse(str(season_poster), media_type="image/jpeg")
        root_dir = vs.get_series_root_dir(db, [video])
        if root_dir:
            root_poster = root_dir / "tvshow-poster.jpg"
            if root_poster.is_file():
                return FileResponse(str(root_poster), media_type="image/jpeg")
    if video.cover_art_path and Path(video.cover_art_path).exists():
        # 历史扫描可能把横屏缩略图（thumb.jpg 等）写进了 cover_art_path，
        # 当竖封面会被 2:3 区域裁切，跳过它继续走正经竖封面候选。
        cover_name = Path(video.cover_art_path).name.lower()
        if not cover_name.startswith("thumb") and "-thumb." not in cover_name:
            return FileResponse(video.cover_art_path, media_type="image/jpeg")
    # 本地刮削产物优先（含无前缀 poster.jpg/folder.jpg/thumb.jpg），最后才截帧
    for candidate in vs.local_poster_candidates(db, video):
        if candidate.exists():
            # thumb.jpg 常见是横屏缩略图（尤其分集缩略图），
            # 作为竖封面会被 2:3 区域裁切，跳过它继续找正儿八经的竖海报。
            name = candidate.name.lower()
            if name.startswith("thumb") or "-thumb." in name:
                continue
            return FileResponse(str(candidate), media_type="image/jpeg")
    # 远程 TMDB 兜底（经 make_client 走代理），避免退化到截帧/占位
    if video.poster_url:
        data = assets.fetch_tmdb_image(video.poster_url)
        if data:
            return Response(
                content=data,
                media_type="image/jpeg",
                headers={"Cache-Control": assets.IMAGE_CACHE},
            )
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
    base = vs.get_base_name(video.file_name)
    video_dir = Path(video.file_path).parent
    # 分集横屏：本地单集 still 优先，缺失回退总横屏；不再生成 -fanart-frame-v3 截帧
    if video.is_episode:
        if video.backdrop_local_path and Path(video.backdrop_local_path).exists():
            return FileResponse(video.backdrop_local_path, media_type="image/jpeg")
        for candidate in vs.local_fanart_candidates(db, video):
            if candidate.exists():
                return FileResponse(str(candidate), media_type="image/jpeg")
        if video.series_id:
            episodes = vs.series_videos(db, video.series_id)
            root_fanart = vs.find_series_root_file(db, episodes, "tvshow-fanart.jpg")
            if root_fanart is not None:
                return FileResponse(str(root_fanart), media_type="image/jpeg")
            series = vs.get_series(db, video.series_id)
            if series is not None and series.backdrop_local_path:
                if Path(series.backdrop_local_path).exists():
                    return FileResponse(series.backdrop_local_path, media_type="image/jpeg")
        if video.backdrop_url:
            data = assets.fetch_tmdb_image(video.backdrop_url)
            if data:
                return Response(
                    content=data,
                    media_type="image/jpeg",
                    headers={"Cache-Control": assets.IMAGE_CACHE},
                )
        return Response(
            content=placeholder_jpeg(1920, 400, video.title or ""),
            media_type="image/jpeg",
        )
    # 电影：本地文件（含无前缀 fanart.jpg） → 旧截帧 → 截帧兜底
    if video.backdrop_local_path and Path(video.backdrop_local_path).exists():
        return FileResponse(video.backdrop_local_path, media_type="image/jpeg")
    for candidate in [
        *vs.local_fanart_candidates(db, video),
        video_dir / f"{base}-fanart-frame-v3.jpg",
    ]:
        if candidate.exists():
            return FileResponse(str(candidate), media_type="image/jpeg")
    frame = video_dir / f"{base}-fanart-frame-v3.jpg"
    try:
        assets.capture_frame_at(video.file_path, str(frame), 1920, 1080, 45)
    except Exception:
        logger.debug("背景截帧失败 id=%s", id)
    if frame.exists():
        return FileResponse(str(frame), media_type="image/jpeg")
    if video.backdrop_url:
        data = assets.fetch_tmdb_image(video.backdrop_url)
        if data:
            return Response(
                content=data,
                media_type="image/jpeg",
                headers={"Cache-Control": assets.IMAGE_CACHE},
            )
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


@router.post("/{id:int}/refresh-covers")
def download_covers(db: DbSession, id: int):
    """一键从 TMDB 重新拉取封面与横屏（自动取默认那张）。

    路径不能叫 `/covers`：中间件把 `.*/cover` 当静态图片资源提前放行，
    不会写入当前用户，导致这里的 _require_admin 永远判为匿名 → 403。
    （旧路径 /covers 因此从未可用，客户端已同步改名。）
    想自己挑图请用 `GET /{id}/cover-options` + `POST /{id}/cover`。
    """
    _require_admin(db)
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    success = assets.download_all_covers(db, video, force=True)
    return ApiResponse.ok({"videoId": str(id), "success": str(success).lower()})


@router.get("/{id:int}/cover-options")
def video_cover_options(db: DbSession, id: int):
    """本集在 TMDB 上的候选横屏图（本集剧照 still），供用户挑选。

    分集横屏按项目约定就是「本集 still」；这里把候选列出来而不是只自动取一张。
    """
    _require_admin(db)
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    options = assets.episode_still_options(db, video)
    return ApiResponse.ok(
        {
            "videoId": video.id,
            "seasonNumber": video.season_number,
            "episodeNumber": video.episode_number,
            "current": video.backdrop_url,
            "options": options,
        }
    )


@router.post("/{id:int}/cover")
def set_video_cover(db: DbSession, id: int, body: CoverSelectRequest):
    """把选定的 TMDB 图应用为本集横屏封面（落 fanart.jpg）。"""
    _require_admin(db)
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    if not body.filePath:
        return ApiResponse.error("filePath 不能为空")
    ok = assets.apply_video_backdrop(db, video, body.filePath)
    if not ok:
        return ApiResponse.error("图片下载失败，请确认代理或网络")
    return ApiResponse.ok(
        {"videoId": video.id, "applied": body.filePath, "success": True}
    )


# -------------------- TMDB 分层图片（总览 / 季 / 单集 × 海报 / 背景图 / 剧照） --------------------


def _image_context(db, video) -> dict:
    """该视频对应的层级上下文：季号、集号、剧集 TMDB id、媒体类型。"""
    series = db.get(VideoSeries, video.series_id) if video.series_id else None
    return {
        "level_season": assets.season_of(video),
        "level_episode": video.episode_number,
        "series_tmdb_id": series.tmdb_id if series else video.tmdb_id,
        "media_type": (series.media_type if series else video.media_type),
    }


@router.get("/{id:int}/tmdb-images")
def video_tmdb_images(db: DbSession, id: int, level: str = "episode"):
    """某层级的 TMDB 图片候选。

    level=series（总览/剧集总海报、总背景图）| season（季海报）| episode（单集剧照）。
    返回该层级所有可用类型的候选，前端一次拿全。
    """
    _require_admin(db)
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    if level not in assets.IMAGE_LEVELS:
        return ApiResponse.error(f"level 必须是 {assets.IMAGE_LEVELS} 之一")
    ctx = _image_context(db, video)
    options = assets.tmdb_image_options(
        ctx["series_tmdb_id"],
        level,
        season=ctx["level_season"],
        episode=ctx["level_episode"],
        media_type=ctx["media_type"],
    )
    return ApiResponse.ok(
        {
            "videoId": video.id,
            "level": level,
            "seasonNumber": ctx["level_season"],
            "episodeNumber": ctx["level_episode"],
            "options": options,
        }
    )


@router.post("/{id:int}/tmdb-image")
def apply_video_tmdb_image(db: DbSession, id: int, body: TmdbImageSelectRequest):
    """把选定的 TMDB 图落到对应层级（总览 → 剧名根目录；季 → 季目录；单集 → 分集目录）。"""
    _require_admin(db)
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    if not body.filePath:
        return ApiResponse.error("filePath 不能为空")
    episodes = (
        vs.series_videos(db, video.series_id) if video.series_id else [video]
    )
    target = assets.apply_tmdb_image(
        db, video, episodes, body.level, body.kind, body.filePath
    )
    if target is None:
        return ApiResponse.error("图片下载失败或层级不支持，请确认代理或网络")
    return ApiResponse.ok(
        {
            "videoId": video.id,
            "level": body.level,
            "kind": body.kind,
            "applied": body.filePath,
            "path": str(target),
            "success": True,
        }
    )


@router.post("/{id:int}/cover-upload")
async def upload_video_cover(
    db: DbSession,
    id: int,
    file: UploadFile = File(...),
    level: str = Form("episode"),
    kind: str = Form("poster"),
):
    """上传本地图片作为封面/背景（multipart）。

    level=series（总览）| season（季）| episode（单集）
    kind=poster（竖版）| backdrop|still（横版）

    落盘位置与「应用 TMDB 图」完全一致；统一规范化为 JPEG。
    校验失败返回 success=false + 可直接展示的中文提示，前端不必自己翻译。
    """
    _require_admin(db)
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    data = await file.read()
    episodes = vs.series_videos(db, video.series_id) if video.series_id else [video]
    try:
        target = assets.save_uploaded_cover(db, video, episodes, level, kind, data)
    except assets.UploadError as exc:
        return ApiResponse.error(exc.message)
    db.commit()
    return ApiResponse.ok(
        {
            "videoId": video.id,
            "level": level,
            "kind": kind,
            "path": str(target),
            "bytes": len(data),
            "success": True,
        }
    )


ALLOWED_SIZES = {"w92", "w154", "w185", "w342", "w500", "w780", "original"}
TMDB_CDN = "https://image.tmdb.org/t/p"


def _safe_tmdb_path(path: str) -> bool:
    if not path or not path.startswith("/"):
        return False
    if ".." in path or "://" in path or len(path) > 512:
        return False
    return True
