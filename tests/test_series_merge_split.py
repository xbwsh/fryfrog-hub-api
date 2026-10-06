"""剧被拆成多行 VideoSeries 时要能自动合并。

实测故障：`裸体主义沙滩上的修学旅行！！` 重新刮削后剧名变成 `…修学旅行`
（少两个叹号），`_apply_tv_detail` 按剧名找不到系列行 → 新建空剧组，同时把老行
改名，于是同一个目录树下的分集散落在两个剧组：

    剧 A '…修学旅行'    tmdb=329393  0 集     ← 用户看到的（空的）
    剧 B '…修学旅行！！' tmdb=93175   3 集     ← 特别篇和正片其实在这

用户在剧详情里只看到一半（特别篇"消失"）。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services.video_scrape import _merge_split_series


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
    yield db, media, lib
    db.close()


def _add(db, lib, media, show, season, episode, series, tag=""):
    d = media / show / f"第 {season} 季"
    d.mkdir(parents=True, exist_ok=True)
    path = d / f"{show} - S{season:02d}E{episode:02d}{tag}.mp4"
    path.write_bytes(b"x" * 32)
    v = Video(
        file_path=str(path),
        file_name=path.name,
        title=path.stem,
        library_id=lib.id,
        series_id=series.id if series else None,
        tmdb_id=series.tmdb_id if series else None,
        media_type="tv",
        is_series=True,
        season_number=season,
        episode_number=episode,
    )
    db.add(v)
    db.flush()
    return v


def test_merges_orphan_series_of_same_show(env):
    """空壳剧组应被合并掉，分集回到同一个剧组。"""
    db, media, lib = env
    old = VideoSeries(title="某剧！！", tmdb_id=93175, media_type="tv")
    db.add(old)
    db.flush()
    special = _add(db, lib, media, "某剧", 0, 1, old)

    # 模拟重新刮削：新建了空壳剧组（剧名变了）
    new = VideoSeries(title="某剧", tmdb_id=329393, media_type="tv")
    db.add(new)
    db.flush()
    db.commit()

    merged = _merge_split_series(db, [special], new.id)
    db.commit()

    assert merged == 1, f"应合并 1 个空壳剧组，实际 {merged}"
    assert db.get(VideoSeries, old.id) is None, "旧剧组应被删除"
    assert db.get(Video, special.id).series_id == new.id, "分集应改挂到目标剧组"


def test_merges_series_whose_episodes_are_same_show(env):
    """两个剧组都有分集、但同属一个剧名根目录 → 也要合并。"""
    db, media, lib = env
    a = VideoSeries(title="某剧", tmdb_id=1, media_type="tv")
    b = VideoSeries(title="某剧", tmdb_id=2, media_type="tv")
    db.add_all([a, b])
    db.flush()
    ep1 = _add(db, lib, media, "某剧", 1, 1, a)
    ep2 = _add(db, lib, media, "某剧", 1, 2, b)
    db.commit()

    merged = _merge_split_series(db, [ep1, ep2], ep1.series_id)
    db.commit()

    assert merged == 1, f"应合并 1 个，实际 {merged}"
    remaining = db.scalars(select(VideoSeries).where(VideoSeries.title == "某剧")).all()
    assert len(remaining) == 1, "只应剩一个剧组"
    assert db.get(Video, ep1.id).series_id == remaining[0].id
    assert db.get(Video, ep2.id).series_id == remaining[0].id


def test_does_not_merge_different_shows_with_same_title(env):
    """同名但位于不同剧名根目录（不同剧）→ 绝不能合并。"""
    db, media, lib = env
    a = VideoSeries(title="同名剧", tmdb_id=1, media_type="tv")
    b = VideoSeries(title="同名剧", tmdb_id=2, media_type="tv")
    db.add_all([a, b])
    db.flush()
    mine = _add(db, lib, media, "我的剧", 1, 1, a)
    other = _add(db, lib, media, "别人的剧", 1, 1, b)
    # 两者剧名都叫「同名剧」，但文件在各自目录下
    mine.series_name = "同名剧"
    other.series_name = "同名剧"
    db.commit()

    merged = _merge_split_series(db, [mine], mine.series_id)
    db.commit()

    assert merged == 0, "不同剧名根目录不该被合并"
    assert db.get(VideoSeries, b.id) is not None
    assert db.get(Video, other.id).series_id == b.id, "别的剧的分集不能被搬走"


def test_does_not_merge_movie_series(env):
    """media_type 不是 tv 的行不参与合并。"""
    db, media, lib = env
    a = VideoSeries(title="某剧", tmdb_id=1, media_type="tv")
    b = VideoSeries(title="某剧", tmdb_id=2, media_type="movie")
    db.add_all([a, b])
    db.flush()
    ep = _add(db, lib, media, "某剧", 1, 1, a)
    db.commit()

    assert _merge_split_series(db, [ep], ep.series_id) == 0
    assert db.get(VideoSeries, b.id) is not None


def test_noop_without_series(env):
    db, media, lib = env
    v = _add(db, lib, media, "某剧", 1, 1, None)
    db.commit()
    assert _merge_split_series(db, [v], 0) == 0
    assert Path(v.file_path).exists()
