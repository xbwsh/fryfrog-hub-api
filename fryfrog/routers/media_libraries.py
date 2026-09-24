from __future__ import annotations

import os
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
    # 文件系统根（Linux 为 /，Windows 为盘符），对齐 Java File.listRoots()
    import sys

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


@router.get("/browse")
def browse(path: str | None = None):
    """浏览服务器目录：不传 path 返回磁盘根；传 path 列出子目录。契约对齐旧版 Java。"""
    if not path or not str(path).strip():
        return ApiResponse.ok(_list_roots())
    dir_path = Path(path)
    if not dir_path.is_dir():
        return ApiResponse.ok([])
    return ApiResponse.ok(_list_children(dir_path))
