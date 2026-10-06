"""特别篇（第 0 季）竖屏封面缺失：季海报必须真实落到季目录里。

实测故障：`裸体主义沙滩上的修学旅行！！` 的 S00E01 目录里只有 `-frame-v3.jpg`
（截帧），没有海报；而 `第 0 季/` 下也没有 tvshow-poster.jpg（总海报在剧根）。
TMDB 其实有第 0 季海报。

链路：
  download_all_covers 见 find_shared_vertical_poster 有值（剧根总海报）就跳过
  下载分集竖封面；而 get_cover 回退剧根时用 get_metadata_dir 的**重建路径**，
  与实际媒体目录对不上 → 两头落空 → 退化成截帧。
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
from fryfrog.services import video_assets as assets


@pytest.fixture()
def env(tmp_path, monkeypatch):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    lib_root = tmp_path / "media"
    show_root = lib_root / "某剧"
    season_dir = show_root / "第 0 季"
    ep_dir = season_dir / "第 1 集"
    ep_dir.mkdir(parents=True)

    # 剧根总海报存在（find_shared_vertical_poster 会找到它）
    (show_root / "tvshow-poster.jpg").write_bytes(b"ROOT-POSTER")

    lib = MediaLibrary(name="L", path=str(lib_root), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()
    series = VideoSeries(title="某剧", tmdb_id=93175)
    db.add(series)
    db.flush()
    ep = Video(
        file_path=str(ep_dir / "某剧 - S00E01.mp4"),
        file_name="某剧 - S00E01.mp4",
        title="某剧 S00E01",
        library_id=lib.id,
        series_id=series.id,
        tmdb_id=93175,
        media_type="tv",
        is_series=True,
        season_number=0,
        episode_number=1,
    )
    db.add(ep)
    db.commit()

    yield db, ep, season_dir, show_root
    db.close()


def test_ensure_season_poster_downloads_tmdb_season_poster(env, monkeypatch):
    """TMDB 有季海报时优先下载它。"""
    db, ep, season_dir, _ = env
    downloaded: list[Path] = []

    def fake_download(url, target, force=False):
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"TMDB-SEASON")
        downloaded.append(target)
        return True

    class _FakeClient:
        def get_season(self, tmdb_id, season):
            assert season == 0, "特别篇应查第 0 季"
            return {"poster_path": "/s0.jpg"}

        def image_url(self, path, size=None):
            return f"https://img{path}"

    monkeypatch.setattr(assets, "download_image", fake_download)
    monkeypatch.setattr("fryfrog.services.tmdb.TmdbClient", _FakeClient)

    path, created = assets.ensure_season_poster(db, ep)
    assert created is True
    assert path == season_dir / "tvshow-poster.jpg"
    assert path.read_bytes() == b"TMDB-SEASON", "应下载 TMDB 第 0 季海报"
    assert downloaded


def test_ensure_season_poster_copies_root_when_no_season_poster(env, monkeypatch):
    """TMDB 没有季海报时复制剧根总海报，避免季目录空着。"""
    db, ep, season_dir, show_root = env

    class _NoPosterClient:
        def get_season(self, tmdb_id, season):
            return {"poster_path": None}

        def image_url(self, path, size=None):
            return None

    monkeypatch.setattr("fryfrog.services.tmdb.TmdbClient", _NoPosterClient)

    path, created = assets.ensure_season_poster(db, ep)
    assert created is True
    assert path == season_dir / "tvshow-poster.jpg"
    assert path.read_bytes() == (show_root / "tvshow-poster.jpg").read_bytes()


def test_ensure_season_poster_is_idempotent(env):
    """已存在则不动、且 created=False（避免统计虚高）。"""
    db, ep, season_dir, _ = env
    existing = season_dir / "tvshow-poster.jpg"
    existing.write_bytes(b"ALREADY")

    path, created = assets.ensure_season_poster(db, ep)
    assert created is False
    assert path == existing
    assert existing.read_bytes() == b"ALREADY", "不该覆盖已有季海报"


def test_ensure_season_poster_skips_non_episode(env):
    db, ep, _, _ = env
    movie = Video(
        file_path=str(Path(ep.file_path).parent / "movie.mp4"),
        file_name="movie.mp4",
        title="movie",
        is_series=False,
        season_number=None,
    )
    assert assets.ensure_season_poster(db, movie) == (None, False)


def test_specials_get_shared_poster_after_ensure(env, monkeypatch):
    """补上季海报后，共享查找应命中季目录（而不是只命中剧根）。"""
    db, ep, season_dir, _ = env

    class _NoPosterClient:
        def get_season(self, tmdb_id, season):
            return {"poster_path": None}

        def image_url(self, path, size=None):
            return None

    monkeypatch.setattr("fryfrog.services.tmdb.TmdbClient", _NoPosterClient)
    assets.ensure_season_poster(db, ep)

    found = assets.find_shared_vertical_poster(db, ep)
    assert found == season_dir / "tvshow-poster.jpg", f"应命中季海报，实际 {found}"
