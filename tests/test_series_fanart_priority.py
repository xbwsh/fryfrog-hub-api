from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.routers.video.assets import get_cover
from fryfrog.routers.video import series as series_router
from pathlib import Path


def _show_layout(tmp_path):
    """剧名/第 1 季/第 1 集/x.mkv 结构，返回 db 与 series。"""
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    media = tmp_path / "media"
    show = media / "某剧"
    lib = MediaLibrary(name="剧库", path=str(media), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()
    series = VideoSeries(title="某剧", media_type="tv")
    db.add(series)
    db.flush()
    ep_dir = show / "第 1 季" / "第 1 集"
    ep_dir.mkdir(parents=True)
    media_file = ep_dir / "某剧 - S01E01.mkv"
    media_file.write_bytes(b"x" * 32)
    video = Video(
        file_path=str(media_file),
        file_name=media_file.name,
        title="某剧 S01E01",
        library_id=lib.id,
        series_id=series.id,
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
    )
    db.add(video)
    db.commit()
    return db, series, video


def _body(resp) -> bytes:
    return Path(resp.path).read_bytes()


def test_root_fanart_beats_stale_misplaced_backdrop(tmp_path):
    """根目录 tvshow-fanart.jpg 存在时，旧错位缓存（季/集目录横屏）
    不得再抢占总横屏，且缓存应被修正回根目录。"""
    db, series, _ = _show_layout(tmp_path)
    show = tmp_path / "media" / "某剧"
    season_dir = show / "第 1 季"
    (show / "tvshow-fanart.jpg").write_bytes(b"ROOT-FANART")
    stale = season_dir / "fanart.jpg"
    stale.write_bytes(b"STALE-FANART")
    series.backdrop_local_path = str(stale)
    db.commit()

    resp = series_router.get_series_fanart(db, series.id)
    assert _body(resp) == b"ROOT-FANART"
    db.refresh(series)
    assert series.backdrop_local_path == str(show / "tvshow-fanart.jpg")


def test_misplaced_backdrop_not_trusted_when_root_missing(tmp_path):
    """根目录没有总横屏时，指向季/集目录的错位缓存不作为总横屏返回，
    回退到本地横屏候选，且不再把回退结果写回缓存。"""
    db, series, _ = _show_layout(tmp_path)
    show = tmp_path / "media" / "某剧"
    season_dir = show / "第 1 季"
    local = season_dir / "fanart.jpg"
    local.write_bytes(b"LOCAL-FANART")
    series.backdrop_local_path = str(show / "第 1 集" / "missing-fanart.jpg")
    db.commit()

    resp = series_router.get_series_fanart(db, series.id)
    assert _body(resp) == b"LOCAL-FANART"
    db.refresh(series)
    assert series.backdrop_local_path == str(show / "第 1 集" / "missing-fanart.jpg")


def test_cover_skips_horizontal_thumb(tmp_path):
    """竖封面候选里混入横屏 thumb.jpg（历史扫描写进 cover_art_path）
    时应跳过，优先返回正经竖海报。"""
    db, series, video = _show_layout(tmp_path)
    ep_dir = tmp_path / "media" / "某剧" / "第 1 季" / "第 1 集"
    (ep_dir / "poster.jpg").write_bytes(b"VERTICAL-POSTER")
    thumb = ep_dir / "thumb.jpg"
    thumb.write_bytes(b"HORIZONTAL-THUMB")
    video.cover_art_path = str(thumb)
    db.commit()

    resp = get_cover(db, video.id)
    assert _body(resp) == b"VERTICAL-POSTER"
