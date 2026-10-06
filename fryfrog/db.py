from __future__ import annotations

import logging
from collections.abc import Generator
from pathlib import Path

from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from fryfrog.config import get_settings

logger = logging.getLogger(__name__)


class Base(DeclarativeBase):
    pass


_engine = None
_SessionLocal: sessionmaker | None = None


def get_engine():
    global _engine, _SessionLocal
    if _engine is None:
        settings = get_settings()
        db_path = Path(settings.sqlite_path)
        db_path.parent.mkdir(parents=True, exist_ok=True)
        _engine = create_engine(
            f"sqlite:///{db_path}",
            connect_args={"check_same_thread": False, "timeout": 30},
            future=True,
        )

        @event.listens_for(_engine, "connect")
        def _set_sqlite_pragma(dbapi_conn, _):
            cursor = dbapi_conn.cursor()
            cursor.execute("PRAGMA journal_mode=WAL")
            cursor.execute("PRAGMA synchronous=NORMAL")
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.close()

        _SessionLocal = sessionmaker(bind=_engine, autoflush=False, expire_on_commit=False)
    return _engine


def get_session_factory() -> sessionmaker:
    get_engine()
    assert _SessionLocal is not None
    return _SessionLocal


def get_db() -> Generator[Session, None, None]:
    session = get_session_factory()()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def init_db() -> None:
    """创建表结构（SQLite 文件库）并补齐老库缺失的列。"""
    from fryfrog import models  # noqa: F401  确保模型已注册

    engine = get_engine()
    Base.metadata.create_all(engine)
    _ensure_columns(engine)


# 老库补列：create_all 只建表不加列，这里按模型元数据做幂等 ALTER TABLE。
# 表名/列名/类型都来自本地模型定义，不来自外部输入。
def _ensure_columns(engine) -> None:
    """老库补列：create_all 只建表不加列，这里按模型元数据做幂等 ALTER TABLE。

    直接尝试加列，失败就回滚并跳过——SQLite 对「NOT NULL 无默认值」
    「非恒定默认值（CURRENT_TIMESTAMP）」都会拒绝，与其复刻它的规则，
    不如让数据库自己判定（失败只影响该列，不影响启动）。
    """
    from sqlalchemy import inspect, text
    from sqlalchemy.exc import SQLAlchemyError
    from sqlalchemy.schema import CreateColumn

    inspector = inspect(engine)
    tables = set(inspector.get_table_names())
    if not tables:
        return
    dialect = engine.dialect
    added: list[str] = []
    skipped: list[str] = []

    for table in Base.metadata.sorted_tables:
        if table.name not in tables:
            continue
        existing = {c["name"] for c in inspector.get_columns(table.name)}
        for column in table.columns:
            if column.name in existing:
                continue
            ddl = str(CreateColumn(column).compile(dialect=dialect)).strip()
            savepoint = f"addcol_{table.name}_{column.name}"
            try:
                with engine.begin() as conn:
                    conn.execute(text(f'SAVEPOINT "{savepoint}"'))
                    conn.execute(text(f'ALTER TABLE "{table.name}" ADD COLUMN {ddl}'))
                    conn.execute(text(f'RELEASE SAVEPOINT "{savepoint}"'))
                added.append(f"{table.name}.{column.name}")
            except SQLAlchemyError:
                skipped.append(f"{table.name}.{column.name}")

    if added:
        logger.info("已为老库补齐 %d 个列: %s", len(added), ", ".join(added))
    if skipped:
        # 仅提示：新库由 create_all 直接建全，这些列只影响极端陈旧的老库
        logger.warning("以下列无法安全补加，已跳过: %s", ", ".join(skipped))
