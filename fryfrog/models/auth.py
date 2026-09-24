from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from fryfrog.db import Base
from fryfrog.models.base import TimestampMixin


class AuthToken(TimestampMixin, Base):
    __tablename__ = "auth_tokens"

    token: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, index=True)
