"""音乐 REST API（/api/v1/music）与 Subsonic 兼容层（/rest）。"""

from __future__ import annotations

import hashlib
import html
import json
import logging
import re
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Body, Depends, Query, Request, Response
from fastapi.responses import FileResponse, PlainTextResponse
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from fryfrog.config import get_settings
from fryfrog.core.api_response import ApiResponse, PageResponse
from fryfrog.core.crypto import SubsonicPasswordEncryptor
from fryfrog.core.deps import DbSession, get_media_library_service
from fryfrog.core.exceptions import BadRequestException, ResourceNotFoundException
from fryfrog.core.security import ANONYMOUS_ID, UserService, current_user_id, set_current_user_id, verify_password
from fryfrog.core.signer import sign
from fryfrog.core.utils import placeholder_jpeg
from fryfrog.models.library import MediaLibrary
from fryfrog.models.music import (
    MusicAlbum,
    MusicArtist,
    MusicBookmark,
    MusicPlayQueue,
    MusicPlayStat,
    MusicPlaylist,
    MusicPlaylistEntry,
    MusicRating,
    MusicSong,
    MusicStar,
)
from fryfrog.models.user import User, UserRole
from fryfrog.services.assets import cover_bytes
from fryfrog.services.media_library import MediaLibraryService
from fryfrog.services.music_scan import organize_music_library, scan_music_library

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/music", tags=["音乐"])
subsonic_router = APIRouter(prefix="/rest", tags=["Subsonic"])

TYPE_SONG = "SONG"
TYPE_ALBUM = "ALBUM"
TYPE_ARTIST = "ARTIST"

API_VERSION = "1.16.1"
SERVER_VERSION = "0.1.0"
SERVER_TYPE = "fryfrog-hub"

ERROR_GENERIC = 0
ERROR_MISSING_PARAM = 10
ERROR_AUTH = 40
ERROR_UNAUTHORIZED = 50
ERROR_NOT_FOUND = 70


class SubsonicApiError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


# ── 公共查询助手 ──────────────────────────────────────────


def _allowed_ids(db: Session) -> list[int]:
    return MediaLibraryService(UserService()).get_allowable_library_ids(db)


def _starred_ids(db: Session, user_id: int, target_type: str, ids: list[int]) -> set[int]:
    if not ids:
        return set()
    rows = db.scalars(
        select(MusicStar.target_id).where(
            MusicStar.user_id == user_id,
            MusicStar.target_type == target_type,
            MusicStar.target_id.in_(ids),
        )
    ).all()
    return set(rows)


def _rating_map(db: Session, user_id: int, target_type: str, ids: list[int]) -> dict[int, int]:
    if not ids:
        return {}
    rows = db.execute(
        select(MusicRating.target_id, MusicRating.rating).where(
            MusicRating.user_id == user_id,
            MusicRating.target_type == target_type,
            MusicRating.target_id.in_(ids),
        )
    ).all()
    return {int(r[0]): int(r[1]) for r in rows}


def _play_count_map(db: Session, user_id: int, song_ids: list[int]) -> dict[int, int]:
    if not song_ids:
        return {}
    rows = db.execute(
        select(MusicPlayStat.song_id, MusicPlayStat.play_count).where(
            MusicPlayStat.user_id == user_id,
            MusicPlayStat.song_id.in_(song_ids),
        )
    ).all()
    return {int(r[0]): int(r[1]) for r in rows}


def _set_star(db: Session, user_id: int, target_type: str, target_id: int, status: bool) -> None:
    row = db.scalar(
        select(MusicStar).where(
            MusicStar.user_id == user_id,
            MusicStar.target_type == target_type,
            MusicStar.target_id == target_id,
        )
    )
    if status and row is None:
        db.add(MusicStar(user_id=user_id, target_type=target_type, target_id=target_id))
    elif not status and row is not None:
        db.delete(row)
    db.flush()


def _set_rating(db: Session, user_id: int, target_type: str, target_id: int, rating: int) -> None:
    if rating < 0 or rating > 5:
        raise BadRequestException("rating 必须在 0-5 之间")
    row = db.scalar(
        select(MusicRating).where(
            MusicRating.user_id == user_id,
            MusicRating.target_type == target_type,
            MusicRating.target_id == target_id,
        )
    )
    if rating == 0:
        if row is not None:
            db.delete(row)
    elif row is not None:
        row.rating = rating
    else:
        db.add(MusicRating(user_id=user_id, target_type=target_type, target_id=target_id, rating=rating))
    db.flush()


def _require_song(db: Session, song_id: int) -> MusicSong:
    song = db.get(MusicSong, song_id)
    if song is None:
        raise ResourceNotFoundException("MusicSong", "id", song_id)
    return song


def _require_album(db: Session, album_id: int) -> MusicAlbum:
    album = db.get(MusicAlbum, album_id)
    if album is None:
        raise ResourceNotFoundException("MusicAlbum", "id", album_id)
    return album


def _require_artist(db: Session, artist_id: int) -> MusicArtist:
    artist = db.get(MusicArtist, artist_id)
    if artist is None:
        raise ResourceNotFoundException("MusicArtist", "id", artist_id)
    return artist


def _songs_of_album(db: Session, album_id: int) -> list[MusicSong]:
    return list(
        db.scalars(
            select(MusicSong)
            .where(MusicSong.album_id == album_id)
            .order_by(MusicSong.disc_number.asc(), MusicSong.track_number.asc(), MusicSong.id.asc())
        ).all()
    )


def _albums_of_artist(db: Session, artist_id: int) -> list[MusicAlbum]:
    return list(
        db.scalars(
            select(MusicAlbum).where(MusicAlbum.artist_id == artist_id).order_by(MusicAlbum.year.asc(), MusicAlbum.title.asc())
        ).all()
    )


def _album_duration(db: Session, album_id: int) -> int:
    total = db.scalar(
        select(func.coalesce(func.sum(MusicSong.duration_seconds), 0)).where(MusicSong.album_id == album_id)
    )
    return int(total or 0)


def _song_dto(db: Session, song: MusicSong, user_id: int) -> dict:
    starred = song.id in _starred_ids(db, user_id, TYPE_SONG, [song.id]) if user_id else False
    ratings = _rating_map(db, user_id, TYPE_SONG, [song.id]) if user_id else {}
    plays = _play_count_map(db, user_id, [song.id]) if user_id else {}
    has_lyrics = bool(song.lyrics_content) or bool(song.lyrics_path and Path(song.lyrics_path).is_file())
    return {
        "id": song.id,
        "title": song.title,
        "artistName": song.artist_name,
        "albumName": song.album_name,
        "artistId": song.artist_id,
        "albumId": song.album_id,
        "trackNumber": song.track_number,
        "discNumber": song.disc_number,
        "durationSeconds": song.duration_seconds,
        "format": song.format,
        "bitRate": song.bit_rate,
        "genre": song.genre,
        "year": song.year,
        "fileSize": song.file_size,
        "streamUrl": sign(f"/api/v1/music/songs/{song.id}/stream"),
        "coverUrl": sign(f"/api/v1/music/songs/{song.id}/cover"),
        "lyricsUrl": sign(f"/api/v1/music/songs/{song.id}/lyrics") if has_lyrics else None,
        "starred": starred,
        "rating": ratings.get(song.id),
        "playCount": plays.get(song.id, 0),
    }


def _song_dtos(db: Session, songs: list[MusicSong], user_id: int) -> list[dict]:
    ids = [s.id for s in songs]
    starred = _starred_ids(db, user_id, TYPE_SONG, ids) if user_id else set()
    ratings = _rating_map(db, user_id, TYPE_SONG, ids) if user_id else {}
    plays = _play_count_map(db, user_id, ids) if user_id else {}
    out = []
    for song in songs:
        has_lyrics = bool(song.lyrics_content) or bool(song.lyrics_path and Path(song.lyrics_path).is_file())
        out.append(
            {
                "id": song.id,
                "title": song.title,
                "artistName": song.artist_name,
                "albumName": song.album_name,
                "artistId": song.artist_id,
                "albumId": song.album_id,
                "trackNumber": song.track_number,
                "discNumber": song.disc_number,
                "durationSeconds": song.duration_seconds,
                "format": song.format,
                "bitRate": song.bit_rate,
                "genre": song.genre,
                "year": song.year,
                "fileSize": song.file_size,
                "streamUrl": sign(f"/api/v1/music/songs/{song.id}/stream"),
                "coverUrl": sign(f"/api/v1/music/songs/{song.id}/cover"),
                "lyricsUrl": sign(f"/api/v1/music/songs/{song.id}/lyrics") if has_lyrics else None,
                "starred": song.id in starred,
                "rating": ratings.get(song.id),
                "playCount": plays.get(song.id, 0),
            }
        )
    return out


