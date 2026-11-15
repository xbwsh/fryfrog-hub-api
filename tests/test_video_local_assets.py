from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video
from fryfrog.routers.video import assets as video_routes
from fryfrog.routers.video import scrape as scrape_routes
from fryfrog.schemas.video import VideoBindRequest
from fryfrog.services import video_scan, video_service as vs
from fryfrog.services.video_assets import upgrade_legacy_assets
from fryfrog.services import video_assets


class _FakeProbe:
    """扫描不需要真实 ffmpeg，时长/分辨率返回空即可。"""

    def probe_video_duration(self, path):
        return None

    def probe_video_resolution(self, path):
        return None


class _SuccessfulProbe:
    def probe_video_duration(self, path):
        return 600.0

    def probe_video_resolution(self, path):
        return (1920, 1080)


def _setup(tmp_path, monkeypatch, with_artwork: bool):
    folder = tmp_path / "adn-499"
    folder.mkdir()
    (folder / "adn-499.mp4").write_bytes(b"fake-video")
    if with_artwork:
        (folder / "poster.jpg").write_bytes(b"\xff\xd8\xff\xe0poster")
        (folder / "fanart.jpg").write_bytes(b"\xff\xd8\xff\xe0fanart")
        (folder / "thumb.jpg").write_bytes(b"\xff\xd8\xff\xe0thumb")
        (folder / "adn-499.nfo").write_text(
            "<movie><title>ADN-499</title></movie>", encoding="utf-8"
        )
    monkeypatch.setattr(video_scan, "get_media_probe", lambda: _FakeProbe())

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="剧集", path=str(tmp_path), type="VIDEO", enable_scraping=False)
    db.add(lib)
    db.flush()
    return db, lib, folder


def test_scan_adopts_unprefixed_artwork_and_cleans_frames(tmp_path, monkeypatch):
    """关掉扫描刮削的库：本地 poster.jpg/fanart.jpg 要被识别，冗余 frame-v3 要被清理。"""
    db, lib, folder = _setup(tmp_path, monkeypatch, with_artwork=True)
    frame = folder / "adn-499-frame-v3.jpg"
    fanart_frame = folder / "adn-499-fanart-frame-v3.jpg"
    frame.write_bytes(b"frame")
    fanart_frame.write_bytes(b"frame")

    video_scan.scan_video_library(db, lib)
    db.commit()

    video = db.scalar(select(Video))
    assert video is not None
    assert Path(video.cover_art_path).samefile(folder / "poster.jpg")
    assert Path(video.backdrop_local_path).samefile(folder / "fanart.jpg")
    assert not frame.exists()
    assert not fanart_frame.exists()


def test_scan_keeps_frame_when_referenced_by_db(tmp_path, monkeypatch):
    """手动选帧的结果（cover_art_path 指向 frame-v3）不能被扫描删掉。"""
    db, lib, folder = _setup(tmp_path, monkeypatch, with_artwork=True)
    frame = folder / "adn-499-frame-v3.jpg"
    frame.write_bytes(b"frame")
    video = Video(
        file_path=str((folder / "adn-499.mp4").resolve()),
        file_name="adn-499.mp4",
        title="adn-499",
        cover_art_path=str(frame),
        library_id=lib.id,
    )
    db.add(video)
    db.flush()

    video_scan.scan_video_library(db, lib)
    db.commit()

    assert frame.exists()
    assert Path(db.scalar(select(Video)).cover_art_path).samefile(frame)


def test_scan_keeps_frame_when_no_local_artwork(tmp_path, monkeypatch):
    """没有本地正式素材时，frame-v3 是唯一封面，保留。"""
    db, lib, folder = _setup(tmp_path, monkeypatch, with_artwork=False)
    frame = folder / "adn-499-frame-v3.jpg"
    frame.write_bytes(b"frame")
    (folder / "thumb.jpg").write_bytes(b"horizontal thumbnail")

    video_scan.scan_video_library(db, lib)
    db.commit()

    assert frame.exists()


