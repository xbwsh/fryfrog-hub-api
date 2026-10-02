from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video
from fryfrog.services import video_scan, video_service as vs


class _FakeProbe:
    """扫描不需要真实 ffmpeg，时长/分辨率返回空即可。"""

    def probe_video_duration(self, path):
        return None

    def probe_video_resolution(self, path):
        return None


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

    video_scan.scan_video_library(db, lib)
    db.commit()

    assert frame.exists()
    assert db.scalar(select(Video)).cover_art_path is None


def test_local_candidates_order(tmp_path):
    """无前缀命名排在 {base}-poster 之后，thumb 只作封面最后兜底。"""
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    video = Video(file_path=str(tmp_path / "a" / "adn-499.mp4"), file_name="adn-499.mp4", title="x")
    db.add(video)
    db.flush()
    poster_names = [p.name for p in vs.local_poster_candidates(db, video)]
    assert poster_names == ["adn-499-poster.jpg", "adn-499-poster.jpg", "poster.jpg", "folder.jpg", "thumb.jpg"]
    fanart_names = [p.name for p in vs.local_fanart_candidates(db, video)]
    assert fanart_names == ["adn-499-fanart.jpg", "adn-499-fanart.jpg", "fanart.jpg"]
