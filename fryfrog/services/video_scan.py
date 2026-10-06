from __future__ import annotations

import logging
import shutil
from datetime import datetime, timedelta
from pathlib import Path

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from fryfrog.core.utils import clean_title, primary_title
from fryfrog.media_core import get_media_probe
from fryfrog.models.library import MediaLibrary, SystemSetting
from fryfrog.models.video import Video, VideoActor, VideoSeries, WatchProgress
from fryfrog.services.fsutil import VIDEO_EXTS, iter_files, parse_episode

logger = logging.getLogger(__name__)

ASSET_ONLY_SUFFIXES = {".nfo", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".srt", ".ass", ".ssa", ".vtt", ".sub", ".idx"}
HIDDEN_ASSET_DIR_PREFIX = ".frames-"

# 扫描簿记存入 SystemSetting（不必为「上次扫描」单独建表）
SCAN_SETTING_PREFIX = "video_scan"


def _scan_setting_key(library_id: int, name: str) -> str:
    return f"{SCAN_SETTING_PREFIX}.{name}.{library_id}"


def _read_scan_setting(db: Session, library_id: int, name: str) -> str | None:
    return db.scalar(
        select(SystemSetting.value).where(SystemSetting.key == _scan_setting_key(library_id, name))
    )


def _write_scan_setting(db: Session, library_id: int, name: str, value: str) -> None:
    key = _scan_setting_key(library_id, name)
    row = db.scalar(select(SystemSetting).where(SystemSetting.key == key))
    if row is None:
        db.add(SystemSetting(key=key, value=value, description="视频库扫描簿记"))
    else:
        row.value = value
    db.flush()


def _to_int(value: str | None) -> int | None:
    try:
        return int(value) if value not in (None, "") else None
    except ValueError:
        return None


def _missing_grace() -> float:
    from fryfrog.config import get_settings

    return max(float(get_settings().scan_missing_grace_seconds), 0.0)


def _guard_min_ratio() -> float:
    from fryfrog.config import get_settings

    return max(min(float(get_settings().scan_guard_min_ratio), 1.0), 0.0)


