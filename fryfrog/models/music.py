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


class MusicArtist(TimestampMixin, Base):
    __tablename__ = "music_artists"

    name: Mapped[str] = mapped_column(String, nullable=False, index=True)
    sort_name: Mapped[str | None] = mapped_column(String, nullable=True)
    cover_art_path: Mapped[str | None] = mapped_column(String, nullable=True)
    library_id: Mapped[int | None] = mapped_column("library_id", Integer, nullable=True, index=True)


class MusicAlbum(TimestampMixin, Base):
    __tablename__ = "music_albums"

    title: Mapped[str] = mapped_column(String, nullable=False, index=True)
    artist_name: Mapped[str | None] = mapped_column(String, nullable=True)
    artist_id: Mapped[int | None] = mapped_column(ForeignKey("music_artists.id"), nullable=True, index=True)
    year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    genre: Mapped[str | None] = mapped_column(String, nullable=True)
    cover_art_path: Mapped[str | None] = mapped_column(String, nullable=True)
    track_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    library_id: Mapped[int | None] = mapped_column("library_id", Integer, nullable=True, index=True)

    artist: Mapped[MusicArtist | None] = relationship(lazy="joined")


class MusicSong(TimestampMixin, Base):
    __tablename__ = "music_songs"

    title: Mapped[str] = mapped_column(String, nullable=False, index=True)
    artist_name: Mapped[str | None] = mapped_column(String, nullable=True)
    album_name: Mapped[str | None] = mapped_column(String, nullable=True)
    album_id: Mapped[int | None] = mapped_column(ForeignKey("music_albums.id"), nullable=True, index=True)
    artist_id: Mapped[int | None] = mapped_column(ForeignKey("music_artists.id"), nullable=True, index=True)
    track_number: Mapped[int | None] = mapped_column(Integer, nullable=True)
    disc_number: Mapped[int | None] = mapped_column(Integer, nullable=True)
    duration_seconds: Mapped[float | None] = mapped_column(Double, nullable=True)
    file_path: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    file_size: Mapped[int | None] = mapped_column(Integer, nullable=True)
    format: Mapped[str | None] = mapped_column(String, nullable=True)
    bit_rate: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sample_rate: Mapped[int | None] = mapped_column(Integer, nullable=True)
    genre: Mapped[str | None] = mapped_column(String, nullable=True)
    year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    lyrics_path: Mapped[str | None] = mapped_column(String, nullable=True)
    lyrics_content: Mapped[str | None] = mapped_column(Text, nullable=True)
    library_id: Mapped[int | None] = mapped_column("library_id", Integer, nullable=True, index=True)


class MusicPlaylist(TimestampMixin, Base):
    __tablename__ = "music_playlists"

    name: Mapped[str] = mapped_column(String, nullable=False)
    user_id: Mapped[int] = mapped_column("user_id", Integer, nullable=False, index=True)
    comment: Mapped[str | None] = mapped_column(String, nullable=True)
    is_public: Mapped[bool | None] = mapped_column(Boolean, nullable=True, default=False)


class MusicPlaylistEntry(TimestampMixin, Base):
    __tablename__ = "music_playlist_entries"
    __table_args__ = (UniqueConstraint("playlist_id", "position", name="uq_playlist_pos"),)

    playlist_id: Mapped[int] = mapped_column(ForeignKey("music_playlists.id"), nullable=False, index=True)
    song_id: Mapped[int | None] = mapped_column(ForeignKey("music_songs.id"), nullable=True)
    position: Mapped[int] = mapped_column(Integer, nullable=False)


class MusicRating(TimestampMixin, Base):
    __tablename__ = "music_ratings"
    __table_args__ = (
        UniqueConstraint("user_id", "target_type", "target_id", name="uq_music_rating"),
    )

    user_id: Mapped[int] = mapped_column("user_id", Integer, nullable=False, index=True)
    target_type: Mapped[str] = mapped_column("target_type", String(16), nullable=False)
    target_id: Mapped[int] = mapped_column("target_id", Integer, nullable=False)
    rating: Mapped[int] = mapped_column(Integer, nullable=False)


class MusicStar(TimestampMixin, Base):
    __tablename__ = "music_stars"
    __table_args__ = (
        UniqueConstraint("user_id", "target_type", "target_id", name="uq_music_star"),
    )

    user_id: Mapped[int] = mapped_column("user_id", Integer, nullable=False, index=True)
    target_type: Mapped[str] = mapped_column("target_type", String(16), nullable=False)
    target_id: Mapped[int] = mapped_column("target_id", Integer, nullable=False)


class MusicBookmark(TimestampMixin, Base):
    __tablename__ = "music_bookmarks"

    user_id: Mapped[int] = mapped_column("user_id", Integer, nullable=False, index=True)
    song_id: Mapped[int] = mapped_column(ForeignKey("music_songs.id"), nullable=False)
    position_seconds: Mapped[float | None] = mapped_column(Double, nullable=True)
    comment: Mapped[str | None] = mapped_column(String, nullable=True)
    created_at_millis: Mapped[int | None] = mapped_column(Integer, nullable=True)


class MusicPlayStat(TimestampMixin, Base):
    __tablename__ = "music_play_stats"

    song_id: Mapped[int] = mapped_column(ForeignKey("music_songs.id"), nullable=False, index=True)
    user_id: Mapped[int | None] = mapped_column("user_id", Integer, nullable=True, index=True)
    play_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    last_played_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class MusicPlayQueue(TimestampMixin, Base):
    __tablename__ = "music_play_queues"

    user_id: Mapped[int] = mapped_column("user_id", Integer, nullable=False, index=True)
    entry_ids: Mapped[str | None] = mapped_column(Text, nullable=True)
    current_song_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    position_seconds: Mapped[float | None] = mapped_column(Double, nullable=True)
    changed_at_millis: Mapped[int | None] = mapped_column(Integer, nullable=True)