@pytest.mark.parametrize("manual_frame", [False, True])
def test_scan_checks_cached_episode_frame_when_only_the_season_poster_changes(
    tmp_path, monkeypatch, manual_frame,
):
    db, lib, folder = _setup(tmp_path, monkeypatch, with_artwork=False)
    monkeypatch.setattr(video_scan, "get_media_probe", _SuccessfulProbe)
    source = folder / "示例动画 #1.mp4"
    (folder / "adn-499.mp4").rename(source)
    frame = source.with_name("示例动画 #1-frame-v3.jpg")
    frame.write_bytes(b"cached")
    video_scan.scan_video_library(db, lib)
    db.commit()
    ep = db.scalar(select(Video))
    assert frame.is_file()
    if manual_frame:
        ep.cover_art_path = str(frame)
        db.commit()

    season_dir = vs.get_season_dir(db, ep)
    season_dir.mkdir(parents=True, exist_ok=True)
    poster = season_dir / "tvshow-poster.jpg"
    poster.write_bytes(b"season poster")
    monkeypatch.setattr(video_assets, "parse_nfo", lambda *_: pytest.fail("媒体未变不应重读 NFO"))
    assert video_scan.scan_video_library(db, lib) == (1, 0)
    db.commit()
    assert frame.is_file() is manual_frame
    monkeypatch.setattr(video_routes, "get_visible_video", lambda db, id: db.get(Video, id))
    assert Path(video_routes.get_cover(db, ep.id).path) == poster


@pytest.mark.parametrize("downloaded", [False, True])
def test_binding_cleans_frame_only_after_local_show_poster_exists(tmp_path, monkeypatch, downloaded):
    db, lib, folder = _setup(tmp_path, monkeypatch, with_artwork=False)
    source = folder / "示例动画 #1.mp4"
    (folder / "adn-499.mp4").rename(source)
    frame = source.with_name("示例动画 #1-frame-v3.jpg")
    frame.write_bytes(b"cached")
    video_scan.scan_video_library(db, lib)
    db.commit()
    ep = db.scalar(select(Video))

    def download_show_poster(session, videos):
        if downloaded:
            root = vs.get_series_root_dir(session, videos)
            root.mkdir(parents=True, exist_ok=True)
            (root / "tvshow-poster.jpg").write_bytes(b"new poster")

    monkeypatch.setattr(scrape_routes, "_require_visible", lambda *_: None)
    monkeypatch.setattr(scrape_routes, "submit_job", lambda key, stage, total, work, **kw: work(db))
    monkeypatch.setattr(scrape_routes.scrape, "bind_series", lambda *_: [ep])
    monkeypatch.setattr(scrape_routes, "_download_root_art_if_series", download_show_poster)
    monkeypatch.setattr(video_assets, "upgrade_legacy_assets", lambda *_: None)
    bind = VideoBindRequest(tmdbId=123, mediaType="tv")
    assert scrape_routes.tmdb_bind(db, ep.id, bind).success
    assert frame.is_file() is not downloaded


def test_local_candidates_order(tmp_path):
    """无前缀命名排在 {base}-poster 之后，thumb 只作封面最后兜底。"""
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    video = Video(file_path=str(tmp_path / "a" / "adn-499.mp4"), file_name="adn-499.mp4", title="x")
    db.add(video)
    db.flush()
    poster_names = [p.name for p in vs.local_poster_candidates(db, video)]
    assert poster_names == ["adn-499-poster.jpg", "poster.jpg", "folder.jpg", "thumb.jpg", "adn-499-poster.jpg", "poster.jpg"]
    fanart_names = [p.name for p in vs.local_fanart_candidates(db, video)]
    assert fanart_names == ["adn-499-fanart.jpg", "fanart.jpg", "adn-499-fanart.jpg"]


def test_two_movies_use_private_artwork_over_saved_shared_paths(tmp_path, monkeypatch):
    db, lib, folder = _setup(tmp_path, monkeypatch, with_artwork=True)
    (folder / "other.mp4").write_bytes(b"fake-video")
    video_scan.scan_video_library(db, lib)
    db.commit()
    movies = db.scalars(select(Video).order_by(Video.file_name)).all()
    assert len(movies) == 2

    # 先保留扫描记下的旧共享路径，再放入影片专属素材。
    monkeypatch.setattr(video_routes, "get_visible_video", lambda db, id: db.get(Video, id))
    for movie in movies:
        base = Path(movie.file_name).stem
        poster = vs.get_poster_path(db, movie)
        fanart = vs.get_fanart_path(db, movie)
        assert poster.name == f"{base}-poster.jpg"
        assert fanart.name == f"{base}-fanart.jpg"
        poster.write_bytes(movie.file_name.encode())
        fanart.write_bytes(movie.file_name.encode())
        assert Path(video_routes.get_cover(db, movie.id).path) == poster
        assert Path(video_routes.get_fanart(db, movie.id).path) == fanart

    # 素材整理不能把专属素材收敛回共享文件。
    upgrade_legacy_assets(db, movies[0])
    assert list(tmp_path.rglob("adn-499-poster.jpg"))
    assert list(tmp_path.rglob("adn-499-fanart.jpg"))
    assert (folder / "poster.jpg").is_file()
