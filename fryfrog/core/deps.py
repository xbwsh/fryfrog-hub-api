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
    # 用户管理面只对 admin 开放。注意必须精确/数字锚定：
    # `^/api/v1/users.*$` 会把 `/users/me`（普通用户取自己信息）也锁死。
    r"^/api/v1/users$",
    r"^/api/v1/users/\d+.*$",
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

# 必须锚定结尾（`$`）：前缀匹配会让 `cover-options`/`cover-upload` 这类
# 管理端点被当成静态图片资源提前放行——中间件不写当前用户，
# 路由里的 _require_admin 永远判为匿名 → 管理员也 403。
STATIC_RESOURCE_PATTERNS = [
    r".*/cover$",
    r".*/fanart$",
    r".*/pages/\d+$",
    r".*/artist/image$",
    r".*/character/.*/image$",
    r".*/actor/.*/image$",
    r".*/image$",
    r".*/stream$",
    r".*/stream/transcode$",
    r".*/subtitles/.*$",
    r".*/subtitle/vtt$",
    r".*/lyrics$",
    r".*/file$",
    r".*/tmdb-image-proxy$",
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

    # /rest/* Subsonic 由自身路由鉴权。必须放在静态资源判断之前：
    # `/rest/stream` 以 "stream" 结尾，会先撞上 `.*stream$` 的签名要求，
    # 导致 Subsonic 客户端（用 u/p 鉴权、不带 sig）拿 401，音乐播放全挂。
    if path.startswith("/rest/"):
        return await call_next(request)

    if _matches_any(path, STATIC_RESOURCE_PATTERNS) and method in ("GET", "HEAD", "OPTIONS"):
        # 只有读请求按静态资源放行（再做签名校验）。写请求即便路径像图片资源
        # （如 POST /video/{id}/cover）也必须走正常鉴权，否则 current_user 不会
        # 被写入，路由里的 _require_admin 永远判为匿名 → 403。
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
        # 后台扫描持写锁时这里可能撞锁，重试而不是直接 500
        commit_with_retry(db)
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


def commit_with_retry(db, *, attempts: int = 5, max_sleep: float = 3.2) -> None:
    """提交，遇 SQLite 写锁争用则退避重试。

    SQLite 单写者：后台扫描/刷新持锁期间，commit 会抛 `database is locked`。
    单次 `db.commit()` 内部已有 SQLite 的 busy 等待（连接 `timeout=30`），
    重试再叠加退避窗口，足以熬过「后台每处理一部剧提交一次」的短时争用。

    关键语义（防止"假成功"）：commit 失败后执行 rollback 会**丢弃本次事务的
    全部写入**（新增对象退回 transient、更新被过期还原），此时再重试提交的
    是空事务——必然"成功"，但数据已静默丢失。因此：
    - 会话带有待写入状态（new/dirty/deleted）时，失败一次就如实抛错，
      绝不重试——宁可让客户端看到失败，也不能把丢数据伪装成成功；
    - 只读会话（GET 请求的中间件路径）没有可丢失状态，重试是安全的，
      保留退避重试以熬过短时争用。
    长时间持锁的根治手段是缩短后台事务（分批提交），不是加长这里的重试。
    """
    import time

    from sqlalchemy.exc import OperationalError

    for attempt in range(attempts):
        had_writes = bool(getattr(db, "new", None) or getattr(db, "dirty", None) or getattr(db, "deleted", None))
        try:
            db.commit()
            return
        except OperationalError:
            db.rollback()
            if had_writes:
                raise
            if attempt == attempts - 1:
                raise
            time.sleep(min(0.4 * (2**attempt), max_sleep))


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
