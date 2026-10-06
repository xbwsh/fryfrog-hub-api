"""复现：两个库指向同一批文件（或路径重叠）时的 UNIQUE 冲突。"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")
os.environ.setdefault("SCAN_MISSING_GRACE_SECONDS", "3600")

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video
from fryfrog.services import video_scan


class _FakeProbe:
    def probe_video_duration(self, path):
        return 60.0

    def probe_video_resolution(self, path):
        return (1920, 1080)


def test_same_file_owned_by_another_library_does_not_crash(tmp_path, monkeypatch):
    """同一路径已被别的库入库时，重扫必须认领该行而不是新建（会撞 UNIQUE）。"""
    monkeypatch.setattr(video_scan, "get_media_probe", lambda: _FakeProbe())
    shared = tmp_path / "shared"
    shared.mkdir()
    (shared / "a.mp4").write_bytes(b"x" * 100)

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    lib_a = MediaLibrary(name="A", path=str(shared), type="VIDEO", enable_scraping=False)
    lib_b = MediaLibrary(name="B", path=str(shared), type="VIDEO", enable_scraping=False)
    db.add_all([lib_a, lib_b])
    db.commit()

    video_scan.scan_video_library(db, lib_a)
    db.commit()
    assert db.scalar(select(Video)).library_id == lib_a.id

    # 第二个库扫同一批文件：不能抛 IntegrityError
    video_scan.scan_video_library(db, lib_b)
    db.commit()

    rows = db.scalars(select(Video)).all()
    assert len(rows) == 1, f"应只有一行，实际 {len(rows)}"
    assert rows[0].library_id == lib_b.id, "后扫的库应认领该行"