def _resolve_missing(db: Session, library: MediaLibrary, missing_ids: list[int], seen: int) -> int:
    """宽限期满仍缺失的行才删；顺带清掉因此变成空壳的系列行。

    两重保护：
    1. 宽限期（scan_missing_grace_seconds）：拷入中/挂载抖动不会立刻删行；
    2. 磁盘异常护栏：本轮实见文件数不足上轮存量的 scan_guard_min_ratio 时整轮暂缓，
       等下一轮复核；磁盘真的变小了，下一轮的基准就是新数量，删除照常放行。
    """
    grace = _missing_grace()
    threshold = datetime.now() - timedelta(seconds=grace)
    missing_ids = [
        vid
        for vid in missing_ids
        if (missing := db.scalar(select(Video.missing_since).where(Video.id == vid))) is not None
        and missing < threshold
    ]
    if not missing_ids:
        return 0

    previous = _to_int(_read_scan_setting(db, library.id or 0, "last_count"))
    guard_ratio = _guard_min_ratio()
    if seen > 0 and previous and guard_ratio > 0 and seen < previous * guard_ratio:
        # 目录还在但内容几乎清空的典型场景：挂载掉了/盘没就绪，本轮只记录不删
        logger.warning(
            "视频库疑似磁盘异常（本轮 %d 条 / 上轮 %d 条），暂缓删除 %d 条: %s",
            seen,
            previous,
            len(missing_ids),
            library.name,
        )
        return 0

    series_ids = {
        sid
        for sid in db.scalars(select(Video.series_id).where(Video.id.in_(missing_ids))).all()
        if sid is not None
    }
    # videos 有子表外键（watch_progress / video_actors）且连接开了 foreign_keys=ON，
    # 必须先清子行再删视频，否则 IntegrityError: FOREIGN KEY constraint failed。
    db.execute(delete(WatchProgress).where(WatchProgress.video_id.in_(missing_ids)))
    db.execute(delete(VideoActor).where(VideoActor.video_id.in_(missing_ids)))
    for vid in missing_ids:
        video = db.get(Video, vid)
        if video is not None:
            db.delete(video)
    db.flush()

    # 分集删空的系列行一并清掉，避免库里留下没有分集的空剧
    orphaned_series = 0
    if series_ids:
        kept = set(
            db.scalars(select(Video.series_id).where(Video.series_id.in_(series_ids))).all()
        )
        for sid in series_ids - kept:
            series = db.get(VideoSeries, sid)
            if series is not None:
                db.delete(series)
                orphaned_series += 1
        db.flush()

    logger.info(
        "视频库清理已删除文件: %s, 删除 %d 条（空剧组 %d 个）",
        library.name,
        len(missing_ids),
        orphaned_series,
    )
    return len(missing_ids)


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
    scanned_at = datetime.now()
    probe = get_media_probe()

    # 按库取一次现有记录：file_path 是绝对路径，同库内文件名互不重复。（rel → Video）
    existing: dict[str, Video] = {}
    for video in db.scalars(select(Video).where(Video.library_id == library.id)).all():
        try:
            existing[str(Path(video.file_path).resolve())] = video
        except OSError:
            continue

    for path in iter_files(root, VIDEO_EXTS):
        try:
            file_path = str(path.resolve())
            try:
                file_stat = path.stat()
            except OSError:
                file_stat = None

            video = existing.pop(file_path, None)
            is_new = video is None
            if video is None:
                # file_path 全表唯一：该文件可能已被别的库入库（库路径重叠/换过库目录），
                # 必须先查库再决定是否新建，否则会 INSERT 重复行撞 UNIQUE。
                video = db.scalar(select(Video).where(Video.file_path == file_path))
                if video is not None:
                    existing.pop(file_path, None)
                else:
                    video = Video(file_path=file_path)
                    db.add(video)
                    is_new = True

            video.file_name = path.name
            if is_new or not video.original_file_name:
                video.original_file_name = path.name
            video.library_id = library.id
            video.is_adult = bool(library.is_adult)
            video.format = path.suffix.lstrip(".").upper() or None
            video.last_seen_at = scanned_at
            # 文件回来了：清掉缺失标记（宽限期内删行不会发生）
            video.missing_since = None

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

            if file_stat is not None:
                video.file_size = file_stat.st_size

            # mtime + size 都没变 → 跳过重复 ffprobe（扫描高频触发时的主要 CPU 开销）
            unchanged = (
                file_stat is not None
                and video.media_probed_mtime == file_stat.st_mtime
                and video.file_size == file_stat.st_size
            )
            if not unchanged:
                if not video.duration_seconds:
                    duration = probe.probe_video_duration(file_path)
                    if duration:
                        video.duration_seconds = duration
                        video.duration_minutes = int(duration // 60) or 1
                if not video.resolution:
                    wh = probe.probe_video_resolution(file_path)
                    if wh and wh[0] and wh[1]:
                        video.resolution = f"{wh[0]}x{wh[1]}"
                if file_stat is not None:
                    video.media_probed_mtime = file_stat.st_mtime

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

            # 文件没变过就没有新素材/NFO 可读，跳过磁盘解析与渲染清理
            if is_new or not unchanged:
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
    # 本轮没见到的记录：超过宽限期才删；文件回来会在上面清掉 missing_since
    missing_ids = [video.id for video in existing.values() if video.id is not None]
    for video in existing.values():
        if video.id is not None and video.missing_since is None:
            video.missing_since = scanned_at
    db.flush()
    removed_rows = _resolve_missing(db, library, missing_ids, count)
    _write_scan_setting(db, library.id or 0, "last_count", str(count))
    _write_scan_setting(db, library.id or 0, "last_scan_at", scanned_at.isoformat())
    if frames_removed:
        logger.info("视频库扫描清理帧截图 %d 个: %s", frames_removed, library.name)
    removed_dirs = cleanup_orphan_asset_dirs(db, root)
    if removed_dirs:
        logger.info("视频库扫描清理空壳素材目录 %d 个: %s", removed_dirs, library.name)
    logger.info(
        "视频库扫描完成: %s, 新增/更新 %d 条, 清理已删除 %d 条", library.name, count, removed_rows
    )
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
