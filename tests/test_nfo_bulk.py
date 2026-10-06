"""批量统一 NFO：`POST /video/nfo/regenerate-all`。

为什么需要它：`scrape_video_if_needed` 开头是 `if video.tmdb_id: return`，
已绑定的剧不会再走刮削流程，所以**重新刮削刷不到 NFO**，存量剧只能靠批量入口。
"""

from __future__ import annotations

import os
import xml.etree.ElementTree as ET

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.core.exceptions import ForbiddenException
from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.routers.video import scrape as scrape_router

TV_DETAIL = {
    "id": 106882,
    "name": "直到夏日结束之前",
    "overview": "总简介",
    "first_air_date": "2020-07-31",
    "vote_average": 9.0,
    "genres": [{"name": "动画"}],
    "seasons": [
        {"season_number": 1, "name": "第 1 季", "poster_path": "/s1.jpg"},
        {"season_number": 2, "name": "夏日的结束", "poster_path": "/s2.jpg"},
    ],
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
    for sn in (1, 2):
        d = show / f"第 {sn} 季"
        d.mkdir(parents=True)
        p = d / f"直到夏日结束之前 - S{sn:02d}E01.mp4"
        p.write_bytes(b"x" * 32)
        db.add(
            Video(
                file_path=str(p),
                file_name=p.name,
                title=f"直到夏日结束之前 S{sn:02d}E01",
                library_id=lib.id,
                series_id=series.id,
                tmdb_id=106882,
                media_type="tv",
                is_series=True,
                season_number=sn,
                episode_number=1,
            )
        )
    db.commit()

    # 后台任务同步执行，避免线程带来的不确定性
    calls = {}

    def fake_submit_job(module, stage, total, work, **kwargs):
        calls.update(module=module, stage=stage, total=total)
        work(db)

    monkeypatch.setattr(scrape_router, "submit_job", fake_submit_job)
    monkeypatch.setattr(scrape_router, "_require_admin", lambda db, **k: None)
    monkeypatch.setattr(scrape_router, "progress_svc", type("P", (), {
        "update_progress": staticmethod(lambda *a, **k: None)
    }))
    monkeypatch.setattr(
        scrape_router.TmdbClient, "get_tv", lambda self, tid, *a, **k: TV_DETAIL
    )

    yield db, lib, series, show, calls
    db.close()


def test_regenerate_all_writes_series_and_season_nfo(env):
    db, lib, _series, show, calls = env

    resp = scrape_router.regenerate_all_nfo(db, libraryId=lib.id)

    assert resp.success, resp
    assert calls["stage"] == "nfo"
    assert calls["total"] == 1, "本库只有一部剧"

    root_nfo = show / "tvshow.nfo"
    assert root_nfo.is_file()
    assert ET.fromstring(root_nfo.read_text(encoding="utf-8")).tag == "tvshow"

    for sn in (1, 2):
        season_nfo = show / f"第 {sn} 季" / "season.nfo"
        assert season_nfo.is_file(), f"第 {sn} 季缺 season.nfo"
        assert ET.fromstring(season_nfo.read_text(encoding="utf-8")).tag == "season"

    s2 = ET.fromstring((show / "第 2 季" / "season.nfo").read_text(encoding="utf-8"))
    assert s2.findtext("title") == "夏日的结束"


def test_regenerate_all_overwrites_existing_nfo(env):
    """这是「统一成新格式」入口：已有文件也要覆盖。"""
    db, lib, _series, show, _calls = env
    target = show / "tvshow.nfo"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("<tvshow><plot>旧格式</plot></tvshow>", encoding="utf-8")

    scrape_router.regenerate_all_nfo(db, libraryId=lib.id)

    root = ET.fromstring(target.read_text(encoding="utf-8"))
    assert root.findtext("plot") == "总简介", "应被新格式覆盖"


def test_regenerate_all_filters_by_library(env):
    """指定别的库时不该处理本库的剧。"""
    db, _lib, _series, show, calls = env

    scrape_router.regenerate_all_nfo(db, libraryId=9999)

    assert calls["total"] == 0
    assert not (show / "tvshow.nfo").exists()


def test_regenerate_all_without_library_covers_all(env):
    db, _lib, _series, show, calls = env

    scrape_router.regenerate_all_nfo(db, libraryId=None)

    assert calls["total"] == 1
    assert (show / "tvshow.nfo").is_file()


def test_regenerate_all_requires_admin(env, monkeypatch):
    db, _lib, _series, _show, _calls = env

    def deny(db, **kwargs):
        raise ForbiddenException("需要管理员权限")

    monkeypatch.setattr(scrape_router, "_require_admin", deny)
    with pytest.raises(ForbiddenException):
        scrape_router.regenerate_all_nfo(db, libraryId=None)
