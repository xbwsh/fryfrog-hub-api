from __future__ import annotations

import logging
import os
import shutil
import socket
import subprocess
import threading
from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse, Response, StreamingResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from fryfrog.core.api_response import ApiResponse, PageResponse
from fryfrog.core.deps import DbSession
from fryfrog.core.exceptions import BadRequestException, ForbiddenException, ResourceNotFoundException
from fryfrog.core.security import UserService, current_user_id
from fryfrog.core.signer import sign
from fryfrog.core.utils import placeholder_jpeg
from fryfrog.media_core import get_ffmpeg_runtime, get_media_probe
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoActor, VideoSeries
from fryfrog.schemas.video import (
    FrameSelectRequest,
    LibrarySeriesGroupDTO,
    LogoSelectRequest,
    SeriesDTO,
    SeriesFrameSelectRequest,
    SeriesListDTO,
    SeriesMetadataUpdateRequest,
    UpdatePositionRequest,
    UpdateWatchedRequest,
    VideoBindRequest,
    VideoMetadataUpdateRequest,
    WatchProgressDTO,
    apply_watch_progress,
)
from fryfrog.services import progress as progress_svc
from fryfrog.services import video_assets as assets
from fryfrog.services import video_scrape as scrape
from fryfrog.services import video_service as vs
from fryfrog.services.media_library import MediaLibraryService
from fryfrog.services.tmdb import TmdbClient

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/video", tags=["视频"])
series_router = APIRouter(prefix="/series", tags=["视频系列"])


def _mls(db: Session) -> MediaLibraryService:
    return MediaLibraryService(UserService())


def _require_admin(db: Session) -> None:
    if not UserService().is_admin(db, current_user_id()):
        raise ForbiddenException("需要管理员权限")


def _require_visible(db: Session, library_id: int | None, resource: str, rid: int) -> None:
    if not _mls(db).is_visible_to_current_user(db, library_id):
        raise ResourceNotFoundException(resource, "id", rid)


def _video_logo_url(video: Video) -> str | None:
    return assets.logo_file_url(video.logo_local_path, f"/api/v1/video/{video.id}/logo")


def _series_logo_url(series: VideoSeries) -> str | None:
    return assets.logo_file_url(series.logo_local_path, f"/api/v1/video/series/{series.id}/logo")


def _to_video_dto_with(db: Session, video: Video, progress, favorite: bool) -> dict:
    from fryfrog.schemas.video import VideoDTO

    flags = vs.asset_flags(db, video)
    dto = VideoDTO.from_entity(
        video,
        has_nfo=flags["has_nfo"],
        has_poster=flags["has_poster"],
        has_fanart=flags["has_fanart"],
        has_metadata_dir=flags["has_metadata_dir"],
        favorite=favorite,
        logo_url=_video_logo_url(video),
    )
    return apply_watch_progress(dto, progress).model_dump()


def _to_video_dto(db: Session, video: Video, favorite: bool = False) -> dict:
    progress = vs.get_progress(db, current_user_id(), video.id)
    return _to_video_dto_with(db, video, progress, favorite)


def _page_videos(db: Session, videos: list[Video], page: int, size: int, total: int) -> dict:
    uid = current_user_id()
    ids = [v.id for v in videos]
    fav = vs.favorite_status_map(db, uid, vs.TYPE_VIDEO, ids)
    prog = vs.get_progress_map(db, uid, ids)
    dtos = [_to_video_dto_with(db, v, prog.get(v.id), fav.get(v.id, False)) for v in videos]
    return PageResponse.of(dtos, page, size, total).model_dump()


def _allowed_ids(db: Session) -> list[int]:
    return _mls(db).get_allowable_library_ids(db)


def _actor_dict(a: VideoActor) -> dict:
    return {
        "id": a.id,
        "videoId": a.video_id,
        "name": a.name,
        "character": a.character,
        "imagePath": a.image_path,
        "imageUrl": scrape.actor_image_url(a),
        "sourceActorId": a.source_actor_id,
    }


def _series_visible(db: Session, series: VideoSeries) -> bool:
    mls = _mls(db)
    if not mls.is_restricted_current_user(db):
        return True
    vids = vs.series_videos(db, series.id)
    return any(mls.is_visible_to_current_user(db, v.library_id) for v in vids)


def _load_series_detail(db: Session, series: VideoSeries, favorite: bool) -> dict:
    from fryfrog.schemas.video import VideoDTO

    uid = current_user_id()
    episodes = vs.series_videos(db, series.id)
    fav_map = vs.favorite_status_map(db, uid, vs.TYPE_VIDEO, [e.id for e in episodes])
    prog_map = vs.get_progress_map(db, uid, [e.id for e in episodes])
    dtos = []
    for e in episodes:
        raw = _to_video_dto_with(db, e, prog_map.get(e.id), fav_map.get(e.id, False))
        dtos.append(VideoDTO(**raw))
    return SeriesDTO.from_entity(series, dtos, favorite).model_dump()


# ==================== 固定路径（须在 /{id} 之前） ====================


@router.get("/search/title")
def search_by_title(db: DbSession, q: str, page: int = 0, size: int = 20):
    allowed = _allowed_ids(db)
    base = select(Video).where(Video.library_id.in_(allowed), Video.title.ilike(f"%{q}%"))
    total = db.scalar(select(func.count()).select_from(base.subquery())) or 0
    rows = list(db.scalars(base.order_by(Video.title.asc()).offset(page * size).limit(size)).all())
    return ApiResponse.ok(_page_videos(db, rows, page, size, total))


@router.get("/search/director")
def search_by_director(db: DbSession, q: str, page: int = 0, size: int = 20):
    allowed = _allowed_ids(db)
    base = select(Video).where(Video.library_id.in_(allowed), Video.director.ilike(f"%{q}%"))
    total = db.scalar(select(func.count()).select_from(base.subquery())) or 0
    rows = list(db.scalars(base.order_by(Video.title.asc()).offset(page * size).limit(size)).all())
    return ApiResponse.ok(_page_videos(db, rows, page, size, total))


@router.get("/favorites")
def get_favorites(db: DbSession, page: int = 0, size: int = 20):
    uid = current_user_id()
    allowed = _allowed_ids(db)
    fav_ids = vs.favorite_content_ids(db, uid, vs.TYPE_VIDEO)
    if not fav_ids or not allowed:
        return ApiResponse.ok(PageResponse.of([], page, size, 0).model_dump())
    base = select(Video).where(Video.id.in_(fav_ids), Video.library_id.in_(allowed))
    total = db.scalar(select(func.count()).select_from(base.subquery())) or 0
    rows = list(db.scalars(base.order_by(Video.title.asc()).offset(page * size).limit(size)).all())
    return ApiResponse.ok(_page_videos(db, rows, page, size, total))


