from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.core.security import set_current_user_id
from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary, UserLibrary
from fryfrog.models.music import MusicAlbum, MusicArtist, MusicSong
from fryfrog.models.user import User, UserRole
from fryfrog.routers import music


def _setup(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib_allowed = MediaLibrary(name="音乐A", path=str(tmp_path / "a"), type="MUSIC", enabled=True)
    lib_hidden = MediaLibrary(name="音乐B", path=str(tmp_path / "b"), type="MUSIC", enabled=True)
    db.add_all([lib_allowed, lib_hidden])
    db.flush()

    seen = {}
    for lib, name in ((lib_allowed, "授权艺人"), (lib_hidden, "隐藏艺人")):
        artist = MusicArtist(name=name, library_id=lib.id)
        db.add(artist)
        db.flush()
        album = MusicAlbum(title=f"{name}专辑", artist_name=name, artist_id=artist.id, library_id=lib.id)
        db.add(album)
        db.flush()
        song = MusicSong(
            title=f"{name}歌曲",
            artist_name=name,
            album_name=album.title,
            artist_id=artist.id,
            album_id=album.id,
            library_id=lib.id,
            file_path=str(tmp_path / name / "song.mp3"),
            genre="摇滚" if lib.id == lib_allowed.id else "民谣",
            format="mp3",
            duration_seconds=180,
        )
        db.add(song)
        db.flush()
        seen[lib.id] = (artist, album, song)
    db.commit()
    return db, seen


def _granted_user(db, lib_allowed) -> int:
    user = User(username="limited", password_hash="x", role=UserRole.USER)
    db.add(user)
    db.flush()
    db.add(UserLibrary(user_id=user.id, library_id=lib_allowed.id))
    db.commit()
    return user.id


def test_music_rest_lists_filter_by_library_permission(tmp_path):
    """受限用户：artists/albums/songs/genres 只能看到被授权的音乐库。"""
    db, seen = _setup(tmp_path)
    lib_allowed = db.get(MediaLibrary, next(iter(seen)))
    user_id = _granted_user(db, lib_allowed)

    set_current_user_id(user_id)
    try:
        page = music.list_artists(db, page=0, size=20).data
        assert page["totalElements"] == 1
        assert [a["name"] for a in page["content"]] == ["授权艺人"]

        page = music.list_albums(db, page=0, size=20).data
        assert page["totalElements"] == 1
        assert [a["title"] for a in page["content"]] == ["授权艺人专辑"]

        page = music.search_songs(db, page=0, size=20).data
        assert page["totalElements"] == 1
        assert [s["title"] for s in page["content"]] == ["授权艺人歌曲"]

        assert music.get_genres(db).data == ["摇滚"]
        home = music.music_home(db).data
        assert [g["libraryName"] for g in home] == ["音乐A"]
    finally:
        set_current_user_id(None)


def test_music_detail_and_content_deny_unauthorized(tmp_path):
    """受限用户访问未授权库的详情/歌曲/封面一律 404（不暴露存在性）。"""
    db, seen = _setup(tmp_path)
    lib_allowed = db.get(MediaLibrary, next(iter(seen)))
    user_id = _granted_user(db, lib_allowed)
    hidden_lib_id = [k for k in seen if k != lib_allowed.id][0]
    _, _, hidden_song = seen[hidden_lib_id]
    hidden_album = db.get(MusicAlbum, hidden_song.album_id)
    hidden_artist = db.get(MusicArtist, hidden_song.artist_id)

    set_current_user_id(user_id)
    try:
        with pytest.raises(ResourceNotFoundException):
            music.get_song(hidden_song.id, db)
        with pytest.raises(ResourceNotFoundException):
            music.stream_song(hidden_song.id, db)
        with pytest.raises(ResourceNotFoundException):
            music.get_song_cover(hidden_song.id, db)
        with pytest.raises(ResourceNotFoundException):
            music.get_album(hidden_album.id, db)
        with pytest.raises(ResourceNotFoundException):
            music.get_album_songs(hidden_album.id, db)
        with pytest.raises(ResourceNotFoundException):
            music.get_album_cover(hidden_album.id, db)
        with pytest.raises(ResourceNotFoundException):
            music.get_artist(hidden_artist.id, db)
        with pytest.raises(ResourceNotFoundException):
            music.get_artist_cover(hidden_artist.id, db)

        allowed = seen[lib_allowed.id]
        assert music.get_song(allowed[2].id, db).data["title"] == "授权艺人歌曲"
        assert music.get_album(allowed[1].id, db).data["title"] == "授权艺人专辑"
        assert music.get_artist(allowed[0].id, db).data["name"] == "授权艺人"
    finally:
        set_current_user_id(None)


def test_music_admin_sees_all_libraries(tmp_path):
    """管理员不受授权限制，看到全部启用音乐库。"""
    db, _ = _setup(tmp_path)
    admin = User(username="boss", password_hash="x", role=UserRole.ADMIN)
    db.add(admin)
    db.commit()

    set_current_user_id(admin.id)
    try:
        assert music.list_artists(db, page=0, size=20).data["totalElements"] == 2
        assert music.list_albums(db, page=0, size=20).data["totalElements"] == 2
        assert music.search_songs(db, page=0, size=20).data["totalElements"] == 2
    finally:
        set_current_user_id(None)
