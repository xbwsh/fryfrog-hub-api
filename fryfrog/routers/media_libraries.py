from __future__ import annotations

import os
import sys
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException

from fryfrog.core.api_response import ApiResponse
from fryfrog.core.deps import DbSession, get_media_library_service
from fryfrog.models.video import Video
from fryfrog.schemas.common import (
    MediaLibraryCreateRequest,
    MediaLibraryDTO,
    MediaLibraryUpdateRequest,
)
from fryfrog.services.media_library import MediaLibraryService

router = APIRouter(prefix="/api/v1/media-libraries", tags=["媒体库"])


def _browse_item(name: str, path: str, writable: bool) -> dict:
    # 与旧版 Java MediaLibraryBrowseService 契约一致
    return {"name": name, "path": path, "writable": writable}


def _list_roots() -> list[dict]:
    result: list[dict] = []
    seen: set[str] = set()
    for p in ("/data/media/video", "/data/media", "/data", "/data/music"):
        dir_path = Path(p)
        try:
            if dir_path.is_dir():
                resolved = str(dir_path.resolve())
                if resolved not in seen:
                    seen.add(resolved)
                    result.append(_browse_item(p, resolved, os.access(resolved, os.W_OK)))
        except OSError:
            continue
    fs_roots: list[str] = []
    if sys.platform == "win32":
        import string

        fs_roots = [f"{d}:\\" for d in string.ascii_uppercase if Path(f"{d}:\\").exists()]
    else:
        fs_roots = ["/"]
    for root_path in fs_roots:
        if root_path in seen:
            continue
        if Path(root_path).exists():
            seen.add(root_path)
            result.append(_browse_item(root_path, root_path, os.access(root_path, os.W_OK)))
    return result


def _list_children(dir_path: Path) -> list[dict]:
    result: list[dict] = []
    try:
        for child in dir_path.iterdir():
            name = child.name
            if name.startswith("."):
                continue
            try:
                if not child.is_dir():
                    continue
                writable = os.access(child, os.W_OK)
            except OSError:
                continue
            result.append(_browse_item(name, str(child.absolute()), writable))
    except PermissionError:
        raise HTTPException(status_code=403, detail="Permission denied")
    except OSError:
        return []
    result.sort(key=lambda e: e["name"].lower())
    return result


# ── 固定路径必须在 /{library_id} 之前 ──────────────────────────