@router.get("/tmdb/search")
def tmdb_search(q: str):
    return ApiResponse.ok(scrape.search_tmdb(q))


@router.post("/tmdb/rescrape-library/{library_id:int}")
def rescrape_library(db: DbSession, library_id: int):
    _require_admin(db)
    lib = db.get(MediaLibrary, library_id)
    if lib is None:
        raise ResourceNotFoundException("MediaLibrary", "id", library_id)

    def job():
        session = None
        try:
            from fryfrog.db import get_session_factory

            session = get_session_factory()()
            scrape.rescrape_by_library(session, library_id)
            session.commit()
            logger.info("[Rescrape] Library %s completed", library_id)
        except Exception:
            logger.exception("[Rescrape] Library %s failed", library_id)
            if session:
                session.rollback()
        finally:
            if session:
                session.close()

    threading.Thread(target=job, daemon=True).start()
    return ApiResponse.ok(f"Rescrape started for library {library_id}")


@router.post("/refresh-all-actors")
def refresh_all_actors(db: DbSession):
    _require_admin(db)
    mls = _mls(db)
    scrape_libs = [
        lib.id
        for lib in mls.get_visible_libraries(db)
        if lib.enable_scraping and lib.is_video_type()
    ]
    videos = list(
        db.scalars(
            select(Video).where(
                Video.tmdb_id.is_not(None), Video.library_id.in_(scrape_libs or [-1])
            )
        ).all()
    )
    module = "actors"
    progress_svc.update_progress(
        module, stage="actors", running=True, total=len(videos), completed=0, failed=0
    )

    def job():
        session = None
        try:
            from fryfrog.db import get_session_factory
            from fryfrog.services.video_scrape import save_actors

            session = get_session_factory()()
            completed = failed = 0
            client = TmdbClient()
            for v in videos:
                try:
                    detail = (
                        client.get_movie(v.tmdb_id)
                        if (v.media_type or "") == "movie"
                        else client.get_tv(v.tmdb_id)
                    )
                    cast = ((detail or {}).get("credits") or {}).get("cast") or []
                    save_actors(session, v, cast)
                    completed += 1
                    progress_svc.update_progress(
                        module, completed=completed, failed=failed, currentItem=v.title
                    )
                except Exception:
                    failed += 1
                    progress_svc.update_progress(module, completed=completed, failed=failed)
            session.commit()
            progress_svc.update_progress(module, stage="done", running=False)
        except Exception:
            if session:
                session.rollback()
            progress_svc.update_progress(module, stage="error", running=False)
        finally:
            if session:
                session.close()

    threading.Thread(target=job, daemon=True).start()
    return ApiResponse.ok(
        {
            "totalVideos": len(videos),
            "status": "submitted",
            "message": "批量刷新演员任务已提交，正在后台执行",
            "module": module,
        }
    )


@router.post("/refresh-all-logos")
def refresh_all_logos(db: DbSession):
    _require_admin(db)
    mls = _mls(db)
    scrape_libs = {
        lib.id
        for lib in mls.get_enabled_libraries(db)
        if lib.enable_scraping and lib.is_video_type()
    }
    series_list = [
        s
        for s in db.scalars(select(VideoSeries)).all()
        if s.tmdb_id is not None
        and any(v.library_id in scrape_libs for v in vs.series_videos(db, s.id))
    ]
    movies = list(
        db.scalars(
            select(Video).where(
                Video.series_id.is_(None),
                Video.tmdb_id.is_not(None),
                Video.media_type == "movie",
                Video.library_id.in_(scrape_libs or [-1]),
            )
        ).all()
    )
    total = len(series_list) + len(movies)
    module = "logo:all"
    progress_svc.update_progress(module, stage="logo", running=True, total=total, completed=0)

    def job():
        session = None
        try:
            from fryfrog.db import get_session_factory

            session = get_session_factory()()
            completed = 0
            for s in series_list:
                try:
                    assets.download_series_logo(session, s)
                except Exception:
                    pass
                completed += 1
                progress_svc.update_progress(module, completed=completed, currentItem=s.title)
            for m in movies:
                try:
                    assets.download_movie_logo(session, m)
                except Exception:
                    pass
                completed += 1
                progress_svc.update_progress(module, completed=completed, currentItem=m.title)
            session.commit()
            progress_svc.update_progress(module, stage="done", running=False)
        except Exception:
            if session:
                session.rollback()
            progress_svc.update_progress(module, stage="error", running=False)
        finally:
            if session:
                session.close()

    threading.Thread(target=job, daemon=True).start()
    return ApiResponse.ok(
        {
            "totalSeries": len(series_list),
            "totalMovies": len(movies),
            "total": total,
            "status": "submitted",
            "message": "批量补全 logo 任务已提交，正在后台执行",
            "module": module,
        }
    )


@router.post("/refresh-all-resolutions")
def refresh_all_resolutions(db: DbSession):
    all_videos = list(db.scalars(select(Video)).all())
    missing = [v for v in all_videos if not v.resolution]
    module = "resolution"
    progress_svc.update_progress(
        module, stage="resolution", running=True, total=len(missing), completed=0
    )

    def job():
        session = None
        try:
            from fryfrog.db import get_session_factory

            session = get_session_factory()()
            probe = get_media_probe()
            updated = 0
            for v in missing:
                try:
                    wh = probe.probe_video_resolution(v.file_path)
                    if wh and wh[0] and wh[1]:
                        v.resolution = f"{wh[0]}x{wh[1]}"
                        updated += 1
                    progress_svc.update_progress(
                        module, completed=updated, currentItem=v.file_name
                    )
                except Exception:
                    pass
            session.commit()
            progress_svc.update_progress(module, stage="done", running=False)
        except Exception:
            if session:
                session.rollback()
            progress_svc.update_progress(module, stage="error", running=False)
        finally:
            if session:
                session.close()

    threading.Thread(target=job, daemon=True).start()
    return ApiResponse.ok(
        {
            "totalVideos": len(all_videos),
            "pendingVideos": len(missing),
            "status": "submitted",
            "message": "批量补全分辨率任务已提交，正在后台执行",
            "module": module,
        }
    )


