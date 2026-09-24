from __future__ import annotations

import os
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

from fryfrog.core.api_response import ApiResponse
from fryfrog.config import get_settings

router = APIRouter(prefix="/api/v1/logs", tags=["日志"])

ALLOWED = {"app.log", "video.log"}


def _log_dir() -> Path:
    return Path(os.environ.get("LOG_DIR", get_settings().log_home or "logs"))


@router.get("")
def list_logs():
    log_dir = _log_dir()
    items = []
    if log_dir.exists():
        for name in sorted(ALLOWED):
            path = log_dir / name
            if path.is_file():
                st = path.stat()
                items.append({"name": name, "size": st.st_size, "lastModified": int(st.st_mtime * 1000)})
    return ApiResponse.ok(items)


@router.get("/{file_name}")
def download_log(file_name: str):
    if file_name not in ALLOWED:
        raise HTTPException(status_code=400, detail="Invalid log file")
    path = _log_dir() / file_name
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Log not found")
    return FileResponse(path, media_type="application/octet-stream", filename=file_name)
