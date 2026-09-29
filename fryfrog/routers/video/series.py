"""系列域：剧集列表、日历、季封面/背景/logo、系列详情。"""
from __future__ import annotations

import logging
import shutil
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse, Response
from sqlalchemy import func, select

from fryfrog.core.api_response import ApiResponse, PageResponse
from fryfrog.core.deps import DbSession
from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.core.security import current_user_id
from fryfrog.core.signer import sign
from fryfrog.core.utils import placeholder_jpeg
from fryfrog.media_core import get_media_probe
from fryfrog.models.video import Video, VideoActor, VideoSeries
from fryfrog.schemas.video import (
    LibrarySeriesGroupDTO,
    LogoSelectRequest,
    SeriesDTO,
    SeriesFrameSelectRequest,
    SeriesListDTO,
    SeriesMetadataUpdateRequest,
)
from fryfrog.services import progress as progress_svc
from fryfrog.services import video_assets as assets
from fryfrog.services import video_scrape as scrape
from fryfrog.services import video_service as vs
from fryfrog.services.tmdb import TmdbClient

from ._common import (
    _actor_dict,
    _allowed_ids,
    _mls,
    _require_admin,
    _to_video_dto,
    _to_video_dto_with,
    submit_job,
)
from .assets import get_cover, get_fanart

logger = logging.getLogger(__name__)

