"""剧集 logo 本地文件识别。

实测故障：手工放在剧/季目录的 `tvshow-logo.png` 在 App 里不显示，
只有电影有 logo。根因有两层：
1. `_series_logo_url` 只看 DB 字段，不扫描本地文件；
2. 第一版修复用了 `series.videos`，但 VideoSeries 并没有这个关系，
   于是静默失效——必须由调用方把分集传入。
本测试同时覆盖变体命名（`tvshow-logo (1).png` 等）与不该命中的反例。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.schemas.video import SeriesDTO, SeriesListDTO, _series_logo_url
from fryfrog.services.video_assets import find_local_series_logo, find_local_video_logo

CASES = [
    ("tvshow-logo.png", True),
    ("tvshow-logo (1).png", True),
    ("tvshow-logo (4).png", True),
    ("tvshow-logo-2.png", True),
    ("clearlogo.png", True),
    ("logo.png", True),
    ("tvshow-logo-backup-2024.png", False),
    ("unrelated.png", False),
]


def _make_series(tmp_path, db, filename: str):
    show = tmp_path / filename.replace(" ", "_").replace(".", "_")
    season = show / "第 1 季"
    season.mkdir(parents=True)
    (season / filename).write_bytes(b"\x89PNG\r\n\x1a\n" + b"0" * 64)
    series = VideoSeries(title=f"剧-{filename}", tmdb_id=100)
    db.add(series)
    db.flush()
    ep = Video(
        file_path=str(season / "剧 - S01E01.mp4"),
        file_name="剧 - S01E01.mp4",
        title="剧 S01E01",
        series_id=series.id,
        library_id=1,
        is_series=True,
        season_number=1,
        episode_number=1,
    )
    db.add(ep)
    db.flush()
    return series, ep


@pytest.fixture()
def db():
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    yield session
    session.close()


@pytest.mark.parametrize("filename,expected", CASES)
def test_series_logo_variants(tmp_path, db, filename, expected):
    series, ep = _make_series(tmp_path, db, filename)

    found = find_local_series_logo(db, [ep])
    assert (found is not None) is expected, filename

    dto = SeriesListDTO.from_entity(series, [ep], False)
    assert (dto.logoUrl is not None) is expected, f"SeriesListDTO.logoUrl 对 {filename} 判断错误"

    detail = SeriesDTO.from_entity(series, [], False)
    # 详情 DTO 不传分集时不应误报（也验证不会因为没有 videos 关系而崩）
    assert detail is not None


def _make_series_real_layout(tmp_path, db, *, logo_at_root: bool, logo_in_season: bool = False):
    """真实布局：<库根>/<剧名>/第 1 季/第 1 集/<文件>.mp4。

    两个"真实"缺一不可，否则测不到剧根 logo：
    - **多一层分集目录**：上面的 `_make_series` 把文件直接放季目录，`parent.parent`
      正好是剧根，于是漏掉了实际部署形态；
    - **library_id + MediaLibrary**：`get_metadata_dir` 没有它时会退回「文件的父
      目录」当库根再拼剧名，算出的剧根是错的（`.../第 1 集/<剧名>`）。
    """
    media = tmp_path / "media"
    show = media / "直到夏日结束之前"
    season = show / "第 1 季"
    ep_dir = season / "第 1 集"
    ep_dir.mkdir(parents=True)
    if logo_at_root:
        (show / "tvshow-logo.png").write_bytes(b"\x89PNG\r\n\x1a\n" + b"0" * 64)
    if logo_in_season:
        (season / "tvshow-logo.png").write_bytes(b"\x89PNG\r\n\x1a\n" + b"0" * 64)

    lib = MediaLibrary(name="anime", path=str(media), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()
    series = VideoSeries(title="直到夏日结束之前", tmdb_id=106882)
    db.add(series)
    db.flush()
    ep = Video(
        file_path=str(ep_dir / "直到夏日结束之前 - S01E01.mp4"),
        file_name="直到夏日结束之前 - S01E01.mp4",
        title="直到夏日结束之前 S01E01",
        series_id=series.id,
        library_id=lib.id,
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
    )
    db.add(ep)
    db.flush()
    return series, ep


def test_logo_at_show_root_is_exposed_in_real_layout(tmp_path, db):
    """回归：logo 与总海报同层（剧根），真实布局下必须能暴露 logoUrl。

    实测 series 94：`/vol00/.../直到夏日结束之前/tvshow-logo.png` 存在，
    但 `logoUrl` 为 None —— 因为查找只看「分集目录 + 季目录」，从不看剧根。
    """
    series, ep = _make_series_real_layout(tmp_path, db, logo_at_root=True)

    assert _series_logo_url(series, [ep]) is not None, "剧根有 logo 却没暴露"
    assert find_local_series_logo(db, [ep]) is not None
    assert SeriesListDTO.from_entity(series, [ep], False).logoUrl is not None


def test_logo_in_season_dir_still_works(tmp_path, db):
    """季目录里的 logo 仍要能找到（保留旧行为）。"""
    series, ep = _make_series_real_layout(
        tmp_path, db, logo_at_root=False, logo_in_season=True
    )
    assert _series_logo_url(series, [ep]) is not None


def test_no_logo_anywhere_stays_none(tmp_path, db):
    """哪里都没有 logo 时必须返回 None（不能因为剧根存在就误报）。"""
    series, ep = _make_series_real_layout(tmp_path, db, logo_at_root=False)

    assert _series_logo_url(series, [ep]) is None
    assert find_local_series_logo(db, [ep]) is None
    assert SeriesListDTO.from_entity(series, [ep], False).logoUrl is None


def test_series_dto_exposes_logo_when_episodes_passed(tmp_path, db):
    """两条 DTO 路径都要能识别本地 logo。

    路由侧实际形态：详情 SeriesDTO 传 DTO + file_source=ORM 分集；
    列表 SeriesListDTO 直接传 ORM 分集。
    """
    from fryfrog.schemas.video import VideoDTO

    series, ep = _make_series(tmp_path, db, "tvshow-logo (2).png")
    assert SeriesDTO.from_entity(series, [], False) is not None  # 不传分集不该崩

    detail = SeriesDTO.from_entity(series, [VideoDTO.from_entity(ep)], False, file_source=[ep])
    assert detail.logoUrl is not None, "详情 DTO 有 ORM 分集时应暴露 logoUrl"

    listed = SeriesListDTO.from_entity(series, [ep], False)
    assert listed.logoUrl is not None, "列表 DTO 传 ORM 分集时应暴露 logoUrl"


def test_movie_logo_variants(tmp_path, db):
    movie_dir = tmp_path / "movie"
    movie_dir.mkdir()
    (movie_dir / "movie-logo (3).png").write_bytes(b"x")
    movie = Video(file_path=str(movie_dir / "某电影.mp4"), file_name="某电影.mp4", title="某电影")
    db.add(movie)
    db.flush()
    assert find_local_video_logo(movie) is not None
