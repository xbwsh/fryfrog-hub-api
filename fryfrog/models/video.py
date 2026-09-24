from __future__ import annotations

from datetime import datetime

from sqlalchemy import (
    Boolean,
    DateTime,
    Double,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from fryfrog.db import Base
from fryfrog.models.base import TimestampMixin


class VideoSeries(TimestampMixin, Base):
    __tablename__ = "video_series"

    title: Mapped[str] = mapped_column(String, nullable=False, index=True)
    original_title: Mapped[str | None] = mapped_column(String, nullable=True)
    overview: Mapped[str | None] = mapped_column(Text, nullable=True)
    media_type: Mapped[str | None] = mapped_column(String, nullable=True)
    tmdb_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    imdb_id: Mapped[str | None] = mapped_column(String, nullable=True)
    rating: Mapped[float | None] = mapped_column(Double, nullable=True)
    year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    release_date: Mapped[str | None] = mapped_column(String, nullable=True)
    poster_url: Mapped[str | None] = mapped_column(String, nullable=True)
    backdrop_url: Mapped[str | None] = mapped_column(String, nullable=True)
    poster_local_path: Mapped[str | None] = mapped_column(String, nullable=True)
    backdrop_local_path: Mapped[str | None] = mapped_column(String, nullable=True)
    logo_url: Mapped[str | None] = mapped_column(String, nullable=True)
    logo_local_path: Mapped[str | None] = mapped_column(String, nullable=True)
    metadata_source: Mapped[str | None] = mapped_column(String, nullable=True)
    status: Mapped[str | None] = mapped_column(String, nullable=True)
    is_adult: Mapped[bool | None] = mapped_column(Boolean, nullable=True, default=False)
    number_of_seasons: Mapped[int | None] = mapped_column(Integer, nullable=True)
    season_number: Mapped[int | None] = mapped_column(Integer, nullable=True, default=1)
    total_episodes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    next_episode_date: Mapped[str | None] = mapped_column(String, nullable=True)
    next_episode_number: Mapped[str | None] = mapped_column(String, nullable=True)
    metadata_dir: Mapped[str | None] = mapped_column(String, nullable=True)


class Video(TimestampMixin, Base):
    __tablename__ = "videos"

    title: Mapped[str] = mapped_column(String, nullable=False, index=True)
    director: Mapped[str | None] = mapped_column(String, nullable=True)
    actors: Mapped[str | None] = mapped_column(String, nullable=True)
    genre: Mapped[str | None] = mapped_column(String, nullable=True)
    year: Mapped[int | None] = mapped_column("year", Integer, nullable=True)
    release_date: Mapped[str | None] = mapped_column(String, nullable=True)
    duration_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    duration_seconds: Mapped[float | None] = mapped_column(Double, nullable=True)
    file_path: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    file_name: Mapped[str] = mapped_column(String, nullable=False, index=True)
    original_file_name: Mapped[str | None] = mapped_column(String, nullable=True)
    file_size: Mapped[int | None] = mapped_column(Integer, nullable=True)
    format: Mapped[str | None] = mapped_column(String, nullable=True)
    resolution: Mapped[str | None] = mapped_column(String, nullable=True)
    cover_art_path: Mapped[str | None] = mapped_column(String, nullable=True)
    backdrop_local_path: Mapped[str | None] = mapped_column(String, nullable=True)
    is_adult: Mapped[bool | None] = mapped_column(Boolean, nullable=True, default=False)
    tmdb_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    media_type: Mapped[str | None] = mapped_column(String, nullable=True)
    original_title: Mapped[str | None] = mapped_column(String, nullable=True)
    overview: Mapped[str | None] = mapped_column(Text, nullable=True)
    poster_url: Mapped[str | None] = mapped_column(String, nullable=True)
    backdrop_url: Mapped[str | None] = mapped_column(String, nullable=True)
    logo_url: Mapped[str | None] = mapped_column(String, nullable=True)
    logo_local_path: Mapped[str | None] = mapped_column(String, nullable=True)
    imdb_id: Mapped[str | None] = mapped_column(String, nullable=True)
    rating: Mapped[float | None] = mapped_column(Double, nullable=True)
    vote_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    metadata_source: Mapped[str | None] = mapped_column(String, nullable=True)
    tags: Mapped[str | None] = mapped_column(Text, nullable=True)
    view_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    studio: Mapped[str | None] = mapped_column(String, nullable=True)
    subtitle: Mapped[str | None] = mapped_column(String, nullable=True)
    metadata_updated_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    scrape_attempted_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    series_id: Mapped[int | None] = mapped_column(
        ForeignKey("video_series.id", name="fk_video_series"), nullable=True, index=True
    )
    season_number: Mapped[int | None] = mapped_column(Integer, nullable=True)
    episode_number: Mapped[int | None] = mapped_column(Integer, nullable=True)
    is_series: Mapped[bool | None] = mapped_column(Boolean, nullable=True, default=False)
    series_name: Mapped[str | None] = mapped_column(String, nullable=True)
    status: Mapped[str | None] = mapped_column(String, nullable=True)
    library_id: Mapped[int | None] = mapped_column("library_id", Integer, nullable=True, index=True)

    series: Mapped[VideoSeries | None] = relationship(lazy="joined")


class VideoActor(TimestampMixin, Base):
    __tablename__ = "video_actors"

    video_id: Mapped[int] = mapped_column(ForeignKey("videos.id"), nullable=False, index=True)
    name: Mapped[str] = mapped_column(String, nullable=False)
    character: Mapped[str | None] = mapped_column(String, nullable=True)
    image_path: Mapped[str | None] = mapped_column(String, nullable=True)
    image_url: Mapped[str | None] = mapped_column(String, nullable=True)
    source_actor_id: Mapped[int | None] = mapped_column(Integer, nullable=True)


class ActorProfile(TimestampMixin, Base):
    __tablename__ = "actor_profiles"

    actor_id: Mapped[int] = mapped_column("actor_id", Integer, unique=True, nullable=False, index=True)
    tmdb_id: Mapped[int | None] = mapped_column("tmdb_id", Integer, nullable=True)
    name: Mapped[str | None] = mapped_column(String, nullable=True)
    biography: Mapped[str | None] = mapped_column(Text, nullable=True)
    also_known_as_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    birthday: Mapped[str | None] = mapped_column(String, nullable=True)
    deathday: Mapped[str | None] = mapped_column(String, nullable=True)
    gender: Mapped[int | None] = mapped_column(Integer, nullable=True)
    place_of_birth: Mapped[str | None] = mapped_column(String, nullable=True)
    homepage: Mapped[str | None] = mapped_column(String, nullable=True)
    imdb_id: Mapped[str | None] = mapped_column(String, nullable=True)
    known_for_department: Mapped[str | None] = mapped_column(String, nullable=True)
    popularity: Mapped[float | None] = mapped_column(Double, nullable=True)
    cast_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    crew_json: Mapped[str | None] = mapped_column(Text, nullable=True)
    fetched_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class WatchProgress(TimestampMixin, Base):
    __tablename__ = "watch_progress"
    __table_args__ = (UniqueConstraint("user_id", "video_id", name="uq_watch_progress"),)

    user_id: Mapped[int | None] = mapped_column("user_id", Integer, nullable=True, index=True)
    video_id: Mapped[int] = mapped_column(ForeignKey("videos.id"), nullable=False)
    position_seconds: Mapped[float | None] = mapped_column(Double, nullable=True)
    duration_seconds: Mapped[float | None] = mapped_column(Double, nullable=True)
    completed: Mapped[bool | None] = mapped_column(Boolean, nullable=True, default=False)


class Favorite(TimestampMixin, Base):
    __tablename__ = "favorites"
    __table_args__ = (
        UniqueConstraint("user_id", "content_type", "content_id", name="uq_favorites"),
    )

    user_id: Mapped[int] = mapped_column("user_id", Integer, nullable=False, index=True)
    content_type: Mapped[str] = mapped_column("content_type", String(16), nullable=False)
    content_id: Mapped[int] = mapped_column("content_id", Integer, nullable=False)
