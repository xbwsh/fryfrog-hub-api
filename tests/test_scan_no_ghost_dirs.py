from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services import video_assets as assets


def _unorganized_env(tmp_path):
    """散放视频：文件平铺在库根，没有「剧名/」目录。"""
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib_root = tmp_path / "media"
    lib_root.mkdir()
    lib = MediaLibrary(name="剧库", path=str(lib_root), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()
    media = lib_root / "明明是老人却过于强大.mp4"
    media.write_bytes(b"x" * 32)
    series = VideoSeries(title="明明是老人却过于强大", media_type="tv")
    db.add(series)
    db.flush()
    ep = Video(
        file_path=str(media),
        file_name=media.name,
        title="明明是老人却过于强大",
        library_id=lib.id,
        series_id=series.id,
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
    )
    db.add(ep)
    db.commit()
    return db, lib_root, series, ep


def test_no_ghost_dirs_for_unorganized_video(tmp_path):
    """扫描的素材保障（NFO/季海报/总素材）不得为散放视频 mkdir 剧名空目录：
    这类重建路径目录删掉后每次扫描又长回来。"""
    db, lib_root, series, ep = _unorganized_env(tmp_path)
    show_dir = lib_root / "明明是老人却过于强大"

    assert assets.ensure_series_nfo(db, series, [ep]) == (None, False)
    assert assets.ensure_season_poster(db, ep) == (None, False)
    result = assets.download_series_root_art(db, series, [ep])
    assert result["poster"] is False and result["fanart"] is False
    db.refresh(series)
    assert series.poster_local_path is None

    assert not show_dir.exists()


def test_season_nfo_skips_missing_season_dir(tmp_path):
    """季目录不存在时不写 season.nfo、不建目录。"""
    db, lib_root, series, ep = _unorganized_env(tmp_path)
    assert assets.generate_season_nfo(db, ep, {"season_number": 1}) is None
    assert not (lib_root / "明明是老人却过于强大").exists()
