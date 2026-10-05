"""视频浏览域：搜索、收藏、详情、元数据、观看进度。"""
from __future__ import annotations

import logging
from pathlib import Path

from fastapi import APIRouter
from sqlalchemy import func, or_, select

from fryfrog.core.api_response import ApiResponse, PageResponse
from fryfrog.core.deps import DbSession
from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.core.security import current_user_id
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoActor, VideoSeries
from fryfrog.schemas.video import (
    LibrarySeriesGroupDTO,
    SeriesListDTO,
    UpdatePositionRequest,
    UpdateWatchedRequest,
    VideoMetadataUpdateRequest,
    WatchProgressDTO,
)
from fryfrog.services import video_service as vs

from ._common import (
    _actor_dict,
    _allowed_ids,
    _page_videos,
    _require_visible,
    _to_video_dto,
    clamp_paging,
)

logger = logging.getLogger(__name__)

router = APIRouter()
# ==================== 固定路径（须在 /{id} 之前） ====================


@router.get("/search/title")
def search_by_title(db: DbSession, q: str, page: int = 0, size: int = 20):
    page, size = clamp_paging(page, size)
    allowed = _allowed_ids(db)
    base = select(Video).where(Video.library_id.in_(allowed), Video.title.ilike(f"%{q}%"))
    total = db.scalar(select(func.count()).select_from(base.subquery())) or 0
    rows = list(db.scalars(base.order_by(Video.title.asc()).offset(page * size).limit(size)).all())
    return ApiResponse.ok(_page_videos(db, rows, page, size, total))


@router.get("/search/director")
def search_by_director(db: DbSession, q: str, page: int = 0, size: int = 20):
    page, size = clamp_paging(page, size)
    allowed = _allowed_ids(db)
    base = select(Video).where(Video.library_id.in_(allowed), Video.director.ilike(f"%{q}%"))
    total = db.scalar(select(func.count()).select_from(base.subquery())) or 0
    rows = list(db.scalars(base.order_by(Video.title.asc()).offset(page * size).limit(size)).all())
    return ApiResponse.ok(_page_videos(db, rows, page, size, total))


@router.get("/search/library")
def search_by_library(
    db: DbSession,
    libraryId: int,
    q: str,
    page: int = 0,
    size: int = 20,
):
    """库内搜索：返回与库分组（grouped-by-library）同构的两段结果。

    系列按剧名（含原名）匹配，命中返回剧卡而非平铺分集；单片按片名
    匹配。与分组视图保持同一套可见性规则——本应用刮削的库只显示已
    绑定（tmdb_id 非空）条目，避免把未刮削内容搜进库视图；外部刮削
    的库不过滤。两段各自按 page/size 切片，调用方翻页拼接。
    """
    page, size = clamp_paging(page, size)
    allowed = set(_allowed_ids(db))
    if libraryId not in allowed:
        raise ResourceNotFoundException("MediaLibrary", "id", libraryId)
    lib = db.get(MediaLibrary, libraryId)
    if lib is None or not lib.is_video_type() or not lib.enabled:
        raise ResourceNotFoundException("MediaLibrary", "id", libraryId)

    keyword = q.strip()
    empty = LibrarySeriesGroupDTO(
        libraryId=lib.id,
        libraryName=lib.name,
        libraryPath=lib.path,
        subType=lib.sub_type,
        series=[],
        standaloneVideos=[],
        seriesCount=0,
        standaloneCount=0,
    )
    if not keyword:
        # 空关键词 = 空结果，搜索页不应退化成整库拉取
        return ApiResponse.ok(empty.model_dump())

    pat = f"%{keyword}%"
    match_title = or_(
        VideoSeries.title.ilike(pat), VideoSeries.original_title.ilike(pat)
    )
    series_q = (
        select(VideoSeries)
        .where(match_title)
        .where(
            select(Video.id)
            .where(
                Video.series_id == VideoSeries.id,
                Video.library_id == lib.id,
            )
            .exists()
        )
    )
    standalone_where = (
        Video.series_id.is_(None),
        Video.library_id == lib.id,
        Video.title.ilike(pat),
    )
    # 与 grouped_by_library 的视图分离一致：enable_scraping=false 的库
    # tmdb_id 可能恒空，不过滤，否则整库搜索消失。
    if lib.enable_scraping:
        series_q = series_q.where(VideoSeries.tmdb_id.is_not(None))
        standalone_where = (*standalone_where, Video.tmdb_id.is_not(None))

    series_total = int(
        db.scalar(select(func.count()).select_from(series_q.subquery())) or 0
    )
    standalone_total = int(
        db.scalar(
            select(func.count()).select_from(Video).where(*standalone_where)
        )
        or 0
    )
    paged_series = list(
        db.scalars(
            series_q.order_by(func.lower(VideoSeries.title).asc())
            .offset(page * size)
            .limit(size)
        ).all()
    )
    paged_standalone = list(
        db.scalars(
            select(Video)
            .where(*standalone_where)
            .order_by(Video.title.asc())
            .offset(page * size)
            .limit(size)
        ).all()
    )
    uid = current_user_id()
    series_fav = vs.favorite_status_map(
        db, uid, vs.TYPE_SERIES, [s.id for s in paged_series]
    )
    standalone_fav = vs.favorite_status_map(
        db, uid, vs.TYPE_VIDEO, [v.id for v in paged_standalone]
    )
    episodes_map = vs.series_videos_map(db, [s.id for s in paged_series])
    series_dtos = [
        SeriesListDTO.from_entity(
            s, episodes_map.get(s.id, []), series_fav.get(s.id, False)
        ).model_dump()
        for s in paged_series
    ]
    standalone_dtos = [
        SeriesListDTO.from_standalone_video(v, standalone_fav.get(v.id, False)).model_dump()
        for v in paged_standalone
    ]
    return ApiResponse.ok(
        LibrarySeriesGroupDTO(
            libraryId=lib.id,
            libraryName=lib.name,
            libraryPath=lib.path,
            subType=lib.sub_type,
            series=series_dtos,
            standaloneVideos=standalone_dtos,
            seriesCount=series_total,
            standaloneCount=standalone_total,
        ).model_dump()
    )


