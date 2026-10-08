"""SQLite 写锁争用：提交要重试，不能把正常请求变成 500。

实测：后台扫描持写锁时，认证中间件（每个请求都会走到）的 commit 抛
`sqlite3.OperationalError: database is locked`，日志里累计 16 次未处理异常。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest
from sqlalchemy.exc import OperationalError

from fryfrog.core.deps import commit_with_retry


class _FakeDB:
    def __init__(self, failures: int) -> None:
        self.failures = failures
        self.commits = 0
        self.rollbacks = 0

    def commit(self):
        self.commits += 1
        if self.failures > 0:
            self.failures -= 1
            raise OperationalError("COMMIT", {}, Exception("database is locked"))

    def rollback(self):
        self.rollbacks += 1


def test_retries_until_commit_succeeds():
    db = _FakeDB(failures=2)
    commit_with_retry(db, attempts=3)
    assert db.commits == 3
    assert db.rollbacks == 2, "每次失败后要回滚，避免会话处于失败事务里"


def test_no_retry_needed_on_clean_commit():
    db = _FakeDB(failures=0)
    commit_with_retry(db)
    assert db.commits == 1
    assert db.rollbacks == 0


def test_raises_after_exhausting_attempts():
    db = _FakeDB(failures=99)
    with pytest.raises(OperationalError):
        commit_with_retry(db, attempts=3)
    assert db.commits == 3, "最多重试 attempts 次"


def test_non_lock_error_propagates_immediately():
    class _Boom:
        def __init__(self):
            self.commits = 0

        def commit(self):
            self.commits += 1
            raise ValueError("不是锁问题")

        def rollback(self):
            pass

    db = _Boom()
    with pytest.raises(ValueError):
        commit_with_retry(db, attempts=3)
    assert db.commits == 1, "非锁异常应立刻抛出，不做重试"


def test_pending_writes_never_retried_after_rollback(monkeypatch):
    """关键语义：commit 失败 → rollback 已丢弃全部待写入变更（新增退回
    transient、更新被过期还原），此时再 commit 提交的是空事务——必然
    "成功"，但数据已静默丢失。带写入状态的会话失败必须如实抛错，
    绝不允许重试出假成功。"""
    monkeypatch.setattr("time.sleep", lambda s: None)

    class _FakeDBWithWrites:
        def __init__(self):
            self.commits = 0
            self.rollbacks = 0
            self.new = {"pending-row"}  # 会话里有待写入对象
            self.dirty = set()
            self.deleted = set()

        def commit(self):
            self.commits += 1
            raise OperationalError("COMMIT", {}, Exception("database is locked"))

        def rollback(self):
            self.rollbacks += 1

    db = _FakeDBWithWrites()
    with pytest.raises(OperationalError):
        commit_with_retry(db, attempts=5)
    assert db.commits == 1, "有写入状态时失败一次就要抛，不能重试出空事务假成功"
    assert db.rollbacks == 1


def test_default_budget_survives_background_burst(monkeypatch):
    """回归：默认重试预算要够大。

    实测刷新大库时**登录直接 500**：刷新任务持锁，认证中间件 commit 撞锁，
    原本只重试 3 次（0.2+0.4=0.6 秒）完全不够，刷新跑几分钟就几分钟登不进去。
    """
    monkeypatch.setattr("time.sleep", lambda s: None)  # 别真睡，用例要快
    db = _FakeDB(failures=4)
    commit_with_retry(db)  # 用默认参数
    assert db.commits == 5, "默认应能扛住 4 次连续锁失败"


def test_backoff_is_bounded(monkeypatch):
    """退避必须封顶，否则重试预算会拖到几分钟（请求挂死）。"""
    slept: list[float] = []
    monkeypatch.setattr("time.sleep", lambda s: slept.append(s))

    db = _FakeDB(failures=99)
    with pytest.raises(OperationalError):
        commit_with_retry(db)

    assert slept, "应有退避"
    assert max(slept) <= 3.2, f"单次退避应封顶 3.2s，实际 {max(slept)}"
    total = sum(slept)
    assert total <= 8, f"总退避应控制在数秒内，实际 {total}"
    # 指数退避：先小后大
    assert slept[0] < slept[-1]


def test_get_db_commits_through_retry(monkeypatch):
    """路由会话的提交也必须走重试路径：裸 commit 撞锁会把已生成的响应变成 500。"""
    import fryfrog.db as dbmod

    class _Session:
        def __init__(self) -> None:
            self.commits = 0
            self.rollbacks = 0
            self.closed = False

        def commit(self):
            self.commits += 1
            if self.commits == 1:
                raise OperationalError("COMMIT", {}, Exception("database is locked"))

        def rollback(self):
            self.rollbacks += 1

        def close(self):
            self.closed = True

    session = _Session()
    monkeypatch.setattr(dbmod, "get_session_factory", lambda: (lambda: session))
    monkeypatch.setattr("time.sleep", lambda s: None)

    gen = dbmod.get_db()
    next(gen)
    with pytest.raises(StopIteration):
        next(gen)

    assert session.commits == 2, "第一次撞锁后应重试"
    assert session.closed, "生成器收尾必须关会话"
    assert session.rollbacks == 1, "重试前要回滚失败事务"
