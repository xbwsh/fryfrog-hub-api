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
