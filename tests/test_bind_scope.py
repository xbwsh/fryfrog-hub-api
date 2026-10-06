"""绑定连带范围：绝不能跨剧按标题批量改写。

实测故障：11 部无关剧各自的 S01E01（文件在不同目录）被一次 movie 绑定
全部改成同一部电影（tmdb 259413），因为旧实现按 `Video.title == video.title`
全库匹配，而它们当时标题相同。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from unittest.mock import patch

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services import video_scrape

MOVIE_DETAIL = {
    "id": 259413,
    "title": "甜美姐姐！",
    "original_title": "アマネェ!",
    "release_date": "2013-12-20",
    "credits": {"cast": [], "crew": []},
    "genres": [],
}


class _FakeClient:
    def __init__(self) -> None:
        self.movie_calls: list[int] = []

    def get_movie(self, tmdb_id: int):
        self.movie_calls.append(tmdb_id)
        return dict(MOVIE_DETAIL, id=tmdb_id)

    def get_tv(self, tmdb_id: int):
        return {"id": tmdb_id, "name": f"剧{tmdb_id}", "seasons": [], "credits": {"cast": []}}

    def image_url(self, path, size=None):
        return None


def _setup(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    # 三部不同的剧，各有一个 S01E01，且**当时标题相同**
    series = []
    videos = []
    for i, name in enumerate(("凡人修仙传", "乡下也就只有这些娱乐了", "傲娇好色")):
        folder = tmp_path / name
        folder.mkdir(parents=True)
        series.append(VideoSeries(title=name))
        db.add(series[-1])
        db.flush()
        v = Video(
            file_path=str(folder / f"{name} - S01E01.mp4"),
            file_name=f"{name} - S01E01.mp4",
            title="第 1 集",          # ← 关键：三集标题完全相同
            series_id=series[-1].id,
            series_name=name,
            is_series=True,
            season_number=1,
            episode_number=1,
        )
        db.add(v)
        videos.append(v)
    db.flush()
    return db, series, videos


def test_movie_bind_does_not_touch_other_series(tmp_path):
    """movie 绑定时，标题相同的其他剧分集绝不能被改写。"""
    db, series, videos = _setup(tmp_path)
    fake = _FakeClient()
    with patch.object(video_scrape, "TmdbClient", lambda: fake), \
         patch.object(video_scrape, "save_actors", lambda *a, **k: None):
        bound = video_scrape.bind_series(db, videos[0].id, 259413, "movie")
    db.commit()

    assert [v.id for v in bound] == [videos[0].id], "只应绑定目标这一条"
    assert videos[0].title == "甜美姐姐！"
    for other in videos[1:]:
        db.refresh(other)
        assert other.title == "第 1 集", f"{other.file_name} 被误改"
        assert other.tmdb_id is None, f"{other.file_name} 被误绑"


def test_movie_bind_groups_only_same_directory(tmp_path):
    """同一目录下的分片（CD1/CD2）仍应一起绑定。"""
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    folder = tmp_path / "movie"
    folder.mkdir()
    a = Video(file_path=str(folder / "movie.CD1.mp4"), file_name="movie.CD1.mp4", title="某电影")
    b = Video(file_path=str(folder / "movie.CD2.mp4"), file_name="movie.CD2.mp4", title="某电影")
    other_dir = tmp_path / "elsewhere"
    other_dir.mkdir()
    c = Video(file_path=str(other_dir / "movie.mp4"), file_name="movie.mp4", title="某电影")
    db.add_all([a, b, c])
    db.flush()

    fake = _FakeClient()
    with patch.object(video_scrape, "TmdbClient", lambda: fake), \
         patch.object(video_scrape, "save_actors", lambda *a, **k: None):
        bound = video_scrape.bind_series(db, a.id, 259413, "movie")
    db.commit()

    ids = sorted(v.id for v in bound)
    assert ids == sorted([a.id, b.id]), "同目录分片应一起绑"
    db.refresh(c)
    assert c.tmdb_id is None, "不同目录的同名文件不能被连带"


def test_tv_bind_still_covers_whole_series(tmp_path):
    """tv 绑定仍应覆盖同剧全部分集（这是期望行为）。"""
    db, series, videos = _setup(tmp_path)
    second = Video(
        file_path=str(tmp_path / "凡人修仙传" / "凡人修仙传 - S01E02.mp4"),
        file_name="凡人修仙传 - S01E02.mp4",
        title="第 2 集",
        series_id=series[0].id,
        series_name="凡人修仙传",
        is_series=True,
        season_number=1,
        episode_number=2,
    )
    db.add(second)
    db.flush()

    fake = _FakeClient()
    with patch.object(video_scrape, "TmdbClient", lambda: fake), \
         patch.object(video_scrape, "save_actors", lambda *a, **k: None), \
         patch.object(video_scrape, "_apply_tv_detail", lambda db_, v, d, c: setattr(v, "tmdb_id", d["id"])):
        bound = video_scrape.bind_series(db, videos[0].id, 318729, "tv")
    db.commit()

    assert {v.id for v in bound} == {videos[0].id, second.id}, "同剧分集都应覆盖"
    for other in videos[1:]:
        db.refresh(other)
        assert other.tmdb_id is None, "其他剧不受影响"
