from __future__ import annotations

from sqlalchemy import Boolean, Double, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from fryfrog.db import Base
from fryfrog.models.base import TimestampMixin


class Ebook(TimestampMixin, Base):
    __tablename__ = "ebooks"

    title: Mapped[str] = mapped_column(String, nullable=False, index=True)
    author: Mapped[str | None] = mapped_column(String, nullable=True, index=True)
    publisher: Mapped[str | None] = mapped_column(String, nullable=True)
    language: Mapped[str | None] = mapped_column(String, nullable=True)
    pub_year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    rating: Mapped[float | None] = mapped_column(Double, nullable=True)
    overview: Mapped[str | None] = mapped_column(Text, nullable=True)
    series: Mapped[str | None] = mapped_column(String, nullable=True)
    series_part: Mapped[int | None] = mapped_column(Integer, nullable=True)
    source_id: Mapped[str | None] = mapped_column(String, nullable=True)
    metadata_source: Mapped[str | None] = mapped_column(String, nullable=True)
    format: Mapped[str] = mapped_column(String(16), nullable=False)  # EPUB/PDF/MOBI
    file_path: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    file_mtime: Mapped[int | None] = mapped_column(Integer, nullable=True)
    file_size: Mapped[int | None] = mapped_column(Integer, nullable=True)
    cover_art_path: Mapped[str | None] = mapped_column(String, nullable=True)
    total_chapters: Mapped[int | None] = mapped_column(Integer, nullable=True)
    library_id: Mapped[int | None] = mapped_column("library_id", Integer, nullable=True, index=True)


class EbookProgress(TimestampMixin, Base):
    __tablename__ = "ebook_progress"
    __table_args__ = (UniqueConstraint("user_id", "ebook_id", name="uq_ebook_progress"),)

    user_id: Mapped[int] = mapped_column("user_id", Integer, nullable=False)
    ebook_id: Mapped[int] = mapped_column(ForeignKey("ebooks.id"), nullable=False)
    position_percent: Mapped[float | None] = mapped_column(Double, nullable=True)
    chapter_index: Mapped[int | None] = mapped_column(Integer, nullable=True)
    completed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
