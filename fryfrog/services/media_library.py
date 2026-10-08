from __future__ import annotations

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from fryfrog.config import get_settings
from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.core.security import ANONYMOUS_ID, UserService, current_user_id_or_none
from fryfrog.core.utils import normalize_path
from fryfrog.models.library import MediaLibrary, UserLibrary


class MediaLibraryService:
    def __init__(self, user_service: UserService):
        self.user_service = user_service

    def init(self, db: Session) -> None:
        self._migrate_old_type_values(db)
        settings = get_settings()
        if db.scalar(select(MediaLibrary.id).limit(1)) is None and settings.video_root_paths:
            self._migrate_legacy_config(db, settings.video_root_paths)

    def _migrate_old_type_values(self, db: Session) -> None:
        for library in db.scalars(select(MediaLibrary)).all():
            type_val = library.type
            if type_val and type_val.upper() in ("MOVIE", "TV", "MIXED"):
                library.sub_type = type_val.upper()
                library.type = "VIDEO"
                db.flush()

    def _migrate_legacy_config(self, db: Session, legacy_root_paths: str) -> None:
        paths = [p.strip() for p in legacy_root_paths.split(",") if p.strip()]
        for order, path in enumerate(paths):
            db.add(
                MediaLibrary(
                    name="默认资源库",
                    path=path,
                    type="VIDEO",
                    sub_type="MIXED",
                    enabled=True,
                    sort_order=order,
                    description="从 application.yml 迁移的默认配置",
                )
            )
        db.flush()

    def get_all_libraries(self, db: Session) -> list[MediaLibrary]:
        return list(
            db.scalars(select(MediaLibrary).order_by(MediaLibrary.sort_order.asc())).all()
        )

    def get_visible_libraries(self, db: Session) -> list[MediaLibrary]:
        user_id = current_user_id_or_none()
        if not self.is_restricted_user(db, user_id):
            return self.get_all_libraries(db)
        allowed = self.get_allowed_library_ids(db, user_id)
        return [lib for lib in self.get_all_libraries(db) if lib.id in allowed]

    def get_enabled_libraries(self, db: Session) -> list[MediaLibrary]:
        return list(
            db.scalars(
                select(MediaLibrary)
                .where(MediaLibrary.enabled.is_(True))
                .order_by(MediaLibrary.sort_order.asc())
            ).all()
        )

    def get_library_by_id(self, db: Session, library_id: int) -> MediaLibrary:
        lib = db.get(MediaLibrary, library_id)
        if lib is None:
            raise ResourceNotFoundException("MediaLibrary", "id", library_id)
        return lib

    def create_library(self, db: Session, data: dict) -> MediaLibrary:
        if data.get("sort_order") is None:
            from sqlalchemy import func

            data["sort_order"] = int(db.scalar(select(func.count(MediaLibrary.id))) or 0)
        if data.get("path"):
            data["path"] = normalize_path(data["path"])
        lib = MediaLibrary(**{k: v for k, v in data.items() if v is not None or k in ("description", "sub_type")})
        db.add(lib)
        db.flush()
        return lib

    def update_library(self, db: Session, library_id: int, data: dict) -> MediaLibrary:
        lib = self.get_library_by_id(db, library_id)
        for field in (
            "name",
            "type",
            "sub_type",
            "enabled",
            "enable_scraping",
            "is_adult",
            "sort_order",
            "description",
        ):
            if field in data and data[field] is not None:
                setattr(lib, field, data[field])
        if data.get("path") is not None:
            lib.path = normalize_path(data["path"])
        db.flush()
        return lib

    def delete_library(self, db: Session, library_id: int) -> None:
        lib = self.get_library_by_id(db, library_id)
        deleted_order = lib.sort_order or 0
        _purge_library_rows(db, library_id)
        db.delete(lib)
        db.flush()
        for other in self.get_all_libraries(db):
            current = other.sort_order or 0
            if current > deleted_order:
                other.sort_order = current - 1
        db.flush()

    def toggle_library(self, db: Session, library_id: int) -> MediaLibrary:
        lib = self.get_library_by_id(db, library_id)
        lib.enabled = not lib.enabled
        db.flush()
        return lib

    def get_enabled_library_ids(self, db: Session) -> list[int]:
        return [lib.id for lib in self.get_enabled_libraries(db)]

    def get_allowable_library_ids(self, db: Session) -> list[int]:
        user_id = current_user_id_or_none()
        if user_id is None:
            return self.get_enabled_library_ids(db)
        return self.get_allowed_library_ids(db, user_id)

    def get_allowed_library_ids(self, db: Session, user_id: int | None) -> list[int]:
        if user_id is None or user_id == ANONYMOUS_ID or self.user_service.is_admin(db, user_id):
            return self.get_enabled_library_ids(db)
        enabled = set(self.get_enabled_library_ids(db))
        rows = db.scalars(select(UserLibrary).where(UserLibrary.user_id == user_id)).all()
        return [r.library_id for r in rows if r.library_id in enabled]

    def is_restricted_current_user(self, db: Session) -> bool:
        return self.is_restricted_user(db, current_user_id_or_none())

    def is_restricted_user(self, db: Session, user_id: int | None) -> bool:
        return (
            user_id is not None
            and user_id != ANONYMOUS_ID
            and not self.user_service.is_admin(db, user_id)
        )

    def is_visible_to_current_user(self, db: Session, library_id: int | None) -> bool:
        if not self.is_restricted_current_user(db):
            return True
        return library_id is not None and library_id in self.get_allowable_library_ids(db)

    def assign_libraries(self, db: Session, user_id: int, library_ids: list[int] | None) -> None:
        self.user_service.get_user(db, user_id)
        target = list(dict.fromkeys(library_ids or []))
        current_rows = db.scalars(select(UserLibrary).where(UserLibrary.user_id == user_id)).all()
        current = {r.library_id for r in current_rows}
        for add_id in target:
            if add_id not in current:
                self.get_library_by_id(db, add_id)
                db.add(UserLibrary(user_id=user_id, library_id=add_id))
        for remove_id in current - set(target):
            for row in current_rows:
                if row.library_id == remove_id:
                    db.delete(row)
        db.flush()

    def get_assigned_library_ids(self, db: Session, user_id: int) -> list[int]:
        rows = db.scalars(select(UserLibrary).where(UserLibrary.user_id == user_id)).all()
        return [r.library_id for r in rows]

    def get_enabled_paths(self, db: Session) -> list[str]:
        return [lib.path for lib in self.get_enabled_libraries(db)]

    def is_path_in_enabled_library(self, db: Session, file_path: str | None) -> bool:
        if not file_path:
            return False
        return any(file_path.startswith(p) for p in self.get_enabled_paths(db))

    def find_by_path(self, db: Session, path: str) -> MediaLibrary | None:
        for lib in self.get_all_libraries(db):
            if path.startswith(lib.path) or lib.path.startswith(path):
                return lib
        return None

