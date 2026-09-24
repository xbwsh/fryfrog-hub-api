from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException

from fryfrog.core.api_response import ApiResponse
from fryfrog.core.deps import DbSession, get_media_library_service
from fryfrog.schemas.common import (
    MediaLibraryCreateRequest,
    MediaLibraryDTO,
    MediaLibraryUpdateRequest,
)
from fryfrog.services.media_library import MediaLibraryService

router = APIRouter(prefix="/api/v1/media-libraries", tags=["媒体库"])


@router.get("")
def list_libraries(db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    libs = service.get_visible_libraries(db)
    return ApiResponse.ok([MediaLibraryDTO.from_entity(x).model_dump(exclude_none=True) for x in libs])


@router.get("/{library_id}")
def get_library(library_id: int, db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    lib = service.get_library_by_id(db, library_id)
    if not service.is_visible_to_current_user(db, library_id):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    return ApiResponse.ok(MediaLibraryDTO.from_entity(lib).model_dump(exclude_none=True))


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


@router.post("/scan")
def scan_all(db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    libs = service.get_enabled_libraries(db)
    # 异步扫描由各模块调度器执行；这里同步触发各类型扫描
    from fryfrog.services import scan as scan_svc

    scan_svc.scan_all_enabled(db)
    return ApiResponse.ok({"status": "started", "libraryCount": len(libs)})


@router.post("/{library_id}/scan")
def scan_one(library_id: int, db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    lib = service.get_library_by_id(db, library_id)
    from fryfrog.services import scan as scan_svc

    scan_svc.scan_library(db, lib)
    return ApiResponse.ok({"libraryId": lib.id, "libraryName": lib.name, "status": "started"})


@router.get("/scan/progress")
def scan_progress(db: DbSession, service: MediaLibraryService = Depends(get_media_library_service), library_id: int | None = None):
    from fryfrog.services import progress as progress_svc

    items = progress_svc.get_scrape_progress(library_id)
    return ApiResponse.ok(items)


@router.get("/{library_id}/pipeline-progress")
def pipeline_progress(library_id: int, db: DbSession, service: MediaLibraryService = Depends(get_media_library_service)):
    lib = service.get_library_by_id(db, library_id)
    if not service.is_visible_to_current_user(db, library_id):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    from fryfrog.services import progress as progress_svc

    return ApiResponse.ok(progress_svc.get_pipeline_progress(lib))


@router.get("/browse")
def browse(path: str | None = None, includeFiles: bool = False):
    """目录浏览（新建媒体库选目录用）。默认从 /data 起（Docker 媒体挂载点）。"""
    fallback = Path("/data") if Path("/data").is_dir() else Path.cwd()
    if path:
        try:
            root = Path(path).expanduser().resolve()
        except OSError:
            root = fallback
    else:
        root = fallback.resolve()

    if not root.exists() or not root.is_dir():
        # 前端可能传了宿主机路径（如 /volume1/...），回退到 /data 避免空白
        root = fallback.resolve()

    entries = []
    try:
        for item in root.iterdir():
            if item.name.startswith("."):
                continue
            try:
                is_dir = item.is_dir()
            except OSError:
                continue
            if not is_dir and not includeFiles:
                continue
            entries.append(
                {
                    "name": item.name,
                    "path": str(item),
                    "isDir": is_dir,
                    "type": "directory" if is_dir else "file",
                }
            )
    except PermissionError:
        raise HTTPException(status_code=403, detail="Permission denied")

    entries.sort(key=lambda e: (not e["isDir"], e["name"].lower()))
    return ApiResponse.ok(entries)