@router.get("/scrape/progress")
def scrape_progress(module: str | None = None):
    key = module or "video"
    items = progress_svc.get_scrape_progress()
    for item in items:
        if item.get("module") == key:
            return ApiResponse.ok(item)
    return ApiResponse.ok(
        {
            "module": key,
            "stage": "idle",
            "running": False,
            "total": 0,
            "completed": 0,
            "failed": 0,
            "skipped": 0,
            "pending": 0,
            "percent": 0.0,
            "startedAt": None,
            "updatedAt": None,
            "currentItem": None,
            "items": [],
        }
    )


@router.post("/organize")
def organize(db: DbSession, path: str | None = None):
    _require_admin(db)
    if path:
        videos = list(db.scalars(select(Video).where(Video.file_path.like(f"{path}%"))).all())
    else:
        videos = list(db.scalars(select(Video)).all())
    result = assets.organize_videos(db, videos)
    return ApiResponse.ok(result, message="整理完成")


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


@router.get("/actor/{actor_id:int}")
def get_actor_detail(db: DbSession, actor_id: int):
    actor = db.get(VideoActor, actor_id)
    if actor is None:
        raise ResourceNotFoundException("VideoActor", "id", actor_id)
    video = db.get(Video, actor.video_id)
    if video is not None:
        _require_visible(db, video.library_id, "VideoActor", actor_id)
    dto = scrape.get_actor_detail(db, actor, refresh=False)
    if not dto.get("imageUrl"):
        dto["imageUrl"] = scrape.actor_image_url(actor)
    return ApiResponse.ok(dto)


@router.get("/actor/{actor_id:int}/refresh")
def refresh_actor_detail(db: DbSession, actor_id: int):
    _require_admin(db)
    actor = db.get(VideoActor, actor_id)
    if actor is None:
        raise ResourceNotFoundException("VideoActor", "id", actor_id)
    dto = scrape.get_actor_detail(db, actor, refresh=True)
    if not dto.get("imageUrl"):
        dto["imageUrl"] = scrape.actor_image_url(actor)
    return ApiResponse.ok(dto)


@router.get("/actor/{actor_id:int}/works")
def get_actor_works(db: DbSession, actor_id: int, page: int = 0, size: int = 20):
    actor = db.get(VideoActor, actor_id)
    if actor is None:
        raise ResourceNotFoundException("VideoActor", "id", actor_id)
    video_ids: set[int] = set()
    if actor.source_actor_id is not None:
        rows = db.scalars(
            select(VideoActor).where(VideoActor.source_actor_id == actor.source_actor_id)
        ).all()
        video_ids.update(r.video_id for r in rows)
    if actor.name:
        rows = db.scalars(
            select(VideoActor).where(func.lower(VideoActor.name) == actor.name.lower())
        ).all()
        video_ids.update(r.video_id for r in rows)
    if not video_ids:
        return ApiResponse.ok(PageResponse.of([], page, size, 0).model_dump())

    allowed = set(_allowed_ids(db))
    videos = [
        v
        for v in db.scalars(select(Video).where(Video.id.in_(list(video_ids)))).all()
        if v.library_id in allowed
    ]
    episodes_by_series: dict[int, list[Video]] = {}
    standalone: list[Video] = []
    for v in videos:
        if v.series_id:
            episodes_by_series.setdefault(v.series_id, []).append(v)
        else:
            standalone.append(v)

    uid = current_user_id()
    series_ids = list(episodes_by_series.keys())
    series_list = (
        list(db.scalars(select(VideoSeries).where(VideoSeries.id.in_(series_ids))).all())
        if series_ids
        else []
    )
    series_fav = vs.favorite_status_map(db, uid, vs.TYPE_SERIES, series_ids)
    video_fav = vs.favorite_status_map(db, uid, vs.TYPE_VIDEO, [v.id for v in standalone])

    items: list[SeriesListDTO] = []
    for s in series_list:
        items.append(
            SeriesListDTO.from_entity(
                s, episodes_by_series.get(s.id, []), series_fav.get(s.id, False)
            )
        )
    for v in standalone:
        items.append(SeriesListDTO.from_standalone_video(v, video_fav.get(v.id, False)))
    items.sort(key=lambda x: (-(x.year or 0), (x.title or "").lower()))
    total = len(items)
    start = min(page * size, total)
    end = min(start + size, total)
    return ApiResponse.ok(
        PageResponse.of([i.model_dump() for i in items[start:end]], page, size, total).model_dump()
    )


@router.get("/actor/{actor_id:int}/image")
def get_actor_image(db: DbSession, actor_id: int):
    actor = db.get(VideoActor, actor_id)
    if actor is None:
        raise ResourceNotFoundException("VideoActor", "id", actor_id)
    if actor.image_path and Path(actor.image_path).exists():
        return FileResponse(
            actor.image_path,
            media_type="image/jpeg",
            headers={"Cache-Control": assets.IMAGE_CACHE},
        )
    if actor.image_url:
        data = assets.download_url_bytes(actor.image_url)
        if data:
            return Response(
                content=data,
                media_type="image/jpeg",
                headers={"Cache-Control": assets.IMAGE_CACHE},
            )
    raise ResourceNotFoundException("Image", "actorId", actor_id)


# ==================== /{id} 系列 ====================


@router.put("/{id:int}/favorite")
def set_favorite(db: DbSession, id: int, status: bool):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    vs.set_favorite(db, current_user_id(), vs.TYPE_VIDEO, id, status)
    return ApiResponse.ok(_to_video_dto(db, video, status))


@router.put("/{id:int}/metadata")
def update_metadata(db: DbSession, id: int, body: VideoMetadataUpdateRequest):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    updated = False
    fields = {
        "title": "title",
        "overview": "overview",
        "rating": "rating",
        "year": "year",
        "releaseDate": "release_date",
        "genre": "genre",
        "director": "director",
        "actors": "actors",
        "originalTitle": "original_title",
        "tags": "tags",
    }
    data = body.model_dump(exclude_unset=True)
    for key, attr in fields.items():
        if key in data and data[key] is not None:
            setattr(video, attr, data[key])
            updated = True
    if updated:
        from datetime import datetime

        video.metadata_source = "manual"
        video.metadata_updated_at = datetime.now()
        db.flush()
        logger.info("[Metadata] Updated video id=%s", id)
    return ApiResponse.ok(_to_video_dto(db, video))


@router.get("/{id:int}/actors")
def get_video_actors(db: DbSession, id: int):
    try:
        video = vs.get_video(db, id)
        _require_visible(db, video.library_id, "Video", id)
        actors = vs.get_actors_for_video(db, id)
        if actors:
            return ApiResponse.ok([_actor_dict(a) for a in actors])
    except ResourceNotFoundException:
        pass
    series = vs.get_series(db, id)
    if series is not None:
        rows = list(
            db.scalars(
                select(VideoActor)
                .join(Video, Video.id == VideoActor.video_id)
                .where(Video.series_id == id)
            ).all()
        )
        return ApiResponse.ok([_actor_dict(a) for a in vs.dedup_actors(rows)])
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    return ApiResponse.ok([_actor_dict(a) for a in vs.get_actors_for_video(db, id)])


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