def _purge_library_rows(db: Session, library_id: int) -> None:
    """删库时把库里所有媒体的行一起清掉（含子表）。

    这些表都带 library_id 但**没有外键**，只删 media_libraries 会留下孤儿行：
    file_path/book_path 是全局唯一约束，孤儿行会把重建的同路径库顶死
    （ebooks 实测「扫描永久失败」）；user_libraries 残留还会在库 id 复用时
    白送一次越权授权。删除顺序先子后主，避免 foreign_keys=ON 下的 FK 冲突。
    """
    from fryfrog.models.audiobook import (
        Audiobook,
        AudiobookChapter,
        AudiobookProgress,
        AudiobookTrack,
    )
    from fryfrog.models.comic import Comic, ComicChapter, ComicProgress
    from fryfrog.models.ebook import Ebook, EbookProgress
    from fryfrog.models.music import (
        MusicAlbum,
        MusicArtist,
        MusicBookmark,
        MusicPlayStat,
        MusicPlaylistEntry,
        MusicRating,
        MusicSong,
        MusicStar,
    )
    from fryfrog.models.video import Favorite, Video, VideoActor, VideoSeries, WatchProgress

    # 视频/剧
    video_ids = list(db.scalars(select(Video.id).where(Video.library_id == library_id)).all())
    series_ids = {
        sid
        for sid in db.scalars(select(Video.series_id).where(Video.library_id == library_id)).all()
        if sid is not None
    }
    db.execute(delete(WatchProgress).where(WatchProgress.video_id.in_(video_ids)))
    db.execute(delete(VideoActor).where(VideoActor.video_id.in_(video_ids)))
    db.execute(delete(Favorite).where(Favorite.content_type == "VIDEO", Favorite.content_id.in_(video_ids)))
    db.execute(delete(Video).where(Video.library_id == library_id))
    db.flush()
    if series_ids:
        kept = set(db.scalars(select(Video.series_id).where(Video.series_id.in_(series_ids))).all())
        emptied = [sid for sid in series_ids if sid not in kept]
        if emptied:
            db.execute(delete(Favorite).where(Favorite.content_type == "SERIES", Favorite.content_id.in_(emptied)))
            db.execute(delete(VideoSeries).where(VideoSeries.id.in_(emptied)))
    db.flush()

    # 音乐
    song_ids = list(db.scalars(select(MusicSong.id).where(MusicSong.library_id == library_id)).all())
    album_ids = [a.id for a in db.scalars(select(MusicAlbum.id).where(MusicAlbum.library_id == library_id)).all()]
    artist_ids = [a.id for a in db.scalars(select(MusicArtist.id).where(MusicArtist.library_id == library_id)).all()]
    db.execute(delete(MusicPlaylistEntry).where(MusicPlaylistEntry.song_id.in_(song_ids)))
    db.execute(delete(MusicBookmark).where(MusicBookmark.song_id.in_(song_ids)))
    db.execute(delete(MusicPlayStat).where(MusicPlayStat.song_id.in_(song_ids)))
    for target_type, ids in (("SONG", song_ids), ("ALBUM", album_ids), ("ARTIST", artist_ids)):
        if not ids:
            continue
        db.execute(delete(MusicStar).where(MusicStar.target_type == target_type, MusicStar.target_id.in_(ids)))
        db.execute(delete(MusicRating).where(MusicRating.target_type == target_type, MusicRating.target_id.in_(ids)))
    db.execute(delete(MusicSong).where(MusicSong.library_id == library_id))
    db.execute(delete(MusicAlbum).where(MusicAlbum.library_id == library_id))
    db.execute(delete(MusicArtist).where(MusicArtist.library_id == library_id))
    db.flush()

    # 有声书
    book_ids = list(db.scalars(select(Audiobook.id).where(Audiobook.library_id == library_id)).all())
    db.execute(delete(AudiobookTrack).where(AudiobookTrack.audiobook_id.in_(book_ids)))
    db.execute(delete(AudiobookChapter).where(AudiobookChapter.audiobook_id.in_(book_ids)))
    db.execute(delete(AudiobookProgress).where(AudiobookProgress.audiobook_id.in_(book_ids)))
    db.execute(delete(Audiobook).where(Audiobook.library_id == library_id))

    # 漫画
    comic_ids = list(db.scalars(select(Comic.id).where(Comic.library_id == library_id)).all())
    db.execute(delete(ComicChapter).where(ComicChapter.comic_id.in_(comic_ids)))
    db.execute(delete(ComicProgress).where(ComicProgress.comic_id.in_(comic_ids)))
    db.execute(delete(Comic).where(Comic.library_id == library_id))

    # 电子书
    ebook_ids = list(db.scalars(select(Ebook.id).where(Ebook.library_id == library_id)).all())
    db.execute(delete(EbookProgress).where(EbookProgress.ebook_id.in_(ebook_ids)))
    db.execute(delete(Ebook).where(Ebook.library_id == library_id))

    # 授权关系：库 id 复用时会白送越权访问
    db.execute(delete(UserLibrary).where(UserLibrary.library_id == library_id))
    db.flush()
