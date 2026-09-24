from __future__ import annotations

from typing import Annotated

from fastapi import Depends, Header, HTTPException, Request
from sqlalchemy.orm import Session

from fryfrog.core.api_response import ApiResponse
from fryfrog.core.security import (
    AuthManager,
    UserService,
    current_user_id_or_none,
    set_current_user_id,
)
from fryfrog.db import get_db
from fryfrog.services.media_library import MediaLibraryService

# 白名单：普通用户允许的自身数据写操作
USER_OWNED_MUTATIONS = [
    r"^/api/v1/auth/logout$",
    r"^/api/v1/users/me/password$",
    r"^/api/v1/users/me/preferences$",
    r"^/api/v1/video/\d+/favorite$",
    r"^/api/v1/video/series/\d+/favorite$",
    r"^/api/v1/video/\d+/progress$",
    r"^/api/v1/video/\d+/watched$",
    r"^/api/v1/music/(songs|albums|artists)/\d+/star$",
    r"^/api/v1/music/(songs|albums|artists)/\d+/rating$",
    r"^/api/v1/music/playlists$",
    r"^/api/v1/music/playlists/\d+$",
    r"^/api/v1/music/scrobble$",
    r"^/api/v1/music/play-queue$",
    r"^/api/v1/music/bookmarks$",
    r"^/api/v1/music/bookmarks/\d+$",
    r"^/api/v1/audiobooks/\d+/progress$",
    r"^/api/v1/audiobooks/\d+/completed$",
    r"^/api/v1/ebooks/\d+/progress$",
    r"^/api/v1/ebooks/\d+/completed$",
    r"^/api/v1/comics/\d+/progress$",
    r"^/api/v1/comics/\d+/completed$",
]

ADMIN_ONLY_READS = [
    r"^/api/v1/media-libraries/browse$",
    r"^/api/v1/settings.*$",
    r"^/api/v1/logs.*$",
]

SIGNED_MEDIA_PATTERNS = [
    r".*/cover$",
    r".*/fanart$",
    r".*/stream$",
    r".*/stream/transcode$",
    r".*/season/\d+/cover$",
    r".*/subtitles/.*",
    r".*/subtitle/vtt$",
    r".*/lyrics$",
    r".*/image$",
    r".*/actor/.*/image$",
    r".*/artist/image$",
    r".*/character/.*/image$",
    r".*/pages/\d+$",
    r".*/file$",
]

STATIC_RESOURCE_PATTERNS = [
    r".*/cover",
    r".*/fanart",
    r".*/pages/\d+",
    r".*/artist/image",
    r".*/character/.*/image",
    r".*/actor/.*/image",
    r".*/image",
    r".*/stream",
    r".*/stream/transcode",
    r".*/subtitles/.*",
    r".*/subtitle/vtt",
    r".*/lyrics",
    r".*/file",
    r".*/tmdb-image-proxy",
]


def _matches_any(path: str, patterns: list[str]) -> bool:
    import re

    return any(re.search(p, path) for p in patterns)


async def auth_middleware(request: Request, call_next):
    import re

    from fryfrog.core import signer

    set_current_user_id(None)
    path = request.url.path
    method = request.method.upper()

    # 由依赖注入/路由处理；中间件只做放行判断需要 auth 的路径
    if path in ("/api/v1/auth/login", "/api/v1/auth/status") or path.startswith(
        ("/api-docs", "/swagger-ui", "/openapi", "/redoc", "/docs")
    ):
        return await call_next(request)

    if _matches_any(path, STATIC_RESOURCE_PATTERNS):
        if _matches_any(path, SIGNED_MEDIA_PATTERNS):
            exp = request.query_params.get("exp")
            sig = request.query_params.get("sig")
            try:
                exp_val = int(exp) if exp else 0
            except ValueError:
                exp_val = 0
            if not signer.verify(path, exp_val, sig):
                return _reject(401, "Unauthorized")
        return await call_next(request)

    # /rest/* Subsonic 由自身路由鉴权
    if path.startswith("/rest/"):
        return await call_next(request)

    auth_header = request.headers.get("Authorization") or ""
    token = auth_header[7:] if auth_header.startswith("Bearer ") else None

    # 依赖会话
    db: Session = next(get_db())
    try:
        auth_manager = AuthManager.from_settings()
        if not auth_manager.enabled:
            return await call_next(request)

        user_id = auth_manager.get_user_id(db, token)
        if user_id is None:
            return _reject(401, "Unauthorized")
        set_current_user_id(user_id)

        user_service = UserService()
        if not user_service.is_admin(db, user_id) and _requires_admin(method, path):
            return _reject(403, "需要管理员权限")

        request.state.db = db
        request.state.user_id = user_id
        response = await call_next(request)
        db.commit()
        return response
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def _requires_admin(method: str, path: str) -> bool:
    import re

    if method in ("POST", "PUT", "PATCH", "DELETE"):
        return not any(re.search(p, path) for p in USER_OWNED_MUTATIONS)
    return any(re.search(p, path) for p in ADMIN_ONLY_READS)


def _reject(status: int, message: str):
    from fastapi.responses import JSONResponse

    return JSONResponse(
        status_code=status,
        content={"success": False, "message": message},
    )


def get_auth_manager() -> AuthManager:
    return AuthManager.from_settings()


def get_user_service() -> UserService:
    from fryfrog.core.crypto import SubsonicPasswordEncryptor
    from fryfrog.config import get_settings

    settings = get_settings()
    return UserService(
        encryptor=SubsonicPasswordEncryptor(settings.subsonic_encrypt_key or None)
    )


def get_media_library_service(
    user_service: Annotated[UserService, Depends(get_user_service)],
) -> MediaLibraryService:
    return MediaLibraryService(user_service)


DbSession = Annotated[Session, Depends(get_db)]
