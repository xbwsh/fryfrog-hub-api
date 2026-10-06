"""批量刷新已绑定视频：不搜索、不清绑定、跳过未绑定。

历史 `rescrape_by_library` 是「先 unbind 全部 → 再按文件名 search_tmdb_best 重绑」，
两个问题：
  1. 原本正确的绑定被清掉后重搜，搜到别的条目就**绑错了**；
  2. 对未绑定的视频也强行搜索——用户把某些视频留在未刮削状态，正是因为 TMDB
     上根本没有它们，强搜只会写入错误内容。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services import video_scrape as scrape

TV_DETAIL = {
    "id": 106882,
    "name": "直到夏日结束之前",
    "overview": "总简介",
    "first_air_date": "2020-07-31",
    "vote_average": 9.0,
    "genres": [{"name": "动画"}],
    "seasons": [{"season_number": 1, "name": "第 1 季", "poster_path": "/s1.jpg"}],
}


@pytest.fixture()
def env(tmp_path, monkeypatch):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    media = tmp_path / "media"
    lib = MediaLibrary(name="L", path=str(media), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()

    series = VideoSeries(title="直到夏日结束之前", tmdb_id=106882, media_type="tv")
    db.add(series)
    db.flush()

    show = media / "直到夏日结束之前"
    season = show / "第 1 季"
    season.mkdir(parents=True)
    bound = []
    for n in (1, 2):
        p = season / f"直到夏日结束之前 - S01E{n:02d}.mp4"
        p.write_bytes(b"x" * 32)
        v = Video(
            file_path=str(p),
            file_name=p.name,
            title=f"直到夏日结束之前 S01E{n:02d}",
            library_id=lib.id,
            series_id=series.id,
            tmdb_id=106882,
            media_type="tv",
            is_series=True,
            season_number=1,
            episode_number=n,
        )
        db.add(v)
        bound.append(v)

    # 未绑定：文件名在 TMDB 上搜不到，用户刻意留着
    unbound = []
    for n in (101, 102):
        p = show / f"PRED-{n}.mp4"
        p.write_bytes(b"x" * 32)
        v = Video(
            file_path=str(p),
            file_name=p.name,
            title=f"PRED-{n}",
            library_id=lib.id,
            media_type="movie",
        )
        db.add(v)
        unbound.append(v)

    db.commit()

    calls = {"get_tv": 0, "get_movie": 0, "search": 0, "get_episode": 0, "get_season": 0}

    def fake_get_tv(self, tid, *a, **k):
        calls["get_tv"] += 1
        assert tid == 106882, f"必须用已有绑定 ID，实际 {tid}"
        return TV_DETAIL

    def fake_get_movie(self, tid, *a, **k):
        calls["get_movie"] += 1
        return {"id": tid, "title": "x"}

    def fake_get_episode(self, tid, season, episode, *a, **k):
        calls["get_episode"] += 1
        return {"name": f"第 {episode} 集", "overview": "分集简介", "runtime": 20}

    def fake_get_season(self, tid, season, *a, **k):
        calls["get_season"] += 1
        return {"season_number": season, "name": "第 1 季"}

    def fake_search(*a, **k):
        calls["search"] += 1
        return None

    monkeypatch.setattr(scrape.TmdbClient, "get_tv", fake_get_tv)
    monkeypatch.setattr(scrape.TmdbClient, "get_movie", fake_get_movie)
    # 这两个不 mock 会真的发请求并 ConnectTimeout（实测 200s）
    monkeypatch.setattr(scrape.TmdbClient, "get_episode", fake_get_episode)
    monkeypatch.setattr(scrape.TmdbClient, "get_season", fake_get_season)
    monkeypatch.setattr(scrape, "search_tmdb_best", fake_search)
    monkeypatch.setattr(scrape, "download_all_covers", lambda *a, **k: True)
    monkeypatch.setattr(scrape.assets, "download_all_covers", lambda *a, **k: True)
    monkeypatch.setattr(scrape.assets, "download_image", lambda *a, **k: False)

    yield db, lib, series, bound, unbound, show, calls
    db.close()


def test_refresh_skips_unbound_videos(env):
    """核心：未绑定的视频完全不动，也不为它们发搜索请求。"""
    db, lib, _series, _bound, unbound, _show, calls = env

    result = scrape.refresh_bound_by_library(db, lib.id)

    assert result["skipped"] == 2, f"应跳过 2 个未绑定，实际 {result}"
    assert calls["search"] == 0, "绝不能对未绑定视频发起搜索"
    for v in unbound:
        assert v.tmdb_id is None, "未绑定视频不该被写入绑定"
        assert v.overview is None, "未绑定视频不该被写入元数据"


def test_refresh_uses_existing_tmdb_id_without_rebinding(env):
    """用已有 ID 刷新：绑定值不变，且不做搜索。"""
    db, lib, series, bound, _unbound, _show, calls = env

    result = scrape.refresh_bound_by_library(db, lib.id)

    assert result["refreshed"] == 2
    assert result["failed"] == 0
    assert calls["search"] == 0, "刷新不该走搜索"
    assert calls["get_tv"] == 1, f"整剧只该取一次详情，实际 {calls['get_tv']}"
    assert series.tmdb_id == 106882, "绑定不能被改动"
    for v in bound:
        assert v.tmdb_id == 106882, "分集绑定不能被改动"
        # 分集简介由 _apply_episode_detail 覆盖（设计如此），不是剧总简介
        assert v.overview == "分集简介", "分集元数据应被刷新"


def test_refresh_writes_new_format_nfo(env):
    """刷新后 NFO 应是新格式：剧根 tvshow.nfo + 季级 season.nfo。"""
    import xml.etree.ElementTree as ET

    db, lib, _series, _bound, _unbound, show, _calls = env

    scrape.refresh_bound_by_library(db, lib.id)

    root_nfo = show / "tvshow.nfo"
    assert root_nfo.is_file(), "剧根应有 tvshow.nfo"
    assert ET.fromstring(root_nfo.read_text(encoding="utf-8")).tag == "tvshow"

    season_nfo = show / "第 1 季" / "season.nfo"
    assert season_nfo.is_file(), "季目录应有 season.nfo"
    s = ET.fromstring(season_nfo.read_text(encoding="utf-8"))
    assert s.findtext("seasonnumber") == "1"


def test_rescrape_by_library_returns_refreshed_count(env):
    """兼容旧签名：返回处理数（内部已是安全刷新）。"""
    db, lib, _series, _bound, _unbound, _show, calls = env

    count = scrape.rescrape_by_library(db, lib.id)

    assert count == 2
    assert calls["search"] == 0


def test_progress_callback_reports_incrementally(env):
    """整批可能跑几分钟，必须逐部剧上报；只在结束时上报一次界面会一直显示 0%。

    实测：库 5（400+ 部剧）刷新跑了 4 分钟，进度条仍是 0%，看起来像卡死。
    """
    db, lib, _series, _bound, _unbound, _show, _calls = env

    ticks: list[tuple] = []
    scrape.refresh_bound_by_library(
        db, lib.id, lambda r, s, f, t: ticks.append((r, s, f, t))
    )

    assert len(ticks) >= 2, f"应至少上报两次（开始 + 每部剧），实际 {ticks}"
    assert ticks[0] == (0, 2, 0, 1), f"首次应上报初始状态，实际 {ticks[0]}"
    assert ticks[-1][0] == 2, f"最后一次 completed 应为 2，实际 {ticks[-1]}"
    assert all(t[3] == 1 for t in ticks), "计划总数应稳定为 1 部剧"
    # 进度必须单调不减，否则客户端进度条会跳
    assert [t[0] for t in ticks] == sorted(t[0] for t in ticks)


def test_progress_callback_failure_does_not_break_refresh(env):
    """回调抛异常不能中断刷新（进度上报是附带功能）。"""
    db, lib, _series, bound, _unbound, _show, _calls = env

    def boom(*_a):
        raise RuntimeError("回调炸了")

    result = scrape.refresh_bound_by_library(db, lib.id, boom)

    assert result["refreshed"] == 2, "刷新本身应照常完成"