@router.get("/{id:int}/progress")
def get_watch_progress(db: DbSession, id: int):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    progress = vs.get_progress(db, current_user_id(), id)
    if progress is None:
        return ApiResponse.ok(None)
    return ApiResponse.ok(WatchProgressDTO.from_entity(progress).model_dump())


@router.put("/{id:int}/progress")
def update_watch_position(db: DbSession, id: int, body: UpdatePositionRequest):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    progress = vs.update_position(db, current_user_id(), id, body.position, body.duration)
    return ApiResponse.ok(WatchProgressDTO.from_entity(progress).model_dump())


@router.put("/{id:int}/watched")
def update_watched(db: DbSession, id: int, body: UpdateWatchedRequest):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    completed = bool(body.completed) if body and body.completed is not None else False
    progress = vs.update_watched(db, current_user_id(), id, completed)
    return ApiResponse.ok(WatchProgressDTO.from_entity(progress).model_dump())


@router.delete("/{id:int}/progress")
def delete_watch_progress(db: DbSession, id: int):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    vs.delete_progress(db, current_user_id(), id)
    return ApiResponse.ok(None)


@router.get("/{id:int}/cover")
def get_cover(db: DbSession, id: int):
    video = vs.get_video(db, id)
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


@router.get("/{id:int}/stream")
def stream_video(db: DbSession, id: int, request: Request):
    video = vs.get_video(db, id)
    path = Path(video.file_path)
    if not path.exists():
        raise ResourceNotFoundException("VideoFile", "id", id)
    file_size = path.stat().st_size
    content_type = _video_content_type(path.name)
    rng = _parse_range(request.headers.get("range"), file_size)
    headers = {"Accept-Ranges": "bytes"}
    if rng is None:
        headers["Content-Length"] = str(file_size)
        return StreamingResponse(
            _file_iterator(path, 0, file_size), media_type=content_type, headers=headers
        )
    start, end = rng
    length = end - start + 1
    headers["Content-Length"] = str(length)
    headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
    return StreamingResponse(
        _file_iterator(path, start, length),
        status_code=206,
        media_type=content_type,
        headers=headers,
    )


@router.get("/{id:int}/stream/transcode")
def stream_transcode(
    db: DbSession,
    id: int,
    quality: str = "1080p",
    maxBitrate: str | None = None,
    subtitle: str | None = None,
):
    runtime = get_ffmpeg_runtime()
    if not runtime.is_available():
        return Response(status_code=503, content=b"Transcoding not available")
    video = vs.get_video(db, id)
    path = Path(video.file_path)
    if not path.exists():
        raise ResourceNotFoundException("VideoFile", "id", id)

    subtitle_path = None
    if subtitle:
        video_dir = path.parent.resolve()
        sub = (video_dir / subtitle).resolve()
        if not str(sub).startswith(str(video_dir)) or not sub.is_file():
            return Response(status_code=400, content=b"Invalid subtitle")
        subtitle_path = str(sub)

    height = {"1080p": 1080, "720p": 720, "480p": 480}.get(quality, 1080)
    vf = f"scale=-2:{height}"
    if subtitle_path:
        vf = f"{vf},subtitles={subtitle_path}"
    cmd = [
        runtime.ffmpeg_path,
        "-i",
        str(path),
        "-vf",
        vf,
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-c:a",
        "aac",
        "-f",
        "mp4",
        "-movflags",
        "frag_keyframe+empty_moov",
    ]
    if maxBitrate:
        cmd.extend(["-b:v", maxBitrate])
    cmd.append("pipe:1")
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

    def gen():
        try:
            assert proc.stdout is not None
            while True:
                chunk = proc.stdout.read(8192)
                if not chunk:
                    break
                yield chunk
        finally:
            proc.kill()

    return StreamingResponse(
        gen(),
        media_type="video/mp4",
        headers={"Accept-Ranges": "none", "Cache-Control": "no-cache"},
    )


@router.get("/{id:int}/playlist.m3u")
def get_playlist(db: DbSession, id: int, request: Request):
    video = vs.get_video(db, id)
    if video.series_id:
        siblings = vs.series_videos(db, video.series_id)
    elif video.tmdb_id:
        siblings = list(db.scalars(select(Video).where(Video.tmdb_id == video.tmdb_id)).all())
        siblings.sort(key=lambda v: (v.season_number or 1, v.episode_number or 1))
    else:
        siblings = [video]

    base_url = _server_base_url(request)
    lines = ["#EXTM3U"]
    series_title = video.series_name or video.title
    for v in siblings:
        title = v.title
        if v.season_number is not None and v.episode_number is not None:
            title = f"S{v.season_number:02d}E{v.episode_number:02d} - {v.title}"
        lines.append(f"#EXTINF:-1,{title}")
        lines.append(f"{base_url}{sign(f'/api/v1/video/{v.id}/stream')}")
    content = ("\n".join(lines) + "\n").encode("utf-8")
    return Response(
        content=content,
        media_type="audio/x-mpegurl; charset=utf-8",
        headers={
            "Content-Disposition": f'attachment; filename="{series_title}.m3u"',
            "Content-Length": str(len(content)),
        },
    )


@router.get("/{id:int}/subtitles")
def list_subtitles(db: DbSession, id: int):
    video = vs.get_video(db, id)
    video_dir = Path(video.file_path).parent
    subtitles = []
    if video_dir.is_dir():
        for f in sorted(video_dir.iterdir()):
            if not f.is_file() or f.suffix.lower() not in assets.SUBTITLE_EXTS:
                continue
            name = f.name
            encoded = quote(name, safe="")
            subtitles.append(
                {
                    "filename": name,
                    "language": _subtitle_lang(name),
                    "url": sign(f"/api/v1/video/{id}/subtitles/{encoded}"),
                }
            )
    return ApiResponse.ok(subtitles)


@router.get("/{id:int}/subtitles/{filename}")
def get_subtitle(db: DbSession, id: int, filename: str):
    video = vs.get_video(db, id)
    video_dir = Path(video.file_path).parent.resolve()
    sub = (video_dir / filename).resolve()
    if not str(sub).startswith(str(video_dir)):
        raise BadRequestException("Invalid subtitle path")
    if not sub.exists():
        raise ResourceNotFoundException("Subtitle", "filename", filename)
    lower = filename.lower()
    media = "text/vtt" if lower.endswith(".vtt") else "text/plain; charset=utf-8"
    return Response(content=sub.read_bytes(), media_type=media)


