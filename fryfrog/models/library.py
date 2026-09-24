from __future__ import annotations

import enum

from sqlalchemy import Boolean, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from fryfrog.db import Base
from fryfrog.models.base import TimestampMixin


class LibraryType(str, enum.Enum):
    VIDEO = "VIDEO"
    MUSIC = "MUSIC"
    AUDIOBOOK = "AUDIOBOOK"
    EBOOK = "EBOOK"
    COMIC = "COMIC"


class VideoSubType(str, enum.Enum):
    MOVIE = "MOVIE"
    TV = "TV"
    MIXED = "MIXED"


class MediaLibrary(TimestampMixin, Base):
    __tablename__ = "media_libraries"

    name: Mapped[str] = mapped_column(String, nullable=False)
    path: Mapped[str] = mapped_column(String, nullable=False)
    type: Mapped[str] = mapped_column(String, nullable=False, index=True)
    sub_type: Mapped[str | None] = mapped_column(String, nullable=True)
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True, index=True)
    enable_scraping: Mapped[bool | None] = mapped_column(Boolean, nullable=True, default=True)
    is_adult: Mapped[bool | None] = mapped_column(Boolean, nullable=True, default=False)
    sort_order: Mapped[int | None] = mapped_column(Integer, nullable=True)
    description: Mapped[str | None] = mapped_column(String, nullable=True)

    def is_video_type(self) -> bool:
        return (self.type or "").upper() == "VIDEO"

    def is_music_type(self) -> bool:
        return (self.type or "").upper() == "MUSIC"

    def is_audiobook_type(self) -> bool:
        return (self.type or "").upper() == "AUDIOBOOK"

    def is_ebook_type(self) -> bool:
        return (self.type or "").upper() == "EBOOK"

    def is_comic_type(self) -> bool:
        return (self.type or "").upper() == "COMIC"

    def is_movie_sub_type(self) -> bool:
        return (self.sub_type or "").upper() == "MOVIE"

    def is_tv_sub_type(self) -> bool:
        return (self.sub_type or "").upper() == "TV"

    def is_mixed_sub_type(self) -> bool:
        return not self.sub_type or self.sub_type.upper() == "MIXED"

    def media_type_filter(self) -> str | None:
        if self.is_movie_sub_type():
            return "movie"
        if self.is_tv_sub_type():
            return "tv"
        return None


class UserLibrary(TimestampMixin, Base):
    __tablename__ = "user_libraries"
    __table_args__ = (UniqueConstraint("user_id", "library_id", name="uq_user_libraries"),)

    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    library_id: Mapped[int] = mapped_column(Integer, nullable=False)


class UserPreference(TimestampMixin, Base):
    __tablename__ = "user_preferences"
    __table_args__ = (UniqueConstraint("user_id", "pref_key", name="uq_user_preferences"),)

    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    pref_key: Mapped[str] = mapped_column(String(64), nullable=False)
    pref_value: Mapped[str | None] = mapped_column(String(2048), nullable=True)


class SystemSetting(TimestampMixin, Base):
    __tablename__ = "system_setting"

    key: Mapped[str] = mapped_column("key", String, nullable=False, unique=True)
    value: Mapped[str | None] = mapped_column("value", String, nullable=True)
    description: Mapped[str | None] = mapped_column(String, nullable=True)
