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
    """批量刷新该库**已绑定**视频的元数据。

    语义已改为「安全刷新」：只处理有 tmdb_id 的记录，用**已有 ID** 拉取，
    不搜索、不清绑定。原实现是「先 unbind 全部，再按文件名 search_tmdb_best
    重绑」，会把正确的绑定改坏，也会去搜用户刻意留在未刮削状态的视频
    （那些在 TMDB 上不存在，强搜只会写入错误内容）。
    绑错的条目请逐个手动重绑。
    """
    _require_admin(db)
    lib = db.get(MediaLibrary, library_id)
    if lib is None:
        raise ResourceNotFoundException("MediaLibrary", "id", library_id)
    total = db.scalar(select(func.count()).where(Video.library_id == library_id)) or 0
    module = f"rescrape:{library_id}"

    def work(session):
        # 逐部剧上报进度：整批可能几分钟，只在结束时上报一次会让界面一直显示 0%
        def on_progress(refreshed, skipped, failed, planned):
            progress_svc.update_progress(
                module,
                total=planned,
                completed=refreshed,
                failed=failed,
                skipped=skipped,
            )

        result = scrape.refresh_bound_by_library(session, library_id, on_progress)
        for v in session.scalars(select(Video).where(Video.library_id == library_id)).all():
            assets.upgrade_legacy_assets(session, v)
        progress_svc.update_progress(
            module,
            completed=result["refreshed"],
            failed=result["failed"],
            skipped=result["skipped"],
        )
        logger.info("[Rescrape] Library %s 完成: %s", library_id, result)

    submit_job(module, "rescrape", total, work)
    return ApiResponse.ok(
        f"已开始刷新资源库 {library_id}（跳过未绑定视频；绑错的请逐个手动重绑）"
    )


@router.post("/nfo/regenerate-all")
def regenerate_all_nfo(db: DbSession, libraryId: int | None = None):
    """批量（重新）生成所有剧的 tvshow.nfo 与各季 season.nfo。

    为什么需要这个：`scrape_video_if_needed` 开头就是 `if video.tmdb_id: return`，
    已绑定的剧不会再走刮削流程，所以**重新刮削刷不到 NFO**。存量剧的剧根 NFO
    （以及本程序此前从不生成的季级 NFO）只能靠这个入口一次性补齐/统一格式。

    默认覆盖已有文件——这是"统一成新格式"的入口；分集 NFO 不在这里处理
    （由扫描/刮削按集生成）。
    """
    _require_admin(db)
    series_rows = list(
        db.execute(
            select(VideoSeries.id, VideoSeries.tmdb_id, VideoSeries.title).order_by(
                VideoSeries.id
            )
        ).all()
    )
    if libraryId is not None:
        allowed = {
            row[0]
            for row in db.execute(
                select(Video.series_id)
                .where(Video.library_id == libraryId, Video.series_id.is_not(None))
                .distinct()
            ).all()
        }
        series_rows = [r for r in series_rows if r[0] in allowed]

    total = len(series_rows)
    module = f"nfo:{libraryId or 'all'}"

    def work(session):
        client = TmdbClient()
        completed = failed = 0
        for series_id, tmdb_id, title in series_rows:
            try:
                series = vs.get_series(session, series_id)
                episodes = vs.series_videos(session, series_id) if series else None
                if series is None or not episodes:
                    failed += 1
                else:
                    detail = client.get_tv(tmdb_id) if tmdb_id else None
                    series_path = assets.generate_series_nfo(
                        session, series, episodes, detail
                    )
                    if series_path:
                        completed += 1
                    else:
                        failed += 1
                    # 季级 NFO：用剧详情里已带的 seasons（零额外请求）
                    seasons_info = {
                        s.get("season_number"): s
                        for s in ((detail or {}).get("seasons") or [])
                        if s.get("season_number") is not None
                    }
                    seen: set[int] = set()
                    for ep in episodes:
                        number = vs.season_of(ep)
                        if number in seen:
                            continue
                        seen.add(number)
                        info = seasons_info.get(number) or {"season_number": number}
                        assets.generate_season_nfo(session, ep, info)
            except Exception:
                logger.exception("[NFO] 生成失败: series=%s %s", series_id, title)
                failed += 1
            progress_svc.update_progress(
                module, total=total, completed=completed, failed=failed
            )
            # 周期性提交，避免长事务卡住 SQLite 写锁（扫描期间尤其明显）
            if (completed + failed) % 20 == 0:
                try:
                    session.commit()
                except Exception:
                    session.rollback()
        logger.info("[NFO] 批量生成完成: %s 成功 / %s 失败 / 共 %s", completed, failed, total)

    submit_job(module, "nfo", total, work)
    return ApiResponse.ok(f"NFO 批量生成已启动，共 {total} 部剧")


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