@router.post("/{id:int}/tmdb/bind")
def tmdb_bind(db: DbSession, id: int, body: VideoBindRequest):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    module = f"bind:{id}"
    progress_svc.update_progress(
        module, stage="bind", running=True, total=1, completed=0
    )

    def job():
        session = None
        try:
            from fryfrog.db import get_session_factory

            session = get_session_factory()()
            bound = scrape.bind_series(session, id, body.tmdbId, body.mediaType)
            progress_svc.update_progress(module, stage="organize")
            assets.organize_videos(session, bound)
            progress_svc.update_progress(module, stage="assets")
            for v in bound:
                assets.generate_nfo(session, v)
                assets.download_all_covers(session, v, force=True)
            session.commit()
            progress_svc.update_progress(module, stage="done", running=False, completed=1)
        except Exception:
            logger.exception("绑定 TMDB 失败 id=%s", id)
            if session:
                session.rollback()
            progress_svc.update_progress(module, stage="error", running=False, completed=1)
        finally:
            if session:
                session.close()

    threading.Thread(target=job, daemon=True).start()
    return ApiResponse.ok({"status": "started", "videoId": id})


@router.post("/{id:int}/tmdb/unbind")
def tmdb_unbind(db: DbSession, id: int):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    if not video.tmdb_id:
        return ApiResponse.ok({"unbound": 0})
    tmdb_id = video.tmdb_id
    count = scrape.unbind_by_tmdb_id(db, tmdb_id)
    return ApiResponse.ok({"tmdbId": tmdb_id, "unbound": count})


@router.post("/{id:int}/tmdb/refresh")
def tmdb_refresh(db: DbSession, id: int):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    module = f"bind:{id}"
    progress_svc.update_progress(module, stage="bind", running=True, total=1, completed=0)

    def job():
        session = None
        try:
            from fryfrog.db import get_session_factory

            session = get_session_factory()()
            results = scrape.rescrape_video(session, id)
            progress_svc.update_progress(module, stage="organize")
            assets.organize_videos(session, results)
            progress_svc.update_progress(module, stage="assets")
            for v in results:
                assets.generate_nfo(session, v)
                assets.download_all_covers(session, v, force=True)
            session.commit()
            progress_svc.update_progress(module, stage="done", running=False, completed=1)
        except Exception:
            logger.exception("刷新 TMDB 失败 id=%s", id)
            if session:
                session.rollback()
            progress_svc.update_progress(module, stage="error", running=False, completed=1)
        finally:
            if session:
                session.close()

    threading.Thread(target=job, daemon=True).start()
    return ApiResponse.ok({"status": "started", "videoId": id})


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


@router.get("/{id:int}")
def get_video_detail(db: DbSession, id: int):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    return ApiResponse.ok(_to_video_dto(db, video))


# ==================== 系列 ====================


@series_router.get("")
def list_series(db: DbSession, page: int = 0, size: int = 20):
    uid = current_user_id()
    all_series = [s for s in db.scalars(select(VideoSeries)).all() if _series_visible(db, s)]
    all_series.sort(key=lambda s: (s.title or "").lower())
    allowed = _allowed_ids(db)
    standalone_total = (
        db.scalar(
            select(func.count())
            .select_from(Video)
            .where(Video.series_id.is_(None), Video.library_id.in_(allowed))
        )
        or 0
    )
    total = len(all_series) + standalone_total
    start = page * size
    if start >= total:
        return ApiResponse.ok(PageResponse.of([], page, size, total).model_dump())
    end = min(start + size, total)
    items: list[dict] = []

    series_end = min(end, len(all_series))
    if start < len(all_series):
        paged = all_series[start:series_end]
        series_fav = vs.favorite_status_map(db, uid, vs.TYPE_SERIES, [s.id for s in paged])
        for s in paged:
            episodes = vs.series_videos(db, s.id)
            items.append(
                SeriesListDTO.from_entity(s, episodes, series_fav.get(s.id, False)).model_dump()
            )

    standalone_start = max(0, start - len(all_series))
    standalone_end = max(0, end - len(all_series))
    if standalone_end > 0:
        rows = list(
            db.scalars(
                select(Video)
                .where(Video.series_id.is_(None), Video.library_id.in_(allowed))
                .order_by(Video.title.asc())
                .offset(standalone_start)
                .limit(standalone_end - standalone_start)
            ).all()
        )
        video_fav = vs.favorite_status_map(db, uid, vs.TYPE_VIDEO, [v.id for v in rows])
        for v in rows:
            items.append(
                SeriesListDTO.from_standalone_video(v, video_fav.get(v.id, False)).model_dump()
            )
    return ApiResponse.ok(PageResponse.of(items, page, size, total).model_dump())


@series_router.get("/grouped-by-library")
def grouped_by_library(db: DbSession, page: int = 0, size: int = 50):
    uid = current_user_id()
    mls = _mls(db)
    allowed = set(_allowed_ids(db))
    libraries = [
        lib
        for lib in mls.get_enabled_libraries(db)
        if lib.is_video_type() and lib.id in allowed
    ]
    libraries.sort(key=lambda x: x.sort_order or 0)

    result: list[dict] = []
    for lib in libraries:
        lib_series = [
            s
            for s in db.scalars(select(VideoSeries)).all()
            if any(v.library_id == lib.id for v in vs.series_videos(db, s.id))
        ]
        lib_series.sort(key=lambda s: (s.title or "").lower())
        paged_series = lib_series[page * size : (page + 1) * size]
        standalone_all = list(
            db.scalars(
                select(Video)
                .where(Video.series_id.is_(None), Video.library_id == lib.id)
                .order_by(Video.title.asc())
            ).all()
        )
        paged_standalone = standalone_all[page * size : (page + 1) * size]
        series_fav = vs.favorite_status_map(db, uid, vs.TYPE_SERIES, [s.id for s in paged_series])
        standalone_fav = vs.favorite_status_map(
            db, uid, vs.TYPE_VIDEO, [v.id for v in paged_standalone]
        )
        series_dtos = [
            SeriesListDTO.from_entity(
                s, vs.series_videos(db, s.id), series_fav.get(s.id, False)
            ).model_dump()
            for s in paged_series
        ]
        standalone_dtos = [
            SeriesListDTO.from_standalone_video(v, standalone_fav.get(v.id, False)).model_dump()
            for v in paged_standalone
        ]
        if series_dtos or standalone_dtos:
            result.append(
                LibrarySeriesGroupDTO(
                    libraryId=lib.id,
                    libraryName=lib.name,
                    libraryPath=lib.path,
                    subType=lib.sub_type,
                    series=series_dtos,
                    standaloneVideos=standalone_dtos,
                    seriesCount=len(lib_series),
                    standaloneCount=len(standalone_all),
                ).model_dump()
            )
    return ApiResponse.ok(result)


