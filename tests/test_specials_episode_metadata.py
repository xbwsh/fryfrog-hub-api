"""特别篇（第 0 季）的分集元数据也要刮削。

实测 bug：`_apply_episode_detail` 的守卫写成 `if not video.season_number ...`，
而第 0 季的 season_number 是 0（falsy），于是特别篇**永远拿不到**集标题、
简介、时长、剧照——整段被 return 掉，且不报错。
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
from fryfrog.services.video_scrape import _apply_episode_detail

EPISODE_PAYLOAD = {
    "name": "前篇 雌屌狩猎。",
    "overview": "「雌屌狩猎。」\n\n田径部的天塚志穂和仓永蕾拉互相激励…",
    "air_date": "2026-04-17",
    "vote_average": 8.5,
    "vote_count": 12,
    "runtime": 26,
    "still_path": "/still.jpg",
}


class _FakeTmdb:
    def __init__(self) -> None:
        self.calls: list[tuple] = []

    def get_episode(self, tv_id, season, episode):
        self.calls.append((tv_id, season, episode))
        return EPISODE_PAYLOAD

    def image_url(self, path, size=None):
        return f"https://img/{size}{path}" if path else None


@pytest.fixture()
def env(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    media = tmp_path / "media"
    media.mkdir()
    lib = MediaLibrary(name="L", path=str(media), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()

    def make(season, episode, name="ep.mp4", is_series=True):
        series = VideoSeries(title="剧", tmdb_id=310640)
        db.add(series)
        db.flush()
        v = Video(
            file_path=str(media / name),
            file_name=name,
            title=name,
            library_id=lib.id,
            series_id=series.id,
            tmdb_id=310640,
            media_type="tv",
            is_series=is_series,
            season_number=season,
            episode_number=episode,
        )
        db.add(v)
        db.flush()
        return v

    yield db, make
    db.close()


def test_specials_season_zero_gets_episode_metadata(env):
    """第 0 季必须真的去请求分集详情并回填（旧代码在这里直接 return）。"""
    db, make = env
    video = make(0, 1, "S00E01.mp4")
    client = _FakeTmdb()

    _apply_episode_detail(db, video, {"id": 310640}, client)

    assert client.calls == [(310640, 0, 1)], "应查询 S00E01 的分集详情"
    assert video.title == "前篇 雌屌狩猎。"
    assert video.overview and "雌屌狩猎" in video.overview
    assert video.release_date == "2026-04-17"
    assert video.rating == 8.5
    assert video.vote_count == 12
    assert video.duration_minutes == 26
    assert video.backdrop_url and "still.jpg" in video.backdrop_url


def test_regular_season_still_works(env):
    db, make = env
    video = make(1, 3, "S01E03.mp4")
    client = _FakeTmdb()

    _apply_episode_detail(db, video, {"id": 310640}, client)

    assert client.calls == [(310640, 1, 3)]
    assert video.title == "前篇 雌屌狩猎。"


def test_missing_season_or_episode_number_is_skipped(env):
    """真正的「没有季/集号」仍要跳过，不能因为放宽守卫就乱请求。"""
    db, make = env
    client = _FakeTmdb()

    _apply_episode_detail(db, make(None, 1, "a.mp4"), {"id": 310640}, client)
    _apply_episode_detail(db, make(1, None, "b.mp4"), {"id": 310640}, client)
    _apply_episode_detail(db, make(1, 1, "c.mp4"), {}, client)

    assert client.calls == [], f"不该发请求，实际 {client.calls}"