series_router = APIRouter(prefix="/series", tags=["视频系列"])
def _series_logo_url(series: VideoSeries) -> str | None:
    return assets.logo_file_url(series.logo_local_path, f"/api/v1/video/series/{series.id}/logo")


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

    def work(session):
        completed = 0
        for s in series_list:
            try:
                eps = vs.series_videos(session, s.id)
                # 总封面/总横屏落地到剧名根目录
                assets.download_series_root_art(session, s, eps)
                for e in eps:
                    assets.download_all_covers(session, e, force=False)
                completed += 1
                progress_svc.update_progress(module, completed=completed, currentItem=s.title)
            except Exception:
                pass

    submit_job(module, "season-covers", len(series_list), work)
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
    season_posters = episode_covers = cleaned_posters = actors = 0
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
    # 总封面/总横屏落地到剧名根目录（与季文件夹同级）；季横屏复用总横屏，不再逐季写入
    root_art = assets.download_series_root_art(db, series, episodes, detail=detail)
    for e in episodes:
        # 已有季海报的分集：竖封面共用季海报，不再下载各自的 poster
        season_dir = vs.get_season_dir(db, e)
        shares_season_poster = bool(season_dir and (season_dir / "tvshow-poster.jpg").is_file())
        if (e.poster_url and not shares_season_poster) or e.backdrop_url:
            if assets.download_all_covers(db, e, force=False, poster=not shares_season_poster):
                episode_covers += 1
        # 自动删除重复的分集海报文件（含历史遗留）
        if shares_season_poster:
            poster = vs.get_poster_path(db, e)
            try:
                if poster.is_file():
                    poster.unlink()
                    cleaned_posters += 1
            except OSError:
                logger.debug("删除分集海报失败: %s", poster, exc_info=True)
            if e.cover_art_path and not Path(e.cover_art_path).exists():
                e.cover_art_path = None
        try:
            scrape.save_actors(db, e, cast)
            actors += 1
        except Exception:
            pass
    return ApiResponse.ok(
        {
            "seriesId": id,
            "seriesTitle": series.title,
            "refreshedSeriesPoster": root_art["poster"],
            "refreshedSeriesFanart": root_art["fanart"],
            "refreshedSeasonPosters": season_posters,
            # 季横屏复用总横屏，不再逐季写入（保留字段兼容旧前端）
            "refreshedSeasonFanarts": 0,
            "refreshedEpisodeCovers": episode_covers,
            "cleanedEpisodePosters": cleaned_posters,
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
        episodes = vs.series_videos(db, id)
        # 总封面 = TMDB 整剧海报，落地在剧名根目录（与季文件夹同级）
        root_poster = _find_series_root_file(db, episodes, "tvshow-poster.jpg")
        if root_poster is not None:
            if series.poster_local_path != str(root_poster):
                series.poster_local_path = str(root_poster)
                db.flush()
            return FileResponse(str(root_poster), media_type="image/jpeg")

        # 本地缓存只信任剧名根目录下的；历史版本误把季海报存进 poster_local_path，不作总封面
        if series.poster_local_path:
            cached = Path(series.poster_local_path)
            roots = {r.resolve() for r in _series_root_candidates(db, episodes)}
            if cached.exists() and cached.parent.resolve() in roots:
                return FileResponse(str(cached), media_type="image/jpeg")

        poster_url = series.poster_url
        if not poster_url and episodes:
            return get_cover(db, episodes[0].id)
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
        # TMDB fetch failed / offline — still prefer episode art over gray box.
        episodes = vs.series_videos(db, id) if series is not None else []
        if episodes:
            return get_cover(db, episodes[0].id)
        return Response(content=placeholder_jpeg(300, 450, title), media_type="image/jpeg")
    return Response(content=data, media_type="image/jpeg")


def _series_root_candidates(db: DbSession, episodes: list) -> list[Path]:
    """剧名根目录候选（与季文件夹同级）：重建 metadata 根 + 同名的媒体旁根。"""
    if not episodes:
        return []
    show_root = vs.get_metadata_dir(db, episodes[0]).parent.parent
    roots = [show_root]
    try:
        media_root = Path(episodes[0].file_path).parent.parent
        if media_root.name == show_root.name:
            roots.append(media_root)
    except Exception:
        pass
    return roots


def _find_series_root_file(db: DbSession, episodes: list, name: str) -> Path | None:
    """剧名根目录下的文件，如总封面 tvshow-poster.jpg / 总横屏 tvshow-fanart.jpg。"""
    seen: set[str] = set()
    for root in _series_root_candidates(db, episodes):
        key = str(root)
        if key in seen:
            continue
        seen.add(key)
        try:
            if (root / name).is_file():
                return root / name
        except Exception:
            continue
    return None


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
        episodes = vs.series_videos(db, id)
        # 总横屏：剧名根目录（与总竖屏放一起，季横屏复用此图）
        root_fanart = _find_series_root_file(db, episodes, "tvshow-fanart.jpg")
        if root_fanart is not None:
            if series.backdrop_local_path != str(root_fanart):
                series.backdrop_local_path = str(root_fanart)
                db.flush()
            return FileResponse(str(root_fanart), media_type="image/jpeg")
        # Local horizontal art next to episodes / season folder.
        local = _find_local_series_fanart(db, episodes)
        if local is not None:
            try:
                series.backdrop_local_path = str(local)
                db.flush()
            except Exception:
                logger.debug("persist series fanart failed id=%s", id)
            return FileResponse(str(local), media_type="image/jpeg")
        # Series row has no backdrop — reuse first episode art (incl. frame grab).
        if episodes:
            return get_fanart(db, episodes[0].id)
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
        episodes = vs.series_videos(db, id) if series is not None else []
        if episodes:
            return get_fanart(db, episodes[0].id)
        return Response(content=placeholder_jpeg(1920, 400, title), media_type="image/jpeg")
    return Response(content=data, media_type="image/jpeg")


def _find_local_series_fanart(db: DbSession, episodes: list) -> Path | None:
    """Horizontal art under episode/season folders (fanart.jpg, series-fanart, …)."""
    if not episodes:
        return None
    names = (
        "fanart.jpg",
        "fanart.png",
        "tvshow-fanart.jpg",
        "series-fanart.jpg",
        "backdrop.jpg",
        "clearart.jpg",
    )
    seen: set[str] = set()
    for ep in episodes:
        candidates: list[Path] = []
        try:
            parent = Path(ep.file_path).parent
            for n in names:
                candidates.append(parent / n)
            base = vs.get_base_name(ep.file_name)
            candidates.append(parent / f"{base}-fanart.jpg")
            candidates.append(parent / f"{base}-series-fanart.jpg")
        except Exception:
            pass
        season_dir = vs.get_season_dir(db, ep)
        if season_dir:
            for n in names:
                candidates.append(season_dir / n)
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


@series_router.get("/{id:int}/logo")
def get_series_logo(db: DbSession, id: int):
    series = vs.get_series(db, id)
    if series is None:
        raise ResourceNotFoundException("Series", "id", id)
    if series.logo_local_path and Path(series.logo_local_path).exists():
        return FileResponse(
            series.logo_local_path, media_type=assets.media_type_of(series.logo_local_path)
        )
    # Local tvshow-logo / logo under season or episode folder.
    episodes = vs.series_videos(db, id)
    local = assets.find_local_series_logo(db, episodes)
    if local is not None:
        series.logo_local_path = str(local)
        db.flush()
        return FileResponse(str(local), media_type=assets.media_type_of(local.name))
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
