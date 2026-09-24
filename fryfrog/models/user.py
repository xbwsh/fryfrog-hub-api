from __future__ import annotations

import enum
from datetime import datetime

from sqlalchemy import Boolean, DateTime, Enum, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from fryfrog.db import Base
from fryfrog.models.base import TimestampMixin


class UserRole(str, enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"


class User(TimestampMixin, Base):
    __tablename__ = "users"

    username: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String, nullable=False)
    subsonic_password: Mapped[str | None] = mapped_column(String, nullable=True)
    nickname: Mapped[str | None] = mapped_column(String, nullable=True)
    avatar: Mapped[str | None] = mapped_column(String, nullable=True)
    role: Mapped[UserRole] = mapped_column(
        Enum(UserRole, name="user_role", native_enum=False, length=16),
        nullable=False,
        default=UserRole.USER,
    )
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    last_login_ip: Mapped[str | None] = mapped_column(String, nullable=True)

    def is_admin(self) -> bool:
        return self.role == UserRole.ADMIN