def _album_dto(db: Session, album: MusicAlbum, user_id: int, songs: list[MusicSong] | None = None) -> dict:
    ids = [album.id]
    starred = album.id in _starred_ids(db, user_id, TYPE_ALBUM, ids) if user_id else False
    ratings = _rating_map(db, user_id, TYPE_ALBUM, ids) if user_id else {}
    song_list = songs if songs is not None else []
    duration = sum(int(s.duration_seconds or 0) for s in song_list) if song_list else _album_duration(db, album.id)
    dto = {
        "id": album.id,
        "title": album.title,
        "artistName": album.artist_name,
        "artistId": album.artist_id,
        "year": album.year,
        "genre": album.genre,
        "coverUrl": sign(f"/api/v1/music/albums/{album.id}/cover"),
        "trackCount": album.track_count if album.track_count is not None else (len(song_list) if song_list else None),
        "durationSeconds": duration,
        "starred": starred,
        "rating": ratings.get(album.id),
    }
    if song_list:
        dto["songs"] = _song_dtos(db, song_list, user_id)
    return dto


def _album_dtos(db: Session, albums: list[MusicAlbum], user_id: int) -> list[dict]:
    ids = [a.id for a in albums]
    starred = _starred_ids(db, user_id, TYPE_ALBUM, ids) if user_id else set()
    ratings = _rating_map(db, user_id, TYPE_ALBUM, ids) if user_id else {}
    counts = dict(
        db.execute(
            select(MusicSong.album_id, func.count(MusicSong.id)).where(MusicSong.album_id.in_(ids)).group_by(MusicSong.album_id)
        ).all()
    ) if ids else {}
    return [
        {
            "id": a.id,
            "title": a.title,
            "artistName": a.artist_name,
            "artistId": a.artist_id,
            "year": a.year,
            "genre": a.genre,
            "coverUrl": sign(f"/api/v1/music/albums/{a.id}/cover"),
            "trackCount": a.track_count if a.track_count is not None else counts.get(a.id),
            "durationSeconds": _album_duration(db, a.id),
            "starred": a.id in starred,
            "rating": ratings.get(a.id),
        }
        for a in albums
    ]


def _artist_dto(db: Session, artist: MusicArtist, user_id: int, album_count: int | None = None, albums: list[dict] | None = None) -> dict:
    starred = artist.id in _starred_ids(db, user_id, TYPE_ARTIST, [artist.id]) if user_id else False
    if album_count is None:
        album_count = int(db.scalar(select(func.count(MusicAlbum.id)).where(MusicAlbum.artist_id == artist.id)) or 0)
    cover_url = None
    if artist.cover_art_path and Path(artist.cover_art_path).is_file():
        cover_url = sign(f"/api/v1/music/artists/{artist.id}/cover")
    dto = {
        "id": artist.id,
        "name": artist.name,
        "sortName": artist.sort_name,
        "coverUrl": cover_url,
        "albumCount": album_count,
        "starred": starred,
    }
    if albums is not None:
        dto["albums"] = albums
    return dto


def _artist_dtos(db: Session, artists: list[MusicArtist], user_id: int) -> list[dict]:
    ids = [a.id for a in artists]
    starred = _starred_ids(db, user_id, TYPE_ARTIST, ids) if user_id else set()
    counts = dict(
        db.execute(
            select(MusicAlbum.artist_id, func.count(MusicAlbum.id)).where(MusicAlbum.artist_id.in_(ids)).group_by(MusicAlbum.artist_id)
        ).all()
    ) if ids else {}
    out = []
    for artist in artists:
        cover_url = None
        if artist.cover_art_path and Path(artist.cover_art_path).is_file():
            cover_url = sign(f"/api/v1/music/artists/{artist.id}/cover")
        out.append(
            {
                "id": artist.id,
                "name": artist.name,
                "sortName": artist.sort_name,
                "coverUrl": cover_url,
                "albumCount": counts.get(artist.id, 0),
                "starred": artist.id in starred,
            }
        )
    return out


def _star_target(type_plural: str) -> str:
    return {"songs": TYPE_SONG, "albums": TYPE_ALBUM, "artists": TYPE_ARTIST}[type_plural]


def _audio_content_type(fmt: str | None) -> str:
    if not fmt:
        return "audio/mpeg"
    return {
        "flac": "audio/flac",
        "m4a": "audio/mp4",
        "mp4": "audio/mp4",
        "ogg": "audio/ogg",
        "opus": "audio/opus",
        "wav": "audio/wav",
        "wma": "audio/x-ms-wma",
        "aac": "audio/aac",
    }.get(fmt.lower(), "audio/mpeg")


def _image_response(path: str | None, label: str = "") -> Response:
    data, media = cover_bytes(path, label=label)
    return Response(content=data, media_type=media)


def _embedded_cover(path: str | None) -> tuple[bytes, str] | None:
    """用 ffmpeg 抽取音频内嵌封面；失败返回 None。"""
    if not path or not Path(path).is_file():
        return None
    from fryfrog.media_core import get_ffmpeg_runtime

    runtime = get_ffmpeg_runtime()
    if not runtime.is_available():
        return None
    import subprocess
    import tempfile

    tmp_dir = Path(tempfile.mkdtemp(prefix="fryfrog-cover-"))
    out = tmp_dir / "cover.jpg"
    try:
        proc = subprocess.run(
            [runtime.ffmpeg_path, "-y", "-i", path, "-an", "-vcodec", "copy", str(out)],
            capture_output=True,
            timeout=10,
            check=False,
            env=runtime.apply_library_env(),
        )
        if proc.returncode == 0 and out.is_file() and out.stat().st_size > 0:
            media = "image/jpeg"
            if out.suffix.lower() == ".png":
                media = "image/png"
            return out.read_bytes(), media
    except Exception:
        logger.debug("Failed to extract embedded cover from %s", path, exc_info=True)
    finally:
        try:
            out.unlink(missing_ok=True)
            tmp_dir.rmdir()
        except OSError:
            pass
    return None


# ── 音乐 REST API ─────────────────────────────────────────


@router.get("/home")
def music_home(db: DbSession):
    uid = current_user_id()
    allowed = _allowed_ids(db)
    libs = [
        lib
        for lib in db.scalars(
            select(MediaLibrary).where(MediaLibrary.enabled.is_(True), MediaLibrary.type == "MUSIC")
        ).all()
        if lib.id in allowed
    ]
    groups = []
    for lib in libs:
        albums = list(
            db.scalars(select(MusicAlbum).where(MusicAlbum.library_id == lib.id).order_by(MusicAlbum.title.asc())).all()
        )
        artists = list(
            db.scalars(select(MusicArtist).where(MusicArtist.library_id == lib.id).order_by(MusicArtist.name.asc())).all()
        )
        groups.append(
            {
                "libraryId": lib.id,
                "libraryName": lib.name,
                "libraryPath": lib.path,
                "albums": _album_dtos(db, albums, uid),
                "artists": _artist_dtos(db, artists, uid),
                "albumCount": len(albums),
                "artistCount": len(artists),
            }
        )
    return ApiResponse.ok(groups)


@router.get("/artists")
def list_artists(db: DbSession, page: int = 0, size: int = 20):
    uid = current_user_id()
    total = int(db.scalar(select(func.count(MusicArtist.id))) or 0)
    artists = list(
        db.scalars(select(MusicArtist).order_by(MusicArtist.name.asc()).offset(page * size).limit(size)).all()
    )
    return ApiResponse.ok(PageResponse.of(_artist_dtos(db, artists, uid), page, size, total).model_dump())


@router.get("/artists/{artist_id}")
def get_artist(artist_id: int, db: DbSession):
    uid = current_user_id()
    artist = _require_artist(db, artist_id)
    albums = _albums_of_artist(db, artist_id)
    dto = _artist_dto(db, artist, uid, album_count=len(albums), albums=_album_dtos(db, albums, uid))
    return ApiResponse.ok(dto)


@router.get("/albums")
def list_albums(db: DbSession, page: int = 0, size: int = 20):
    uid = current_user_id()
    total = int(db.scalar(select(func.count(MusicAlbum.id))) or 0)
    albums = list(
        db.scalars(select(MusicAlbum).order_by(MusicAlbum.title.asc()).offset(page * size).limit(size)).all()
    )
    return ApiResponse.ok(PageResponse.of(_album_dtos(db, albums, uid), page, size, total).model_dump())


@router.get("/albums/{album_id}")
def get_album(album_id: int, db: DbSession):
    uid = current_user_id()
    album = _require_album(db, album_id)
    songs = _songs_of_album(db, album_id)
    return ApiResponse.ok(_album_dto(db, album, uid, songs))


@router.get("/albums/{album_id}/songs")
def get_album_songs(album_id: int, db: DbSession):
    uid = current_user_id()
    _require_album(db, album_id)
    return ApiResponse.ok(_song_dtos(db, _songs_of_album(db, album_id), uid))


