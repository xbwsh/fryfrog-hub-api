from __future__ import annotations

import logging
from typing import Any

from sqlalchemy.orm import Session

from fryfrog.models.library import MediaLibrary

logger = logging.getLogger(__name__)


def scan_library(db: Session, library: MediaLibrary) -> None:
    kind = (library.type or "").upper()
    if kind == "VIDEO":
        from fryfrog.services.video_scan import scan_video_library

        scan_video_library(db, library)
    elif kind == "MUSIC":
        from fryfrog.services.music_scan import scan_music_library

        scan_music_library(db, library)
    elif kind == "AUDIOBOOK":
        from fryfrog.services.audiobook_scan import scan_audiobook_library

        scan_audiobook_library(db, library)
    elif kind == "EBOOK":
        from fryfrog.services.ebook_scan import scan_ebook_library

        scan_ebook_library(db, library)
    elif kind == "COMIC":
        from fryfrog.services.comic_scan import scan_comic_library

        scan_comic_library(db, library)
    else:
        logger.warning("Unknown library type: %s", kind)


def scan_all_enabled(db: Session) -> None:
    from fryfrog.services.media_library import MediaLibraryService
    from fryfrog.core.security import UserService

    service = MediaLibraryService(UserService())
    for lib in service.get_enabled_libraries(db):
        try:
            scan_library(db, lib)
            db.commit()
        except Exception:
            db.rollback()
            logger.exception("Scan failed for library %s", lib.id)
