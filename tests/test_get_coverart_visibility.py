"""Subsonic getCoverArt 库可见性：隐藏库的专辑/歌曲/艺术家封面不得泄露。

实测故障：stream/download 已做 _is_visible_library 校验，但 getCoverArt 的
album/song/artist 分支直接按 id 取图，从不过滤——受限用户遍历 al-/tr-/ar-
就能拿到隐藏库的内嵌封面，泄露媒体库存在性与内容。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import io
from pathlib import Path

import pytest
from PIL import Image
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.core.security import set_current_user_id
from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary, UserLibrary
from fryfrog.models.music import MusicAlbum, MusicArtist, MusicSong
from fryfrog.models.user import User, UserRole
from fryfrog.routers import music


def _jpeg() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (16, 16), (200, 40, 60)).save(buf, format="JPEG")
    return buf.getvalue()


def _setup(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    allowed = MediaLibrary(name="可见库", path=str(tmp_path / "a"), type="MUSIC", enabled=True)
    hidden = MediaLibrary(name="隐藏库", path=str(tmp_path / "b"), type="MUSIC", enabled=True)
    db.add_all([allowed, hidden])
    db.flush()

    # 隐藏库：有封面专辑 + 歌曲 + 艺术家
    hidden_song_path = str(tmp_path / "b" / "s.mp3")
    Path(hidden_song_path).parent.mkdir(parents=True, exist_ok=True)
    Path(hidden_song_path).write_bytes(_jpeg())
    hidden_artist = MusicArtist(name="隐藏歌手", library_id=hidden.id, cover_art_path=hidden_song_path)
    db.add(hidden_artist)
    db.flush()
    hidden_album = MusicAlbum(
        title="隐藏专辑", artist_name="隐藏歌手", library_id=hidden.id,
        cover_art_path=hidden_song_path,
    )
    db.add(hidden_album)
    db.flush()
    hidden_song = MusicSong(
        title="隐藏单曲", album_id=hidden_album.id, artist_id=hidden_artist.id,
        library_id=hidden.id, file_path=hidden_song_path,
    )
    db.add(hidden_song)
    db.flush()

    # 可见库也有一个（对照组）
    vis_song_path = str(tmp_path / "a" / "v.mp3")
    Path(vis_song_path).parent.mkdir(parents=True, exist_ok=True)
    Path(vis_song_path).write_bytes(_jpeg())
    db.add(MusicArtist(name="可见歌手", library_id=allowed.id, cover_art_path=vis_song_path))
    db.flush()
    vis_album = MusicAlbum(
        title="可见专辑", artist_name="可见歌手", library_id=allowed.id, cover_art_path=vis_song_path,
    )
    db.add(vis_album)
    db.flush()
    vis_song = MusicSong(
        title="可见单曲", album_id=vis_album.id, library_id=allowed.id, file_path=vis_song_path,
    )
    db.add(vis_song)
    db.flush()

    user = User(username="limited", password_hash="x", role=UserRole.USER)
    db.add(user)
    db.flush()
    db.add(UserLibrary(user_id=user.id, library_id=allowed.id))
    db.flush()
    db.commit()
    return db, hidden_album.id, hidden_song.id, hidden_artist.id, vis_album.id, vis_song.id, user.id


def _cover(db, params: dict) -> bytes:
    return music._ss_binary(db, "getCoverArt", params, None).body


def test_hidden_album_cover_is_placeholder(tmp_path):
    db, hidden_album, hidden_song, hidden_artist, vis_album, vis_song, uid = _setup(tmp_path)
    set_current_user_id(uid)
    try:
        # hidden 专辑/单曲/艺术家 → placeholder（图是 16x16 红块，placeholder 是别的）
        assert _cover(db, {"id": f"al-{hidden_album}"}) != _jpeg()
        assert _cover(db, {"id": f"tr-{hidden_song}"}) != _jpeg()
        assert _cover(db, {"id": f"ar-{hidden_artist}"}) != _jpeg()
        # 可见库封面正常返回（对照：隔离没有误伤授权库）
        assert _cover(db, {"id": f"al-{vis_album}"}) == _jpeg()
        assert _cover(db, {"id": f"tr-{vis_song}"}) == _jpeg()
    finally:
        set_current_user_id(None)


def test_anonymous_still_sees_everything(tmp_path):
    """匿名用户（无当前用户）仍应看到全部库封面：getCoverArt 的放行语义不能为
    权限校验而收紧匿名,扫描/NFO 等匿名上下文依赖这条路径。"""
    db, hidden_album, _hidden_song, _artist, _v_album, _v_song, _uid = _setup(tmp_path)
    set_current_user_id(None)
    assert _cover(db, {"id": f"al-{hidden_album}"}) == _jpeg()