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
        # 后台扫描持写锁时裸 commit 会直接 500（响应已生成，客户端只看到一个失败），
        # 这里与认证中间件走同一条重试路径，把硬失败换成短暂等待。
        from fryfrog.core.deps import commit_with_retry

        commit_with_retry(session)
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
def _table_has_rows(engine, table_name: str) -> bool:
    """老表是否已有数据；决定 ADD COLUMN 能不能带 NOT NULL。"""
    from sqlalchemy import text
    from sqlalchemy.exc import SQLAlchemyError

    try:
        with engine.connect() as conn:
            return bool(conn.execute(text(f'SELECT 1 FROM "{table_name}" LIMIT 1')).fetchone())
    except SQLAlchemyError:
        return False


def _add_column_ddl(column, dialect, has_rows: bool) -> str:
    """生成 ADD COLUMN 的列定义。

    表里已有数据时，SQLite 拒绝「NOT NULL 且无默认值」的列（`Cannot add a
    NOT NULL column with default value NULL`），于是 enabled / role 这类列在
    老库上永远补不上，运行期直接no such column。这里把 ORM 的 Python 侧
    default 翻译成 SQL 字面量写进 DDL —— 老行由 ADD COLUMN 自身填默认值，
    约束得以建立，比「先加可空列再回填」更干净（不需要重建表来收紧约束）。
    """
    from sqlalchemy.schema import CreateColumn

    ddl = str(CreateColumn(column).compile(dialect=dialect)).strip()
    if not has_rows or not column.nullable is False:
        return ddl
    if "DEFAULT" in ddl.upper():
        return ddl  # 已带默认值，原样使用
    lit = _default_literal(column)
    if lit is None:
        return ddl  # 函数式/无默认值，仍会失败，交由调用方跳过并记日志
    # 插到类型名之后、NOT NULL 之前：`x VARCHAR NOT NULL` → `x VARCHAR DEFAULT 'y' NOT NULL`
    upper = ddl.upper()
    idx = upper.rfind(" NOT NULL")
    if idx == -1:
        return ddl
    return f"{ddl[:idx]} DEFAULT {lit}{ddl[idx:]}"


def _ensure_columns(engine) -> None:
    """老库补列：create_all 只建表不加列，这里按模型元数据做幂等 ALTER TABLE。

    直接尝试加列，失败就回滚并跳过——SQLite 对「NOT NULL 无默认值」
    「非恒定默认值（CURRENT_TIMESTAMP）」都会拒绝，与其复刻它的规则，
    不如让数据库自己判定（失败只影响该列，不影响启动）。

    补上的列必须**回填**：老行该列全是 NULL，而业务代码常按
    `not user.enabled` 这类真值判断，NULL 会被判成「禁用/关闭」，
    导致老库升级后全体用户集体无法登录。因此凡模型声明了 server_default
    的列，补完立刻按该默认值回填老行。
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
    backfilled: list[str] = []
    skipped: list[str] = []

    for table in Base.metadata.sorted_tables:
        if table.name not in tables:
            continue
        existing = {c["name"] for c in inspector.get_columns(table.name)}
        has_rows = _table_has_rows(engine, table.name)
        for column in table.columns:
            if column.name in existing:
                continue
            ddl = _add_column_ddl(column, dialect, has_rows)
            savepoint = f"addcol_{table.name}_{column.name}"
            try:
                with engine.begin() as conn:
                    conn.execute(text(f'SAVEPOINT "{savepoint}"'))
                    conn.execute(text(f'ALTER TABLE "{table.name}" ADD COLUMN {ddl}'))
                    conn.execute(text(f'RELEASE SAVEPOINT "{savepoint}"'))
                added.append(f"{table.name}.{column.name}")
                if has_rows and _backfill_column(engine, table.name, column):
                    backfilled.append(f"{table.name}.{column.name}")
            except SQLAlchemyError:
                skipped.append(f"{table.name}.{column.name}")

    if added:
        logger.info("已为老库补齐 %d 个列: %s", len(added), ", ".join(added))
    if backfilled:
        logger.info("已按默认值回填 %d 个列的老行: %s", len(backfilled), ", ".join(backfilled))
    if skipped:
        # 仅提示：新库由 create_all 直接建全，这些列只影响极端陈旧的老库。
        # 用 error 级别：跳过的列在运行期会表现为 no such column 或 NULL 误判，
        # warning 容易被淹没在启动日志里。
        logger.error("以下列无法安全补加，已跳过（运行期可能报no such column）: %s", ", ".join(skipped))


def _backfill_column(engine, table_name: str, column) -> bool:
    """新补的列给老行填上默认值，避免 NULL 被真值判断当成False。

    默认值有两个来源，都要认：
    - `server_default.arg` —— DDL 层的默认值（SQLite 的 ALTER TABLE 也认它）；
    - `column.default.arg` —— ORM 的 Python 侧默认值（如 `enabled`/`role` 用的是
      `default=`，DDL 里根本没有 DEFAULT 子句）。**这一路更关键**：SQLite 在非空
      表上补 NOT NULL 无默认值的列必然失败，此时列压根没加上，回填也无从谈起；
      所以补列时就要把 Python 侧默认值翻译成 SQL 字面量写进 DDL。
    返回是否真的回填了。
    """
    from sqlalchemy import inspect, text
    from sqlalchemy.exc import SQLAlchemyError

    lit = _default_literal(column)
    if lit is None:
        return False
    insp = inspect(engine)
    if table_name not in set(insp.get_table_names()):
        return False
    try:
        with engine.begin() as conn:
            conn.execute(
                text(f'UPDATE "{table_name}" SET "{column.name}" = {lit} WHERE "{column.name}" IS NULL')
            )
    except SQLAlchemyError:
        logger.exception("回填 %s.%s 的老行失败", table_name, column.name)
        return False
    return True


def _default_literal(column) -> str | None:
    """把列的默认值渲染成 SQL 字面量；函数式默认值返回 None（交给 SQLite 自己处理）。"""
    for holder in (getattr(column, "server_default", None), getattr(column, "default", None)):
        if holder is None:
            continue
        arg = getattr(holder, "arg", None)
        text_val = getattr(arg, "text", None)
        if text_val is None and isinstance(arg, bool):
            # Boolean 的 default=True 是 Python bool，SQLAlchemy 不给 .text
            return "1" if arg else "0"
        if text_val is None and isinstance(arg, str):
            lit = arg.strip()
            if lit and "(" not in lit:
                return f"'{lit}'"
            return None
        if text_val is not None:
            lit = text_val.strip()
            # CURRENT_TIMESTAMP 等函数式默认值：SQLite 的 ADD COLUMN 自己会带上
            return None if ("(" in lit or not lit) else lit
    return None