@router.get("/songs")
def search_songs(
    db: DbSession,
    q: str | None = None,
    genre: str | None = None,
    albumId: int | None = None,
    artistId: int | None = None,
    page: int = 0,
    size: int = 20,
):
    uid = current_user_id()
    query = select(MusicSong)
    if genre:
        query = query.where(MusicSong.genre.ilike(genre))
    if albumId:
        query = query.where(MusicSong.album_id == albumId)
    if artistId:
        query = query.where(MusicSong.artist_id == artistId)
    if q:
        like = f"%{q}%"
        query = query.where(
            or_(MusicSong.title.ilike(like), MusicSong.artist_name.ilike(like), MusicSong.album_name.ilike(like))
        )
    total = int(db.scalar(select(func.count()).select_from(query.subquery())) or 0)
    songs = list(db.scalars(query.order_by(MusicSong.id.asc()).offset(page * size).limit(size)).all())
    return ApiResponse.ok(PageResponse.of(_song_dtos(db, songs, uid), page, size, total).model_dump())


@router.get("/songs/{song_id}")
def get_song(song_id: int, db: DbSession):
    uid = current_user_id()
    return ApiResponse.ok(_song_dto(db, _require_song(db, song_id), uid))


@router.get("/songs/{song_id}/stream")
def stream_song(song_id: int, db: DbSession):
    song = _require_song(db, song_id)
    path = Path(song.file_path)
    if not path.is_file():
        raise ResourceNotFoundException("MusicSong", "file", song_id)
    return FileResponse(path, media_type=_audio_content_type(song.format), filename=path.name)


@router.get("/songs/{song_id}/lyrics")
def get_lyrics(song_id: int, db: DbSession):
    song = _require_song(db, song_id)
    if song.lyrics_content:
        return PlainTextResponse(song.lyrics_content)
    if song.lyrics_path and Path(song.lyrics_path).is_file():
        return PlainTextResponse(Path(song.lyrics_path).read_text(encoding="utf-8", errors="replace"))
    raise ResourceNotFoundException("MusicLyrics", "songId", song_id)


@router.get("/songs/{song_id}/cover")
def get_song_cover(song_id: int, db: DbSession):
    song = _require_song(db, song_id)
    album = db.get(MusicAlbum, song.album_id) if song.album_id else None
    if album and album.cover_art_path and Path(album.cover_art_path).is_file():
        return _image_response(album.cover_art_path)
    embedded = _embedded_cover(song.file_path)
    if embedded:
        return Response(content=embedded[0], media_type=embedded[1])
    return _image_response(None, label=song.album_name or song.title)


@router.get("/albums/{album_id}/cover")
def get_album_cover(album_id: int, db: DbSession):
    album = _require_album(db, album_id)
    if album.cover_art_path and Path(album.cover_art_path).is_file():
        return _image_response(album.cover_art_path)
    for song in _songs_of_album(db, album_id):
        embedded = _embedded_cover(song.file_path)
        if embedded:
            return Response(content=embedded[0], media_type=embedded[1])
    return _image_response(None, label=album.title)


@router.get("/artists/{artist_id}/cover")
def get_artist_cover(artist_id: int, db: DbSession):
    artist = _require_artist(db, artist_id)
    return _image_response(artist.cover_art_path, label=artist.name)


@router.get("/genres")
def get_genres(db: DbSession):
    rows = db.scalars(
        select(MusicSong.genre).where(MusicSong.genre.isnot(None), MusicSong.genre != "").distinct().order_by(MusicSong.genre)
    ).all()
    return ApiResponse.ok(list(rows))


@router.put("/songs/{song_id}/star")
def star_song(song_id: int, db: DbSession, status: bool = Query(...)):
    uid = current_user_id()
    _require_song(db, song_id)
    _set_star(db, uid, TYPE_SONG, song_id, status)
    return ApiResponse.ok(None)


@router.put("/albums/{album_id}/star")
def star_album(album_id: int, db: DbSession, status: bool = Query(...)):
    uid = current_user_id()
    _require_album(db, album_id)
    _set_star(db, uid, TYPE_ALBUM, album_id, status)
    return ApiResponse.ok(None)


@router.put("/artists/{artist_id}/star")
def star_artist(artist_id: int, db: DbSession, status: bool = Query(...)):
    uid = current_user_id()
    _require_artist(db, artist_id)
    _set_star(db, uid, TYPE_ARTIST, artist_id, status)
    return ApiResponse.ok(None)


@router.put("/songs/{song_id}/rating")
def rate_song(song_id: int, db: DbSession, rating: int = Query(...)):
    uid = current_user_id()
    _require_song(db, song_id)
    _set_rating(db, uid, TYPE_SONG, song_id, rating)
    return ApiResponse.ok(None)


@router.put("/albums/{album_id}/rating")
def rate_album(album_id: int, db: DbSession, rating: int = Query(...)):
    uid = current_user_id()
    _require_album(db, album_id)
    _set_rating(db, uid, TYPE_ALBUM, album_id, rating)
    return ApiResponse.ok(None)


@router.put("/artists/{artist_id}/rating")
def rate_artist(artist_id: int, db: DbSession, rating: int = Query(...)):
    uid = current_user_id()
    _require_artist(db, artist_id)
    _set_rating(db, uid, TYPE_ARTIST, artist_id, rating)
    return ApiResponse.ok(None)


def _playlist_dict(pl: MusicPlaylist) -> dict:
    return {
        "id": pl.id,
        "name": pl.name,
        "comment": pl.comment,
        "public": bool(pl.is_public),
        "userId": pl.user_id,
        "createdAt": pl.created_at.isoformat() if pl.created_at else None,
    }


def _can_read_playlist(pl: MusicPlaylist, user_id: int) -> bool:
    return pl.user_id == user_id or bool(pl.is_public) or user_id == ANONYMOUS_ID


def _can_write_playlist(pl: MusicPlaylist, user_id: int) -> bool:
    return pl.user_id == user_id or user_id == ANONYMOUS_ID


def _playlist_entries(db: Session, playlist_id: int) -> list[MusicSong]:
    entries = list(
        db.scalars(
            select(MusicPlaylistEntry)
            .where(MusicPlaylistEntry.playlist_id == playlist_id)
            .order_by(MusicPlaylistEntry.position.asc())
        ).all()
    )
    songs = []
    for entry in entries:
        if entry.song_id:
            song = db.get(MusicSong, entry.song_id)
            if song:
                songs.append(song)
    return songs


def _replace_playlist_songs(db: Session, playlist_id: int, song_ids: list[int]) -> None:
    for entry in db.scalars(select(MusicPlaylistEntry).where(MusicPlaylistEntry.playlist_id == playlist_id)).all():
        db.delete(entry)
    db.flush()
    for pos, sid in enumerate(song_ids):
        db.add(MusicPlaylistEntry(playlist_id=playlist_id, song_id=sid, position=pos))
    db.flush()


@router.get("/playlists")
def list_playlists(db: DbSession):
    uid = current_user_id()
    rows = list(
        db.scalars(
            select(MusicPlaylist).where(
                or_(MusicPlaylist.user_id == uid, MusicPlaylist.is_public.is_(True))
            )
        ).all()
    )
    return ApiResponse.ok([_playlist_dict(p) for p in rows])


@router.get("/playlists/{playlist_id}")
def get_playlist(playlist_id: int, db: DbSession):
    uid = current_user_id()
    pl = db.get(MusicPlaylist, playlist_id)
    if pl is None or not _can_read_playlist(pl, uid):
        raise ResourceNotFoundException("MusicPlaylist", "id", playlist_id)
    data = _playlist_dict(pl)
    data["songs"] = _song_dtos(db, _playlist_entries(db, playlist_id), uid)
    return ApiResponse.ok(data)


@router.post("/playlists")
def create_playlist(db: DbSession, body: dict = Body(...)):
    uid = current_user_id()
    pl = MusicPlaylist(
        name=body.get("name") or "New Playlist",
        comment=body.get("comment"),
        is_public=bool(body.get("isPublic") or body.get("public")),
        user_id=uid,
    )
    db.add(pl)
    db.flush()
    song_ids = [int(x) for x in (body.get("songIds") or [])]
    if song_ids:
        _replace_playlist_songs(db, pl.id, song_ids)
    return ApiResponse.ok(_playlist_dict(pl))


@router.put("/playlists/{playlist_id}")
def update_playlist(playlist_id: int, db: DbSession, body: dict = Body(...)):
    uid = current_user_id()
    pl = db.get(MusicPlaylist, playlist_id)
    if pl is None or not _can_write_playlist(pl, uid):
        raise ResourceNotFoundException("MusicPlaylist", "id", playlist_id)
    if body.get("name") is not None:
        pl.name = body["name"]
    if body.get("comment") is not None:
        pl.comment = body["comment"]
    public = body.get("isPublic", body.get("public"))
    if public is not None:
        pl.is_public = bool(public)
    db.flush()
    if body.get("songIds") is not None:
        _replace_playlist_songs(db, playlist_id, [int(x) for x in body["songIds"]])
    if body.get("songIdsToAdd"):
        pos = int(db.scalar(select(func.coalesce(func.max(MusicPlaylistEntry.position), -1)).where(MusicPlaylistEntry.playlist_id == playlist_id)) or -1) + 1
        for sid in body["songIdsToAdd"]:
            db.add(MusicPlaylistEntry(playlist_id=playlist_id, song_id=int(sid), position=pos))
            pos += 1
        db.flush()
    if body.get("songIndexesToRemove"):
        entries = list(
            db.scalars(
                select(MusicPlaylistEntry)
                .where(MusicPlaylistEntry.playlist_id == playlist_id)
                .order_by(MusicPlaylistEntry.position.asc())
            ).all()
        )
        drop = {int(i) for i in body["songIndexesToRemove"]}
        for idx, entry in enumerate(entries):
            if idx in drop:
                db.delete(entry)
        db.flush()
    return ApiResponse.ok(_playlist_dict(pl))