@router.get("/favorites")
def get_favorites(db: DbSession, page: int = 0, size: int = 20):
    page, size = clamp_paging(page, size)
    uid = current_user_id()
    allowed = _allowed_ids(db)
    fav_ids = vs.favorite_content_ids(db, uid, vs.TYPE_VIDEO)
    if not fav_ids or not allowed:
        return ApiResponse.ok(PageResponse.of([], page, size, 0).model_dump())
    base = select(Video).where(Video.id.in_(fav_ids), Video.library_id.in_(allowed))
    total = db.scalar(select(func.count()).select_from(base.subquery())) or 0
    rows = list(db.scalars(base.order_by(Video.title.asc()).offset(page * size).limit(size)).all())
    return ApiResponse.ok(_page_videos(db, rows, page, size, total))


@router.get("/unscraped")
def list_unscraped(
    db: DbSession, page: int = 0, size: int = 20, libraryId: int | None = None
):
    """未刮削视频（tmdb_id 为空）平铺列表：前端「未刮削」入口，批量补刮削用。

    文件不动库，仅按元数据状态过滤；DTO 与搜索结果同构，前端可复用列表渲染。
    libraryId 可选——库内入口只看本库的未刮削。
    只统计「本应用刮削」的库：外部刮削的库（enable_scraping=false）不进待办。
    """
    page, size = clamp_paging(page, size)
    allowed = _allowed_ids(db)
    managed = select(MediaLibrary.id).where(MediaLibrary.enable_scraping.is_(True))
    base = select(Video).where(
        Video.library_id.in_(allowed),
        Video.library_id.in_(managed),
        Video.tmdb_id.is_(None),
    )
    if libraryId is not None:
        base = base.where(Video.library_id == libraryId)
    total = db.scalar(select(func.count()).select_from(base.subquery())) or 0
    rows = list(db.scalars(base.order_by(Video.title.asc()).offset(page * size).limit(size)).all())
    return ApiResponse.ok(_page_videos(db, rows, page, size, total))


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


@router.get("/{id:int}")
def get_video_detail(db: DbSession, id: int):
    video = vs.get_video(db, id)
    _require_visible(db, video.library_id, "Video", id)
    return ApiResponse.ok(_to_video_dto(db, video))
