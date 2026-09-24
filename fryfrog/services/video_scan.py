from __future__ import annotations

import logging
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from fryfrog.core.utils import clean_title
from fryfrog.media_core import get_media_probe
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services.fsutil import VIDEO_EXTS, iter_files, parse_episode

logger = logging.getLogger(__name__)


def scan_video_library(db: Session, library: MediaLibrary) -> int:
    """扫描视频库：遍历 VIDEO_EXTS，upsert Video 行。"""
    root = Path(library.path)
    if not root.exists():
        logger.warning("媒体库路径不存在: %s", library.path)
        return 0

    count = 0
    probe = get_media_probe()
    for path in iter_files(root, VIDEO_EXTS):
        try:
            file_path = str(path.resolve())
            video = db.scalar(select(Video).where(Video.file_path == file_path))
            is_new = video is None
            if video is None:
                video = Video(file_path=file_path)
                db.add(video)

            video.file_name = path.name
            if is_new or not video.original_file_name:
                video.original_file_name = path.name
            video.library_id = library.id
            video.is_adult = bool(library.is_adult)
            video.format = path.suffix.lstrip(".").upper() or None

            title, season, episode = parse_episode(path.stem)
            display = clean_title(title) or path.stem
            if season is not None or episode is not None:
                video.is_series = True
                video.season_number = season or 1
                video.episode_number = episode
                series_name = display
                video.series_name = series_name
                if not video.title or is_new:
                    video.title = f"{series_name} S{video.season_number:02d}E{(episode or 0):02d}"
                series = db.scalar(select(VideoSeries).where(VideoSeries.title == series_name))
                if series is None:
                    series = VideoSeries(title=series_name)
                    db.add(series)
                    db.flush()
                video.series = series
                video.series_id = series.id
            else:
                if not video.title or is_new:
                    video.title = display

            try:
                video.file_size = path.stat().st_size
            except OSError:
                pass

            if not video.duration_seconds:
                duration = probe.probe_video_duration(file_path)
                if duration:
                    video.duration_seconds = duration
                    video.duration_minutes = int(duration // 60) or 1
            if not video.resolution:
                wh = probe.probe_video_resolution(file_path)
                if wh and wh[0] and wh[1]:
                    video.resolution = f"{wh[0]}x{wh[1]}"

            # 本地封面路径探测
            base = path.stem
            poster = path.parent / f"{base}-poster.jpg"
            fanart = path.parent / f"{base}-fanart.jpg"
            if poster.exists() and not video.cover_art_path:
                video.cover_art_path = str(poster)
            if fanart.exists() and not video.backdrop_local_path:
                video.backdrop_local_path = str(fanart)

            # 从已有 NFO 恢复元数据（含 tmdbId）
            from fryfrog.services.video_assets import parse_nfo

            parse_nfo(db, video)

            db.flush()
            count += 1

            if library.enable_scraping and not video.tmdb_id:
                from fryfrog.services.video_scrape import scrape_video_if_needed

                scrape_video_if_needed(db, video)
        except Exception:
            logger.exception("扫描视频失败: %s", path)
            db.rollback()
    db.flush()
    logger.info("视频库扫描完成: %s, 新增/更新 %d 条", library.name, count)
    return count
