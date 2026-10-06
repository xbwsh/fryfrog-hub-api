"""残留记录清理：文件已不存在的行（含空剧组），以及不能误删的护栏。

实测场景：用户手动删掉一批素材后重新刮削，数据库里仍留着指向旧路径的行，
库视图里一直显示已经不存在的条目。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries, WatchProgress
from fryfrog.services.video_scan import (
    purge_missing_videos,
    stale_video_ids,
)


@pytest.fixture()
def env(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    media = tmp_path / "media"
    media.mkdir()
    lib = MediaLibrary(name="L", path=str(media), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()

    def add_video(name: str, *, series=None, exists=True, **kw):
        path = media / name
        if exists:
            path.write_bytes(b"x" * 64)
        v = Video(
            file_path=str(path),
            file_name=name,
            title=name,
            library_id=lib.id,
            series_id=series.id if series else None,
            **kw,
        )
        db.add(v)
        return v

    yield db, lib, media, add_video
    db.close()


def test_stale_video_ids_reports_missing_files(env):
    db, lib, media, add_video = env
    alive = add_video("a.mp4")
    gone = add_video("b.mp4", exists=False)
    db.commit()

    ids = stale_video_ids(db, lib)
    assert ids == [gone.id], f"只应报告文件缺失的行，实际 {ids}"
    assert alive.id not in ids


def test_purge_deletes_rows_and_child_rows(env):
    """删除时必须先清子表，否则 foreign_keys=ON 下会 IntegrityError。"""
    db, lib, media, add_video = env
    gone = add_video("b.mp4", exists=False)
    db.flush()
    db.add(WatchProgress(video_id=gone.id, position_seconds=12.0))
    db.commit()

    result = purge_missing_videos(db, lib, force=True)
    db.commit()

    assert result["deleted"] == 1, result
    assert db.get(Video, gone.id) is None
    assert db.scalars(select(WatchProgress).where(WatchProgress.video_id == gone.id)).all() == []


def test_purge_removes_series_that_lost_all_episodes(env):
    db, lib, media, add_video = env
    series = VideoSeries(title="空壳剧", tmdb_id=1)
    db.add(series)
    db.flush()
    add_video("gone1.mp4", series=series, exists=False)
    add_video("gone2.mp4", series=series, exists=False)
    db.commit()

    result = purge_missing_videos(db, lib, force=True)
    db.commit()
    assert result["deleted"] == 2
    assert result["orphanedSeries"] == 1, result
    assert db.get(VideoSeries, series.id) is None


def test_purge_keeps_series_with_remaining_episodes(env):
    db, lib, media, add_video = env
    series = VideoSeries(title="还有分集", tmdb_id=2)
    db.add(series)
    db.flush()
    add_video("keep.mp4", series=series)
    add_video("gone.mp4", series=series, exists=False)
    db.commit()

    result = purge_missing_videos(db, lib, force=True)
    db.commit()
    assert result["deleted"] == 1
    assert result["orphanedSeries"] == 0
    assert db.get(VideoSeries, series.id) is not None


def test_dry_run_reports_without_deleting(env):
    db, lib, media, add_video = env
    gone = add_video("gone.mp4", exists=False)
    db.commit()

    result = purge_missing_videos(db, lib, force=True, dry_run=True)
    db.commit()
    assert result["stale"] == 1
    assert result["deleted"] == 0
    assert db.get(Video, gone.id) is not None, "dry run 不该删任何行"


def test_guard_blocks_purge_when_disk_looks_wrong(env, monkeypatch):
    """盘掉线/挂载异常时（现有文件数远低于上轮）必须整体拒绝，避免清空库。"""
    db, lib, media, add_video = env
    add_video("gone.mp4", exists=False)
    db.commit()

    # 伪造上轮扫描存量很大 → 触发护栏
    from fryfrog.services import video_scan as vs

    monkeypatch.setattr(vs, "_read_scan_setting", lambda *a, **k: "1000")
    result = purge_missing_videos(db, lib, force=True)
    assert result["deleted"] == 0, result
    assert result["skipped"], "应给出跳过原因"
    assert db.scalars(select(Video)).all(), "护栏拦截时不能删任何行"


def test_purge_respects_grace_period_without_force(env):
    """不强制时，宽限期内（刚移走的文件）不删。"""
    db, lib, media, add_video = env
    gone = add_video("gone.mp4", exists=False)
    db.commit()

    result = purge_missing_videos(db, lib, force=False)
    assert result["deleted"] == 0, result
    assert db.get(Video, gone.id) is not None


def test_purge_noop_when_library_path_missing(env):
    db, lib, media, add_video = env
    add_video("gone.mp4", exists=False)
    db.commit()
    lib.path = str(media / "does-not-exist")

    result = purge_missing_videos(db, lib, force=True)
    assert result["deleted"] == 0
    assert result["skipped"], "库路径不存在时应跳过并说明"