@router.get("")
def list_libraries(db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    libs = service.get_visible_libraries(db)
    return ApiResponse.ok([MediaLibraryDTO.from_entity(x).model_dump(exclude_none=True) for x in libs])


@router.post("")
def create_library(body: MediaLibraryCreateRequest, db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    data = {
        "name": body.name,
        "path": body.path,
        "type": body.type,
        "sub_type": body.subType,
        "enabled": body.enabled if body.enabled is not None else True,
        "enable_scraping": body.enableScraping if body.enableScraping is not None else True,
        "is_adult": body.isAdult if body.isAdult is not None else False,
        "sort_order": body.sortOrder,
        "description": body.description,
    }
    lib = service.create_library(db, data)
    return ApiResponse.ok(MediaLibraryDTO.from_entity(lib).model_dump(exclude_none=True))


@router.get("/browse")
def browse(path: str | None = None):
    """浏览服务器目录：不传 path 返回磁盘根；传 path 列出子目录。契约对齐旧版 Java。"""
    if not path or not str(path).strip():
        return ApiResponse.ok(_list_roots())
    dir_path = Path(path)
    if not dir_path.is_dir():
        return ApiResponse.ok([])
    return ApiResponse.ok(_list_children(dir_path))


@router.post("/scan")
def scan_all(db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    """后台扫描全部启用库，立即返回；进度由 /scan/progress 轮询。

    扫描可能耗时数分钟，必须放后台线程——客户端默认 15 秒超时，
    同步执行会让它误判失败，而服务端其实还在扫。
    """
    libs = service.get_enabled_libraries(db)
    library_ids = [lib.id for lib in libs]
    from fryfrog.services import scan as scan_svc

    scan_svc.submit_scan_job(library_ids)
    return ApiResponse.ok({"status": "started", "libraryCount": len(libs)})


@router.get("/scan/progress")
def scan_progress(db: DbSession, service: MediaLibraryService = Depends(get_media_library_service), library_id: int | None = None):
    from fryfrog.services import progress as progress_svc

    items = progress_svc.get_scrape_progress(library_id)
    return ApiResponse.ok(items)


# ── 带 ID 的路径 ──────────────────────────────────────────────


@router.get("/{library_id}")
def get_library(library_id: int, db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    lib = service.get_library_by_id(db, library_id)
    if not service.is_visible_to_current_user(db, library_id):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    return ApiResponse.ok(MediaLibraryDTO.from_entity(lib).model_dump(exclude_none=True))


@router.put("/{library_id}")
def update_library(
    library_id: int,
    body: MediaLibraryUpdateRequest,
    db: DbSession,
    service: MediaLibraryService = Depends(get_media_library_service),
):
    data = {
        "name": body.name,
        "path": body.path,
        "type": body.type,
        "sub_type": body.subType,
        "enabled": body.enabled,
        "enable_scraping": body.enableScraping,
        "is_adult": body.isAdult,
        "sort_order": body.sortOrder,
        "description": body.description,
    }
    lib = service.update_library(db, library_id, data)
    return ApiResponse.ok(MediaLibraryDTO.from_entity(lib).model_dump(exclude_none=True))


@router.delete("/{library_id}")
def delete_library(library_id: int, db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    service.delete_library(db, library_id)
    return ApiResponse.ok({"deleted": library_id})


@router.put("/{library_id}/toggle")
def toggle_library(library_id: int, db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    lib = service.toggle_library(db, library_id)
    return ApiResponse.ok(MediaLibraryDTO.from_entity(lib).model_dump(exclude_none=True))


@router.post("/{library_id}/scan")
def scan_one(library_id: int, db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    """后台扫描单个库，立即返回；进度由 /{id}/pipeline-progress 轮询。"""
    lib = service.get_library_by_id(db, library_id)
    from fryfrog.services import scan as scan_svc

    scan_svc.submit_scan_job([lib.id])
    return ApiResponse.ok({"libraryId": lib.id, "libraryName": lib.name, "status": "started"})


@router.get("/{library_id}/pipeline-progress")
def pipeline_progress(library_id: int, db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    lib = service.get_library_by_id(db, library_id)
    if not service.is_visible_to_current_user(db, library_id):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    from fryfrog.services import progress as progress_svc

    return ApiResponse.ok(progress_svc.get_pipeline_progress(lib))


@router.get("/{library_id}/stale-records")
def stale_records(library_id: int, db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    """体检：列出本库中文件已不存在的记录（只报告，不改动）。

    用于确认「手动删过文件但数据库仍有残留」的规模。
    """
    lib = service.get_library_by_id(db, library_id)
    if not service.is_visible_to_current_user(db, library_id):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    from fryfrog.services import video_scan as scan_svc

    ids = scan_svc.stale_video_ids(db, lib)
    samples = []
    for vid in ids[:50]:
        video = db.get(Video, vid)
        if video is not None:
            samples.append(
                {
                    "id": video.id,
                    "fileName": video.file_name,
                    "filePath": video.file_path,
                    "tmdbId": video.tmdb_id,
                    "seriesId": video.series_id,
                }
            )
    return ApiResponse.ok(
        {"libraryId": lib.id, "libraryPath": lib.path, "staleCount": len(ids), "samples": samples}
    )


@router.post("/{library_id}/purge-stale")
def purge_stale_records(
    library_id: int,
    db: DbSession,
    dryRun: bool = False,
    force: bool = True,
    service: MediaLibraryService = Depends(get_media_library_service),
):
    """清理残留记录：删除本库中文件已不存在的行（含因此变空的剧组）。

    force 默认 true（用户主动清理时不走宽限期），但磁盘护栏始终生效：
    现有文件数不足上轮存量的一半时整体拒绝，避免盘掉线时清空库。
    dryRun=true 只统计不删。
    """
    lib = service.get_library_by_id(db, library_id)
    if not service.is_visible_to_current_user(db, library_id):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    from fryfrog.services import video_scan as scan_svc

    result = scan_svc.purge_missing_videos(db, lib, force=force, dry_run=dryRun)
    db.commit()
    return ApiResponse.ok({"libraryId": lib.id, "dryRun": dryRun, **result})
