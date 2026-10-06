"""特别篇（Season 0）不能被吞成第 1 季。

实测：`S00E01` 解析出 season=0，但旧代码写 `season or 1` → 存成 1，
特别篇被并进第 1 季，用户在剧里看不到「特别篇」这一季。
TMDB 约定特别篇就是 Season 0。
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
from fryfrog.services import video_assets as assets
from fryfrog.services import video_service as vs
from fryfrog.services.video_scan import parse_episode, scan_video_library


class _V:
    def __init__(self, season, episode):
        self.season_number = season
        self.episode_number = episode


@pytest.mark.parametrize(
    "season,episode,expect_s,expect_e",
    [
        (0, 1, 0, 1),      # 特别篇：必须保留 0
        (0, 0, 0, 0),
        (1, 5, 1, 5),
        (None, 3, 1, 3),   # 缺失回退第 1 季
        (None, None, 1, 0),
        (3, None, 3, 0),
    ],
)
def test_season_of_preserves_zero(season, episode, expect_s, expect_e):
    v = _V(season, episode)
    assert assets.season_of(v) == expect_s
    assert assets.episode_of(v) == expect_e
    # video_service 转出同一实现
    assert vs.season_of(v) == expect_s
    assert vs.episode_of(v) == expect_e


def test_parse_episode_reads_s00():
    assert parse_episode("剧 - S00E01") == ("剧", 0, 1)
    assert parse_episode("剧 - S01E01") == ("剧", 1, 1)


def test_series_detail_groups_specials_into_own_season():
    """详情接口必须把特别篇分到「第 0 季」，不能并进第 1 季。

    实测故障：分集自身的 seasonNumber=0 是对的，但 SeriesDTO.from_entity 分桶时
    用了 `ep.seasonNumber or 1`（camelCase，DTO 字段名），0 被吞成 1 →
    接口返回「第 1 季（4 集）」，特别篇混在第 1 季里且剧里根本没有「特别篇」季。
    """
    from fryfrog.schemas.video import SeriesDTO, VideoDTO

    class _Series:
        id = 1
        title = "某剧"
        original_title = None
        cover_url = None
        backdrop_url = None
        logo_url = None
        logo_local_path = None
        poster_local_path = None
        backdrop_local_path = None
        overview = None
        media_type = "tv"
        tmdb_id = 555
        rating = None
        year = 2024
        release_date = None
        season_number = 1
        number_of_seasons = 2
        total_episodes = 4
        status = None
        is_adult = False

    eps = [
        VideoDTO(id=10, title="特别篇", fileName="S00E01.mp4", seasonNumber=0, episodeNumber=1),
        VideoDTO(id=11, title="E1", fileName="S01E01.mp4", seasonNumber=1, episodeNumber=1),
        VideoDTO(id=12, title="E2", fileName="S01E02.mp4", seasonNumber=1, episodeNumber=2),
    ]
    detail = SeriesDTO.from_entity(_Series(), eps, False)
    by_num = {s.seasonNumber: [e.id for e in s.episodes] for s in detail.seasons}

    assert 0 in by_num, f"没有第 0 季（特别篇）: {sorted(by_num)}"
    assert by_num[0] == [10], f"第 0 季内容不对: {by_num[0]}"
    assert by_num[1] == [11, 12], f"第 1 季不该混入特别篇: {by_num[1]}"


def test_series_detail_defaults_missing_season_to_1():
    """seasonNumber 缺失（None）才回退第 1 季。"""
    from fryfrog.schemas.video import SeriesDTO, VideoDTO

    class _Series:
        id = 1
        title = "某剧"
        original_title = None
        cover_url = None
        backdrop_url = None
        logo_url = None
        logo_local_path = None
        poster_local_path = None
        backdrop_local_path = None
        overview = None
        media_type = "tv"
        tmdb_id = 555
        rating = None
        year = 2024
        release_date = None
        season_number = None
        number_of_seasons = None
        total_episodes = None
        status = None
        is_adult = False

    detail = SeriesDTO.from_entity(
        _Series(),
        [VideoDTO(id=20, title="x", fileName="x.mp4", seasonNumber=None, episodeNumber=1)],
        False,
    )
    assert [s.seasonNumber for s in detail.seasons] == [1]


def test_scan_keeps_specials_in_season_zero(tmp_path, monkeypatch):
    """扫描后 S00Exx 必须落在第 0 季，而不是第 1 季。"""
    # 关掉刮削：否则扫描会去连 TMDB，在无网环境每次连接超时 40s（实测本用例 2 分钟）
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    media = tmp_path / "media"
    season0 = media / "某剧" / "特别篇"
    season1 = media / "某剧" / "第 1 季"
    season0.mkdir(parents=True)
    season1.mkdir(parents=True)
    (season0 / "某剧 - S00E01.mp4").write_bytes(b"x" * 128)
    (season0 / "某剧 - S00E02.mp4").write_bytes(b"x" * 128)
    (season1 / "某剧 - S01E01.mp4").write_bytes(b"x" * 128)

    lib = MediaLibrary(
        name="L", path=str(media), type="VIDEO", enabled=True, enable_scraping=False
    )
    db.add(lib)
    db.commit()

    scan_video_library(db, lib)
    db.commit()

    rows = list(db.execute(
        __import__("sqlalchemy").select(
            Video.file_name, Video.season_number, Video.episode_number
        ).order_by(Video.file_name)
    ).all())
    got = {(r[1], r[2]) for r in rows}
    assert (0, 1) in got and (0, 2) in got, f"特别篇没落在第 0 季: {got}"
    assert (1, 1) in got
    # 第 1 季不该混入特别篇
    assert len([r for r in rows if r[1] == 1]) == 1, rows
