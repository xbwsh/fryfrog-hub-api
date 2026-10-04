from __future__ import annotations

import logging
import shutil
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from fryfrog.core.utils import clean_title, primary_title
from fryfrog.media_core import get_media_probe
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services.fsutil import VIDEO_EXTS, iter_files, parse_episode

logger = logging.getLogger(__name__)

ASSET_ONLY_SUFFIXES = {".nfo", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".srt", ".ass", ".ssa", ".vtt", ".sub", ".idx"}
HIDDEN_ASSET_DIR_PREFIX = ".frames-"


def cleanup_orphan_asset_dirs(db: Session, root: Path) -> int:
    """删除「只有素材没有视频」的孤儿目录（旧方案残留/手动遗留）。

    保守规则：目录内没有任何视频文件；所有文件都是素材类后缀；
    且目录内文件未被任何 Video 的 cover_art_path/backdrop_local_path/logo_local_path 引用。
    """
    referenced: set[str] = set()
    for v in db.scalars(select(Video)).all():
        for p in (v.cover_art_path, v.backdrop_local_path, v.logo_local_path):
            if p:
                try:
                    referenced.add(str(Path(p).resolve()))
                except OSError:
                    pass
    removed = 0
    try:
        for d in root.rglob("*"):
            if not d.is_dir() or d.resolve() == root.resolve():
                continue
            # 目录里还有视频 → 不是空壳，跳过
            if any(p.is_file() and p.suffix.lower() in VIDEO_EXTS for p in d.rglob("*")):
                continue
            # 目录内文件全为素材类且未被引用 → 删除（rmtree 空壳）
            if _is_orphan_asset_dir(d) and not _dir_references_media(d, referenced):
                shutil.rmtree(d, ignore_errors=True)
                removed += 1
    except OSError:
        logger.debug("孤儿素材目录清理失败: %s", root, exc_info=True)
    return removed


def _is_orphan_asset_dir(d: Path) -> bool:
    """目录中只有素材类文件（无视频）且无被引用文件时视为孤儿空壳。"""
    if not d.is_dir():
        return False
    for p in d.rglob("*"):
        if p.is_dir():
            if p.name.startswith(HIDDEN_ASSET_DIR_PREFIX):
                continue
            return False
        if p.suffix.lower() not in ASSET_ONLY_SUFFIXES:
            return False
    return True


def _dir_references_media(d: Path, referenced: set[str]) -> bool:
    """目录内任一文件被 DB 素材字段引用时，不能当作孤儿目录删除。"""
    for p in d.rglob("*"):
        if p.is_file():
            try:
                if str(p.resolve()) in referenced:
                    return True
            except OSError:
                continue
    return False


def sync_series_from_episode(db: Session, video: Video) -> bool:
    """分集已绑（典型来源：NFO 恢复）但系列行缺 tmdb_id 时回填。

    NFO 路径只写分集字段，系列行不落——而详情页的刮削菜单和系列级
    操作（季海报 / Logo）都看系列行，缺了会把整部剧误判成未绑定。
    同一部剧的分集共用剧 ID，任取一条已绑分集即可修复。
    """
    series = video.series
    if video.tmdb_id is None or series is None or series.tmdb_id is not None:
        return False
    series.tmdb_id = video.tmdb_id
    if series.metadata_source is None:
        series.metadata_source = video.metadata_source or "nfo"
    db.flush()
    return True


def scan_video_library(db: Session, library: MediaLibrary) -> int:
    """扫描视频库：遍历 VIDEO_EXTS，upsert Video 行。"""
    root = Path(library.path)
    if not root.exists():
        logger.warning("媒体库路径不存在: %s", library.path)
        return 0

    count = 0
    frames_removed = 0
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
            # 主标题：中文名.英文名.2025 → 中文名。剧名与 TMDB 名一致，
            # 避免同一部剧因刮削先后而分裂成多个剧集/目录。
            display = primary_title(path.name) or clean_title(title) or path.stem
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

            # 本地封面路径探测（含无前缀 poster.jpg/fanart.jpg/thumb.jpg 等手工刮削命名）
            from fryfrog.services import video_service as vs

            if not video.cover_art_path:
                for poster in vs.local_poster_candidates(db, video):
                    if poster.exists():
                        video.cover_art_path = str(poster)
                        break
            if not video.backdrop_local_path:
                for fanart in vs.local_fanart_candidates(db, video):
                    if fanart.exists():
                        video.backdrop_local_path = str(fanart)
                        break

            # 从已有 NFO 恢复元数据（含 tmdbId）
            from fryfrog.services.video_assets import (
                parse_nfo,
                parse_series_nfo,
                prune_private_vertical_cover,
            )

            parse_nfo(db, video)
            sync_series_from_episode(db, video)
            parse_series_nfo(db, video)
            # 分集竖屏共用季/剧海报：扫描顺手清掉历史私有副本
            prune_private_vertical_cover(db, video)

            frames_removed += cleanup_redundant_frames(db, video)

            db.flush()
            count += 1

            if library.enable_scraping and not video.tmdb_id:
                from fryfrog.services.video_scrape import scrape_video_if_needed

                scrape_video_if_needed(db, video)
        except Exception:
            logger.exception("扫描视频失败: %s", path)
            db.rollback()
    db.flush()
    if frames_removed:
        logger.info("视频库扫描清理帧截图 %d 个: %s", frames_removed, library.name)
    removed_dirs = cleanup_orphan_asset_dirs(db, root)
    if removed_dirs:
        logger.info("视频库扫描清理空壳素材目录 %d 个: %s", removed_dirs, library.name)
    logger.info("视频库扫描完成: %s, 新增/更新 %d 条", library.name, count)
    return count


def cleanup_redundant_frames(db: Session, video: Video) -> int:
    """本地已有正式封面/背景时，删掉此前封面/背景兜底自动生成的 -frame-v3 截图。

    只删未被 DB 引用的帧（手动选帧的结果 cover_art_path/backdrop_local_path 会指向它）。
    """
    from fryfrog.services import video_service as vs

    video_dir = Path(video.file_path).parent
    base = vs.get_base_name(video.file_name)
    removed = 0
    frame = video_dir / f"{base}-frame-v3.jpg"
    if (
        frame.exists()
        and str(frame) != video.cover_art_path
        and any(p.exists() for p in vs.local_poster_candidates(db, video))
    ):
        try:
            frame.unlink()
            removed += 1
        except OSError:
            logger.debug("清理帧截图失败: %s", frame, exc_info=True)
    fanart_frame = video_dir / f"{base}-fanart-frame-v3.jpg"
    if (
        fanart_frame.exists()
        and str(fanart_frame) != video.backdrop_local_path
        and any(p.exists() for p in vs.local_fanart_candidates(db, video))
    ):
        try:
            fanart_frame.unlink()
            removed += 1
        except OSError:
            logger.debug("清理帧截图失败: %s", fanart_frame, exc_info=True)
    return removed
