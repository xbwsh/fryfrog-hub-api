from __future__ import annotations

from collections.abc import Generator

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from fryfrog.config import get_settings


class Base(DeclarativeBase):
    pass


_engine = None
_SessionLocal: sessionmaker | None = None


def get_engine():
    global _engine, _SessionLocal
    if _engine is None:
        settings = get_settings()
        _engine = create_engine(
            settings.database_url,
            pool_size=settings.db_pool_size,
            pool_pre_ping=True,
            future=True,
        )
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
    """创建表结构（兼容既有 PostgreSQL 库）。"""
    from fryfrog import models  # noqa: F401  确保模型已注册

    engine = get_engine()
    Base.metadata.create_all(engine)
