"""视频路由共享工具（DTO 组装、权限校验、后台任务提交）。"""
from __future__ import annotations

import logging
import threading
from pathlib import Path

from sqlalchemy.orm import Session

from fryfrog.core.api_response import PageResponse
from fryfrog.core.exceptions import ForbiddenException, ResourceNotFoundException
from fryfrog.core.security import UserService, current_user_id
from fryfrog.core.signer import sign
from fryfrog.models.video import Video, VideoActor
from fryfrog.schemas.video import apply_watch_progress
from fryfrog.services import progress as progress_svc
from fryfrog.services import video_assets as assets
from fryfrog.services import video_scrape as scrape
from fryfrog.services import video_service as vs
from fryfrog.services.media_library import MediaLibraryService

logger = logging.getLogger(__name__)
def _mls(db: Session) -> MediaLibraryService:
    return MediaLibraryService(UserService())


def _require_admin(db: Session) -> None:
    if not UserService().is_admin(db, current_user_id()):
        raise ForbiddenException("需要管理员权限")


def _require_visible(db: Session, library_id: int | None, resource: str, rid: int) -> None:
    if not _mls(db).is_visible_to_current_user(db, library_id):
        raise ResourceNotFoundException(resource, "id", rid)


def _video_logo_url(video: Video) -> str | None:
    # Local file next to media (movie-logo.png) wins if logo_local_path empty.
    if not (video.logo_local_path and Path(video.logo_local_path).exists()):
        local = assets.find_local_video_logo(video)
        if local is not None:
            try:
                video.logo_local_path = str(local)
            except Exception:
                pass
            return sign(f"/api/v1/video/{video.id}/logo")
    return assets.logo_file_url(video.logo_local_path, f"/api/v1/video/{video.id}/logo")


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


def submit_job(
    module: str, stage: str, total: int, work, *, error_fields=None, **done_fields
) -> None:
    """提交后台批量任务：登记进度 → 独立 session 线程执行 → done/error 收尾。

    work(session) 内可自行调用 progress_svc.update_progress 汇报单项进度；
    done_fields / error_fields 分别合并进成功与失败时的进度更新。
    """
    progress_svc.update_progress(
        module, stage=stage, running=True, total=total, completed=0, failed=0
    )

    def job():
        session = None
        try:
            from fryfrog.db import get_session_factory

            session = get_session_factory()()
            work(session)
            session.commit()
            progress_svc.update_progress(module, stage="done", running=False, **done_fields)
        except Exception:
            logger.exception("[Job] %s failed", module)
            if session:
                session.rollback()
            progress_svc.update_progress(
                module, stage="error", running=False, **(error_fields or {})
            )
        finally:
            if session:
                session.close()

    threading.Thread(target=job, daemon=True).start()