@router.delete("/playlists/{playlist_id}")
def delete_playlist(playlist_id: int, db: DbSession):
    uid = current_user_id()
    pl = db.get(MusicPlaylist, playlist_id)
    if pl is None or not _can_write_playlist(pl, uid):
        raise ResourceNotFoundException("MusicPlaylist", "id", playlist_id)
    for entry in db.scalars(select(MusicPlaylistEntry).where(MusicPlaylistEntry.playlist_id == playlist_id)).all():
        db.delete(entry)
    db.delete(pl)
    db.flush()
    return ApiResponse.ok(None)


def _scrobble(db: Session, user_id: int, song_id: int, time_ms: int | None, submission: bool) -> None:
    _require_song(db, song_id)
    if not submission:
        return
    row = db.scalar(select(MusicPlayStat).where(MusicPlayStat.song_id == song_id, MusicPlayStat.user_id == user_id))
    if row is None:
        row = MusicPlayStat(song_id=song_id, user_id=user_id, play_count=0)
        db.add(row)
    row.play_count = (row.play_count or 0) + 1
    row.last_played_at = datetime.now()
    db.flush()


@router.post("/scrobble")
def scrobble(db: DbSession, body: dict = Body(...)):
    uid = current_user_id()
    song_id = int(body.get("songId") or 0)
    submission = body.get("submission")
    submission = True if submission is None else bool(submission)
    _scrobble(db, uid, song_id, body.get("time"), submission)
    return ApiResponse.ok(None)


@router.get("/play-queue")
def get_play_queue(db: DbSession):
    uid = current_user_id()
    queue = db.scalar(select(MusicPlayQueue).where(MusicPlayQueue.user_id == uid))
    if queue is None:
        return ApiResponse.ok(None)
    return ApiResponse.ok(
        {
            "id": queue.id,
            "entryIds": queue.entry_ids,
            "currentSongId": queue.current_song_id,
            "positionSeconds": queue.position_seconds,
            "changedAtMillis": queue.changed_at_millis,
        }
    )


@router.put("/play-queue")
def save_play_queue(db: DbSession, body: dict = Body(...)):
    uid = current_user_id()
    song_ids = body.get("songIds") or []
    entry_ids = ",".join(str(int(x)) for x in song_ids)
    queue = db.scalar(select(MusicPlayQueue).where(MusicPlayQueue.user_id == uid))
    if queue is None:
        queue = MusicPlayQueue(user_id=uid)
        db.add(queue)
    queue.entry_ids = entry_ids
    queue.current_song_id = int(body["currentSongId"]) if body.get("currentSongId") is not None else None
    queue.position_seconds = float(body["positionSeconds"]) if body.get("positionSeconds") is not None else None
    queue.changed_at_millis = int(time.time() * 1000)
    db.flush()
    return ApiResponse.ok(
        {
            "id": queue.id,
            "entryIds": queue.entry_ids,
            "currentSongId": queue.current_song_id,
            "positionSeconds": queue.position_seconds,
            "changedAtMillis": queue.changed_at_millis,
        }
    )


@router.get("/bookmarks")
def list_bookmarks(db: DbSession):
    uid = current_user_id()
    rows = list(db.scalars(select(MusicBookmark).where(MusicBookmark.user_id == uid)).all())
    return ApiResponse.ok(
        [
            {
                "id": b.id,
                "songId": b.song_id,
                "positionSeconds": b.position_seconds,
                "comment": b.comment,
                "createdAtMillis": b.created_at_millis,
            }
            for b in rows
        ]
    )


@router.post("/bookmarks")
def create_bookmark(db: DbSession, body: dict = Body(...)):
    uid = current_user_id()
    song_id = int(body.get("songId") or 0)
    _require_song(db, song_id)
    row = db.scalar(select(MusicBookmark).where(MusicBookmark.user_id == uid, MusicBookmark.song_id == song_id))
    if row is None:
        row = MusicBookmark(user_id=uid, song_id=song_id)
        db.add(row)
    row.position_seconds = float(body["positionSeconds"]) if body.get("positionSeconds") is not None else None
    row.comment = body.get("comment")
    row.created_at_millis = int(time.time() * 1000)
    db.flush()
    return ApiResponse.ok(
        {
            "id": row.id,
            "songId": row.song_id,
            "positionSeconds": row.position_seconds,
            "comment": row.comment,
            "createdAtMillis": row.created_at_millis,
        }
    )


@router.delete("/bookmarks/{song_id}")
def delete_bookmark(song_id: int, db: DbSession):
    uid = current_user_id()
    row = db.scalar(select(MusicBookmark).where(MusicBookmark.user_id == uid, MusicBookmark.song_id == song_id))
    if row:
        db.delete(row)
        db.flush()
    return ApiResponse.ok(None)


@router.post("/organize")
def organize(
    db: DbSession,
    libraryId: int = Query(...),
    dryRun: bool = Query(True),
    service: MediaLibraryService = Depends(get_media_library_service),
):
    lib = service.get_library_by_id(db, libraryId)
    if not service.is_visible_to_current_user(db, libraryId):
        raise ResourceNotFoundException("MediaLibrary", "id", libraryId)
    return ApiResponse.ok(organize_music_library(db, lib, dryRun))


@router.post("/scan")
def scan(
    db: DbSession,
    libraryId: int | None = Query(None),
    service: MediaLibraryService = Depends(get_media_library_service),
):
    if libraryId is not None:
        lib = service.get_library_by_id(db, libraryId)
        if not service.is_visible_to_current_user(db, libraryId):
            raise ResourceNotFoundException("MediaLibrary", "id", libraryId)
        libs = [lib]
    else:
        libs = [lib for lib in service.get_visible_libraries(db) if lib.is_music_type()]
    results = []
    for lib in libs:
        try:
            results.append(scan_music_library(db, lib))
        except Exception as exc:
            logger.warning("Music scan failed library %s: %s", lib.id, exc)
            results.append({"libraryId": lib.id, "error": str(exc)})
    return ApiResponse.ok({"status": "started", "libraryCount": len(libs), "results": results}, message="扫描任务已启动")


# ── Subsonic ──────────────────────────────────────────────


class SubsonicAuthService:
    def __init__(self, encryptor: SubsonicPasswordEncryptor | None = None):
        settings = get_settings()
        self.encryptor = encryptor or SubsonicPasswordEncryptor(settings.subsonic_encrypt_key or None)
        self.auth_enabled = settings.auth_enabled

    def authenticate(self, db: Session, username: str | None, password: str | None, token: str | None, salt: str | None) -> User | None:
        if not self.auth_enabled:
            return None
        if not username:
            raise SubsonicApiError(ERROR_AUTH, "Missing username")
        user = db.scalar(select(User).where(User.username == username))
        if user is None or not user.enabled:
            raise SubsonicApiError(ERROR_AUTH, "Wrong username or password")
        plain = self.encryptor.decrypt(user.subsonic_password) if user.subsonic_password else None

        valid = False
        if password:
            pass_val = password
            if pass_val.startswith("enc:"):
                try:
                    pass_val = bytes.fromhex(pass_val[4:]).decode("utf-8")
                except ValueError:
                    raise SubsonicApiError(ERROR_AUTH, "Invalid enc password")
            if plain is not None:
                valid = pass_val == plain
            elif user.password_hash:
                valid = verify_password(pass_val, user.password_hash)
        elif token and salt:
            if plain is None:
                raise SubsonicApiError(ERROR_AUTH, "Token auth requires password reset (no subsonic password on record)")
            expected = hashlib.md5((plain + salt).encode("utf-8")).hexdigest()
            valid = expected.lower() == token.lower()

        if not valid:
            raise SubsonicApiError(ERROR_AUTH, "Wrong username or password")
        return user


def _ss_artist_id(i) -> str:
    return f"ar-{i}"


def _ss_album_id(i) -> str:
    return f"al-{i}"


def _ss_song_id(i) -> str:
    return f"tr-{i}"


def _ss_playlist_id(i) -> str:
    return f"pl-{i}"


def _ss_parse(value: str | None, prefix: str) -> int | None:
    if not value:
        return None
    if value.startswith(prefix):
        try:
            return int(value[len(prefix) :])
        except ValueError:
            return None
    return None


def _ss_parse_song(value: str | None) -> int | None:
    sid = _ss_parse(value, "tr-")
    if sid is not None:
        return sid
    if value and value.isdigit():
        return int(value)
    return None


def _ss_parse_any(value: str | None) -> tuple[str, int] | None:
    if not value:
        return None
    for kind, prefix in (("artist", "ar-"), ("album", "al-"), ("song", "tr-"), ("playlist", "pl-")):
        num = _ss_parse(value, prefix)
        if num is not None:
            return kind, num
    if value.isdigit():
        return "song", int(value)
    return None


