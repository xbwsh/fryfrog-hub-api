"""登录失败限速：计数必须跨请求共享，且 /rest 与 /auth/login 共用同一计数。

实测故障：失败计数原本是 AuthManager 的实例字段，而每个请求都会新建实例
（`get_auth_manager()` 直接 `AuthManager.from_settings()`），计数永远攒不满，
429 锁定分支不可达，可无限爆破；`/rest` 更是完全没有任何限速。
"""

from __future__ import annotations

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.core import security
from fryfrog.core.crypto import SubsonicPasswordEncryptor
from fryfrog.core.security import LoginThrottle, hash_password
from fryfrog.db import Base
from fryfrog.models.user import User, UserRole


class _Settings:
    auth_enabled = True
    auth_token_ttl = 3600
    auth_login_max_failures = 3
    auth_login_lock_minutes = 15
    subsonic_encrypt_key = ""


@pytest.fixture(autouse=True)
def _fresh_throttle(monkeypatch):
    monkeypatch.setattr(security, "get_settings", lambda: _Settings())
    LoginThrottle._shared = None
    yield
    LoginThrottle._shared = None


def _db():
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    db.add(
        User(
            username="admin",
            password_hash=hash_password("correct-horse"),
            role=UserRole.ADMIN,
            enabled=True,
        )
    )
    db.commit()
    return db


def _login(db, password: str):
    return security.AuthManager.from_settings().login(db, "admin", password, "1.2.3.4")


def test_lockout_triggers_across_requests():
    db = _db()
    for _ in range(3):
        assert _login(db, "wrong").error == "INVALID"

    locked = _login(db, "wrong")
    assert locked.error == "LOCKED", "阈值攒满后必须锁定"
    assert locked.retry_after_seconds > 0
    assert _login(db, "correct-horse").error == "LOCKED", "锁定期间正确密码也不放行"
    db.close()


def test_success_clears_counter():
    db = _db()
    for _ in range(2):
        assert _login(db, "wrong").error == "INVALID"
    assert _login(db, "correct-horse").ok()

    for _ in range(2):
        assert _login(db, "wrong").error == "INVALID", "成功后计数应清零，不会立刻锁定"
    db.close()


def test_subsonic_auth_shares_throttle(monkeypatch):
    from fryfrog.routers import music
    from fryfrog.routers.music import SubsonicApiError, SubsonicAuthService

    monkeypatch.setattr(music, "get_settings", lambda: _Settings())
    db = _db()
    service = SubsonicAuthService(encryptor=SubsonicPasswordEncryptor(None))

    for _ in range(3):
        with pytest.raises(SubsonicApiError):
            service.authenticate(db, "admin", "wrong", None, None)

    with pytest.raises(SubsonicApiError) as exc:
        service.authenticate(db, "admin", "wrong", None, None)
    assert "retry" in exc.value.message.lower(), exc.value.message

    # /rest 与 /auth/login 共用计数：Web 侧也应处于锁定状态
    assert _login(db, "correct-horse").error == "LOCKED"

    LoginThrottle._shared.record_success("admin")
    assert service.authenticate(db, "admin", "correct-horse", None, None) is not None
    db.close()
