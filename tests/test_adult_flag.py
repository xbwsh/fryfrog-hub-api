"""成人标记：库级声明优先于 TMDB 的 adult 标记，且要写进 NFO。

实测问题（series 94 '直到夏日结束之前'，位于 is_adult=True 的库 6）：
    剧级 isAdult=True  ← 对
    分集 E1 isAdult=False  ← 错
成因是三处赋值口径互相打架：
    video_scan   `video.is_adult = bool(library.is_adult)`   库级声明
    video_scrape `video.is_adult = bool(detail.get("adult"))` 被 TMDB 改回 false
    video_scrape `series.is_adult = video.is_adult`
JAV/成人番在 TMDB 上通常 adult=false，于是刮削把库级 true 冲掉了。
"""

from __future__ import annotations

import os
import xml.etree.ElementTree as ET

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services import video_assets as assets
from fryfrog.services import video_scan, video_scrape

TV_DETAIL = {
    "id": 106882,
    "name": "直到夏日结束之前",
    "overview": "总简介",
    "first_air_date": "2020-07-31",
    "adult": False,  # 关键：TMDB 说不是成人
    "genres": [{"name": "动画"}],
    "seasons": [{"season_number": 1, "name": "第 1 季"}],
}


def test_series_nfo_has_adult_tag_when_adult():
    series = VideoSeries(title="某剧", is_adult=True)
    root = ET.fromstring(assets.build_series_nfo(series, None))
    assert root.findtext("adult") == "true"
    assert root.findtext("mpaa") == "NC-17"


def test_series_nfo_omits_adult_tag_when_not_adult():
    """不写 <adult>false</adult>：解析端只升不降，显式 false 没有意义。"""
    series = VideoSeries(title="某剧", is_adult=False)
    text = assets.build_series_nfo(series, None)
    root = ET.fromstring(text)

    assert root.find("adult") is None
    assert root.findtext("mpaa") == "PG"


def test_series_nfo_adult_from_sample_episode():
    """系列行没标记但分集有（库级设置写在分集上）时也要输出。"""
    series = VideoSeries(title="某剧", is_adult=False)
    ep = Video(file_path="/m/a.mp4", file_name="a.mp4", is_adult=True)
    root = ET.fromstring(assets.build_series_nfo(series, None, sample=ep))
    assert root.findtext("adult") == "true"


def test_episode_nfo_has_adult_tag_when_adult():
    ep = Video(
        file_path="/m/a.mp4",
        file_name="a.mp4",
        title="某集",
        is_adult=True,
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
    )
    root = ET.fromstring(assets._build_nfo(ep))
    assert root.findtext("adult") == "true"


def test_episode_nfo_omits_adult_tag_when_not_adult():
    ep = Video(
        file_path="/m/a.mp4", file_name="a.mp4", title="某集", is_adult=False
    )
    assert ET.fromstring(assets._build_nfo(ep)).find("adult") is None


@pytest.fixture()
def db():
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    yield session
    session.close()


class _Client:
    """只实现被调用到的两个方法；漏 mock 的会走真实网络（实测 40s 超时）。"""

    def image_url(self, path, size=None):
        return None

    def get_episode(self, tmdb_id, season, episode):
        return None


def test_scrape_does_not_downgrade_adult(db):
    """库级已标成人时，刮削不能因为 TMDB adult=false 把它改回去。"""
    ep = Video(
        file_path="/m/a.mp4",
        file_name="a.mp4",
        title="某剧 S01E01",
        is_adult=True,  # 来自库级设置
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
    )

    video_scrape._apply_tv_detail(db, ep, TV_DETAIL, _Client())

    assert ep.is_adult is True, "TMDB 的 adult=false 不该覆盖库级声明"


def test_scrape_upgrades_adult_from_tmdb(db):
    """反向：TMDB 明确标成人时仍要能升上去。"""
    ep = Video(
        file_path="/m/a.mp4",
        file_name="a.mp4",
        title="某剧 S01E01",
        is_adult=False,
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
    )

    detail = dict(TV_DETAIL)
    detail["adult"] = True
    video_scrape._apply_tv_detail(db, ep, detail, _Client())

    assert ep.is_adult is True


def test_series_adult_is_sticky(db):
    """整剧只要有一次被判成人，后续刮削非成人分集不能把它降回去。"""
    series = VideoSeries(title="某剧", media_type="tv", is_adult=True)
    db.add(series)
    db.flush()
    ep = Video(
        file_path="/m/a.mp4",
        file_name="a.mp4",
        title="某剧 S01E01",
        is_adult=False,
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
        series_id=series.id,
    )
    db.add(ep)
    db.flush()

    video_scrape._apply_tv_detail(db, ep, TV_DETAIL, _Client())

    assert series.is_adult is True, "剧级成人标记只升不降"


def test_scan_sets_adult_from_library_and_self_heals(tmp_path):
    """库级 is_adult 要能自愈已有记录（历史数据里分集是 false）。"""
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    media = tmp_path / "media"
    show = media / "直到夏日结束之前" / "第 1 季" / "第 1 集"
    show.mkdir(parents=True)
    vf = show / "直到夏日结束之前 - S01E01.mp4"
    vf.write_bytes(b"x" * 32)

    # 关掉刮削：否则扫描会去 TMDB 搜索片名，无网时 ConnectTimeout 40s。
    # 本用例只验证 is_adult 的自愈，与刮削无关。
    lib = MediaLibrary(
        name="L",
        path=str(media),
        type="VIDEO",
        enabled=True,
        is_adult=True,
        enable_scraping=False,
    )
    db.add(lib)
    db.flush()
    # 预置一条 is_adult=False 的历史记录（模拟被刮削冲掉的状态）
    db.add(
        Video(
            file_path=str(vf),
            file_name=vf.name,
            title="直到夏日结束之前 S01E01",
            library_id=lib.id,
            is_adult=False,
            media_type="tv",
            is_series=True,
            season_number=1,
            episode_number=1,
        )
    )
    db.commit()

    video_scan.scan_video_library(db, lib)
    db.commit()

    rows = db.query(Video).all()
    assert rows and all(v.is_adult for v in rows), "库级设置应把历史记录升为成人"