def _created_ms(dt: datetime | None) -> int | None:
    if dt is None:
        return None
    return int(dt.timestamp() * 1000)


def _ss_to_song_clean(db: Session, song: MusicSong, user_id: int) -> dict:
    starred = song.id in _starred_ids(db, user_id, TYPE_SONG, [song.id]) if user_id else False
    ratings = _rating_map(db, user_id, TYPE_SONG, [song.id]) if user_id else {}
    plays = _play_count_map(db, user_id, [song.id]) if user_id else {}
    return {
        "id": _ss_song_id(song.id),
        "parent": _ss_album_id(song.album_id) if song.album_id else None,
        "isDir": False,
        "title": song.title,
        "album": song.album_name,
        "artist": song.artist_name,
        "track": song.track_number,
        "discNumber": song.disc_number,
        "year": song.year,
        "genre": song.genre,
        "coverArt": _ss_album_id(song.album_id) if song.album_id else None,
        "size": song.file_size,
        "contentType": _audio_content_type(song.format),
        "suffix": (song.format or "").lower() or None,
        "duration": int(song.duration_seconds) if song.duration_seconds is not None else None,
        "bitRate": song.bit_rate,
        "playCount": plays.get(song.id, 0),
        "starred": datetime.now().isoformat() if starred else None,
        "userRating": ratings.get(song.id),
        "created": _created_ms(song.created_at),
        "artistId": _ss_artist_id(song.artist_id) if song.artist_id else None,
        "albumId": _ss_album_id(song.album_id) if song.album_id else None,
        "path": song.file_path,
        "type": "music",
    }


def _ss_to_album(db: Session, album: MusicAlbum, user_id: int) -> dict:
    starred = album.id in _starred_ids(db, user_id, TYPE_ALBUM, [album.id]) if user_id else False
    ratings = _rating_map(db, user_id, TYPE_ALBUM, [album.id]) if user_id else {}
    songs = _songs_of_album(db, album.id)
    return {
        "id": _ss_album_id(album.id),
        "name": album.title,
        "artist": album.artist_name,
        "artistId": _ss_artist_id(album.artist_id) if album.artist_id else None,
        "coverArt": _ss_album_id(album.id),
        "songCount": len(songs) if songs else album.track_count,
        "duration": sum(int(s.duration_seconds or 0) for s in songs),
        "year": album.year,
        "genre": album.genre,
        "starred": datetime.now().isoformat() if starred else None,
        "userRating": ratings.get(album.id),
        "created": _created_ms(album.created_at),
    }


def _ss_to_artist(db: Session, artist: MusicArtist, user_id: int) -> dict:
    starred = artist.id in _starred_ids(db, user_id, TYPE_ARTIST, [artist.id]) if user_id else False
    count = int(db.scalar(select(func.count(MusicAlbum.id)).where(MusicAlbum.artist_id == artist.id)) or 0)
    return {
        "id": _ss_artist_id(artist.id),
        "name": artist.name,
        "coverArt": _ss_artist_id(artist.id),
        "albumCount": count,
        "starred": datetime.now().isoformat() if starred else None,
    }


def _ss_dir_child(album: MusicAlbum) -> dict:
    return {
        "id": _ss_album_id(album.id),
        "parent": _ss_artist_id(album.artist_id) if album.artist_id else None,
        "isDir": True,
        "title": album.title,
        "album": album.title,
        "artist": album.artist_name,
        "artistId": _ss_artist_id(album.artist_id) if album.artist_id else None,
        "coverArt": _ss_album_id(album.id),
        "year": album.year,
        "genre": album.genre,
        "type": "album",
    }


def _ss_songs(db: Session, songs: list[MusicSong], user_id: int) -> list[dict]:
    return [_ss_to_song_clean(db, s, user_id) for s in songs]


def _json_to_xml(name: str, data: Any) -> str:
    if data is None:
        return ""
    if isinstance(data, list):
        return "".join(_json_to_xml(name, item) for item in data)
    if isinstance(data, bool):
        return f'<{name} value="{"true" if data else "false"}"/>'
    if not isinstance(data, dict):
        text = html.escape(str(data), quote=True)
        return f'<{name} value="{text}"/>'
    attrs: list[str] = []
    children: list[str] = []
    for key, value in data.items():
        if value is None:
            continue
        if isinstance(value, (dict, list)):
            children.append(_json_to_xml(key, value))
        elif isinstance(value, bool):
            attrs.append(f'{key}="{"true" if value else "false"}"')
        else:
            attrs.append(f'{key}="{html.escape(str(value), quote=True)}"')
    attr_str = (" " + " ".join(attrs)) if attrs else ""
    if children:
        return f"<{name}{attr_str}>{''.join(children)}</{name}>"
    return f"<{name}{attr_str}/>"


def _render_envelope(payload: dict, fmt: str | None, callback: str | None) -> Response:
    body = {"subsonic-response": payload}
    fmt_l = (fmt or "xml").lower()
    if fmt_l in ("json", "jsonp"):
        text = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
        if fmt_l == "jsonp":
            cb = callback or "callback"
            if not re.match(r"^[A-Za-z0-9_.$]+$", cb):
                cb = "callback"
            return Response(content=f"{cb}({text});", media_type="text/javascript; charset=utf-8")
        return Response(content=text, media_type="application/json; charset=utf-8")
    xml_body = _json_to_xml("subsonic-response", payload)
    return Response(content=f'<?xml version="1.0" encoding="UTF-8"?>\n{xml_body}', media_type="application/xml; charset=utf-8")


def _ok_envelope(extra: dict | None = None) -> dict:
    env = {
        "status": "ok",
        "version": API_VERSION,
        "type": SERVER_TYPE,
        "serverVersion": SERVER_VERSION,
        "openSubsonic": True,
    }
    if extra:
        env.update(extra)
    return env


def _error_envelope(code: int, message: str) -> dict:
    env = _ok_envelope()
    env["status"] = "failed"
    env["error"] = {"code": code, "message": message}
    return env


def _q(params: dict, key: str, default: str | None = None) -> str | None:
    value = params.get(key)
    if value is None or value == "":
        return default
    return str(value)


def _qi(params: dict, key: str, default: int | None = None) -> int | None:
    value = params.get(key)
    if value is None or value == "":
        return default
    try:
        return int(str(value))
    except ValueError:
        return default


_SCAN_STATE = {"scanning": False, "count": 0}
_SCAN_LOCK = threading.Lock()


class _Params(dict):
    """支持 multi-value 的简单参数袋（query + form）。"""

    def get_all(self, key: str) -> list[str]:
        value = self.get(key)
        if value is None:
            return []
        return value if isinstance(value, list) else [str(value)]


def _collect_params(request: Request, query: dict, form: dict | None) -> _Params:
    params = _Params()
    for key, value in query.items():
        params.setdefault(key, value)
    for key, value in (form or {}).items():
        params[key] = value
    return params


@subsonic_router.api_route("/{method}", methods=["GET"], name="subsonic_get")
@subsonic_router.api_route("/{method}", methods=["POST"], name="subsonic_post")
async def subsonic_entry(method: str, request: Request, db: DbSession):
    multi: dict[str, list[str]] = {}
    for key, value in request.query_params.multi_items():
        multi.setdefault(key, []).append(value)
    form_map: dict[str, Any] = {}
    if request.method == "POST":
        try:
            form = await request.form()
            for key, value in form.multi_items():
                form_map.setdefault(key, [])
                if isinstance(form_map[key], list):
                    form_map[key].append(value)
        except Exception:
            form_map = {}
    # 展平：单值取 str，多值保留 list
    query_flat = {k: (v[0] if len(v) == 1 else v) for k, v in multi.items()}
    form_flat = {k: (v[0] if len(v) == 1 else v) for k, v in form_map.items()}
    params = _collect_params(request, query_flat, form_flat)
    # 合并 multi：query 优先拼接 form
    for key, values in multi.items():
        extra = form_map.get(key)
        if extra:
            params[key] = values + list(extra)
        elif len(values) > 1:
            params[key] = values
    fmt = _q(params, "f")
    callback = _q(params, "callback")
    method_name = method[:-5] if method.endswith(".view") else method
    try:
        if method_name in ("stream", "download", "getCoverArt", "getAvatar", "hls"):
            return _ss_binary(db, method_name, params, request)
        return _render_envelope(_ss_dispatch(db, method_name, params, request), fmt, callback)
    except SubsonicApiError as exc:
        return _render_envelope(_error_envelope(exc.code, exc.message), fmt, callback)
    except Exception as exc:
        logger.warning("Subsonic %s error: %s", method_name, exc, exc_info=True)
        return _render_envelope(_error_envelope(ERROR_GENERIC, str(exc)), fmt, callback)


def _ss_auth(db: Session, params: dict) -> User | None:
    service = SubsonicAuthService()
    user = service.authenticate(db, _q(params, "u"), _q(params, "p"), _q(params, "t"), _q(params, "s"))
    if user is not None:
        set_current_user_id(user.id)
    return user


