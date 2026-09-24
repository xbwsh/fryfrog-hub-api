from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, Integer, func
from sqlalchemy.orm import Mapped, mapped_column

from fryfrog.db import Base


class TimestampMixin:
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    created_at: Mapped[datetime | None] = mapped_column(
        DateTime, name="created_at", server_default=func.now(), nullable=True
    )
    updated_at: Mapped[datetime | None] = mapped_column(
        DateTime, name="updated_at", server_default=func.now(), onupdate=func.now(), nullable=True
    )
