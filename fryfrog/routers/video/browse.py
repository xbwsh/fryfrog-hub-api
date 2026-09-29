"""视频浏览域：搜索、收藏、详情、元数据、观看进度。"""
from __future__ import annotations

import logging
from pathlib import Path

from fastapi import APIRouter
from sqlalchemy import func, select

from fryfrog.core.api_response import ApiResponse, PageResponse
from fryfrog.core.deps import DbSession
from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.core.security import current_user_id
from fryfrog.models.video import Video, VideoActor
from fryfrog.schemas.video import (
    UpdatePositionRequest,
    UpdateWatchedRequest,
    VideoMetadataUpdateRequest,
    WatchProgressDTO,
)
from fryfrog.services import video_service as vs

from ._common import _actor_dict, _allowed_ids, _page_videos, _require_visible, _to_video_dto

logger = logging.getLogger(__name__)

router = APIRouter()
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
