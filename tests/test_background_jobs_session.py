"""后台批量任务必须用自己的 session 重新取对象。

实测故障：任务闭包捕获请求 session 里加载的 ORM 对象，任务线程里这些对象是
detached 的——赋值不会进任何 session，flush 也写不进去：磁盘上文件都下载好了，
数据库字段还是空的（logo/分辨率/季封面全中招）。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from fryfrog.core.security import set_current_user_id
from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.user import User, UserRole
from fryfrog.models.video import Video, VideoSeries
from fryfrog.routers.video import scrape, series as series_router


@pytest.fixture(autouse=True)
def _reset_current_user():
    """这些用例要把当前用户设成管理员；漏掉重置会污染同进程里的其他用例。"""
    yield
    set_current_user_id(None)


class _FakeProbe:
    def probe_video_resolution(self, path):
        return (1920, 1080)


def _setup(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'db.sqlite'}")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="剧集", path=str(tmp_path), type="VIDEO", enable_scraping=True)
    db.add(lib)
    db.flush()

    drama = VideoSeries(title="剧A", tmdb_id=101, media_type="TV")
    db.add(drama)
    db.flush()
    for i in (1, 2):
        db.add(
            Video(
                file_path=str(tmp_path / f"a-e{i}.mkv"),
                file_name=f"a-e{i}.mkv",
                title=f"剧A E{i}",
                library_id=lib.id,
                series_id=drama.id,
                tmdb_id=101,
                season_number=1,
                episode_number=i,
                resolution=None,
            )
        )
    movie = Video(
        file_path=str(tmp_path / "movie.mkv"),
        file_name="movie.mkv",
        title="电影",
        library_id=lib.id,
        tmdb_id=202,
        media_type="movie",
        resolution=None,
    )
    db.add(movie)
    admin = User(username="root", password_hash="x", role=UserRole.ADMIN, enabled=True)
    db.add(admin)
    db.commit()
    set_current_user_id(admin.id)
    return engine, db, drama.id, movie.id


def _job_in_new_session(engine, monkeypatch, module, recorder=None):
    """把 submit_job 换成「立刻用独立 session 跑一遍」：等价真实后台线程。"""

    def fake_submit_job(module_name, stage, total, work, **kwargs):
        if recorder is not None:
            recorder["total"] = total
        job_db = sessionmaker(bind=engine)()
        try:
            work(job_db)
            job_db.commit()
        finally:
            job_db.close()

    monkeypatch.setattr(module, "submit_job", fake_submit_job)


def _fresh(engine):
    return sessionmaker(bind=engine)()


def test_refresh_all_resolutions_persists(tmp_path, monkeypatch):
    engine, db, _series_id, _movie_id = _setup(tmp_path)
    _job_in_new_session(engine, monkeypatch, scrape)
    monkeypatch.setattr(scrape, "get_media_probe", lambda: _FakeProbe())

    response = scrape.refresh_all_resolutions(db)
    assert response.data["pendingVideos"] == 3, response.data

    fresh = _fresh(engine)
    resolutions = list(fresh.scalars(select(Video.resolution)).all())
    assert resolutions == ["1920x1080"] * 3, f"分辨率没落库: {resolutions}"


def test_refresh_all_logos_writes_back_to_job_session(tmp_path, monkeypatch):
    engine, db, series_id, _movie_id = _setup(tmp_path)
    _job_in_new_session(engine, monkeypatch, scrape)

    def fake_series_logo(job_db, series_obj, *a, **k):
        series_obj.logo_local_path = "/media/tvshow-logo.png"
        job_db.flush()
        return True

    def fake_movie_logo(job_db, video_obj, *a, **k):
        video_obj.logo_local_path = "/media/movie-logo.png"
        job_db.flush()
        return True

    monkeypatch.setattr(scrape.assets, "download_series_logo", fake_series_logo)
    monkeypatch.setattr(scrape.assets, "download_movie_logo", fake_movie_logo)

    scrape.refresh_all_logos(db)

    fresh = _fresh(engine)
    assert fresh.get(VideoSeries, series_id).logo_local_path == "/media/tvshow-logo.png"
    assert all(v.logo_local_path == "/media/movie-logo.png" for v in fresh.scalars(select(Video).where(Video.media_type == "movie")).all())


def test_refresh_all_season_covers_persists(tmp_path, monkeypatch):
    engine, db, series_id, _movie_id = _setup(tmp_path)
    _job_in_new_session(engine, monkeypatch, series_router)

    def fake_root_art(job_db, series_obj, episodes):
        series_obj.poster_local_path = "/media/tvshow-poster.jpg"
        job_db.flush()
        return True

    def fake_covers(job_db, video_obj, force=False):
        video_obj.cover_art_path = "/media/episode-poster.jpg"
        job_db.flush()
        return True

    monkeypatch.setattr(series_router.assets, "download_series_root_art", fake_root_art)
    monkeypatch.setattr(series_router.assets, "download_all_covers", fake_covers)

    series_router.refresh_all_season_covers(db)

    fresh = _fresh(engine)
    assert fresh.get(VideoSeries, series_id).poster_local_path == "/media/tvshow-poster.jpg"
    posters = list(
        fresh.scalars(select(Video.cover_art_path).where(Video.series_id == series_id)).all()
    )
    assert posters == ["/media/episode-poster.jpg"] * 2, posters