@series_router.get("/calendar")
def series_calendar(db: DbSession):
    from datetime import date, timedelta

    today = date.today()
    result = []
    for s in db.scalars(select(VideoSeries)).all():
        if not _series_visible(db, s):
            continue
        if not s.next_episode_date or (s.media_type or "").lower() != "tv":
            continue
        try:
            d = date.fromisoformat(s.next_episode_date)
        except ValueError:
            continue
        if d < today - timedelta(days=1):
            continue
        result.append(
            {
                "seriesId": s.id,
                "title": s.title,
                "coverUrl": sign(f"/api/v1/video/series/{s.id}/cover"),
                "fanartUrl": sign(f"/api/v1/video/series/{s.id}/fanart"),
                "nextEpisodeDate": s.next_episode_date,
                "nextEpisodeNumber": s.next_episode_number,
            }
        )
    result.sort(key=lambda x: x.get("nextEpisodeDate") or "")
    return ApiResponse.ok(result)


@series_router.get("/favorites")
def favorite_series(db: DbSession, page: int = 0, size: int = 20):
    uid = current_user_id()
    fav_ids = vs.favorite_content_ids(db, uid, vs.TYPE_SERIES)
    visible = []
    for sid in fav_ids:
        s = vs.get_series(db, sid)
        if s and _series_visible(db, s):
            visible.append(s)
    visible.sort(key=lambda s: (s.title or "").lower())
    total = len(visible)
    start = min(page * size, total)
    end = min(start + size, total)
    items = [
        SeriesListDTO.from_entity(s, vs.series_videos(db, s.id), True).model_dump()
        for s in visible[start:end]
    ]
    return ApiResponse.ok(PageResponse.of(items, page, size, total).model_dump())


@series_router.post("/refresh-all-season-covers")
def refresh_all_season_covers(db: DbSession):
    mls = _mls(db)
    scrape_libs = {
        lib.id
        for lib in mls.get_enabled_libraries(db)
        if lib.enable_scraping and lib.is_video_type()
    }
    series_list = [
        s
        for s in db.scalars(select(VideoSeries)).all()
        if s.tmdb_id is not None
        and any(v.library_id in scrape_libs for v in vs.series_videos(db, s.id))
    ]
    module = "season-covers"
    progress_svc.update_progress(
        module, stage="season-covers", running=True, total=len(series_list), completed=0
    )

    def job():
        session = None
        try:
            from fryfrog.db import get_session_factory

            session = get_session_factory()()
            completed = 0
            for s in series_list:
                try:
                    for e in vs.series_videos(session, s.id):
                        assets.download_all_covers(session, e, force=False)
                    completed += 1
                    progress_svc.update_progress(module, completed=completed, currentItem=s.title)
                except Exception:
                    pass
            session.commit()
            progress_svc.update_progress(module, stage="done", running=False)
        except Exception:
            if session:
                session.rollback()
            progress_svc.update_progress(module, stage="error", running=False)
        finally:
            if session:
                session.close()

    threading.Thread(target=job, daemon=True).start()
    return ApiResponse.ok(
        {
            "totalSeries": len(series_list),
            "status": "submitted",
            "message": "批量刷新任务已提交，正在后台执行",
            "module": module,
        }
    )


@series_router.put("/{id:int}/favorite")
def set_series_favorite(db: DbSession, id: int, status: bool):
    uid = current_user_id()
    series = vs.get_series(db, id)
    if series is not None:
        episodes = vs.series_videos(db, id)
        if episodes and not any(
            _mls(db).is_visible_to_current_user(db, v.library_id) for v in episodes
        ):
            raise ResourceNotFoundException("Series", "id", id)
    vs.set_favorite(db, uid, vs.TYPE_SERIES, id, status)
    series = vs.get_series(db, id)
    if series is None:
        raise ResourceNotFoundException("Series", "id", id)
    return ApiResponse.ok(_load_series_detail(db, series, status))


@series_router.put("/{id:int}/metadata")
def update_series_metadata(db: DbSession, id: int, body: SeriesMetadataUpdateRequest):
    _require_admin(db)
    series = vs.get_series(db, id)
    if series is None:
        raise ResourceNotFoundException("Series", "id", id)
    episodes = vs.series_videos(db, id)
    if not episodes or not any(
        _mls(db).is_visible_to_current_user(db, v.library_id) for v in episodes
    ):
        raise ResourceNotFoundException("Series", "id", id)
    data = body.model_dump(exclude_unset=True)
    fields = {
        "title": "title",
        "overview": "overview",
        "rating": "rating",
        "year": "year",
        "releaseDate": "release_date",
        "originalTitle": "original_title",
        "status": "status",
    }
    updated = False
    for key, attr in fields.items():
        if key in data and data[key] is not None:
            setattr(series, attr, data[key])
            updated = True
    if updated:
        series.metadata_source = "manual"
        db.flush()
    favorite = vs.favorite_status_map(db, current_user_id(), vs.TYPE_SERIES, [id]).get(id, False)
    return ApiResponse.ok(_load_series_detail(db, series, favorite))


@series_router.post("/{id:int}/frames/select")
def select_series_fanart(db: DbSession, id: int, body: SeriesFrameSelectRequest):
    if not body.videoId:
        return ApiResponse.error("videoId 不能为空")
    series = vs.get_series(db, id)
    if series is None:
        return ApiResponse.error(f"系列不存在: {id}")
    video = vs.get_video(db, body.videoId)
    if video.series_id != id:
        return ApiResponse.error("该视频不属于此系列")
    frame_path = assets.frames_cache_dir(video) / f"frame-{body.index}.jpg"
    if not frame_path.exists():
        return ApiResponse.error("候选帧不存在，请先调用单集生成接口")
    video_dir = Path(video.file_path).parent
    base = vs.get_base_name(video.file_name)
    output_path = video_dir / f"{base}-series-fanart.jpg"
    duration = get_media_probe().probe_video_duration(video.file_path) or 0
    ratios = assets.FRAME_RATIOS
    pos = duration * ratios[body.index] if duration > 0 else 30 + body.index * 30
    ok = assets.capture_frame_at(video.file_path, str(output_path), 1920, 1080, pos)
    if not ok:
        shutil.copyfile(frame_path, output_path)
    series.backdrop_local_path = str(output_path)
    db.flush()
    return ApiResponse.ok({"seriesId": id, "videoId": body.videoId, "path": str(output_path)})


