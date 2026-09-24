from __future__ import annotations

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from sqlalchemy.orm import Session

from fryfrog.core.api_response import ApiResponse
from fryfrog.core.deps import DbSession, get_auth_manager, get_user_service
from fryfrog.core.security import AuthManager, UserService
from fryfrog.schemas.common import LoginRequest, UserDTO

router = APIRouter(prefix="/api/v1/auth", tags=["认证"])


@router.post("/login")
def login(
    body: LoginRequest,
    request: Request,
    db: DbSession,
    auth_manager: AuthManager = Depends(get_auth_manager),
    user_service: UserService = Depends(get_user_service),
):
    if not auth_manager.enabled:
        return ApiResponse.ok({"token": ""}, message="Auth disabled")

    username = body.username or "admin"
    ip = request.client.host if request.client else ""
    result = auth_manager.login(db, username, body.password, ip)
    if result.ok():
        data: dict = {"token": result.token}
        user = user_service.find_by_username(db, username)
        if user:
            data["user"] = UserDTO.from_user(user).model_dump(exclude_none=True)
        return ApiResponse.ok(data)
    if result.error == "LOCKED":
        raise HTTPException(status_code=429, detail="登录失败次数过多，请稍后再试")
    raise HTTPException(status_code=401, detail="用户名或密码错误")


@router.post("/logout")
def logout(
    db: DbSession,
    auth_manager: AuthManager = Depends(get_auth_manager),
    authorization: str | None = Header(default=None),
):
    if authorization and authorization.startswith("Bearer "):
        auth_manager.logout(db, authorization[7:])
    return ApiResponse.ok(None)


@router.get("/status")
def status(auth_manager: AuthManager = Depends(get_auth_manager)):
    return ApiResponse.ok({"enabled": auth_manager.enabled})


@router.get("/me")
def me(
    request: Request,
    db: DbSession,
    user_service: UserService = Depends(get_user_service),
):
    user_id = getattr(request.state, "user_id", None)
    if user_id is None:
        raise HTTPException(status_code=404, detail="User not found: id=未登录")
    user = user_service.get_user(db, user_id)
    return ApiResponse.ok(UserDTO.from_user(user).model_dump(exclude_none=True))
