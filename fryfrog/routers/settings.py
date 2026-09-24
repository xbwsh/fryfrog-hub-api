from __future__ import annotations

from fastapi import APIRouter
from sqlalchemy import select

from fryfrog.core.api_response import ApiResponse
from fryfrog.core.deps import DbSession
from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.models.library import SystemSetting
from fryfrog.schemas.common import SettingUpdateRequest

router = APIRouter(prefix="/api/v1/settings", tags=["设置"])


def _dto(row: SystemSetting) -> dict:
    return {
        "id": row.id,
        "key": row.key,
        "value": row.value,
        "description": row.description,
        "createdAt": row.created_at,
        "updatedAt": row.updated_at,
    }


@router.get("")
def list_settings(db: DbSession):
    rows = db.scalars(select(SystemSetting)).all()
    return ApiResponse.ok([_dto(r) for r in rows])


@router.get("/{key}")
def get_setting(key: str, db: DbSession):
    row = db.scalar(select(SystemSetting).where(SystemSetting.key == key))
    if row is None:
        raise ResourceNotFoundException("SystemSetting", "key", key)
    return ApiResponse.ok(_dto(row))


@router.put("/{key}")
def update_setting(key: str, body: SettingUpdateRequest, db: DbSession):
    row = db.scalar(select(SystemSetting).where(SystemSetting.key == key))
    if row is None:
        row = SystemSetting(key=key)
        db.add(row)
    row.value = body.value
    if body.description is not None:
        row.description = body.description
    db.flush()
    return ApiResponse.ok(_dto(row))