@series_router.get("/{id:int}/actors")
def get_series_actors(db: DbSession, id: int):
    series = vs.get_series(db, id)
    if series is None:
        raise ResourceNotFoundException("Series", "id", id)
    rows = list(
        db.scalars(
            select(VideoActor)
            .join(Video, Video.id == VideoActor.video_id)
            .where(Video.series_id == id)
        ).all()
    )
    return ApiResponse.ok([_actor_dict(a) for a in vs.dedup_actors(rows)])


@series_router.post("/{id:int}/refresh-season-covers")
def refresh_season_covers(db: DbSession, id: int):
    series = vs.get_series(db, id)
    if series is None:
        return ApiResponse.error(f"系列不存在: {id}")
    if not series.tmdb_id:
        return ApiResponse.error("系列没有 TMDB ID，无法获取资源")
    episodes = vs.series_videos(db, id)
    season_posters = episode_covers = actors = 0
    client = TmdbClient()
    seasons = {e.season_number or 1 for e in episodes}
    for sn in seasons:
        season = client.get_season(series.tmdb_id, sn)
        if season and season.get("poster_path"):
            ep = next((e for e in episodes if (e.season_number or 1) == sn), None)
            if ep:
                season_dir = vs.get_season_dir(db, ep)
                if season_dir:
                    season_dir.mkdir(parents=True, exist_ok=True)
                    url = client.image_url(season["poster_path"])
                    if url and assets.download_image(
                        url, season_dir / "tvshow-poster.jpg", force=True
                    ):
                        season_posters += 1
    detail = client.get_tv(series.tmdb_id)
    cast = ((detail or {}).get("credits") or {}).get("cast") or []
    for e in episodes:
        if e.poster_url and assets.download_all_covers(db, e, force=False):
            episode_covers += 1
        try:
            scrape.save_actors(db, e, cast)
            actors += 1
        except Exception:
            pass
    return ApiResponse.ok(
        {
            "seriesId": id,
            "seriesTitle": series.title,
            "refreshedSeasonPosters": season_posters,
            "refreshedEpisodeCovers": episode_covers,
            "refreshedActors": actors,
            "cleanedOldActorsDirs": 0,
            "totalSeasons": series.number_of_seasons,
            "totalEpisodes": len(episodes),
        }
    )


@series_router.post("/{id:int}/refresh-logo")
def refresh_series_logo(db: DbSession, id: int):
    series = vs.get_series(db, id)
    if series is None:
        return ApiResponse.error(f"系列不存在: {id}")
    if not series.tmdb_id:
        return ApiResponse.error("系列没有 TMDB ID，无法获取 logo")
    ok = assets.download_series_logo(db, series)
    return ApiResponse.ok(
        {
            "seriesId": id,
            "seriesTitle": series.title,
            "downloaded": ok,
            "logoUrl": _series_logo_url(series),
        }
    )


@series_router.get("/{id:int}/logo-options")
def get_series_logo_options(db: DbSession, id: int):
    series = vs.get_series(db, id)
    if series is None:
        return ApiResponse.error(f"系列不存在: {id}")
    if not series.tmdb_id:
        return ApiResponse.error("系列没有 TMDB ID，无法查询 logo")
    return ApiResponse.ok(assets.tv_logo_options(series.tmdb_id))


@series_router.post("/{id:int}/logo")
def set_series_logo(db: DbSession, id: int, body: LogoSelectRequest):
    _require_admin(db)
    if not body.filePath:
        return ApiResponse.error("filePath 不能为空")
    series = vs.get_series(db, id)
    if series is None:
        return ApiResponse.error(f"系列不存在: {id}")
    episodes = vs.series_videos(db, id)
    if not episodes or not any(
        _mls(db).is_visible_to_current_user(db, v.library_id) for v in episodes
    ):
        raise ResourceNotFoundException("Series", "id", id)
    if not series.tmdb_id:
        return ApiResponse.error("系列没有 TMDB ID，无法设置 logo")
    ok = assets.download_series_logo(db, series, file_path=body.filePath)
    return ApiResponse.ok(
        {
            "seriesId": id,
            "seriesTitle": series.title,
            "downloaded": ok,
            "logoUrl": _series_logo_url(series),
        }
    )


@series_router.get("/{id:int}/cover")
def get_series_cover(db: DbSession, id: int):
    series = vs.get_series(db, id)
    title = "Unknown"
    poster_url = None
    if series is not None:
        title = series.title
        if series.poster_local_path and Path(series.poster_local_path).exists():
            return FileResponse(series.poster_local_path, media_type="image/jpeg")
        poster_url = series.poster_url
    else:
        video = vs.get_video(db, id)
        title = video.title
        if video.cover_art_path and Path(video.cover_art_path).exists():
            return FileResponse(video.cover_art_path, media_type="image/jpeg")
        poster = Path(video.file_path).parent / f"{vs.get_base_name(video.file_name)}-poster.jpg"
        if poster.exists():
            return FileResponse(str(poster), media_type="image/jpeg")
        poster_url = video.poster_url
    if not poster_url:
        return Response(content=placeholder_jpeg(300, 450, title), media_type="image/jpeg")
    data = assets.fetch_tmdb_image(poster_url)
    if not data:
        return Response(content=placeholder_jpeg(300, 450, title), media_type="image/jpeg")
    return Response(content=data, media_type="image/jpeg")


@series_router.get("/{id:int}/season/{season_number:int}/cover")
def get_season_cover(db: DbSession, id: int, season_number: int):
    series = vs.get_series(db, id)
    if series is None:
        return Response(content=placeholder_jpeg(300, 450, "Unknown"), media_type="image/jpeg")
    season_video = next(
        (v for v in vs.series_videos(db, id) if v.season_number == season_number), None
    )
    if season_video is not None:
        season_dir = vs.get_season_dir(db, season_video)
        if season_dir:
            poster = season_dir / "tvshow-poster.jpg"
            if poster.exists():
                return FileResponse(str(poster), media_type="image/jpeg")
    if series.tmdb_id:
        client = TmdbClient()
        season = client.get_season(series.tmdb_id, season_number)
        if season and season.get("poster_path"):
            url = client.image_url(season["poster_path"])
            data = assets.download_url_bytes(url) if url else None
            if data:
                return Response(content=data, media_type="image/jpeg")
    return get_series_cover(db, id)


