"""刮削域：TMDB 搜索/绑定/刷新、批量补全任务、演员。"""
from __future__ import annotations

import logging
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse, Response
from sqlalchemy import func, select

from fryfrog.core.api_response import ApiResponse, PageResponse
from fryfrog.core.deps import DbSession
from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.core.security import current_user_id
from fryfrog.media_core import get_media_probe
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoActor, VideoSeries
from fryfrog.schemas.video import SeriesListDTO, VideoBindRequest
from fryfrog.services import progress as progress_svc
from fryfrog.services import video_assets as assets
from fryfrog.services import video_scrape as scrape
from fryfrog.services import video_service as vs
from fryfrog.services.tmdb import TmdbClient

from ._common import _allowed_ids, _mls, _require_admin, _require_visible, clamp_paging, submit_job

logger = logging.getLogger(__name__)

router = APIRouter()


def _download_root_art_if_series(session, videos) -> None:
    """绑定的是剧集时，把总封面/总横屏落地到剧名根目录（与季文件夹同级）。"""
    eps = [v for v in videos if v.series_id]
    if not eps:
        return
    series = vs.get_series(session, eps[0].series_id)
    if series is not None and series.tmdb_id:
        assets.download_series_root_art(session, series, eps)
@router.get("/tmdb/search")
def tmdb_search(q: str):
    return ApiResponse.ok(scrape.search_tmdb(q))


@router.post("/tmdb/rescrape-library/{library_id:int}")
def rescrape_library(db: DbSession, library_id: int):
    _require_admin(db)
    lib = db.get(MediaLibrary, library_id)
    if lib is None:
        raise ResourceNotFoundException("MediaLibrary", "id", library_id)
    total = db.scalar(select(func.count()).where(Video.library_id == library_id)) or 0
    module = f"rescrape:{library_id}"

    def work(session):
        scrape.rescrape_by_library(session, library_id)
        for v in session.scalars(select(Video).where(Video.library_id == library_id)).all():
            assets.upgrade_legacy_assets(session, v)
        progress_svc.update_progress(module, completed=total)
        logger.info("[Rescrape] Library %s completed", library_id)

    submit_job(module, "rescrape", total, work)
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

    def work(session):
        from fryfrog.services.video_scrape import save_actors

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

    submit_job(module, "actors", len(videos), work)
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

    def work(session):
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

    submit_job(module, "logo", total, work)
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

    def work(session):
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

    submit_job(module, "resolution", len(missing), work)
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
    page, size = clamp_paging(page, size)
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


@router.post("/{id:int}/tmdb/bind")
def tmdb_bind(db: DbSession, id: int, body: VideoBindRequest):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    module = f"bind:{id}"

    def work(session):
        bound = scrape.bind_series(session, id, body.tmdbId, body.mediaType)
        progress_svc.update_progress(module, stage="organize")
        for v in bound:
            assets.upgrade_legacy_assets(session, v)
        progress_svc.update_progress(module, stage="assets")
        for v in bound:
            assets.generate_nfo(session, v)
            assets.download_all_covers(session, v, force=True)
        _download_root_art_if_series(session, bound)

    submit_job(module, "bind", 1, work, error_fields={"completed": 1}, completed=1)
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

