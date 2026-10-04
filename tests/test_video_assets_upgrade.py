from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video
from fryfrog.services import video_assets as assets
from fryfrog.services import video_scan
from fryfrog.services import video_service as vs


def _db(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="库", path=str(tmp_path), type="VIDEO")
    session.add(lib)
    session.flush()
    return session, lib


def _mkvideo(session, lib, rel: str, **kw) -> Video:
    video = Video(
        file_path=str((Path(lib.path) / rel).resolve()),
        file_name=Path(rel).name,
        library_id=lib.id,
        **kw,
    )
    session.add(video)
    session.flush()
    return video


def test_rename_show_dir_movie(tmp_path):
    """重新刮削后剧名变化：电影目录原地改名，不再新建目录搬视频。"""
    for name in ("初恋时间/初恋时间.mkv", "初恋时间/x.mkv"):
        p = tmp_path / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"fake")
    session, lib = _db(tmp_path)
    video = _mkvideo(session, lib, "初恋时间/初恋时间.mkv", title="初恋詩間")
    session.flush()
    assert vs.rename_show_dir(session, video) is True
    session.commit()
    assert (tmp_path / "初恋詩間").is_dir()
    assert not (tmp_path / "初恋时间").exists()
    assert video.file_path.startswith(str((tmp_path / "初恋詩間").resolve()))


def test_upgrade_migrates_legacy_metadata_dir_assets(tmp_path):
    """老方案素材写在 metadata 目录：重新刮削后素材与视频归置到新剧名目录。"""
    video_path = tmp_path / "初恋时间" / "初恋时间.mkv"
    video_path.parent.mkdir(parents=True, exist_ok=True)
    video_path.write_bytes(b"fake")
    # 老方案素材（title 初恋詩間 时的旧 metadata 目录）
    legacy = tmp_path / "初恋詩間"
    legacy.mkdir(parents=True, exist_ok=True)
    (legacy / "初恋时间.nfo").write_text("<movie><title>x</title></movie>", encoding="utf-8")
    (legacy / "初恋时间-poster.jpg").write_bytes(b"\xff\xd8\xff\xe0old")
    session, lib = _db(tmp_path)
    video = _mkvideo(session, lib, "初恋时间/初恋时间.mkv", title="初恋詩間")
    session.flush()
    assets.upgrade_legacy_assets(session, video)
    session.commit()
    target = tmp_path / "初恋詩間"
    assert (target / "初恋时间.mkv").exists()
    assert (target / "初恋时间.nfo").exists()
    assert (target / "poster.jpg").exists()
    assert not (tmp_path / "初恋时间").exists()


def test_upgrade_collects_stray_assets_left_by_old_organize(tmp_path):
    """旧 organize 已把视频搬去新名目录、素材留在旧名目录：重刮后回收素材并清空壳。"""
    video_path = tmp_path / "初恋詩間" / "初恋詩間.mkv"
    video_path.parent.mkdir(parents=True, exist_ok=True)
    video_path.write_bytes(b"fake")
    old_dir = tmp_path / "初恋时间"
    old_dir.mkdir(parents=True, exist_ok=True)
    (old_dir / "初恋詩間.nfo").write_text("<movie><title>y</title></movie>", encoding="utf-8")
    (old_dir / "初恋詩間-poster.jpg").write_bytes(b"\xff\xd8\xff\xe0old")
    session, lib = _db(tmp_path)
    video = _mkvideo(session, lib, "初恋詩間/初恋詩間.mkv", title="初恋詩間")
    session.flush()
    assets.upgrade_legacy_assets(session, video)
    session.commit()
    assert (video_path.parent / "初恋詩間.nfo").exists()
    assert (video_path.parent / "poster.jpg").exists()
    assert not (tmp_path / "初恋时间").exists()


def test_upgrade_moves_scattered_library_root_video_into_show_dir(tmp_path):
    """散放库根的视频：刮削后归置进「剧名」目录，素材随视频走，库根不留散件。"""
    vf = tmp_path / "初恋时间.mkv"
    vf.write_bytes(b"fake")
    (tmp_path / "初恋时间.nfo").write_text("<movie><title>x</title></movie>", encoding="utf-8")
    session, lib = _db(tmp_path)
    video = _mkvideo(session, lib, "初恋时间.mkv", title="初恋詩間")
    session.flush()
    assets.upgrade_legacy_assets(session, video)
    session.commit()
    target_dir = tmp_path / "初恋詩間"
    assert (target_dir / "初恋时间.mkv").exists()
    assert (target_dir / "初恋时间.nfo").exists()
    assert not (tmp_path / "初恋时间.mkv").exists()
    assert video.file_path.startswith(str(target_dir.resolve()))


def test_cleanup_removes_orphan_asset_dirs_but_keeps_referenced_or_video_dirs(tmp_path):
    """只有素材没有视频的孤儿目录要清理；被 DB 引用或含视频的目录保留。"""
    session, lib = _db(tmp_path)
    orphan = tmp_path / "test1"
    orphan.mkdir(parents=True, exist_ok=True)
    (orphan / "片段.nfo").write_text("<movie><title>x</title></movie>", encoding="utf-8")
    (orphan / "poster.jpg").write_bytes(b"\xff\xd8\xff\xe0img")
    referenced = tmp_path / "被引用"
    referenced.mkdir(parents=True, exist_ok=True)
    (referenced / "poster.jpg").write_bytes(b"\xff\xd8\xff\xe0img")
    ref_video = _mkvideo(
        session, lib, "被引用/x.mkv", title="被引用",
        cover_art_path=str((referenced / "poster.jpg").resolve()),
    )
    session.flush()
    # 有视频的目录
    has_video = tmp_path / "有视频"
    has_video.mkdir(parents=True, exist_ok=True)
    (has_video / "a.mkv").write_bytes(b"fake")
    assert video_scan.cleanup_orphan_asset_dirs(session, tmp_path) == 1
    session.commit()
    assert not (tmp_path / "test1").exists()
    assert (tmp_path / "被引用").exists()
    assert (tmp_path / "有视频").exists()
