from __future__ import annotations

import logging
import threading
import time
from typing import Any

from sqlalchemy.orm import Session

from fryfrog.models.library import MediaLibrary
from fryfrog.services import progress as progress_svc

logger = logging.getLogger(__name__)

# 同一个库不允许并发扫描：热监听、周期扫描、手动扫描可能同时触发同一库，
# 并发跑会互相 unlink/写库，也可能把对方刚建的记录当缺失处理。
# 记录起始时间并设自愈超时：扫描线程可能卡在刮削的网络请求上，
# 没有超时的话占位永不释放，之后所有触发都会被静默吞掉。
_scan_lock = threading.Lock()
_running_scans: dict[int, float] = {}


def _claim_library_scan(library: MediaLibrary) -> bool:
    """抢占某库的扫描权；拿不到（正在扫且未超时）返回 False。"""
    if library.id is None:
        return True
    from fryfrog.config import get_settings

    stale_after = max(float(get_settings().scan_stale_after_seconds), 60.0)
    now = time.monotonic()
    with _scan_lock:
        started = _running_scans.get(library.id)
        if started is not None and (now - started) < stale_after:
            return False
        if started is not None:
            logger.warning(
                "上次扫描已卡住 %.0f 秒，放弃占位并重新触发: %s", now - started, library.name
            )
        _running_scans[library.id] = now
        return True


def scan_progress_module(library: MediaLibrary) -> str:
    """与 progress.get_pipeline_progress 约定一致的进度键。"""
    return f"scan:{library.type}:{library.id}"


def report_scan_progress(library: MediaLibrary, **fields: Any) -> None:
    """把扫描进度写进内存进度表。

    写两份：
    - `scan:{TYPE}:{id}`：/scan/progress 聚合用，也提供 percent/running；
    - `pipeline:{id}`：/{id}/pipeline-progress 的 stage 与 currentItem 取自这里，
      不写的话客户端会一直显示 idle。
    """
    progress_svc.update_progress(scan_progress_module(library), libraryId=library.id, **fields)
    progress_svc.update_progress(
        f"pipeline:{library.id}", libraryId=library.id, **fields
    )


def scan_library(db: Session, library: MediaLibrary) -> None:
    if not _claim_library_scan(library):
        logger.info("该库正在扫描中，跳过重复触发: %s", library.name)
        return
    report_scan_progress(library, stage="scan", running=True, total=1, completed=0, failed=0)
    failed = 0
    try:
        _scan_library(db, library)
    except Exception:
        failed = 1
        raise
    finally:
        if library.id is not None:
            with _scan_lock:
                _running_scans.pop(library.id, None)
        # 写终态：客户端以 running/percent 判断扫描结束，不写会一直停在"扫描中"
        report_scan_progress(
            library,
            stage="done",
            running=False,
            total=1,
            completed=1,
            failed=failed,
            currentItem=None,
        )


def _scan_library(db: Session, library: MediaLibrary) -> None:
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
    """同步扫描全部启用库（周期调度与后台任务共用）。"""
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


def submit_scan_job(library_ids: list[int]) -> None:
    """后台线程扫描多个库；立即返回，进度由 /scan/progress 轮询。

    library_ids 为空（无启用库）时立即收尾，避免客户端轮询一直等。
    """
    total = len(library_ids)

    def work(session: Session) -> None:
        from fryfrog.services.media_library import MediaLibraryService
        from fryfrog.core.security import UserService

        service = MediaLibraryService(UserService())
        for index, lib_id in enumerate(library_ids):
            library = service.get_library_by_id(session, lib_id)
            # 双键：scan:ALL 供 /scan/progress 聚合；pipeline:{id} 供单库详情页
            progress_svc.update_progress(
                "scan:ALL", libraryId=lib_id, currentItem=library.name, completed=index
            )
            progress_svc.update_progress(
                f"pipeline:{lib_id}",
                libraryId=lib_id,
                stage="scan",
                running=True,
                total=1,
                completed=0,
                currentItem=library.name,
            )
            try:
                scan_library(session, library)
                session.commit()
            except Exception:
                session.rollback()
                logger.exception("Scan failed for library %s", lib_id)
                progress_svc.update_progress("scan:ALL", failed=index + 1, completed=index + 1)

    if total == 0:
        progress_svc.update_progress("scan:ALL", stage="done", running=False, total=0, completed=0)
        return

    from fryfrog.routers.video._common import submit_job

    submit_job("scan:ALL", "scan", total, work)
