from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.routers.video import browse


def _setup(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="影片", path=str(tmp_path), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()

    s1 = VideoSeries(title="初恋时间", original_title="Hatsukoi Time", tmdb_id=101, media_type="TV")
    s2 = VideoSeries(title="幸存者", tmdb_id=102)
    s3 = VideoSeries(title="未绑定剧", tmdb_id=None)
    db.add_all([s1, s2, s3])
    db.flush()
    for s, eps in ((s1, 2), (s2, 1), (s3, 1)):
        for i in range(eps):
            db.add(
                Video(
                    file_path=str(tmp_path / f"{s.title}-E{i}.mkv"),
                    file_name=f"{s.title}-E{i}.mkv",
                    title=f"{s.title} S01E{i:02d}",
                    library_id=lib.id,
                    series_id=s.id,
                    tmdb_id=s.tmdb_id,
                    season_number=1,
                    episode_number=i + 1,
                )
            )
    mv = Video(
        file_path=str(tmp_path / "初恋电影.mkv"),
        file_name="初恋电影.mkv",
        title="初恋电影",
        library_id=lib.id,
        tmdb_id=42,
    )
    free = Video(
        file_path=str(tmp_path / "未绑定电影.mkv"),
        file_name="未绑定电影.mkv",
        title="未绑定电影",
        library_id=lib.id,
        tmdb_id=None,
    )
    db.add_all([mv, free])
    db.commit()
    return db


def _search(db, **kwargs):
    resp = browse.search_by_library(db, **kwargs)
    assert resp.success
    return resp.data


def test_search_matches_series_and_standalone(tmp_path):
    """剧名命中返回剧卡（分集不平铺），片名命中返回单片卡。"""
    db = _setup(tmp_path)
    data = _search(db, libraryId=1, q="初恋")

    assert data["libraryId"] == 1
    assert data["seriesCount"] == 1
    assert len(data["series"]) == 1
    assert data["series"][0]["type"] == "series"
    assert data["series"][0]["title"] == "初恋时间"
    assert data["standaloneCount"] == 1
    assert data["standaloneVideos"][0]["title"] == "初恋电影"


def test_search_matches_original_title(tmp_path):
    """系列原名（original_title）也能命中。"""
    db = _setup(tmp_path)
    data = _search(db, libraryId=1, q="Hatsukoi")
    assert data["seriesCount"] == 1
    assert data["series"][0]["title"] == "初恋时间"


def test_search_excludes_unbound_when_managed(tmp_path):
    """enable_scraping 库的未绑定条目不进搜索结果（与库分组视图一致）。"""
    db = _setup(tmp_path)
    data = _search(db, libraryId=1, q="未绑定")
    assert data["series"] == []
    assert data["standaloneVideos"] == []
    assert data["seriesCount"] == 0
    assert data["standaloneCount"] == 0


def test_search_keeps_unbound_for_external_library(tmp_path):
    """外部刮削库（enable_scraping=false）不过滤 tmdb_id 恒空的条目。"""
    db = _setup(tmp_path)
    lib = db.get(MediaLibrary, 1)
    lib.enable_scraping = False
    db.commit()

    data = _search(db, libraryId=1, q="未绑定")
    assert data["seriesCount"] == 1
    assert data["series"][0]["title"] == "未绑定剧"
    assert data["standaloneCount"] == 1
    assert data["standaloneVideos"][0]["title"] == "未绑定电影"


def test_search_paging_slices_each_segment(tmp_path):
    """两段各自按 page/size 切片，翻页不重不漏。"""
    db = _setup(tmp_path)
    page0 = _search(db, libraryId=1, q="初恋", page=0, size=1)
    assert len(page0["series"]) == 1
    assert len(page0["standaloneVideos"]) == 1
    assert page0["seriesCount"] == 1
    assert page0["standaloneCount"] == 1


def test_search_blank_keyword_returns_empty(tmp_path):
    """空关键词不退化整库拉取，直接返回空结果。"""
    db = _setup(tmp_path)
    data = _search(db, libraryId=1, q="   ")
    assert data["series"] == []
    assert data["standaloneVideos"] == []
    assert data["seriesCount"] == 0


def test_search_unknown_library_raises(tmp_path):
    """不可见/不存在的库直接 404，不泄露内容。"""
    db = _setup(tmp_path)
    try:
        browse.search_by_library(db, libraryId=999, q="x")
    except ResourceNotFoundException:
        return
    raise AssertionError("应当抛出 ResourceNotFoundException")
