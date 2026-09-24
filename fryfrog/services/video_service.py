from __future__ import annotations

import logging
import re
from pathlib import Path

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.core.security import ANONYMOUS_ID, current_user_id
from fryfrog.core.utils import clean_title
from fryfrog.models.video import Favorite, Video, VideoActor, VideoSeries, WatchProgress

logger = logging.getLogger(__name__)

TYPE_VIDEO = "VIDEO"
TYPE_SERIES = "SERIES"
COMPLETED_THRESHOLD = 0.95
UNSCRAPED_DIR_NAME = "未识别"


# -------------------- NFO / 路径 --------------------

def get_base_name(file_name: str) -> str:
    idx = file_name.rfind(".")
    return file_name[:idx] if idx >= 0 else file_name


def _has_cjk(text: str | None) -> bool:
    return any("\u4e00" <= ch <= "\u9fff" or "\u3400" <= ch <= "\u4dbf" for ch in (text or ""))


def _select_show_name(video: Video) -> str:
    if _has_cjk(video.title):
        return video.title or "Unknown"
    if _has_cjk(video.original_title):
        return video.original_title or "Unknown"
    return video.title or video.original_title or "Unknown"


def _clean_folder(title: str) -> str:
    cleaned = re.sub(r'[<>:"/\\|?*]', "_", clean_title(title)).strip()
    return cleaned or "Unknown"


def get_metadata_dir(db: Session, video: Video) -> Path:
    season = video.season_number or 1
    episode = video.episode_number or 1
    show = _clean_folder(_select_show_name(video))
    is_tv = (video.media_type or "").lower() == "tv"
    base: Path | None = None
    if video.library_id is not None:
        from fryfrog.models.library import MediaLibrary

        lib = db.get(MediaLibrary, video.library_id)
        if lib and lib.path:
            base = Path(lib.path)
    if base is None:
        base = Path(video.file_path).parent
    dir_path = base / show
    if is_tv:
        dir_path = dir_path / f"第 {season} 季" / f"第 {episode} 集"
    return dir_path


def get_season_dir(db: Session, video: Video) -> Path | None:
    md = get_metadata_dir(db, video)
    return md.parent if md else None


def get_nfo_path(db: Session, video: Video) -> Path:
    return get_metadata_dir(db, video) / f"{get_base_name(video.file_name)}.nfo"


def get_poster_path(db: Session, video: Video) -> Path:
    return get_metadata_dir(db, video) / f"{get_base_name(video.file_name)}-poster.jpg"


def get_fanart_path(db: Session, video: Video) -> Path:
    return get_metadata_dir(db, video) / f"{get_base_name(video.file_name)}-fanart.jpg"


def asset_flags(db: Session, video: Video) -> dict:
    video_dir = Path(video.file_path).parent
    base = get_base_name(video.file_name)
    return {
        "has_nfo": (video_dir / f"{base}.nfo").exists(),
        "has_poster": (video_dir / f"{base}-poster.jpg").exists(),
        "has_fanart": (video_dir / f"{base}-fanart.jpg").exists(),
        "has_metadata_dir": get_metadata_dir(db, video).exists(),
    }


# -------------------- 查询 --------------------

def get_video(db: Session, video_id: int) -> Video:
    video = db.get(Video, video_id)
    if video is None:
        raise ResourceNotFoundException("Video", "id", video_id)
    return video


def get_series(db: Session, series_id: int) -> VideoSeries | None:
    return db.get(VideoSeries, series_id)


def series_videos(db: Session, series_id: int) -> list[Video]:
    return list(
        db.scalars(
            select(Video)
            .where(Video.series_id == series_id)
            .order_by(Video.season_number.asc(), Video.episode_number.asc())
        ).all()
    )


def get_actors_for_video(db: Session, video_id: int) -> list[VideoActor]:
    return list(db.scalars(select(VideoActor).where(VideoActor.video_id == video_id)).all())


def dedup_actors(actors: list[VideoActor]) -> list[VideoActor]:
    by_source: dict[int, VideoActor] = {}
    no_source: list[VideoActor] = []
    for a in actors:
        if a.source_actor_id is not None:
            by_source.setdefault(a.source_actor_id, a)
        else:
            no_source.append(a)
    return list(by_source.values()) + no_source


# -------------------- 收藏 --------------------

def set_favorite(db: Session, user_id: int, content_type: str, content_id: int, status: bool) -> None:
    row = db.scalar(
        select(Favorite).where(
            Favorite.user_id == user_id,
            Favorite.content_type == content_type,
            Favorite.content_id == content_id,
        )
    )
    if status:
        if row is None:
            db.add(Favorite(user_id=user_id, content_type=content_type, content_id=content_id))
            db.flush()
    elif row is not None:
        db.delete(row)
        db.flush()


def favorite_status_map(
    db: Session, user_id: int, content_type: str, content_ids: list[int]
) -> dict[int, bool]:
    if not content_ids:
        return {}
    rows = db.scalars(
        select(Favorite).where(
            Favorite.user_id == user_id,
            Favorite.content_type == content_type,
            Favorite.content_id.in_(content_ids),
        )
    ).all()
    return {r.content_id: True for r in rows}


def favorite_content_ids(db: Session, user_id: int, content_type: str) -> list[int]:
    return list(
        db.scalars(
            select(Favorite.content_id).where(
                Favorite.user_id == user_id, Favorite.content_type == content_type
            )
        ).all()
    )


# -------------------- 观看进度 --------------------

def get_progress(db: Session, user_id: int, video_id: int) -> WatchProgress | None:
    return db.scalar(
        select(WatchProgress).where(
            WatchProgress.user_id == user_id, WatchProgress.video_id == video_id
        )
    )


def get_progress_map(db: Session, user_id: int, video_ids: list[int]) -> dict[int, WatchProgress]:
    if not video_ids:
        return {}
    rows = db.scalars(
        select(WatchProgress).where(
            WatchProgress.user_id == user_id, WatchProgress.video_id.in_(video_ids)
        )
    ).all()
    return {r.video_id: r for r in rows}


def update_position(
    db: Session, user_id: int, video_id: int, position: float, duration: float | None
) -> WatchProgress:
    get_video(db, video_id)
    progress = get_progress(db, user_id, video_id)
    if progress is None:
        progress = WatchProgress(user_id=user_id, video_id=video_id)
        db.add(progress)
    progress.position_seconds = position
    if duration is not None:
        progress.duration_seconds = duration
    dur = progress.duration_seconds
    if dur and dur > 0:
        progress.completed = (position / dur) >= COMPLETED_THRESHOLD
    db.flush()
    return progress


def update_watched(db: Session, user_id: int, video_id: int, completed: bool) -> WatchProgress:
    get_video(db, video_id)
    progress = get_progress(db, user_id, video_id)
    if progress is None:
        progress = WatchProgress(user_id=user_id, video_id=video_id)
        db.add(progress)
    progress.completed = completed
    if completed and progress.duration_seconds and progress.duration_seconds > 0:
        progress.position_seconds = progress.duration_seconds
    db.flush()
    return progress


def delete_progress(db: Session, user_id: int, video_id: int) -> None:
    progress = get_progress(db, user_id, video_id)
    if progress is not None:
        db.delete(progress)
        db.flush()


# -------------------- 用户 ID --------------------

def uid() -> int:
    return current_user_id() if current_user_id() is not None else ANONYMOUS_ID