def _ss_uid(user: User | None) -> int:
    return user.id if user else ANONYMOUS_ID


def _ss_dispatch(db: Session, method: str, params: dict, request: Request) -> dict:
    user = _ss_auth(db, params)
    uid = _ss_uid(user)
    username = user.username if user else "anonymous"

    if method == "ping":
        return _ok_envelope()
    if method == "getLicense":
        return _ok_envelope({"license": {"valid": True}})
    if method == "getMusicFolders":
        allowed = _allowed_ids(db)
        libs = [
            lib
            for lib in db.scalars(select(MediaLibrary).where(MediaLibrary.enabled.is_(True), MediaLibrary.type == "MUSIC")).all()
            if lib.id in allowed
        ]
        return _ok_envelope({"musicFolders": {"musicFolder": [{"id": str(lib.id), "name": lib.name} for lib in libs]}})
    if method in ("getArtists", "getIndexes"):
        artists = list(db.scalars(select(MusicArtist).order_by(MusicArtist.name.asc())).all())
        by_letter: dict[str, list] = {}
        for artist in artists:
            name = artist.name or ""
            letter = name[:1].upper() if name else "#"
            if not re.match(r"^[A-Z]$", letter):
                letter = "#"
            by_letter.setdefault(letter, []).append(_ss_to_artist(db, artist, uid))
        indexes = [{"name": k, "artist": by_letter[k]} for k in sorted(by_letter)]
        key = "artists" if method == "getArtists" else "indexes"
        return _ok_envelope({key: {"index": indexes}})
    if method == "getArtist":
        artist = _ss_find_artist(db, _q(params, "id"))
        if artist is None:
            raise SubsonicApiError(ERROR_NOT_FOUND, "Artist not found")
        dto = _ss_to_artist(db, artist, uid)
        dto["album"] = [_ss_to_album(db, a, uid) for a in _albums_of_artist(db, artist.id)]
        return _ok_envelope({"artist": dto})
    if method == "getAlbum":
        album = _ss_find_album(db, _q(params, "id"))
        if album is None:
            raise SubsonicApiError(ERROR_NOT_FOUND, "Album not found")
        dto = _ss_to_album(db, album, uid)
        songs = _songs_of_album(db, album.id)
        dto["song"] = _ss_songs(db, songs, uid)
        dto["songCount"] = len(songs)
        return _ok_envelope({"album": dto})
    if method == "getSong":
        song_id = _ss_parse_song(_q(params, "id"))
        song = db.get(MusicSong, song_id) if song_id else None
        if song is None:
            raise SubsonicApiError(ERROR_NOT_FOUND, "Song not found")
        return _ok_envelope({"song": _ss_to_song_clean(db, song, uid)})
    if method == "getMusicDirectory":
        return _ok_envelope({"directory": _ss_directory(db, _q(params, "id"), uid)})
    if method in ("getAlbumList", "getAlbumList2"):
        key = "albumList2" if method == "getAlbumList2" else "albumList"
        albums = _ss_album_list(db, params, uid)
        return _ok_envelope({key: {"album": [_ss_to_album(db, a, uid) for a in albums]}})
    if method == "getRandomSongs":
        size = _qi(params, "size", 10) or 10
        genre = _q(params, "genre")
        from_year = _qi(params, "fromYear")
        to_year = _qi(params, "toYear")
        query = select(MusicSong)
        if genre:
            query = query.where(MusicSong.genre.ilike(genre))
        if from_year is not None:
            query = query.where(MusicSong.year >= from_year)
        if to_year is not None:
            query = query.where(MusicSong.year <= to_year)
        pool = list(db.scalars(query).all())
        import random

        picked = random.sample(pool, min(size, len(pool))) if pool else []
        return _ok_envelope({"randomSongs": {"song": _ss_songs(db, picked, uid)}})
    if method == "getSongsByGenre":
        genre = _q(params, "genre") or ""
        count = _qi(params, "count", 10) or 10
        offset = _qi(params, "offset", 0) or 0
        songs = list(
            db.scalars(
                select(MusicSong).where(MusicSong.genre.ilike(genre)).offset(offset).limit(count)
            ).all()
        )
        return _ok_envelope({"songsByGenre": {"song": _ss_songs(db, songs, uid)}})
    if method == "getGenres":
        rows = db.execute(
            select(MusicSong.genre, func.count(MusicSong.id))
            .where(MusicSong.genre.isnot(None), MusicSong.genre != "")
            .group_by(MusicSong.genre)
            .order_by(MusicSong.genre)
        ).all()
        genres = [{"value": g, "songCount": c} for g, c in rows]
        return _ok_envelope({"genres": {"genre": genres}})
    if method in ("search", "search2", "search3"):
        query = _q(params, "query") or _q(params, "any") or ""
        ac = _qi(params, "artistCount", 20) or 20
        alc = _qi(params, "albumCount", 20) or 20
        sc = _qi(params, "songCount", 20) or 20
        result = _ss_search(db, query, ac, alc, sc, uid)
        if method == "search3":
            return _ok_envelope({"searchResult3": result})
        return _ok_envelope({"searchResult2": result})
    if method == "getNowPlaying":
        return _ok_envelope({"nowPlaying": {"entry": []}})
    if method in ("getStarred", "getStarred2"):
        starred = _ss_starred(db, uid)
        return _ok_envelope({"starred": starred, "starred2": starred})
    if method == "getPlaylists":
        rows = list(
            db.scalars(
                select(MusicPlaylist).where(or_(MusicPlaylist.user_id == uid, MusicPlaylist.is_public.is_(True)))
            ).all()
        )
        return _ok_envelope({"playlists": {"playlist": [_ss_playlist_meta(db, p) for p in rows]}})
    if method == "getPlaylist":
        pid = _ss_parse(_q(params, "id"), "pl-") or _ss_parse_song(_q(params, "id"))
        pl = db.get(MusicPlaylist, pid) if pid else None
        if pl is None or not _can_read_playlist(pl, uid):
            raise SubsonicApiError(ERROR_NOT_FOUND, "Playlist not found")
        return _ok_envelope({"playlist": _ss_playlist_meta(db, pl, with_entries=True, user_id=uid)})
    if method == "createPlaylist":
        return _ok_envelope({"playlist": _ss_create_playlist(db, params, uid)})
    if method == "updatePlaylist":
        _ss_update_playlist(db, params, uid)
        return _ok_envelope()
    if method == "deletePlaylist":
        pid = _ss_parse(_q(params, "id"), "pl-") or _ss_parse_song(_q(params, "id"))
        pl = db.get(MusicPlaylist, pid) if pid else None
        if pl is None or not _can_write_playlist(pl, uid):
            raise SubsonicApiError(ERROR_NOT_FOUND, "Playlist not found")
        for entry in db.scalars(select(MusicPlaylistEntry).where(MusicPlaylistEntry.playlist_id == pl.id)).all():
            db.delete(entry)
        db.delete(pl)
        db.flush()
        return _ok_envelope()
    if method in ("star", "unstar"):
        _ss_star(db, uid, params, method == "star")
        return _ok_envelope()
    if method == "setRating":
        _ss_set_rating(db, uid, params)
        return _ok_envelope()
    if method == "scrobble":
        ids = params.get_all("id") if hasattr(params, "get_all") else []
        if not ids and params.get("id"):
            raw = params.get("id")
            ids = raw if isinstance(raw, list) else [raw]
        submission = (_q(params, "submission") or "true").lower() != "false"
        for raw_id in ids:
            sid = _ss_parse_song(str(raw_id))
            if sid is None:
                continue
            _scrobble(db, uid, sid, None, submission)
        return _ok_envelope()
    if method == "getBookmarks":
        return _ok_envelope({"bookmarks": _ss_bookmarks(db, uid, username)})
    if method == "createBookmark":
        sid = _ss_parse_song(_q(params, "id"))
        if sid is None:
            raise SubsonicApiError(ERROR_MISSING_PARAM, "Missing id")
        pos = _q(params, "position")
        pos_val = float(pos) if pos else None
        row = db.scalar(select(MusicBookmark).where(MusicBookmark.user_id == uid, MusicBookmark.song_id == sid))
        if row is None:
            row = MusicBookmark(user_id=uid, song_id=sid)
            db.add(row)
        row.position_seconds = pos_val
        row.comment = _q(params, "comment")
        row.created_at_millis = int(time.time() * 1000)
        db.flush()
        return _ok_envelope()
    if method == "deleteBookmark":
        sid = _ss_parse_song(_q(params, "id"))
        if sid is not None:
            row = db.scalar(select(MusicBookmark).where(MusicBookmark.user_id == uid, MusicBookmark.song_id == sid))
            if row:
                db.delete(row)
                db.flush()
        return _ok_envelope()
    if method == "getPlayQueue":
        queue = _ss_play_queue(db, uid, username)
        return _ok_envelope({"playQueue": queue} if queue else {})
    if method == "savePlayQueue":
        _ss_save_play_queue(db, uid, params)
        return _ok_envelope()
    if method in ("getUser", "getUsers"):
        u = user or User(username="anonymous", password_hash="", role=UserRole.USER, enabled=True)
        dto = {
            "username": u.username if user else "anonymous",
            "adminRole": bool(user and user.is_admin()),
            "streamRole": True,
            "downloadRole": True,
            "coverArtRole": True,
            "commentRole": True,
            "podcastRole": False,
            "shareRole": False,
            "jukeboxRole": False,
            "scrobblingEnabled": True,
            "maxBitRate": 0,
            "folder": ",".join(str(x) for x in _allowed_ids(db)) or None,
        }
        if method == "getUser":
            return _ok_envelope({"user": dto})
        return _ok_envelope({"users": {"user": [dto]}})
    if method == "getScanStatus":
        return _ok_envelope({"scanStatus": {"scanning": _SCAN_STATE["scanning"], "count": _SCAN_STATE["count"]}})
    if method == "startScan":
        count = int(db.scalar(select(func.count(MediaLibrary.id)).where(MediaLibrary.type == "MUSIC", MediaLibrary.enabled.is_(True))) or 0)
        with _SCAN_LOCK:
            _SCAN_STATE["count"] = count
        return _ok_envelope({"scanStatus": {"scanning": False, "count": count}})
    if method == "getLyrics":
        return _ok_envelope({"lyrics": _ss_lyrics(db, params)})
    if method == "getTopSongs":
        return _ok_envelope({"topSongs": {"song": []}})
    if method in ("getSimilarSongs", "getSimilarSongs2"):
        return _ok_envelope({"similarSongs": {"song": []}})
    if method in ("getArtistInfo", "getArtistInfo2", "getAlbumInfo", "getAlbumInfo2", "getVideos", "getVideoInfo"):
        return _ok_envelope()
    raise SubsonicApiError(ERROR_GENERIC, f"Method not implemented: {method}")


