from __future__ import annotations

from sqlalchemy import (
    Boolean,
    Double,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from fryfrog.db import Base
from fryfrog.models.base import TimestampMixin


class Audiobook(TimestampMixin, Base):
    __tablename__ = "audiobooks"

    title: Mapped[str] = mapped_column(String, nullable=False, index=True)
    author: Mapped[str | None] = mapped_column(String, nullable=True, index=True)
    narrator: Mapped[str | None] = mapped_column(String, nullable=True)
    overview: Mapped[str | None] = mapped_column(Text, nullable=True)
    pub_year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    rating: Mapped[float | None] = mapped_column(Double, nullable=True)
    source_id: Mapped[str | None] = mapped_column(String, nullable=True)
    metadata_source: Mapped[str | None] = mapped_column(String, nullable=True)
    series: Mapped[str | None] = mapped_column(String, nullable=True)
    series_part: Mapped[int | None] = mapped_column(Integer, nullable=True)
    library_id: Mapped[int | None] = mapped_column("library_id", Integer, nullable=True, index=True)
    book_path: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    play_type: Mapped[str] = mapped_column(String(16), nullable=False)  # SINGLE/MULTI
    file_path: Mapped[str | None] = mapped_column(String, nullable=True)
    cover_art_path: Mapped[str | None] = mapped_column(String, nullable=True)
    total_duration_seconds: Mapped[float | None] = mapped_column(Double, nullable=True)
    track_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    total_file_size: Mapped[int | None] = mapped_column(Integer, nullable=True)


class AudiobookTrack(TimestampMixin, Base):
    __tablename__ = "audiobook_tracks"
    __table_args__ = (
        UniqueConstraint("audiobook_id", "track_index", name="uq_audiobook_track"),
    )

    audiobook_id: Mapped[int] = mapped_column(ForeignKey("audiobooks.id"), nullable=False)
    track_index: Mapped[int] = mapped_column("track_index", Integer, nullable=False)
    title: Mapped[str | None] = mapped_column(String, nullable=True)
    file_path: Mapped[str] = mapped_column(String, nullable=False, unique=True)
    format: Mapped[str | None] = mapped_column(String, nullable=True)
    duration_seconds: Mapped[float | None] = mapped_column(Double, nullable=True)
    file_size: Mapped[int | None] = mapped_column(Integer, nullable=True)


class AudiobookChapter(TimestampMixin, Base):
    __tablename__ = "audiobook_chapters"
    __table_args__ = (
        UniqueConstraint("audiobook_id", "chapter_index", name="uq_audiobook_chapter"),
    )

    audiobook_id: Mapped[int] = mapped_column(ForeignKey("audiobooks.id"), nullable=False)
    chapter_index: Mapped[int] = mapped_column("chapter_index", Integer, nullable=False)
    title: Mapped[str | None] = mapped_column(String, nullable=True)
    start_seconds: Mapped[float] = mapped_column(Double, nullable=False)
    end_seconds: Mapped[float] = mapped_column(Double, nullable=False)


class AudiobookProgress(TimestampMixin, Base):
    __tablename__ = "audiobook_progress"
    __table_args__ = (
        UniqueConstraint("user_id", "audiobook_id", name="uq_audiobook_progress"),
    )

    user_id: Mapped[int] = mapped_column("user_id", Integer, nullable=False)
    audiobook_id: Mapped[int] = mapped_column(ForeignKey("audiobooks.id"), nullable=False)
    track_index: Mapped[int | None] = mapped_column("track_index", Integer, nullable=True)
    position_seconds: Mapped[float | None] = mapped_column(Double, nullable=True)
    completed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