@series_router.get("/{id:int}/fanart")
def get_series_fanart(db: DbSession, id: int):
    series = vs.get_series(db, id)
    title = "Unknown"
    backdrop_url = None
    if series is not None:
        title = series.title
        if series.backdrop_local_path and Path(series.backdrop_local_path).exists():
            return FileResponse(series.backdrop_local_path, media_type="image/jpeg")
        backdrop_url = series.backdrop_url
    else:
        video = vs.get_video(db, id)
        title = video.title
        if video.backdrop_local_path and Path(video.backdrop_local_path).exists():
            return FileResponse(video.backdrop_local_path, media_type="image/jpeg")
        base = vs.get_base_name(video.file_name)
        fanart = Path(video.file_path).parent / f"{base}-fanart.jpg"
        if not fanart.exists():
            fanart = vs.get_fanart_path(db, video)
        if fanart.exists():
            return FileResponse(str(fanart), media_type="image/jpeg")
        backdrop_url = video.backdrop_url
    if not backdrop_url:
        return Response(content=placeholder_jpeg(1920, 400, title), media_type="image/jpeg")
    data = assets.fetch_tmdb_image(backdrop_url)
    if not data:
        return Response(content=placeholder_jpeg(1920, 400, title), media_type="image/jpeg")
    return Response(content=data, media_type="image/jpeg")


@series_router.get("/{id:int}/logo")
def get_series_logo(db: DbSession, id: int):
    series = vs.get_series(db, id)
    if series is None:
        raise ResourceNotFoundException("Series", "id", id)
    if series.logo_local_path and Path(series.logo_local_path).exists():
        return FileResponse(
            series.logo_local_path, media_type=assets.media_type_of(series.logo_local_path)
        )
    logo_url = series.logo_url
    if not logo_url and series.tmdb_id:
        logos = assets.tv_logo_options(series.tmdb_id)
        logo_url = logos[0]["filePath"] if logos else None
    if not logo_url:
        raise ResourceNotFoundException("Logo", "id", id)
    data = assets.fetch_tmdb_image(logo_url)
    if not data:
        raise ResourceNotFoundException("Logo", "id", id)
    return Response(content=data, media_type=assets.media_type_of(logo_url))


@series_router.get("/{id:int}")
def get_series_detail(db: DbSession, id: int, type: str | None = None):
    uid = current_user_id()
    if type != "standalone":
        series = vs.get_series(db, id)
        if series is not None:
            episodes = vs.series_videos(db, id)
            if _mls(db).is_restricted_user(db, uid) and (
                not episodes
                or not any(
                    _mls(db).is_visible_to_current_user(db, v.library_id) for v in episodes
                )
            ):
                raise ResourceNotFoundException("Series", "id", id)
            favorite = vs.favorite_status_map(db, uid, vs.TYPE_SERIES, [id]).get(id, False)
            return ApiResponse.ok(_load_series_detail(db, series, favorite))
    if type != "series":
        try:
            video = vs.get_video(db, id)
        except ResourceNotFoundException:
            raise ResourceNotFoundException("Series", "id", id)
        if video.series_id:
            raise ResourceNotFoundException("Series", "id", id)
        if _mls(db).is_restricted_user(db, uid) and not _mls(db).is_visible_to_current_user(
            db, video.library_id
        ):
            raise ResourceNotFoundException("Series", "id", id)
        favorite = vs.favorite_status_map(db, uid, vs.TYPE_VIDEO, [id]).get(id, False)
        from fryfrog.schemas.video import VideoDTO

        episode = VideoDTO(**_to_video_dto(db, video, favorite))
        return ApiResponse.ok(
            SeriesDTO.from_standalone_video(video, episode, favorite).model_dump()
        )
    raise ResourceNotFoundException("Series", "id", id)


# ==================== 工具 ====================

VIDEO_CONTENT_TYPES = {
    ".mkv": "video/x-matroska",
    ".mp4": "video/mp4",
    ".avi": "video/x-msvideo",
    ".mov": "video/quicktime",
    ".wmv": "video/x-ms-wmv",
    ".flv": "video/x-flv",
    ".webm": "video/webm",
    ".ts": "video/mp2t",
    ".m4v": "video/x-m4v",
    ".m2ts": "video/mp2t",
}

ALLOWED_SIZES = {"w92", "w154", "w185", "w342", "w500", "w780", "original"}
TMDB_CDN = "https://image.tmdb.org/t"


def _video_content_type(name: str) -> str:
    return VIDEO_CONTENT_TYPES.get(Path(name).suffix.lower(), "application/octet-stream")


def _parse_range(range_header: str | None, file_size: int) -> tuple[int, int] | None:
    if not range_header or not range_header.startswith("bytes="):
        return None
    spec = range_header[6:].split(",")[0].strip()
    if "-" not in spec:
        return None
    start_s, end_s = spec.split("-", 1)
    try:
        if start_s == "":
            suffix = int(end_s)
            start = max(file_size - suffix, 0)
            end = file_size - 1
        else:
            start = int(start_s)
            end = int(end_s) if end_s else file_size - 1
    except ValueError:
        return None
    if start >= file_size or start > end:
        return None
    return start, min(end, file_size - 1)


def _file_iterator(path: Path, start: int, length: int, chunk: int = 64 * 1024):
    with open(path, "rb") as f:
        f.seek(start)
        remaining = length
        while remaining > 0:
            data = f.read(min(chunk, remaining))
            if not data:
                break
            remaining -= len(data)
            yield data


def _subtitle_lang(filename: str) -> str:
    stem = filename.rsplit(".", 1)[0] if "." in filename else filename
    if "." in stem:
        lang = stem.rsplit(".", 1)[-1]
        if lang:
            return lang
    return "und"


def _server_base_url(request: Request) -> str:
    env_base = os.environ.get("VIDEO_BASE_URL", "")
    if env_base:
        return env_base.rstrip("/")
    scheme = request.headers.get("x-forwarded-proto") or request.url.scheme
    forwarded_host = request.headers.get("x-forwarded-host")
    if forwarded_host:
        return f"{scheme}://{forwarded_host}"
    host = request.url.hostname or "localhost"
    if host in ("localhost", "127.0.0.1"):
        lan = _detect_lan_ip()
        if lan:
            host = lan
    xf_port = request.headers.get("x-forwarded-port")
    port = int(xf_port) if xf_port and xf_port.isdigit() else (request.url.port or 80)
    return f"{scheme}://{host}:{port}"


def _detect_lan_ip() -> str | None:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return None


def _safe_tmdb_path(path: str) -> bool:
    if not path or not path.startswith("/"):
        return False
    if ".." in path or "://" in path or len(path) > 512:
        return False
    return True


router.include_router(series_router)
