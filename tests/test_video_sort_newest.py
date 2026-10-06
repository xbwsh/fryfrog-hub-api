"""分库视图排序：最新入库优先。

实测诉求：库页面一直显示最老的条目（原来按剧名字母序），希望看到新入库的。
这里锁定「按 created_at（扫描首次入库时间）倒序」。
"""

from __future__ import annotations

import os
from datetime import datetime, timedelta
from pathlib import Path

os.environ.setdefault("AUTH_ENABLED", "false")

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video
from fryfrog.routers.video.series import grouped_by_library


class _FakeLibService:
    """只返回一个视频库，避免依赖全局媒体库状态。"""

    def __init__(self, lib: MediaLibrary) -> None:
        self.lib = lib

    def get_enabled_libraries(self, db):
        return [self.lib]


def test_library_grouped_sorted_newest_first(tmp_path, monkeypatch):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    media = tmp_path / "media"
    media.mkdir()
    lib = MediaLibrary(
        name="L", path=str(media), type="VIDEO", enabled=True, enable_scraping=False
    )
    db.add(lib)
    db.flush()

    base = datetime(2026, 1, 1, 12, 0, 0)
    # 三个单片：入库时间与标题字母序刻意相反
    for i, (title, age_h) in enumerate(
        [("z-最老但字母最后", 72), ("m-中间", 24), ("a-最新但字母最前", 1)]
    ):
        db.add(
            Video(
                file_path=str(media / f"{title}.mp4"),
                file_name=f"{title}.mp4",
                title=title,
                library_id=lib.id,
                tmdb_id=1000 + i,
                created_at=base - timedelta(hours=age_h),
            )
        )
    db.commit()

    # 只替换「取哪些库」与「当前用户可见库」，其余（排序、DTO）走真实实现
    monkeypatch.setattr(
        "fryfrog.routers.video.series._mls", lambda db_: _FakeLibService(lib)
    )
    monkeypatch.setattr("fryfrog.routers.video.series._allowed_ids", lambda db_: [lib.id])

    resp = grouped_by_library(db=db, page=0, size=50)
    data = resp["data"] if isinstance(resp, dict) else resp.data
    group = next(g for g in data if g["libraryId"] == lib.id)
    titles = [v["title"] for v in group["standaloneVideos"]]
    assert titles == ["a-最新但字母最前", "m-中间", "z-最老但字母最后"], titles
    assert Path(media).is_dir()


def test_series_sorted_by_newest_episode(tmp_path, monkeypatch):
    """剧卡按该剧最新一集的入库时间排，而不是剧名字母序。"""
    from fryfrog.models.video import VideoSeries

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    media = tmp_path / "media"
    media.mkdir()
    lib = MediaLibrary(
        name="L", path=str(media), type="VIDEO", enabled=True, enable_scraping=False
    )
    db.add(lib)
    db.flush()

    base = datetime(2026, 1, 1, 12, 0, 0)
    for title, age_h in [("A-老剧", 100), ("Z-新剧", 1)]:
        series = VideoSeries(title=title, tmdb_id=900 + age_h)
        db.add(series)
        db.flush()
        db.add(
            Video(
                file_path=str(media / f"{title}.S01E01.mp4"),
                file_name=f"{title}.S01E01.mp4",
                title=f"{title} S01E01",
                library_id=lib.id,
                series_id=series.id,
                tmdb_id=900 + age_h,
                is_series=True,
                season_number=1,
                episode_number=1,
                created_at=base - timedelta(hours=age_h),
            )
        )
    db.commit()

    monkeypatch.setattr(
        "fryfrog.routers.video.series._mls", lambda db_: _FakeLibService(lib)
    )
    monkeypatch.setattr("fryfrog.routers.video.series._allowed_ids", lambda db_: [lib.id])

    resp = grouped_by_library(db=db, page=0, size=50)
    data = resp["data"] if isinstance(resp, dict) else resp.data
    group = next(g for g in data if g["libraryId"] == lib.id)
    titles = [s["title"] for s in group["series"]]
    assert titles == ["Z-新剧", "A-老剧"], titles
    assert db.scalar(select(Video.id).limit(1)) is not None
