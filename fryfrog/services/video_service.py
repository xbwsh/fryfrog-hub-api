from __future__ import annotations

import logging
import os
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
    # 剧集的 title 刮削后是分集名，剧名必须用 series_name / 剧集行标题，
    # 否则每集都会各建一个顶层目录，整季被拆散。分集名（title）只能当
    # **最后**兜底：实测「慎重勇者」series_name 是发布名（Kono Yuusha…，
    # 无中文）而 title 是分集名（这个勇者过于傲慢），按中文优先直接选中了
    # 分集名当剧名目录——于是季目录/剧根目录全部指错，季海报、总海报、
    # 剧根 NFO 全部落空，剧集详情页退化成拿 16:9 单集剧照当竖封面。
    if video.is_episode:
        series_title = video.series.title if video.series is not None else None
        names = [video.series_name, series_title, video.original_title, video.title]
    else:
        names = [video.title, video.original_title, video.series_name]
    for name in names:
        if _has_cjk(name):
            return name or "Unknown"
    return next((name for name in names if name), "Unknown")


# 单层文件名的字节上限（ext4/大部分 Linux 文件系统都是 255 **字节**，不是字符）。
# 中文/日文一个字 3 字节，所以 85 个汉字就到顶了——实测有条 JAV 文件名 284 字节，
# `get_metadata_dir` 拿它当目录名后 `.exists()` 直接抛
# `OSError: [Errno 36] File name too long`，详情页 500（见 _safe_exists）。
FOLDER_NAME_MAX_BYTES = 200


def truncate_bytes(text: str, limit: int = FOLDER_NAME_MAX_BYTES) -> str:
    """按 UTF-8 **字节**截断，且不切断多字节字符。

    留余量（200 而非 255）：调用方还会在后面拼 `第 N 季` / `第 M 集` 等，
    另外不同文件系统的上限略有差异，留点空间更稳。
    """
    raw = text.encode("utf-8")
    if len(raw) <= limit:
        return text
    # errors="ignore" 会丢掉被切断的那个不完整字符，正好实现「不切字符」
    return raw[:limit].decode("utf-8", errors="ignore").rstrip()


def _clean_folder(title: str) -> str:
    # 目录名里不该再出现 SxxExx：季集已由「第 N 季/第 M 集」表达
    text = re.sub(r"(?i)S\d{1,2}\s*E\d{1,3}", " ", clean_title(title))
    cleaned = re.sub(r'[<>:"/\\|?*]', "_", re.sub(r"\s+", " ", text)).strip()
    cleaned = cleaned or "Unknown"
    # 必须按字节截断：名字过长时 pathlib 的 exists()/stat() 会抛 OSError，
    # 而不是返回 False，调用方一个没接住就是 500。
    return truncate_bytes(cleaned) or "Unknown"


def _safe_exists(path: Path) -> bool:
    """`path.exists()` 的安全版：路径异常时当作"不存在"而不是抛出去。

    `Path.exists()` 只吞 FileNotFoundError 一类，**不吞** OSError(ENAMETOOLONG:
    File name too long)、权限错误、坏符号链接等。这些在媒体库里都是"正常脏数据"，
    不该让整个详情页 500。
    """
    try:
        return path.exists()
    except OSError:
        logger.debug("路径不可访问，按不存在处理: %s", path, exc_info=True)
        return False


def season_of(video: Video) -> int:
    """季号，保留第 0 季（特别篇/OVA）。实现见 video_assets。"""
    from fryfrog.services.video_assets import season_of as _season_of

    return _season_of(video)


def episode_of(video: Video) -> int:
    """集号，0 也保留。实现见 video_assets。"""
    from fryfrog.services.video_assets import episode_of as _episode_of

    return _episode_of(video)


def get_metadata_dir(db: Session, video: Video) -> Path:
    season = season_of(video)
    episode = episode_of(video)
    show = _clean_folder(_select_show_name(video))
    is_tv = video.is_episode
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


def rename_show_dir(db: Session, video: Video) -> bool:
    """重刮后剧名变化时，原地重命名剧名级目录（不新建目录搬文件）。

    目录布局由用户文件系统决定，这里只做「目录名跟随剧名」的最小改动：
    散放库根 / 目录里混着其他剧 / 目标目录已存在 时放弃，保持原位。
    """
    try:
        video_dir = Path(video.file_path).parent
        base: Path | None = None
        if video.library_id is not None:
            from fryfrog.models.library import MediaLibrary

            lib = db.get(MediaLibrary, video.library_id)
            if lib and lib.path:
                base = Path(lib.path)
        if base is None:
            return False
        if video_dir == base:
            return False  # 散放库根，没有剧名级目录可改名
        # 剧名级目录：剧集向上两级（库根/剧名/第N季/第N集）；
        # 电影则是视频所在目录自身（库根/剧名/），散放库根时等于 base 会被拦下
        root = video_dir.parent.parent if video.is_episode else video_dir
        if root == base:
            return False
        new_name = _clean_folder(_select_show_name(video))
        if not new_name or root.name == new_name:
            return False
        new_root = root.parent / new_name
        if new_root.exists() or new_root == root:
            return False
        # 目录里出现其他剧的视频 → 不擅自改名，避免误伤
        from fryfrog.services.fsutil import VIDEO_EXTS

        for p in root.rglob("*"):
            if not p.is_file() or p.suffix.lower() not in VIDEO_EXTS:
                continue
            if str(p.resolve()) == str(Path(video.file_path).resolve()):
                continue
            row = db.scalar(select(Video).where(Video.file_path == str(p.resolve())))
            if row is None:
                continue
            if video.is_episode:
                if row.series_id != video.series_id:
                    return False
            elif row.title != video.title:
                return False
        os.rename(str(root), str(new_root))
        old_prefix = str(root.resolve())
        for row in db.scalars(select(Video)).all():
            if row.file_path and row.file_path.startswith(old_prefix):
                row.file_path = str(new_root.resolve()) + row.file_path[len(old_prefix):]
        db.flush()
        logger.info("剧名级目录重命名: %s → %s", root, new_root)
        return True
    except Exception:
        logger.exception("剧名级目录重命名失败: %s", video.file_name)
        return False


