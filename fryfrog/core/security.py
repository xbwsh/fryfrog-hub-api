from __future__ import annotations

import contextvars
import re
import secrets
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta

import bcrypt
from sqlalchemy import select
from sqlalchemy.orm import Session

from fryfrog.config import get_settings
from fryfrog.core.exceptions import BadRequestException, ResourceNotFoundException
from fryfrog.models.auth import AuthToken
from fryfrog.models.user import User, UserRole

ANONYMOUS_ID = -1
MIN_PASSWORD_LENGTH = 8
USERNAME_PATTERN = re.compile(r"^[a-zA-Z0-9_]{3,64}$")

# 当前请求用户（由中间件写入）。用 ContextVar，便于 FastAPI 线程池传播
_current_user_id: contextvars.ContextVar[int | None] = contextvars.ContextVar(
    "current_user_id", default=None
)


def set_current_user_id(user_id: int | None) -> None:
    _current_user_id.set(user_id)


def current_user_id_or_none() -> int | None:
    return _current_user_id.get()


def current_user_id() -> int:
    uid = current_user_id_or_none()
    return ANONYMOUS_ID if uid is None else uid


def hash_password(raw: str) -> str:
    return bcrypt.hashpw(raw.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def verify_password(raw: str, password_hash: str) -> bool:
    if not raw or not password_hash:
        return False
    try:
        return bcrypt.checkpw(raw.encode("utf-8"), password_hash.encode("utf-8"))
    except Exception:
        return False


@dataclass
class LoginResult:
    token: str | None = None
    error: str | None = None
    retry_after_seconds: int = 0

    def ok(self) -> bool:
        return self.token is not None


@dataclass
class LoginAttempt:
    failures: int = 0
    lock_until: float = 0.0
    last_failure_at: float = 0.0


@dataclass
class AuthManager:
    enabled: bool = True
    token_ttl_seconds: int = 604800
    max_failures: int = 5
    lock_minutes: int = 15
    _attempts: dict[str, LoginAttempt] = field(default_factory=dict)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    @classmethod
    def from_settings(cls) -> "AuthManager":
        s = get_settings()
        return cls(
            enabled=s.auth_enabled,
            token_ttl_seconds=s.auth_token_ttl,
            max_failures=s.auth_login_max_failures,
            lock_minutes=s.auth_login_lock_minutes,
        )

    def login(self, db: Session, username: str, password: str, ip: str) -> LoginResult:
        if not self.enabled:
            return LoginResult(token="")
        if not username:
            return LoginResult(error="INVALID")

        self._purge_stale()
        attempt = self._attempts.setdefault(username, LoginAttempt())
        with self._lock:
            if attempt.lock_until > time.time():
                retry_after = int(attempt.lock_until - time.time() + 0.999)
                return LoginResult(error="LOCKED", retry_after_seconds=retry_after)

        user = db.scalar(select(User).where(User.username == username))
        if user is None or not user.enabled or not verify_password(password, user.password_hash):
            self._record_failure(attempt)
            return LoginResult(error="INVALID")

        with self._lock:
            attempt.failures = 0
            attempt.lock_until = 0.0
        self._attempts.pop(username, None)

        token = str(uuid.uuid4())
        db.add(
            AuthToken(
                token=token,
                user_id=user.id,
                expires_at=datetime.now() + timedelta(seconds=self.token_ttl_seconds),
            )
        )
        user.last_login_at = datetime.now()
        user.last_login_ip = ip
        db.flush()
        return LoginResult(token=token)

    def get_user_id(self, db: Session, token: str | None) -> int | None:
        if not self.enabled or not token:
            return None
        row = db.scalar(select(AuthToken).where(AuthToken.token == token))
        if row is None:
            return None
        if datetime.now() > row.expires_at:
            db.delete(row)
            db.flush()
            return None
        return row.user_id

    def logout(self, db: Session, token: str | None) -> None:
        if not token:
            return
        row = db.scalar(select(AuthToken).where(AuthToken.token == token))
        if row:
            db.delete(row)
            db.flush()

    def invalidate_user_tokens(self, db: Session, user_id: int | None) -> None:
        if user_id is None:
            return
        for row in db.scalars(select(AuthToken).where(AuthToken.user_id == user_id)).all():
            db.delete(row)
        db.flush()

    def _record_failure(self, attempt: LoginAttempt) -> None:
        with self._lock:
            attempt.failures += 1
            attempt.last_failure_at = time.time()
            if attempt.failures >= self.max_failures:
                attempt.lock_until = time.time() + self.lock_minutes * 60
                attempt.failures = 0

    def _purge_stale(self) -> None:
        cutoff = time.time() - 24 * 60 * 60
        with self._lock:
            for key in [k for k, v in self._attempts.items() if v.last_failure_at < cutoff]:
                del self._attempts[key]


class UserService:
    def __init__(self, encryptor=None):
        self.encryptor = encryptor

    def _subsonic_encrypt(self, raw: str) -> str:
        if self.encryptor is None:
            return raw
        return self.encryptor.encrypt(raw)

    def _subsonic_decrypt(self, value: str) -> str:
        if self.encryptor is None or not value:
            return value
        return self.encryptor.decrypt(value)

    def get_user(self, db: Session, user_id: int) -> User:
        user = db.get(User, user_id)
        if user is None:
            raise ResourceNotFoundException("User", "id", user_id)
        return user

    def find_by_username(self, db: Session, username: str) -> User | None:
        return db.scalar(select(User).where(User.username == username))

    def create_user(
        self,
        db: Session,
        username: str,
        raw_password: str,
        nickname: str | None,
        role: UserRole,
    ) -> User:
        self._validate_username(username)
        self._validate_password(raw_password)
        if self.find_by_username(db, username):
            raise BadRequestException(f"用户名已存在: {username}")
        user = User(
            username=username,
            password_hash=hash_password(raw_password),
            subsonic_password=self._subsonic_encrypt(raw_password),
            nickname=nickname or "用户",
            role=role or UserRole.USER,
            enabled=True,
        )
        db.add(user)
        db.flush()
        return user

    def update_user(
        self,
        db: Session,
        user_id: int,
        nickname: str | None,
        avatar: str | None,
        role: UserRole | None,
        enabled: bool | None,
    ) -> User:
        user = self.get_user(db, user_id)
        if nickname is not None:
            user.nickname = nickname
        if avatar is not None:
            user.avatar = avatar
        if role is not None and user.role != role:
            user.role = role
        if enabled is not None:
            user.enabled = enabled
        db.flush()
        return user

    def delete_user(self, db: Session, user_id: int) -> None:
        user = self.get_user(db, user_id)
        db.delete(user)
        db.flush()

    def change_password(self, db: Session, user_id: int, old_password: str, new_password: str) -> None:
        user = self.get_user(db, user_id)
        if not verify_password(old_password or "", user.password_hash):
            raise BadRequestException("原密码不正确")
        self._set_password(db, user, new_password)

    def reset_password(self, db: Session, user_id: int, new_password: str) -> None:
        user = self.get_user(db, user_id)
        self._set_password(db, user, new_password)

    def update_last_login(self, db: Session, user_id: int, ip: str) -> None:
        from datetime import datetime

        user = db.get(User, user_id)
        if user:
            user.last_login_at = datetime.now()
            user.last_login_ip = ip
            db.flush()

    def is_admin(self, db: Session, user_id: int | None) -> bool:
        if user_id is None:
            return False
        user = db.get(User, user_id)
        return bool(user and user.role == UserRole.ADMIN)

    def has_users(self, db: Session) -> bool:
        return db.scalar(select(User.id).limit(1)) is not None

    def create_initial_admin(self, db: Session, raw_password: str | None) -> User:
        if self.find_by_username(db, "admin"):
            raise BadRequestException("管理员账号已存在")
        password = raw_password or secrets.token_urlsafe(12)
        user = User(
            username="admin",
            password_hash=hash_password(password),
            subsonic_password=self._subsonic_encrypt(password),
            nickname="管理员",
            role=UserRole.ADMIN,
            enabled=True,
        )
        db.add(user)
        db.flush()
        return user

    def _set_password(self, db: Session, user: User, new_password: str) -> None:
        self._validate_password(new_password)
        user.password_hash = hash_password(new_password)
        user.subsonic_password = self._subsonic_encrypt(new_password)
        db.flush()

    def _validate_username(self, username: str) -> None:
        if not username or not USERNAME_PATTERN.match(username):
            raise BadRequestException("用户名必须为 3-64 位的字母、数字或下划线")

    def _validate_password(self, password: str) -> None:
        if not password or len(password) < MIN_PASSWORD_LENGTH:
            raise BadRequestException(f"密码至少 {MIN_PASSWORD_LENGTH} 位")
