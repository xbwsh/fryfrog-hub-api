from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy import select

from fryfrog.core.api_response import ApiResponse
from fryfrog.core.deps import (
    DbSession,
    get_auth_manager,
    get_media_library_service,
    get_user_service,
)
from fryfrog.core.exceptions import BadRequestException, ForbiddenException, ResourceNotFoundException
from fryfrog.core.security import AuthManager, UserService
from fryfrog.models.library import UserPreference
from fryfrog.models.user import User, UserRole
from fryfrog.schemas.common import (
    ChangePasswordRequest,
    UserCreateRequest,
    UserDTO,
    UserLibraryUpdateRequest,
    UserPreferenceUpdateRequest,
    UserUpdateRequest,
)
from fryfrog.services.media_library import MediaLibraryService

router = APIRouter(prefix="/api/v1/users", tags=["用户"])


def _require_admin_or_self(request: Request, user_service: UserService, db, target_id: int) -> None:
    uid = getattr(request.state, "user_id", None)
    if uid == target_id:
        return
    if user_service.is_admin(db, uid):
        return
    raise ForbiddenException("需要管理员权限")


@router.get("")
def list_users(db: DbSession, user_service: UserService = Depends(get_user_service)):
    users = db.scalars(select(User)).all()
    return ApiResponse.ok([UserDTO.from_user(u).model_dump(exclude_none=True) for u in users])


@router.get("/me")
def get_me(request: Request, db: DbSession, user_service: UserService = Depends(get_user_service)):
    uid = getattr(request.state, "user_id", None)
    if uid is None:
        raise ResourceNotFoundException("User", "id", "未登录")
    return ApiResponse.ok(UserDTO.from_user(user_service.get_user(db, uid)).model_dump(exclude_none=True))


@router.get("/{user_id}")
def get_user(
    user_id: int,
    request: Request,
    db: DbSession,
    user_service: UserService = Depends(get_user_service),
):
    _require_admin_or_self(request, user_service, db, user_id)
    return ApiResponse.ok(UserDTO.from_user(user_service.get_user(db, user_id)).model_dump(exclude_none=True))


@router.post("")
def create_user(
    body: UserCreateRequest,
    db: DbSession,
    user_service: UserService = Depends(get_user_service),
):
    role = UserRole(body.role) if body.role else UserRole.USER
    user = user_service.create_user(db, body.username, body.password, body.nickname, role)
    return ApiResponse.ok(UserDTO.from_user(user).model_dump(exclude_none=True))


@router.put("/{user_id}")
def update_user(
    user_id: int,
    body: UserUpdateRequest,
    request: Request,
    db: DbSession,
    user_service: UserService = Depends(get_user_service),
    auth_manager: AuthManager = Depends(get_auth_manager),
):
    _require_admin_or_self(request, user_service, db, user_id)
    uid = getattr(request.state, "user_id", None)
    role = None
    if body.role:
        if uid == user_id:
            raise BadRequestException("不能修改自己的角色")
        role = UserRole(body.role)
    enabled = body.enabled
    if enabled is not None and uid == user_id and not enabled:
        raise BadRequestException("不能禁用自己")
    user = user_service.update_user(db, user_id, body.nickname, body.avatar, role, enabled)
    if role is not None or (enabled is not None and not enabled):
        auth_manager.invalidate_user_tokens(db, user_id)
    return ApiResponse.ok(UserDTO.from_user(user).model_dump(exclude_none=True))


@router.delete("/{user_id}")
def delete_user(
    user_id: int,
    request: Request,
    db: DbSession,
    user_service: UserService = Depends(get_user_service),
    auth_manager: AuthManager = Depends(get_auth_manager),
):
    uid = getattr(request.state, "user_id", None)
    if uid == user_id:
        raise BadRequestException("不能删除自己")
    user_service.delete_user(db, user_id)
    auth_manager.invalidate_user_tokens(db, user_id)
    return ApiResponse.ok(None)


@router.put("/me/password")
def change_my_password(
    body: ChangePasswordRequest,
    request: Request,
    db: DbSession,
    user_service: UserService = Depends(get_user_service),
    auth_manager: AuthManager = Depends(get_auth_manager),
):
    uid = getattr(request.state, "user_id", None)
    if uid is None:
        raise ResourceNotFoundException("User", "id", "未登录")
    user_service.change_password(db, uid, body.oldPassword or "", body.newPassword)
    auth_manager.invalidate_user_tokens(db, uid)
    return ApiResponse.ok(None)


@router.put("/{user_id}/password")
def reset_password(
    user_id: int,
    body: ChangePasswordRequest,
    db: DbSession,
    user_service: UserService = Depends(get_user_service),
    auth_manager: AuthManager = Depends(get_auth_manager),
):
    user_service.reset_password(db, user_id, body.newPassword)
    auth_manager.invalidate_user_tokens(db, user_id)
    return ApiResponse.ok(None)


@router.get("/{user_id}/libraries")
def get_libraries(
    user_id: int,
    db: DbSession,
    service: MediaLibraryService = Depends(get_media_library_service),
):
    return ApiResponse.ok(service.get_assigned_library_ids(db, user_id))


@router.put("/{user_id}/libraries")
def put_libraries(
    user_id: int,
    body: UserLibraryUpdateRequest,
    db: DbSession,
    service: MediaLibraryService = Depends(get_media_library_service),
):
    service.assign_libraries(db, user_id, body.libraryIds)
    return ApiResponse.ok(service.get_assigned_library_ids(db, user_id))


@router.get("/me/preferences")
def get_preferences(request: Request, db: DbSession):
    uid = getattr(request.state, "user_id", None)
    if uid is None:
        raise ResourceNotFoundException("User", "id", "未登录")
    rows = db.scalars(select(UserPreference).where(UserPreference.user_id == uid)).all()
    return ApiResponse.ok({r.pref_key: r.pref_value for r in rows if r.pref_value is not None})


@router.put("/me/preferences")
def put_preferences(request: Request, body: UserPreferenceUpdateRequest, db: DbSession):
    uid = getattr(request.state, "user_id", None)
    if uid is None:
        raise ResourceNotFoundException("User", "id", "未登录")
    # 全量替换
    rows = db.scalars(select(UserPreference).where(UserPreference.user_id == uid)).all()
    by_key = {r.pref_key: r for r in rows}
    for key, value in body.preferences.items():
        if value == "":
            if key in by_key:
                db.delete(by_key[key])
        else:
            if key in by_key:
                by_key[key].pref_value = value
            else:
                db.add(UserPreference(user_id=uid, pref_key=key, pref_value=value))
    keep = set(body.preferences.keys())
    for key, row in by_key.items():
        if key not in keep:
            db.delete(row)
    db.flush()
    rows = db.scalars(select(UserPreference).where(UserPreference.user_id == uid)).all()
    return ApiResponse.ok({r.pref_key: r.pref_value for r in rows if r.pref_value is not None})
