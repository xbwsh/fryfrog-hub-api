from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video
from fryfrog.routers.video import browse


def _setup(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="影片", path=str(tmp_path), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()

    for name in ("未刮削A", "未刮削B", "未刮削C"):
        db.add(
            Video(
                file_path=str(tmp_path / f"{name}.mkv"),
                file_name=f"{name}.mkv",
                title=name,
                library_id=lib.id,
                tmdb_id=None,
            )
        )
    for name in ("已刮削X", "已刮削Y"):
        db.add(
            Video(
                file_path=str(tmp_path / f"{name}.mkv"),
                file_name=f"{name}.mkv",
                title=name,
                library_id=lib.id,
                tmdb_id=12345,
                metadata_source="scrape",
            )
        )
    db.commit()
    return db


def test_unscraped_lists_only_missing_tmdb(tmp_path):
    """只返回 tmdb_id 为空的视频，DTO 与搜索同构且带 scraped 标志。"""
    db = _setup(tmp_path)
    resp = browse.list_unscraped(db, page=0, size=20)
    assert resp.success
    data = resp.data
    assert data["totalElements"] == 3
    titles = [v["title"] for v in data["content"]]
    assert titles == ["未刮削A", "未刮削B", "未刮削C"]
    assert all(v["scraped"] is False for v in data["content"])
    assert all(v["tmdbId"] is None for v in data["content"])


def test_unscraped_paging(tmp_path):
    db = _setup(tmp_path)
    page0 = browse.list_unscraped(db, page=0, size=2).data
    assert len(page0["content"]) == 2
    assert page0["totalElements"] == 3
    assert page0["totalPages"] == 2

    page1 = browse.list_unscraped(db, page=1, size=2).data
    assert len(page1["content"]) == 1

    titles = [v["title"] for v in page0["content"] + page1["content"]]
    assert titles == ["未刮削A", "未刮削B", "未刮削C"]  # 翻页不重不漏

    empty = browse.list_unscraped(db, page=9, size=2).data
    assert empty["content"] == []
