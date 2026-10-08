"""电子书/漫画/有声书扫描的删除保护：宽限期 + 磁盘异常护栏。

实测故障：这三类媒体是唯一没有保护的删除路径——挂载掉线（根目录变空）
一轮扫描就把整库记录（连带阅读进度）删光。视频库有 grace+ratio，音乐库
已补齐，这里覆盖其余三类。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

from sqlalchemy import create_engine, event, func, select, text
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base, _ensure_columns
from fryfrog.models.library import MediaLibrary, SystemSetting
from fryfrog.services import audiobook_scan, comic_scan, ebook_scan, scan_guard


class _Settings:
    def __init__(self, grace: float = 0.0, ratio: float = 0.5) -> None:
        self.scan_missing_grace_seconds = grace
        self.scan_guard_min_ratio = ratio


class _FakeProbe:
    def probe_audio_info(self, path):
        return {"tags": {}, "duration": 180.0}


def _engine():
    engine = create_engine("sqlite://")

    @event.listens_for(engine, "connect")
    def _fk_on(dbapi_conn, _):
        cur = dbapi_conn.cursor()
        cur.execute("PRAGMA foreign_keys=ON")
        cur.close()

    Base.metadata.create_all(engine)
    return engine


def _lib(db, path: Path, type_: str) -> MediaLibrary:
    lib = MediaLibrary(name="库", path=str(path), type=type_, enable_scraping=False)
    db.add(lib)
    db.flush()
    return lib


def _baseline(db, prefix: str, lib) -> str | None:
    return db.scalar(
        select(SystemSetting.value).where(SystemSetting.key == f"{prefix}.last_count.{lib.id}")
    )


def _age_missing(db, model) -> None:
    """把缺失标记挪到一年前：让宽限期不再是拦截原因，只剩护栏在兜。"""
    for row in db.scalars(select(model)).all():
        if row.missing_since is not None:
            row.missing_since = row.missing_since.replace(year=row.missing_since.year - 1)
    db.flush()


# ── 电子书 ────────────────────────────────────────────────


def _ebook_setup(tmp_path, monkeypatch, books: int = 3, ratio: float = 0.5, grace: float = 0.0):
    monkeypatch.setattr(scan_guard, "get_settings", lambda: _Settings(grace=grace, ratio=ratio))
    db = sessionmaker(bind=_engine())()
    for i in range(books):
        (tmp_path / f"book-{i}.pdf").write_bytes(b"fake-pdf")
    lib = _lib(db, tmp_path, "EBOOK")
    return db, lib


def test_ebook_missing_root_skips_scan(tmp_path, monkeypatch):
    db, lib = _ebook_setup(tmp_path, monkeypatch, books=2)
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()
    from fryfrog.models.ebook import Ebook

    assert db.scalar(select(func.count(Ebook.id))) == 2

    lib.path = str(tmp_path / "unmounted")
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()
    assert db.scalar(select(func.count(Ebook.id))) == 2, "路径不存在绝不能清库"


def test_ebook_grace_then_delete(tmp_path, monkeypatch):
    from fryfrog.models.ebook import Ebook, EbookProgress

    db, lib = _ebook_setup(tmp_path, monkeypatch, books=3)
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()

    victim = db.scalar(select(Ebook).where(Ebook.file_path.contains("book-0")))
    db.add(EbookProgress(user_id=1, ebook_id=victim.id, position_percent=42.0))
    db.commit()
    Path(victim.file_path).unlink()

    ebook_scan.scan_ebook_library(db, lib)
    db.commit()
    assert db.scalar(select(func.count(Ebook.id))) == 3, "宽限期内不能删行"
    assert db.scalar(select(Ebook).where(Ebook.id == victim.id)).missing_since is not None

    _age_missing(db, Ebook)
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()
    assert db.scalar(select(func.count(Ebook.id))) == 2, "宽限期满且仍缺失 → 删行"
    assert db.scalar(select(func.count(EbookProgress.id))) == 0, "阅读进度必须一并清掉"


def test_ebook_empty_library_is_never_auto_wiped(tmp_path, monkeypatch):
    """空库（挂载掉线）必须被护栏永久兜住，且基线冻结不能变小。"""
    from fryfrog.models.ebook import Ebook

    db, lib = _ebook_setup(tmp_path, monkeypatch, books=3, ratio=0.5)
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()
    assert _baseline(db, "ebook_scan", lib) == "3"

    for path in list(tmp_path.glob("*.pdf")):
        path.unlink()

    for _ in range(3):
        ebook_scan.scan_ebook_library(db, lib)
        db.commit()
        _age_missing(db, Ebook)

    assert db.scalar(select(func.count(Ebook.id))) == 3, "空库视为挂载异常，永不自动清空"
    assert _baseline(db, "ebook_scan", lib) == "3", "拦截期间基线必须冻结"


def test_ebook_returning_file_clears_marker(tmp_path, monkeypatch):
    from fryfrog.models.ebook import Ebook

    db, lib = _ebook_setup(tmp_path, monkeypatch, books=1)
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()

    book = db.scalar(select(Ebook))
    path = Path(book.file_path)
    path.unlink()
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()
    assert db.scalar(select(Ebook).where(Ebook.id == book.id)).missing_since is not None

    path.write_bytes(b"fake-pdf-back")
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()
    assert db.scalar(select(Ebook).where(Ebook.id == book.id)).missing_since is None


def test_ebook_ratio_zero_is_manual_escape_hatch(tmp_path, monkeypatch):
    from fryfrog.models.ebook import Ebook

    db, lib = _ebook_setup(tmp_path, monkeypatch, books=2, ratio=0.0)
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()

    for path in list(tmp_path.glob("*.pdf")):
        path.unlink()
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()
    _age_missing(db, Ebook)
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()

    assert db.scalar(select(func.count(Ebook.id))) == 0
    assert _baseline(db, "ebook_scan", lib) == "0"


# ── 漫画 ──────────────────────────────────────────────────


def _comic_setup(tmp_path, monkeypatch, books: int = 3, ratio: float = 0.5, grace: float = 0.0):
    monkeypatch.setattr(scan_guard, "get_settings", lambda: _Settings(grace=grace, ratio=ratio))
    db = sessionmaker(bind=_engine())()
    for name in ("alpha", "beta", "gamma")[:books]:
        (tmp_path / f"{name}.cbz").write_bytes(b"fake-zip")
    lib = _lib(db, tmp_path, "COMIC")
    return db, lib


def test_comic_missing_root_skips_scan(tmp_path, monkeypatch):
    from fryfrog.models.comic import Comic

    db, lib = _comic_setup(tmp_path, monkeypatch, books=2)
    comic_scan.scan_comic_library(db, lib)
    db.commit()
    assert db.scalar(select(func.count(Comic.id))) == 2

    lib.path = str(tmp_path / "unmounted")
    comic_scan.scan_comic_library(db, lib)
    db.commit()
    assert db.scalar(select(func.count(Comic.id))) == 2, "路径不存在绝不能清库"


def test_comic_grace_then_delete(tmp_path, monkeypatch):
    from fryfrog.models.comic import Comic, ComicProgress

    db, lib = _comic_setup(tmp_path, monkeypatch, books=3)
    comic_scan.scan_comic_library(db, lib)
    db.commit()

    victim = db.scalar(select(Comic).where(Comic.book_path.contains("alpha")))
    db.add(ComicProgress(user_id=1, comic_id=victim.id, page_index=5))
    db.commit()
    Path(tmp_path / "alpha.cbz").unlink()

    comic_scan.scan_comic_library(db, lib)
    db.commit()
    assert db.scalar(select(func.count(Comic.id))) == 3, "宽限期内不能删行"
    assert db.scalar(select(Comic).where(Comic.id == victim.id)).missing_since is not None

    _age_missing(db, Comic)
    comic_scan.scan_comic_library(db, lib)
    db.commit()
    assert db.scalar(select(func.count(Comic.id))) == 2, "宽限期满且仍缺失 → 删行"
    assert db.scalar(select(func.count(ComicProgress.id))) == 0, "阅读进度必须一并清掉"


def test_comic_empty_library_is_never_auto_wiped(tmp_path, monkeypatch):
    from fryfrog.models.comic import Comic

    db, lib = _comic_setup(tmp_path, monkeypatch, books=3, ratio=0.5)
    comic_scan.scan_comic_library(db, lib)
    db.commit()
    assert _baseline(db, "comic_scan", lib) == "3"

    for path in list(tmp_path.glob("*.cbz")):
        path.unlink()

    for _ in range(3):
        comic_scan.scan_comic_library(db, lib)
        db.commit()
        _age_missing(db, Comic)

    assert db.scalar(select(func.count(Comic.id))) == 3, "空库视为挂载异常，永不自动清空"
    assert _baseline(db, "comic_scan", lib) == "3", "拦截期间基线必须冻结"


# ── 有声书 ────────────────────────────────────────────────


def _audiobook_setup(tmp_path, monkeypatch, books: int = 3, ratio: float = 0.5, grace: float = 0.0):
    monkeypatch.setattr(scan_guard, "get_settings", lambda: _Settings(grace=grace, ratio=ratio))
    monkeypatch.setattr(audiobook_scan, "get_media_probe", lambda: _FakeProbe())
    db = sessionmaker(bind=_engine())()
    for name in ("alpha", "beta", "gamma")[:books]:
        folder = tmp_path / name
        folder.mkdir()
        (folder / "track.mp3").write_bytes(b"fake-audio")
    lib = _lib(db, tmp_path, "AUDIOBOOK")
    return db, lib


def test_audiobook_missing_root_skips_scan(tmp_path, monkeypatch):
    from fryfrog.models.audiobook import Audiobook

    db, lib = _audiobook_setup(tmp_path, monkeypatch, books=2)
    audiobook_scan.scan_audiobook_library(db, lib)
    db.commit()
    assert db.scalar(select(func.count(Audiobook.id))) == 2

    lib.path = str(tmp_path / "unmounted")
    audiobook_scan.scan_audiobook_library(db, lib)
    db.commit()
    assert db.scalar(select(func.count(Audiobook.id))) == 2, "路径不存在绝不能清库"


def test_audiobook_grace_then_delete_fixes_zombie(tmp_path, monkeypatch):
    """目录整个被删（旧代码 OSError 兜 True → 永不清理的僵尸行）：宽限期满应删。"""
    from fryfrog.models.audiobook import Audiobook, AudiobookProgress

    db, lib = _audiobook_setup(tmp_path, monkeypatch, books=3)
    audiobook_scan.scan_audiobook_library(db, lib)
    db.commit()

    victim = db.scalar(select(Audiobook).where(Audiobook.book_path.contains("alpha")))
    db.add(AudiobookProgress(user_id=1, audiobook_id=victim.id, position_seconds=60.0))
    db.commit()
    import shutil

    shutil.rmtree(victim.book_path)

    audiobook_scan.scan_audiobook_library(db, lib)
    db.commit()
    assert db.scalar(select(func.count(Audiobook.id))) == 3, "宽限期内不能删行"
    assert (
        db.scalar(select(Audiobook).where(Audiobook.id == victim.id)).missing_since is not None
    )

    _age_missing(db, Audiobook)
    audiobook_scan.scan_audiobook_library(db, lib)
    db.commit()
    assert db.scalar(select(func.count(Audiobook.id))) == 2, "宽限期满且仍缺失 → 删行（僵尸行清掉）"
    assert db.scalar(select(func.count(AudiobookProgress.id))) == 0, "收听进度必须一并清掉"


def test_audiobook_empty_library_is_never_auto_wiped(tmp_path, monkeypatch):
    from fryfrog.models.audiobook import Audiobook

    db, lib = _audiobook_setup(tmp_path, monkeypatch, books=3, ratio=0.5)
    audiobook_scan.scan_audiobook_library(db, lib)
    db.commit()
    assert _baseline(db, "audiobook_scan", lib) == "3"

    for folder in [p for p in tmp_path.iterdir() if p.is_dir()]:
        import shutil

        shutil.rmtree(folder)

    for _ in range(3):
        audiobook_scan.scan_audiobook_library(db, lib)
        db.commit()
        _age_missing(db, Audiobook)

    assert db.scalar(select(func.count(Audiobook.id))) == 3, "空库视为挂载异常，永不自动清空"
    assert _baseline(db, "audiobook_scan", lib) == "3", "拦截期间基线必须冻结"


# ── 老库迁移 ──────────────────────────────────────────────


def test_ensure_columns_adds_missing_since_for_old_books_db(tmp_path):
    """老库（NAS 上升级）必须能就地补出三张表的 missing_since，而不是启动即崩。"""
    engine = create_engine(f"sqlite:///{tmp_path / 'old-books.db'}")
    with engine.begin() as conn:
        conn.execute(
            text(
                "create table ebooks (id integer primary key, title varchar not null, "
                "file_path varchar not null, library_id integer)"
            )
        )
        conn.execute(
            text(
                "create table comics (id integer primary key, title varchar not null, "
                "book_path varchar not null, library_id integer)"
            )
        )
        conn.execute(
            text(
                "create table audiobooks (id integer primary key, title varchar not null, "
                "book_path varchar not null, library_id integer)"
            )
        )

    _ensure_columns(engine)
    _ensure_columns(engine)  # 每次启动都会跑，必须幂等

    with engine.connect() as conn:
        for table in ("ebooks", "comics", "audiobooks"):
            cols = {row[1] for row in conn.execute(text(f"pragma table_info({table})"))}
            assert "missing_since" in cols, f"{table}.missing_since 必须能补出来"
