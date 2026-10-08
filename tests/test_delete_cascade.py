"""删媒体库 / 删用户的级联清理。

这两条路径的表都只有裸 library_id / user_id、没有外键：
- 删库留孤儿媒体行：file_path/book_path 全局唯一，孤儿行会把重建的同路径库顶死
- 删用户留孤儿子行：SQLite 删掉最大 rowid 后新用户会复用同 id，旧用户的
  收藏/进度/歌单直接串号到新用户头上
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from datetime import datetime, timedelta

from sqlalchemy import create_engine, event, select
from sqlalchemy.orm import sessionmaker

from fryfrog.core.security import UserService
from fryfrog.db import Base
from fryfrog.models.audiobook import Audiobook, AudiobookProgress
from fryfrog.models.auth import AuthToken
from fryfrog.models.comic import Comic, ComicProgress
from fryfrog.models.ebook import Ebook, EbookProgress
from fryfrog.models.library import MediaLibrary, UserLibrary, UserPreference
from fryfrog.models.music import (
    MusicBookmark,
    MusicPlayQueue,
    MusicPlayStat,
    MusicPlaylist,
    MusicPlaylistEntry,
    MusicRating,
    MusicSong,
    MusicStar,
)
from fryfrog.models.user import User
from fryfrog.models.video import Favorite, Video, WatchProgress
from fryfrog.services.media_library import MediaLibraryService


def _engine():
    engine = create_engine("sqlite://")

    @event.listens_for(engine, "connect")
    def _fk_on(dbapi_conn, _):
        cur = dbapi_conn.cursor()
        cur.execute("PRAGMA foreign_keys=ON")
        cur.close()

    Base.metadata.create_all(engine)
    return engine


def _user(db, name: str) -> User:
    user = User(username=name, password_hash="x")
    db.add(user)
    db.flush()
    return user


def _lib(db, path: str, type_: str = "EBOOK") -> MediaLibrary:
    lib = MediaLibrary(name=f"库-{path}", path=path, type=type_, enable_scraping=False)
    db.add(lib)
    db.flush()
    return lib


def _ebook(db, lib, file_path: str) -> Ebook:
    book = Ebook(title="书", format="EPUB", file_path=file_path, library_id=lib.id)
    db.add(book)
    db.flush()
    return book


def _seed_user_rows(db, user: User, lib: MediaLibrary, media: dict) -> None:
    """把用户维度的子行铺满：token/偏好/授权/收藏/进度/歌单/队列等。"""
    db.add(AuthToken(token=f"tok-{user.username}", user_id=user.id,
                     expires_at=datetime.now() + timedelta(days=1)))
    db.add(UserPreference(user_id=user.id, pref_key="theme", pref_value="dark"))
    db.add(UserLibrary(user_id=user.id, library_id=lib.id))
    db.add(Favorite(user_id=user.id, content_type="VIDEO", content_id=media["video"].id))
    db.add(WatchProgress(user_id=user.id, video_id=media["video"].id, position_seconds=10.0))
    db.add(EbookProgress(user_id=user.id, ebook_id=media["ebook"].id, position_percent=50.0,
                         completed=False))
    db.add(ComicProgress(user_id=user.id, comic_id=media["comic"].id, page_index=3,
                         completed=False))
    db.add(AudiobookProgress(user_id=user.id, audiobook_id=media["audiobook"].id,
                             track_index=1, completed=False))
    db.add(MusicRating(user_id=user.id, target_type="SONG", target_id=media["song"].id, rating=5))
    db.add(MusicStar(user_id=user.id, target_type="SONG", target_id=media["song"].id))
    db.add(MusicBookmark(user_id=user.id, song_id=media["song"].id, position_seconds=30.0))
    db.add(MusicPlayStat(song_id=media["song"].id, user_id=user.id, play_count=2))
    db.add(MusicPlayQueue(user_id=user.id, entry_ids=str(media["song"].id),
                          current_song_id=media["song"].id))
    playlist = MusicPlaylist(name=f"歌单-{user.username}", user_id=user.id)
    db.add(playlist)
    db.flush()
    db.add(MusicPlaylistEntry(playlist_id=playlist.id, song_id=media["song"].id, position=0))
    db.flush()


def _seed_media(db, lib: MediaLibrary) -> dict:
    """媒体行本身归库所有，删用户时必须原样保留。"""
    media = {
        "ebook": _ebook(db, lib, f"{lib.path}/a.epub"),
        "comic": Comic(title="漫", book_path=f"{lib.path}/c", library_id=lib.id),
        "audiobook": Audiobook(title="书音", book_path=f"{lib.path}/ab",
                               play_type="SINGLE", library_id=lib.id),
        "video": Video(title="片", file_path=f"{lib.path}/v.mp4", file_name="v.mp4",
                       library_id=lib.id),
        "song": MusicSong(title="歌", file_path=f"{lib.path}/s.mp3", library_id=lib.id),
    }
    for row in media.values():
        db.add(row)
    db.flush()
    return media


def _count(db, model, **where) -> int:
    from sqlalchemy import func

    stmt = select(func.count()).select_from(model)
    for col, val in where.items():
        stmt = stmt.where(getattr(model, col) == val)
    return db.scalar(stmt)


# ── 删媒体库 ──────────────────────────────────────────────


def test_delete_library_purges_media_children_and_authz():
    engine = _engine()
    db = sessionmaker(bind=engine)()
    u1, u2 = _user(db, "alice"), _user(db, "bob")

    lib1 = _lib(db, "/m1")
    lib2 = _lib(db, "/m2")
    ebook1 = _ebook(db, lib1, "/m1/a.epub")
    ebook2 = _ebook(db, lib2, "/m2/b.epub")
    comic1 = Comic(title="漫1", book_path="/m1/c1", library_id=lib1.id)
    db.add(comic1)
    db.flush()
    db.add(EbookProgress(user_id=u1.id, ebook_id=ebook1.id, position_percent=10.0, completed=False))
    db.add(EbookProgress(user_id=u2.id, ebook_id=ebook1.id, position_percent=20.0, completed=False))
    db.add(EbookProgress(user_id=u2.id, ebook_id=ebook2.id, position_percent=30.0, completed=False))
    db.add(ComicProgress(user_id=u1.id, comic_id=comic1.id, page_index=1, completed=False))
    db.add(UserLibrary(user_id=u1.id, library_id=lib1.id))
    db.add(UserLibrary(user_id=u1.id, library_id=lib2.id))
    db.add(UserLibrary(user_id=u2.id, library_id=lib2.id))
    db.flush()

    service = MediaLibraryService(UserService())
    service.delete_library(db, lib1.id)

    # 库1 的媒体行 + 子行 + 授权全部清掉，库2 原样
    assert _count(db, Ebook, library_id=lib1.id) == 0
    assert _count(db, Comic, library_id=lib1.id) == 0
    assert _count(db, EbookProgress, ebook_id=ebook1.id) == 0
    assert _count(db, ComicProgress, comic_id=comic1.id) == 0
    assert _count(db, UserLibrary, library_id=lib1.id) == 0
    assert _count(db, Ebook, library_id=lib2.id) == 1
    assert _count(db, EbookProgress, ebook_id=ebook2.id) == 1
    assert _count(db, UserLibrary, library_id=lib2.id) == 2
    assert db.get(MediaLibrary, lib1.id) is None

    # 重建同路径库 + 同 file_path 书：若孤儿行还在，这里会撞唯一约束
    lib1b = _lib(db, "/m1")
    _ebook(db, lib1b, "/m1/a.epub")
    db.flush()


# ── 删用户 ────────────────────────────────────────────────

_USER_DIM_MODELS = [
    AuthToken, UserPreference, UserLibrary, Favorite, WatchProgress,
    EbookProgress, ComicProgress, AudiobookProgress, MusicRating, MusicStar,
    MusicBookmark, MusicPlayStat, MusicPlayQueue, MusicPlaylist,
]


def test_delete_user_purges_all_user_rows_and_spares_media():
    engine = _engine()
    db = sessionmaker(bind=engine)()
    u1, u2 = _user(db, "alice"), _user(db, "bob")
    lib = _lib(db, "/m1")
    media = _seed_media(db, lib)
    _seed_user_rows(db, u1, lib, media)
    _seed_user_rows(db, u2, lib, media)
    playlist1 = db.scalar(select(MusicPlaylist).where(MusicPlaylist.user_id == u1.id))

    UserService().delete_user(db, u1.id)

    # u1 的所有子行清零（含歌单条目），u2 原样
    for model in _USER_DIM_MODELS:
        assert _count(db, model, user_id=u1.id) == 0, model.__name__
        assert _count(db, model, user_id=u2.id) >= 1, model.__name__
    assert _count(db, MusicPlaylistEntry, playlist_id=playlist1.id) == 0
    # 媒体行（归库所有）与 u2 的歌单条目保留
    for row in media.values():
        assert db.get(type(row), row.id) is not None, type(row).__name__
    assert _count(db, MusicPlaylistEntry) == 1
    assert db.get(User, u1.id) is None


def test_reused_user_id_inherits_no_orphan_rows():
    """SQLite 删掉最大 rowid 后新用户会复用同 id——旧子行必须已清干净。"""
    engine = _engine()
    db = sessionmaker(bind=engine)()
    u1 = _user(db, "alice")
    lib = _lib(db, "/m1")
    media = _seed_media(db, lib)
    _seed_user_rows(db, u1, lib, media)
    old_id = u1.id

    UserService().delete_user(db, u1.id)

    u2 = _user(db, "carol")
    assert u2.id == old_id, "SQLite 应复用被删掉的最大 rowid，前提成立才测得到串号"
    for model in _USER_DIM_MODELS:
        assert _count(db, model, user_id=u2.id) == 0, model.__name__