def _ss_find_artist(db: Session, raw: str | None) -> MusicArtist | None:
    aid = _ss_parse(raw, "ar-")
    if aid is not None:
        return db.get(MusicArtist, aid)
    sid = _ss_parse_song(raw)
    if sid:
        song = db.get(MusicSong, sid)
        if song and song.artist_id:
            return db.get(MusicArtist, song.artist_id)
    alid = _ss_parse(raw, "al-")
    if alid is not None:
        album = db.get(MusicAlbum, alid)
        if album and album.artist_id:
            return db.get(MusicArtist, album.artist_id)
    return None


def _ss_find_album(db: Session, raw: str | None) -> MusicAlbum | None:
    alid = _ss_parse(raw, "al-")
    if alid is not None:
        return db.get(MusicAlbum, alid)
    sid = _ss_parse_song(raw)
    if sid:
        song = db.get(MusicSong, sid)
        if song and song.album_id:
            return db.get(MusicAlbum, song.album_id)
    return None


def _ss_directory(db: Session, raw: str | None, uid: int) -> dict:
    artist_id = _ss_parse(raw, "ar-")
    album_id = _ss_parse(raw, "al-")
    if artist_id is not None:
        artist = db.get(MusicArtist, artist_id)
        if artist is None:
            raise SubsonicApiError(ERROR_NOT_FOUND, "Directory not found")
        return {
            "id": _ss_artist_id(artist_id),
            "name": artist.name,
            "child": [_ss_dir_child(a) for a in _albums_of_artist(db, artist_id)],
        }
    if album_id is not None:
        album = db.get(MusicAlbum, album_id)
        if album is None:
            raise SubsonicApiError(ERROR_NOT_FOUND, "Directory not found")
        return {
            "id": _ss_album_id(album_id),
            "name": album.title,
            "child": _ss_songs(db, _songs_of_album(db, album_id), uid),
        }
    raise SubsonicApiError(ERROR_NOT_FOUND, "Directory not found")


def _ss_album_list(db: Session, params: dict, uid: int) -> list[MusicAlbum]:
    type_ = _q(params, "type") or ""
    size = _qi(params, "size", 10) or 10
    offset = _qi(params, "offset", 0) or 0
    from_year = _qi(params, "fromYear")
    to_year = _qi(params, "toYear")
    genre = _q(params, "genre")
    query = select(MusicAlbum)
    if genre:
        query = query.where(MusicAlbum.genre.ilike(genre))
    if type_ == "byYear" and (from_year is not None or to_year is not None):
        lo = from_year if from_year is not None else 1900
        hi = to_year if to_year is not None else 3000
        query = query.where(MusicAlbum.year.between(min(lo, hi), max(lo, hi)))
        query = query.order_by(MusicAlbum.year.desc())
    elif type_ == "newest":
        query = query.order_by(MusicAlbum.year.desc(), MusicAlbum.created_at.desc())
    elif type_ == "alphabeticalByName":
        query = query.order_by(MusicAlbum.title.asc())
    elif type_ == "alphabeticalByArtist":
        query = query.order_by(MusicAlbum.artist_name.asc())
    elif type_ == "starred":
        ids = list(db.scalars(select(MusicStar.target_id).where(MusicStar.user_id == uid, MusicStar.target_type == TYPE_ALBUM)).all())
        if not ids:
            return []
        query = query.where(MusicAlbum.id.in_(ids))
        query = query.order_by(MusicAlbum.title.asc())
    else:
        query = query.order_by(MusicAlbum.created_at.desc())
    return list(db.scalars(query.offset(offset).limit(size)).all())


def _ss_search(db: Session, query: str, artist_count: int, album_count: int, song_count: int, uid: int) -> dict:
    if not query:
        return {}
    like = f"%{query}%"
    artists = list(db.scalars(select(MusicArtist).where(MusicArtist.name.ilike(like)).limit(artist_count)).all())
    albums = list(db.scalars(select(MusicAlbum).where(MusicAlbum.title.ilike(like)).limit(album_count)).all())
    songs = list(
        db.scalars(
            select(MusicSong)
            .where(or_(MusicSong.title.ilike(like), MusicSong.artist_name.ilike(like), MusicSong.album_name.ilike(like)))
            .limit(song_count)
        ).all()
    )
    return {
        "artist": [_ss_to_artist(db, a, uid) for a in artists],
        "album": [_ss_to_album(db, a, uid) for a in albums],
        "song": _ss_songs(db, songs, uid),
    }


def _ss_starred(db: Session, uid: int) -> dict:
    artist_ids = list(db.scalars(select(MusicStar.target_id).where(MusicStar.user_id == uid, MusicStar.target_type == TYPE_ARTIST)).all())
    album_ids = list(db.scalars(select(MusicStar.target_id).where(MusicStar.user_id == uid, MusicStar.target_type == TYPE_ALBUM)).all())
    song_ids = list(db.scalars(select(MusicStar.target_id).where(MusicStar.user_id == uid, MusicStar.target_type == TYPE_SONG)).all())
    artists = [db.get(MusicArtist, i) for i in artist_ids]
    albums = [db.get(MusicAlbum, i) for i in album_ids]
    songs = [db.get(MusicSong, i) for i in song_ids]
    return {
        "artist": [_ss_to_artist(db, a, uid) for a in artists if a],
        "album": [_ss_to_album(db, a, uid) for a in albums if a],
        "song": _ss_songs(db, [s for s in songs if s], uid),
    }


def _ss_playlist_meta(db: Session, pl: MusicPlaylist, with_entries: bool = False, user_id: int = 0) -> dict:
    songs = _playlist_entries(db, pl.id)
    owner = db.get(User, pl.user_id)
    dto = {
        "id": _ss_playlist_id(pl.id),
        "name": pl.name,
        "comment": pl.comment,
        "public": bool(pl.is_public),
        "owner": owner.username if owner else str(pl.user_id),
        "songCount": len(songs),
        "duration": sum(int(s.duration_seconds or 0) for s in songs),
        "created": _created_ms(pl.created_at),
    }
    if with_entries:
        dto["entry"] = _ss_songs(db, songs, user_id)
    return dto


def _ss_create_playlist(db: Session, params: dict, uid: int) -> dict:
    pid = _ss_parse(_q(params, "playlistId"), "pl-")
    name = _q(params, "name")
    song_ids = params.get_all("songId") if hasattr(params, "get_all") else []
    if not song_ids:
        raw_ids = params.get("songId")
        song_ids = raw_ids if isinstance(raw_ids, list) else ([raw_ids] if raw_ids else [])
    parsed = [int(x) for x in (_ss_parse_song(str(s)) for s in song_ids) if x is not None]
    if pid is not None:
        pl = db.get(MusicPlaylist, pid)
        if pl is None or not _can_write_playlist(pl, uid):
            raise SubsonicApiError(ERROR_NOT_FOUND, "Playlist not found")
        if name:
            pl.name = name
        db.flush()
        if song_ids:
            _replace_playlist_songs(db, pl.id, parsed)
    else:
        pl = MusicPlaylist(name=name or "New Playlist", user_id=uid, is_public=False)
        db.add(pl)
        db.flush()
        if parsed:
            _replace_playlist_songs(db, pl.id, parsed)
    return _ss_playlist_meta(db, pl, with_entries=True, user_id=uid)


