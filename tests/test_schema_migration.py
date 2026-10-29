"""保护旧库升级及数据库版本边界。"""
import importlib.util
from pathlib import Path

import pytest
from sqlalchemy import create_engine, text

from fryfrog import db


def _old_db(path):
    script = Path(__file__).resolve().parents[1] / '.github/scripts/make_old_db.py'
    spec = importlib.util.spec_from_file_location('make_old_db', script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.create(path)


def test_old_database_upgrade_is_repeatable_and_preserves_rows(tmp_path, monkeypatch):
    path = tmp_path / 'old.db'
    _old_db(path)
    engine = create_engine(f'sqlite:///{path}')
    monkeypatch.setattr(db, 'get_engine', lambda: engine)
    db.init_db()
    db.init_db()
    with engine.connect() as conn:
        assert conn.execute(text('PRAGMA user_version')).scalar_one() == db.SCHEMA_VERSION
        assert conn.execute(text('SELECT name FROM media_libraries WHERE id=1')).scalar_one() == '旧库'
        assert conn.execute(text('SELECT title FROM videos WHERE id=1')).scalar_one() == '旧片子'
        assert 'missing_since' in {row[1] for row in conn.execute(text('PRAGMA table_info(videos)'))}


def test_failed_upgrade_does_not_mark_database_upgraded(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'broken.db'}")
    with engine.begin() as conn:
        conn.execute(text('CREATE TABLE videos (id INTEGER PRIMARY KEY)'))
        conn.execute(text('INSERT INTO videos (id) VALUES (1)'))
    monkeypatch.setattr(db, 'get_engine', lambda: engine)
    with pytest.raises(RuntimeError, match='数据库迁移失败'):
        db.init_db()
    with engine.connect() as conn:
        assert conn.execute(text('PRAGMA user_version')).scalar_one() == 0
        assert conn.execute(text('SELECT id FROM videos')).scalar_one() == 1


def test_newer_database_version_is_rejected_before_changes(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'newer.db'}")
    with engine.begin() as conn:
        conn.execute(text(f'PRAGMA user_version = {db.SCHEMA_VERSION + 1}'))
    monkeypatch.setattr(db, 'get_engine', lambda: engine)
    with pytest.raises(RuntimeError, match='高于本程序支持'):
        db.init_db()
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM sqlite_master WHERE type='table'")).scalar_one() == 0