def get_season_dir(db: Session, video: Video) -> Path | None:
    md = get_metadata_dir(db, video)
    return md.parent if md else None


def get_series_root_dir(db: Session, episodes: list[Video]) -> Path | None:
    """剧名根目录（与季文件夹同级）：库根/剧名/，总封面 tvshow-poster.jpg 放这里。"""
    if not episodes:
        return None
    return get_metadata_dir(db, episodes[0]).parent.parent


def series_root_candidates(db: Session, episodes: list[Video]) -> list[Path]:
    """剧名根目录候选（与季文件夹同级）：重建 metadata 根 + 同名的媒体旁根。

    只在两边**目录名一致**时追加媒体旁根：整理后结构（剧名/第 1 季/第 1 集/）
    的父级是季目录而非剧根，名称比对能挡住它被误当剧根。
    """
    if not episodes:
        return []
    show_root = get_metadata_dir(db, episodes[0]).parent.parent
    roots = [show_root]
    try:
        media_root = Path(episodes[0].file_path).parent.parent
        if media_root.name == show_root.name:
            roots.append(media_root)
    except Exception:
        pass
    return roots


def find_series_root_file(db: Session, episodes: list[Video], name: str) -> Path | None:
    """剧名根目录下的文件，如总封面 tvshow-poster.jpg / 总横屏 tvshow-fanart.jpg。"""
    seen: set[str] = set()
    for root in series_root_candidates(db, episodes):
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


def get_video_assets_dir(video: Video) -> Path:
    """素材目录：nfo/封面/背景与视频放同一目录（Emby 式，素材跟随视频）。"""
    return Path(video.file_path).parent


def get_nfo_path(db: Session, video: Video) -> Path:
    return get_video_assets_dir(video) / f"{get_base_name(video.file_name)}.nfo"


def get_poster_path(db: Session, video: Video) -> Path:
    """固定命名 poster.jpg（电影/独立条目）；分集竖屏共用季海报。"""
    return get_video_assets_dir(video) / "poster.jpg"


def get_fanart_path(db: Session, video: Video) -> Path:
    return get_video_assets_dir(video) / "fanart.jpg"


def local_poster_candidates(db: Session, video: Video) -> list[Path]:
    """封面候选（有序）：视频同目录固定 poster.jpg → 旧 {base}-poster → 无前缀 → 老 metadata 目录。

    固定命名是 Emby/Kodi 约定；旧变体与老方案（素材写去 metadata 目录）
    保留兼容，重新刮削时会被 sync_legacy_assets 迁移整理。
    """
    video_dir = get_video_assets_dir(video)
    base = get_base_name(video.file_name)
    legacy = get_metadata_dir(db, video)
    return [
        video_dir / "poster.jpg",
        video_dir / f"{base}-poster.jpg",
        video_dir / "folder.jpg",
        video_dir / "thumb.jpg",
        legacy / f"{base}-poster.jpg",
        legacy / "poster.jpg",
    ]


def local_fanart_candidates(db: Session, video: Video) -> list[Path]:
    video_dir = get_video_assets_dir(video)
    base = get_base_name(video.file_name)
    legacy = get_metadata_dir(db, video)
    return [
        video_dir / "fanart.jpg",
        video_dir / f"{base}-fanart.jpg",
        legacy / f"{base}-fanart.jpg",
    ]


def asset_flags(db: Session, video: Video) -> dict:
    video_dir = Path(video.file_path).parent
    base = get_base_name(video.file_name)
    # 一律走 _safe_exists：媒体库里脏数据很常见（超长名、权限、坏链接），
    # 一个 OSError 冒出去就是详情页 500。实测 URE-093 那条 284 字节的名字
    # 让 `get_metadata_dir(...).exists()` 抛 File name too long。
    return {
        "has_nfo": _safe_exists(video_dir / f"{base}.nfo"),
        "has_poster": any(_safe_exists(p) for p in local_poster_candidates(db, video)),
        "has_fanart": any(_safe_exists(p) for p in local_fanart_candidates(db, video)),
        "has_metadata_dir": _safe_exists(get_metadata_dir(db, video)),
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


def series_videos_map(db: Session, series_ids: list[int]) -> dict[int, list[Video]]:
    """批量取多个系列的分集（一次查询），替代逐系列 series_videos 的 N+1。"""
    if not series_ids:
        return {}
    rows = list(
        db.scalars(
            select(Video)
            .where(Video.series_id.in_(series_ids))
            .order_by(
                Video.series_id.asc(),
                Video.season_number.asc(),
                Video.episode_number.asc(),
            )
        ).all()
    )
    result: dict[int, list[Video]] = {sid: [] for sid in series_ids}
    for v in rows:
        result.setdefault(v.series_id, []).append(v)
    return result


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