def _ss_update_playlist(db: Session, params: dict, uid: int) -> None:
    pid = _ss_parse(_q(params, "playlistId"), "pl-")
    if pid is None:
        raise SubsonicApiError(ERROR_MISSING_PARAM, "Missing playlistId")
    pl = db.get(MusicPlaylist, pid)
    if pl is None or not _can_write_playlist(pl, uid):
        raise SubsonicApiError(ERROR_NOT_FOUND, "Playlist not found")
    name = _q(params, "name")
    comment = _q(params, "comment")
    public = _q(params, "public")
    if name is not None:
        pl.name = name
    if comment is not None:
        pl.comment = comment
    if public is not None:
        pl.is_public = public.lower() == "true"
    db.flush()
    to_add = params.get_all("songIdToAdd") if hasattr(params, "get_all") else []
    if not to_add:
        raw_add = params.get("songIdToAdd")
        to_add = raw_add if isinstance(raw_add, list) else ([raw_add] if raw_add else [])
    for raw in to_add:
        sid = _ss_parse_song(str(raw))
        if sid is None:
            continue
        pos = int(db.scalar(select(func.coalesce(func.max(MusicPlaylistEntry.position), -1)).where(MusicPlaylistEntry.playlist_id == pid)) or -1) + 1
        db.add(MusicPlaylistEntry(playlist_id=pid, song_id=sid, position=pos))
        db.flush()
    to_remove = params.get_all("songIndexToRemove") if hasattr(params, "get_all") else []
    if not to_remove:
        raw_rm = params.get("songIndexToRemove")
        to_remove = raw_rm if isinstance(raw_rm, list) else ([raw_rm] if raw_rm else [])
    if to_remove:
        entries = list(
            db.scalars(
                select(MusicPlaylistEntry).where(MusicPlaylistEntry.playlist_id == pid).order_by(MusicPlaylistEntry.position.asc())
            ).all()
        )
        drop = {int(x) for x in to_remove}
        for idx, entry in enumerate(entries):
            if idx in drop:
                db.delete(entry)
        db.flush()


def _ss_star(db: Session, uid: int, params: dict, status: bool) -> None:
    for key in ("id", "albumId", "artistId"):
        values = params.get_all(key) if hasattr(params, "get_all") else []
        if not values:
            raw = params.get(key)
            values = raw if isinstance(raw, list) else ([raw] if raw else [])
        for value in values:
            if key == "albumId":
                num = _ss_parse(str(value), "al-")
                if num is not None:
                    _set_star(db, uid, TYPE_ALBUM, num, status)
            elif key == "artistId":
                num = _ss_parse(str(value), "ar-")
                if num is not None:
                    _set_star(db, uid, TYPE_ARTIST, num, status)
            else:
                parsed = _ss_parse_any(str(value))
                if parsed is None:
                    continue
                kind, num = parsed
                t = {"artist": TYPE_ARTIST, "album": TYPE_ALBUM, "song": TYPE_SONG, "playlist": TYPE_SONG}[kind]
                if kind == "playlist":
                    continue
                _set_star(db, uid, t, num, status)


def _ss_set_rating(db: Session, uid: int, params: dict) -> None:
    raw = _q(params, "id")
    rating = _qi(params, "rating", 0) or 0
    parsed = _ss_parse_any(raw)
    if parsed is None:
        return
    kind, num = parsed
    if kind == "artist":
        _set_rating(db, uid, TYPE_ARTIST, num, rating)
    elif kind == "album":
        _set_rating(db, uid, TYPE_ALBUM, num, rating)
    elif kind == "song":
        _set_rating(db, uid, TYPE_SONG, num, rating)


def _ss_bookmarks(db: Session, uid: int, username: str) -> dict:
    rows = list(db.scalars(select(MusicBookmark).where(MusicBookmark.user_id == uid)).all())
    bookmarks = []
    for b in rows:
        song = db.get(MusicSong, b.song_id)
        entry = _ss_to_song_clean(db, song, uid) if song else {}
        bookmarks.append(
            {
                "username": username,
                "position": int(b.position_seconds or 0),
                "comment": b.comment,
                "created": b.created_at_millis,
                "changed": _created_ms(b.updated_at),
                "entry": entry,
            }
        )
    return {"bookmark": bookmarks}


def _ss_play_queue(db: Session, uid: int, username: str) -> dict | None:
    queue = db.scalar(select(MusicPlayQueue).where(MusicPlayQueue.user_id == uid))
    if queue is None:
        return None
    ids = [int(x) for x in (queue.entry_ids or "").split(",") if x.strip().isdigit()]
    songs = [db.get(MusicSong, i) for i in ids]
    return {
        "current": _ss_song_id(queue.current_song_id) if queue.current_song_id else None,
        "position": int(queue.position_seconds or 0),
        "username": username,
        "changed": queue.changed_at_millis,
        "entry": _ss_songs(db, [s for s in songs if s], uid),
    }


def _ss_save_play_queue(db: Session, uid: int, params: dict) -> None:
    values = params.get_all("id") if hasattr(params, "get_all") else []
    if not values:
        raw_ids = params.get("id")
        values = raw_ids if isinstance(raw_ids, list) else ([raw_ids] if raw_ids else [])
    song_ids = [x for x in (_ss_parse_song(str(v)) for v in values) if x is not None]
    current = _ss_parse_song(_q(params, "current"))
    pos = _q(params, "position")
    queue = db.scalar(select(MusicPlayQueue).where(MusicPlayQueue.user_id == uid))
    if queue is None:
        queue = MusicPlayQueue(user_id=uid)
        db.add(queue)
    queue.entry_ids = ",".join(str(x) for x in song_ids)
    queue.current_song_id = current
    queue.position_seconds = float(pos) if pos else None
    queue.changed_at_millis = int(time.time() * 1000)
    db.flush()


def _ss_lyrics(db: Session, params: dict) -> dict:
    lyrics: dict = {}
    sid = _ss_parse_song(_q(params, "id"))
    song = db.get(MusicSong, sid) if sid else None
    if song is None:
        artist = _q(params, "artist")
        title = _q(params, "title")
        if artist and title:
            song = db.scalar(
                select(MusicSong).where(MusicSong.title.ilike(f"%{title}%"), MusicSong.artist_name.ilike(f"%{artist}%")).limit(1)
            )
    if song is None:
        return lyrics
    lyrics["artist"] = song.artist_name
    lyrics["title"] = song.title
    if song.lyrics_content:
        lyrics["value"] = song.lyrics_content
    elif song.lyrics_path and Path(song.lyrics_path).is_file():
        lyrics["value"] = Path(song.lyrics_path).read_text(encoding="utf-8", errors="replace")
    return lyrics


def _ss_binary(db: Session, method: str, params: dict, request: Request):
    user = _ss_auth(db, params)
    uid = _ss_uid(user)
    if method == "hls":
        raise SubsonicApiError(ERROR_GENERIC, "HLS not supported")
    if method in ("stream", "download"):
        sid = _ss_parse_song(_q(params, "id"))
        song = db.get(MusicSong, sid) if sid else None
        if song is None:
            raise SubsonicApiError(ERROR_NOT_FOUND, "Song not found")
        path = Path(song.file_path)
        if not path.is_file():
            raise SubsonicApiError(ERROR_NOT_FOUND, "Song file not found")
        if method == "stream":
            _scrobble(db, uid, song.id, None, False)
        return FileResponse(
            path,
            media_type=_audio_content_type(song.format),
            filename=path.name if method == "download" else None,
        )
    if method == "getCoverArt":
        raw = _q(params, "id")
        parsed = _ss_parse_any(raw)
        if parsed:
            kind, num = parsed
            if kind == "artist":
                artist = db.get(MusicArtist, num)
                return _image_response(artist.cover_art_path if artist else None)
            if kind == "album":
                album = db.get(MusicAlbum, num)
                if album and album.cover_art_path and Path(album.cover_art_path).is_file():
                    return _image_response(album.cover_art_path)
                if album:
                    for song in _songs_of_album(db, num):
                        embedded = _embedded_cover(song.file_path)
                        if embedded:
                            return Response(content=embedded[0], media_type=embedded[1])
            if kind == "song":
                song = db.get(MusicSong, num)
                if song and song.album_id:
                    album = db.get(MusicAlbum, song.album_id)
                    if album and album.cover_art_path and Path(album.cover_art_path).is_file():
                        return _image_response(album.cover_art_path)
                if song:
                    embedded = _embedded_cover(song.file_path)
                    if embedded:
                        return Response(content=embedded[0], media_type=embedded[1])
        return Response(content=placeholder_jpeg(), media_type="image/jpeg")
    if method == "getAvatar":
        username = _q(params, "username") or (user.username if user else "")
        u = db.scalar(select(User).where(User.username == username)) if username else user
        if u and u.avatar and Path(u.avatar).is_file():
            return _image_response(u.avatar)
        return Response(content=placeholder_jpeg(200, 200, username or "?"), media_type="image/jpeg")
    raise SubsonicApiError(ERROR_GENERIC, f"Method not implemented: {method}")
