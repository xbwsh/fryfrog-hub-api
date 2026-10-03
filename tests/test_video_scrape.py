from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services import video_scrape
from fryfrog.services.video_scrape import search_queries


class _FakeTmdb:
    """Minimal TmdbClient stand-in — no network in unit tests."""

    def __init__(self, *, tv: dict | None = None, movie: dict | None = None):
        self._tv = tv
        self._movie = movie

    def get_tv(self, tmdb_id: int):
        return self._tv

    def get_movie(self, tmdb_id: int):
        return self._movie

    def get_episode(self, show_id: int, season: int, episode: int):
        return None  # skips per-episode detail; not under test here

    def image_url(self, path: str | None, size: str = "original"):
        return f"https://img/{path}" if path else None


def _tv_detail() -> dict:
    return {
        "id": 500,
        "name": "测试剧",
        "original_name": "Test Show",
        "overview": "剧集简介",
        "vote_average": 8.5,
        "vote_count": 100,
        "first_air_date": "2024-01-01",
        "genres": [{"name": "剧情"}],
        "poster_path": "/p.jpg",
        "backdrop_path": "/b.jpg",
        "external_ids": {"imdb_id": "tt1"},
        "adult": False,
        "status": "Ended",
        "credits": {"cast": [{"name": "演员A"}]},
        "created_by": [],
        "number_of_seasons": 1,
        "number_of_episodes": 3,
        "next_episode_to_air": None,
    }


def _movie_detail() -> dict:
    return {
        "id": 42,
        "title": "同名电影",
        "original_title": "Dup Movie",
        "overview": "简介",
        "vote_average": 7.0,
        "vote_count": 10,
        "release_date": "2020-05-01",
        "genres": [],
        "credits": {"cast": [], "crew": []},
        "poster_path": "/m.jpg",
        "backdrop_path": "/mb.jpg",
        "imdb_id": "tt2",
        "adult": False,
        "status": "Released",
    }


def _setup(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)()


def test_bind_tv_expands_to_whole_season(tmp_path, monkeypatch):
    """剧集绑定按 series_id 连带整季：分集 title 各不相同也要全绑。"""
    db = _setup(tmp_path)
    series = VideoSeries(title="测试剧")
    db.add(series)
    db.flush()
    eps = []
    for ep in (1, 2, 3):
        v = Video(
            file_path=str(tmp_path / f"测试剧 S01E{ep:02d}.mkv"),
            file_name=f"测试剧 S01E{ep:02d}.mkv",
            title=f"测试剧 S01E{ep:02d}",
            series_id=series.id,
            season_number=1,
            episode_number=ep,
        )
        db.add(v)
        eps.append(v)
    db.commit()

    monkeypatch.setattr(
        video_scrape, "TmdbClient", lambda: _FakeTmdb(tv=_tv_detail())
    )
    bound = video_scrape.bind_series(db, eps[1].id, 500, "tv")
    db.commit()

    assert len(bound) == 3
    for v in eps:
        assert v.tmdb_id == 500, f"episode {v.title} missed the season bind"
        assert v.metadata_source == "tmdb"
        assert v.series_id == series.id
    assert series.tmdb_id == 500
    assert series.metadata_source == "tmdb"


def test_bind_movie_still_matches_same_title_only(tmp_path, monkeypatch):
    """电影没有 series 归属，保持按同标题连带（重复文件一起绑）。"""
    db = _setup(tmp_path)
    dup_a = Video(
        file_path=str(tmp_path / "a" / "同名电影.mkv"),
        file_name="同名电影.mkv",
        title="同名电影",
    )
    dup_b = Video(
        file_path=str(tmp_path / "b" / "同名电影.mkv"),
        file_name="同名电影.mkv",
        title="同名电影",
    )
    other = Video(
        file_path=str(tmp_path / "别的电影.mkv"),
        file_name="别的电影.mkv",
        title="别的电影",
    )
    db.add_all([dup_a, dup_b, other])
    db.commit()

    monkeypatch.setattr(
        video_scrape, "TmdbClient", lambda: _FakeTmdb(movie=_movie_detail())
    )
    bound = video_scrape.bind_series(db, dup_a.id, 42, "movie")
    db.commit()

    assert len(bound) == 2
    assert dup_a.tmdb_id == 42
    assert dup_b.tmdb_id == 42
    assert other.tmdb_id is None


def test_search_queries_release_name():
    """中文名.英文名.年份.SxxExx.压制信息 这类发布名应能退到各标题段。"""
    q = search_queries(
        "拜托请穿上，鹰峰同学.Haite Kudasai, Takamine-san.2025."
        "S01E01.2160p.BDRip.HEVC.10bit.FLAC.mkv"
    )
    assert q[0] == "拜托请穿上，鹰峰同学 Haite Kudasai, Takamine-san"
    assert "拜托请穿上，鹰峰同学" in q


def test_search_queries_keeps_title_year():
    """标题自带年份不能被当成发布年份截掉。"""
    assert search_queries("Blade Runner 2049.mkv")[0] == "Blade Runner 2049"
    assert search_queries("Blade Runner 2049.2017.2160p.BluRay.x265.mkv")[0] == "Blade Runner 2049"
    assert search_queries("1917.2019.2160p.BluRay.x265.mkv")[0] == "1917"


def test_search_queries_latin_not_split():
    """纯拉丁名不拆段，避免把 Show.Name 拆成 Show 搜到无关作品。"""
    assert search_queries("Show.Name.2025.S01E01.1080p.BluRay.x264.mkv") == ["Show Name"]
    assert search_queries("Inception.2010.1080p.BluRay.x264.mkv") == ["Inception"]


def test_search_queries_fallback_and_empty():
    assert search_queries("", "拜托请穿上，鹰峰同学") == ["拜托请穿上，鹰峰同学"]
    assert search_queries(None) == []
