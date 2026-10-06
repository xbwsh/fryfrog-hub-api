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
