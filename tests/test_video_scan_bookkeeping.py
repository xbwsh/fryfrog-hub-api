"""扫描簿记：删除宽限期 + 磁盘异常护栏。

对应真实故障场景：媒体盘/挂载临时不可用时，一轮扫描不能把整库记录清空。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")
os.environ.setdefault("SCAN_MISSING_GRACE_SECONDS", "4")

from pathlib import Path

from sqlalchemy import create_engine, event, func, select, text
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base, _ensure_columns
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video
from fryfrog.services import video_scan


class _FakeProbe:
    def __init__(self) -> None:
        self.duration_calls = 0

    def probe_video_duration(self, path):
        self.duration_calls += 1
        return 600.0

    def probe_video_resolution(self, path):
        return (1920, 1080)


def _engine_with_fk():
    """与生产一致：连接开启 foreign_keys（否则删视频时的 FK 冲突测不出来）。"""
    engine = create_engine("sqlite://")

    @event.listens_for(engine, "connect")
    def _fk_on(dbapi_conn, _):
        cur = dbapi_conn.cursor()
        cur.execute("PRAGMA foreign_keys=ON")
        cur.close()

    Base.metadata.create_all(engine)
    return engine


def _setup(tmp_path, monkeypatch, count: int = 1):
    probe = _FakeProbe()
    monkeypatch.setattr(video_scan, "get_media_probe", lambda: probe)
    for i in range(count):
        folder = tmp_path / f"show-{i}"
        folder.mkdir()
        (folder / f"show-{i}.mp4").write_bytes(b"fake-video")

    engine = _engine_with_fk()
    db = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="剧集", path=str(tmp_path), type="VIDEO", enable_scraping=False)
    db.add(lib)
    db.flush()
    return db, lib, probe


def _count(db) -> int:
    return db.scalar(select(func.count(Video.id))) or 0


def test_missing_file_is_deleted_after_grace(tmp_path, monkeypatch):
    """真实时序：本轮标记缺失 → 文件仍缺失且宽限期满 → 下一轮才删行。"""
    db, lib, _ = _setup(tmp_path, monkeypatch, count=2)
    victim = tmp_path / "show-0" / "show-0.mp4"

    video_scan.scan_video_library(db, lib)
    db.commit()
    assert _count(db) == 2

    # 第一轮发现文件没了：只标记，不删
    victim.unlink()
    video_scan.scan_video_library(db, lib)
    db.commit()
    assert _count(db) == 2, "宽限期内不能删行"
    assert db.scalar(select(Video.missing_since)) is not None

    # 下一轮文件仍然缺失，且宽限期已过（按挂钟时间算，这里把标记挪到过去，省去 sleep）
    row = db.scalar(select(Video))
    row.missing_since = row.missing_since.replace(year=row.missing_since.year - 1)
    db.flush()
    video_scan._resolve_missing(db, lib, [row.id], seen=1)
    db.commit()
    assert _count(db) == 1, "宽限期满且仍缺失 → 删行"


def test_unavailable_mount_does_not_wipe_library(tmp_path, monkeypatch):
    """护栏：本轮消失条数超过上轮存量的比例阈值时，整轮暂缓删除。"""
    db, lib, _ = _setup(tmp_path, monkeypatch, count=4)

    video_scan.scan_video_library(db, lib)
    db.commit()
    assert _count(db) == 4

    # 模拟挂载掉线：目录还在，内容几乎空
    for i in range(3):
        (tmp_path / f"show-{i}" / f"show-{i}.mp4").unlink()
    for row in db.scalars(select(Video)).all():
        row.missing_since = row.missing_since or None

    video_scan.scan_video_library(db, lib)
    db.commit()
    assert _count(db) == 4, "疑似磁盘异常时一条都不能删"


def test_empty_library_is_never_auto_wiped(tmp_path, monkeypatch):
    """文件全没了（挂载掉了）时护栏永久兜住：只剩空目录就不自动清库。"""
    db, lib, _ = _setup(tmp_path, monkeypatch, count=2)
    video_scan.scan_video_library(db, lib)
    db.commit()

    for i in range(2):
        (tmp_path / f"show-{i}" / f"show-{i}.mp4").unlink()
    for _ in range(3):
        video_scan.scan_video_library(db, lib)
        db.commit()
    assert _count(db) == 2, "空库视为挂载异常，永不自动清空"


def test_delete_resumes_when_library_still_has_content(tmp_path, monkeypatch):
    """库里还留着大部分文件时，宽限期满的缺失记录照常删除（护栏不误伤真实删除）。"""
    db, lib, _ = _setup(tmp_path, monkeypatch, count=3)
    video_scan.scan_video_library(db, lib)
    db.commit()

    (tmp_path / "show-0" / "show-0.mp4").unlink()
    video_scan.scan_video_library(db, lib)
    db.commit()
    assert _count(db) == 3

    victim = db.scalar(select(Video).where(Video.file_path.contains("show-0")))
    victim.missing_since = victim.missing_since.replace(year=victim.missing_since.year - 1)
    db.flush()

    # 文件仍然缺失且标记已过期 → 本轮判定删除；实见 2 条 vs 上轮 3 条，未触发护栏
    video_scan._resolve_missing(db, lib, [victim.id], seen=2)
    db.commit()
    assert _count(db) == 2, "2/3 实见，不该被护栏拦下"


def test_delete_video_with_children_satisfies_foreign_keys(tmp_path, monkeypatch):
    """删视频前必须先清子行：watch_progress/video_actors 有外键，生产开了 foreign_keys=ON。

    实测故障：`sqlite3.IntegrityError: FOREIGN KEY constraint failed`，
    导致整个库的扫描直接失败（Scan failed for library N）。
    """
    from fryfrog.models.video import VideoActor, WatchProgress

    db, lib, _ = _setup(tmp_path, monkeypatch, count=2)
    video_scan.scan_video_library(db, lib)
    db.commit()

    video = db.scalar(select(Video).where(Video.file_path.contains("show-0")))
    db.add(WatchProgress(user_id=1, video_id=video.id, position_seconds=12.0))
    db.add(VideoActor(video_id=video.id, name="测试演员"))
    db.commit()

    (tmp_path / "show-0" / "show-0.mp4").unlink()
    video_scan.scan_video_library(db, lib)
    db.commit()
    assert _count(db) == 2

    victim = db.scalar(select(Video).where(Video.file_path.contains("show-0")))
    victim.missing_since = victim.missing_since.replace(year=victim.missing_since.year - 1)
    db.flush()

    removed = video_scan._resolve_missing(db, lib, [victim.id], seen=1)
    db.commit()

    assert removed == 1
    assert _count(db) == 1
    assert db.scalar(select(func.count(WatchProgress.id))) == 0, "子行必须一并清掉"
    assert db.scalar(select(func.count(VideoActor.id))) == 0


def test_unchanged_file_skips_reprobe(tmp_path, monkeypatch):
    """mtime + size 未变时不再跑 ffprobe（扫描高频触发的主要开销）。"""
    db, lib, probe = _setup(tmp_path, monkeypatch, count=1)

    video_scan.scan_video_library(db, lib)
    db.commit()
    assert probe.duration_calls == 1

    video_scan.scan_video_library(db, lib)
    video_scan.scan_video_library(db, lib)
    db.commit()
    assert probe.duration_calls == 1, "重复扫描不应再次探测"

    target = tmp_path / "show-0" / "show-0.mp4"
    target.write_bytes(b"fake-video-v2")
    os.utime(target, (1, 1))  # 改 mtime，确保走到重探分支
    video = db.scalar(select(Video))
    video.duration_seconds = None
    db.flush()

    video_scan.scan_video_library(db, lib)
    db.commit()
    assert probe.duration_calls == 2


def test_scan_bookkeeping_written(tmp_path, monkeypatch):
    """扫描簿记落 SystemSetting，供护栏与诊断使用。"""
    db, lib, _ = _setup(tmp_path, monkeypatch, count=2)
    video_scan.scan_video_library(db, lib)
    db.commit()

    from fryfrog.models.library import SystemSetting

    keys = {row.key: row.value for row in db.scalars(select(SystemSetting)).all()}
    assert keys[f"video_scan.last_count.{lib.id}"] == "2"
    assert keys[f"video_scan.last_scan_at.{lib.id}"]
    assert Path(tmp_path).is_dir()


def test_ensure_columns_upgrades_old_schema(tmp_path):
    """老库补列：容器升级到新镜像时，旧 SQLite 库要能被就地补列而不是启动即崩。"""
    engine = create_engine(f"sqlite:///{tmp_path / 'old.db'}")
    # 模拟旧库：videos 只有历史列，且 library_id 是 NOT NULL 无默认值（SQLite 不允许补加）
    with engine.begin() as conn:
        conn.execute(
            text(
                "create table videos (id integer primary key, file_path varchar not null, "
                "file_name varchar not null, title varchar not null, duration_seconds float, "
                "resolution varchar, library_id integer not null)"
            )
        )
        conn.execute(
            text("create table media_libraries (id integer primary key, name varchar not null)")
        )
        conn.execute(text("insert into media_libraries (id, name) values (1, '旧库')"))

    _ensure_columns(engine)
    # 第二遍必须幂等（每次容器启动都会跑）
    _ensure_columns(engine)

    with engine.connect() as conn:
        cols = {row[1] for row in conn.execute(text("pragma table_info(videos)"))}
        lib_cols = {row[1] for row in conn.execute(text("pragma table_info(media_libraries)"))}
        names = [row[0] for row in conn.execute(text("select name from media_libraries"))]

    assert {"last_seen_at", "missing_since", "media_probed_mtime"} <= cols
    assert "sort_order" in lib_cols, "可补加的历史列也要补上"
    assert names == ["旧库"], "补列不得动数据"
