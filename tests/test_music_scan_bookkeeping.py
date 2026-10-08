"""音乐库扫描的删除保护：根路径检查 + 宽限期 + 磁盘异常护栏。

实测故障：音乐库是唯一没有保护的一类媒体——`iter_files` 对不存在的根返回空
列表，一次扫描就把整库记录（连带播放列表项/书签/播放统计）删光。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

from sqlalchemy import create_engine, event, func, select, text
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base, _ensure_columns
from fryfrog.models.library import MediaLibrary, SystemSetting
from fryfrog.models.music import MusicPlayStat, MusicSong
from fryfrog.services import music_scan


class _Settings:
    def __init__(self, grace: float = 0.0, ratio: float = 0.5) -> None:
        self.scan_missing_grace_seconds = grace
        self.scan_guard_min_ratio = ratio


class _FakeProbe:
    def probe_audio_info(self, path):
        return {"tags": {}, "duration": 180.0}


def _setup(tmp_path, monkeypatch, songs: int = 2, ratio: float = 0.5, grace: float = 0.0):
    monkeypatch.setattr(music_scan, "get_media_probe", lambda: _FakeProbe())
    monkeypatch.setattr(music_scan, "_extract_embedded_cover", lambda *a, **k: None)
    monkeypatch.setattr(music_scan, "get_settings", lambda: _Settings(grace=grace, ratio=ratio))

    engine = create_engine("sqlite://")

    @event.listens_for(engine, "connect")
    def _fk_on(dbapi_conn, _):
        cur = dbapi_conn.cursor()
        cur.execute("PRAGMA foreign_keys=ON")
        cur.close()

    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    for i in range(songs):
        folder = tmp_path / f"album-{i}"
        folder.mkdir()
        (folder / f"track-{i}.mp3").write_bytes(b"fake-audio")
    lib = MediaLibrary(name="音乐", path=str(tmp_path), type="MUSIC", enable_scraping=False)
    db.add(lib)
    db.flush()
    return db, lib


def _count(db) -> int:
    return db.scalar(select(func.count(MusicSong.id))) or 0


def _baseline(db, lib) -> str | None:
    return db.scalar(select(SystemSetting.value).where(SystemSetting.key == f"music_scan.last_count.{lib.id}"))


def _age_missing(db) -> None:
    """把缺失标记挪到一年前：让宽限期不再是拦截原因，只剩护栏在兜。"""
    for row in db.scalars(select(MusicSong)).all():
        if row.missing_since is not None:
            row.missing_since = row.missing_since.replace(year=row.missing_since.year - 1)
    db.flush()


def test_missing_root_skips_scan_and_keeps_rows(tmp_path, monkeypatch):
    db, lib = _setup(tmp_path, monkeypatch, songs=2)
    music_scan.scan_music_library(db, lib)
    db.commit()
    assert _count(db) == 2

    lib.path = str(tmp_path / "unmounted")
    result = music_scan.scan_music_library(db, lib)
    db.commit()
    assert result["skipped"], "路径不存在必须显式跳过"
    assert _count(db) == 2, "路径不存在绝不能清库"


def test_missing_song_deleted_after_grace(tmp_path, monkeypatch):
    db, lib = _setup(tmp_path, monkeypatch, songs=2)
    music_scan.scan_music_library(db, lib)
    db.commit()

    victim = db.scalar(select(MusicSong).where(MusicSong.file_path.contains("album-0")))
    db.add(MusicPlayStat(song_id=victim.id, user_id=1, play_count=3))
    db.commit()
    Path(victim.file_path).unlink()

    music_scan.scan_music_library(db, lib)
    db.commit()
    assert _count(db) == 2, "宽限期内不能删行"
    assert db.scalar(select(MusicSong).where(MusicSong.id == victim.id)).missing_since is not None

    _age_missing(db)
    music_scan.scan_music_library(db, lib)
    db.commit()
    assert _count(db) == 1, "宽限期满且仍缺失 → 删行"
    assert db.scalar(select(func.count(MusicPlayStat.id))) == 0, "播放统计子行必须一并清掉"


def test_returning_file_clears_missing_marker(tmp_path, monkeypatch):
    db, lib = _setup(tmp_path, monkeypatch, songs=1)
    music_scan.scan_music_library(db, lib)
    db.commit()

    song = db.scalar(select(MusicSong))
    path = Path(song.file_path)
    path.unlink()
    music_scan.scan_music_library(db, lib)
    db.commit()
    assert db.scalar(select(MusicSong).where(MusicSong.id == song.id)).missing_since is not None

    path.write_bytes(b"fake-audio-back")
    music_scan.scan_music_library(db, lib)
    db.commit()
    assert _count(db) == 1
    assert db.scalar(select(MusicSong).where(MusicSong.id == song.id)).missing_since is None


def test_empty_library_is_never_auto_wiped(tmp_path, monkeypatch):
    """空库（挂载掉线）必须被护栏永久兜住，且基线冻结不能变小。"""
    db, lib = _setup(tmp_path, monkeypatch, songs=3, ratio=0.5)
    music_scan.scan_music_library(db, lib)
    db.commit()
    assert _baseline(db, lib) == "3"

    for path in list(tmp_path.rglob("*.mp3")):
        path.unlink()

    for _ in range(3):
        music_scan.scan_music_library(db, lib)
        db.commit()
        _age_missing(db)

    assert _count(db) == 3, "空库视为挂载异常，永不自动清空"
    assert _baseline(db, lib) == "3", "拦截期间基线必须冻结，否则下一轮护栏失效"


def test_partial_deletion_still_applies(tmp_path, monkeypatch):
    """还有大半文件在时照常删（护栏不误伤真实删除）。"""
    db, lib = _setup(tmp_path, monkeypatch, songs=3, ratio=0.5)
    music_scan.scan_music_library(db, lib)
    db.commit()

    victim = db.scalar(select(MusicSong).where(MusicSong.file_path.contains("album-0")))
    Path(victim.file_path).unlink()
    music_scan.scan_music_library(db, lib)
    db.commit()
    assert _count(db) == 3

    _age_missing(db)
    music_scan.scan_music_library(db, lib)
    db.commit()
    assert _count(db) == 2, "2/3 实见，不该被护栏拦下"


def test_ratio_zero_is_manual_escape_hatch(tmp_path, monkeypatch):
    """确认要清空整库时把 SCAN_GUARD_MIN_RATIO 设为 0 即可放行。"""
    db, lib = _setup(tmp_path, monkeypatch, songs=2, ratio=0.0)
    music_scan.scan_music_library(db, lib)
    db.commit()

    for path in list(tmp_path.rglob("*.mp3")):
        path.unlink()
    music_scan.scan_music_library(db, lib)
    db.commit()
    _age_missing(db)
    music_scan.scan_music_library(db, lib)
    db.commit()

    assert _count(db) == 0
    assert _baseline(db, lib) == "0"


def test_ensure_columns_adds_missing_since_for_old_music_db(tmp_path):
    """老库（NAS 上升级）必须能就地补出 music_songs.missing_since，而不是启动即崩。"""
    engine = create_engine(f"sqlite:///{tmp_path / 'old-music.db'}")
    with engine.begin() as conn:
        conn.execute(
            text(
                "create table music_songs (id integer primary key, title varchar not null, "
                "file_path varchar not null, library_id integer)"
            )
        )

    _ensure_columns(engine)
    _ensure_columns(engine)  # 每次启动都会跑，必须幂等

    with engine.connect() as conn:
        cols = {row[1] for row in conn.execute(text("pragma table_info(music_songs)"))}
    assert "missing_since" in cols
