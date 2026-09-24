from __future__ import annotations

from sqlalchemy import Boolean, Double, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from fryfrog.db import Base
from fryfrog.models.base import TimestampMixin


class Comic(TimestampMixin, Base):
    __tablename__ = "comics"

    title: Mapped[str] = mapped_column(String, nullable=False, index=True)
    author: Mapped[str | None] = mapped_column(String, nullable=True, index=True)
    overview: Mapped[str | None] = mapped_column(Text, nullable=True)
    series: Mapped[str | None] = mapped_column(String, nullable=True)
    series_part: Mapped[int | None] = mapped_column(Integer, nullable=True)
    source_id: Mapped[str | None] = mapped_column(String, nullable=True)
    metadata_source: Mapped[str | None] = mapped_column(String, nullable=True)
    pub_year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    rating: Mapped[float | None] = mapped_column(Double, nullable=True)
    book_path: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    cover_art_path: Mapped[str | None] = mapped_column(String, nullable=True)
    total_chapters: Mapped[int | None] = mapped_column(Integer, nullable=True)
    total_size: Mapped[int | None] = mapped_column(Integer, nullable=True)
    library_id: Mapped[int | None] = mapped_column("library_id", Integer, nullable=True, index=True)


class ComicChapter(TimestampMixin, Base):
    __tablename__ = "comic_chapters"

    comic_id: Mapped[int] = mapped_column(ForeignKey("comics.id"), nullable=False, index=True)
    chapter_index: Mapped[int] = mapped_column("chapter_index", Integer, nullable=False)
    title: Mapped[str | None] = mapped_column(String, nullable=True)
    file_path: Mapped[str] = mapped_column(String, nullable=False)
    file_mtime: Mapped[int | None] = mapped_column(Integer, nullable=True)
    type: Mapped[str] = mapped_column(String(16), nullable=False)  # DIRECTORY/ARCHIVE
    page_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    file_size: Mapped[int | None] = mapped_column(Integer, nullable=True)


class ComicProgress(TimestampMixin, Base):
    __tablename__ = "comic_progress"
    __table_args__ = (UniqueConstraint("user_id", "comic_id", name="uq_comic_progress"),)

    user_id: Mapped[int] = mapped_column("user_id", Integer, nullable=False)
    comic_id: Mapped[int] = mapped_column(ForeignKey("comics.id"), nullable=False)
    chapter_index: Mapped[int | None] = mapped_column("chapter_index", Integer, nullable=True)
    page_index: Mapped[int | None] = mapped_column("page_index", Integer, nullable=True)
    completed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
