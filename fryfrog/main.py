from __future__ import annotations

import logging
import secrets
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy import select

from fryfrog.config import get_settings
from fryfrog.core.api_response import ApiResponse
from fryfrog.core.deps import auth_middleware
from fryfrog.core.exceptions import (
    BadRequestException,
    ForbiddenException,
    ResourceNotFoundException,
)
from fryfrog.core.security import UserService
from fryfrog.core.crypto import SubsonicPasswordEncryptor
from fryfrog.db import get_session_factory, init_db
from fryfrog.models.user import User

logger = logging.getLogger("fryfrog")


@asynccontextmanager
async def lifespan(app: FastAPI):
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )
    init_db()
    _bootstrap_admin()
    _init_libraries()
    _migrate_legacy()
    _start_schedulers()
    from fryfrog.services.watcher import start_file_watcher, stop_file_watcher

    start_file_watcher()
    yield
    stop_file_watcher()


def _bootstrap_admin() -> None:
    settings = get_settings()
    session = get_session_factory()()
    try:
        user_service = UserService(
            encryptor=SubsonicPasswordEncryptor(settings.subsonic_encrypt_key or None)
        )
        if not session.scalar(select(User.id).limit(1)):
            password = settings.auth_password or secrets.token_urlsafe(12)
            user_service.create_initial_admin(session, password)
            session.commit()
            if not settings.auth_password:
                logger.warning("Generated random admin password: %s", password)
            else:
                logger.info("Created initial admin user")
    finally:
        session.close()


def _init_libraries() -> None:
    session = get_session_factory()()
    try:
        from fryfrog.services.media_library import MediaLibraryService

        MediaLibraryService(UserService()).init(session)
        session.commit()
    finally:
        session.close()


def _migrate_legacy() -> None:
    session = get_session_factory()()
    try:
        from fryfrog.services.legacy import migrate_legacy_data

        migrate_legacy_data(session)
        session.commit()
    except Exception:
        session.rollback()
        logger.exception("Legacy data migration failed")
    finally:
        session.close()


def _start_schedulers() -> None:
    import threading
    import time

    def _token_cleanup_loop():
        while True:
            try:
                time.sleep(24 * 3600)
                session = get_session_factory()()
                try:
                    from fryfrog.services.legacy import cleanup_expired_tokens

                    cleanup_expired_tokens(session)
                    session.commit()
                finally:
                    session.close()
            except Exception:
                logger.exception("Token cleanup failed")

    def _periodic_scan_loop():
        from fryfrog.config import get_settings

        settings = get_settings()
        interval = max(settings.periodic_scan_interval, 30)
        while True:
            try:
                if settings.watcher_periodic_scan:
                    session = get_session_factory()()
                    try:
                        from fryfrog.services.scan import scan_all_enabled

                        scan_all_enabled(session)
                    finally:
                        session.close()
                time.sleep(interval)
            except Exception:
                logger.exception("Periodic scan failed")
                time.sleep(interval)

    threading.Thread(target=_token_cleanup_loop, daemon=True, name="token-cleanup").start()
    threading.Thread(target=_periodic_scan_loop, daemon=True, name="periodic-scan").start()


def create_app() -> FastAPI:
    app = FastAPI(
        title="Fryfrog Hub API",
        version="0.1.0",
        lifespan=lifespan,
        docs_url="/swagger-ui.html",
        openapi_url="/api-docs",
    )

    @app.middleware("http")
    async def _auth(request: Request, call_next):
        return await auth_middleware(request, call_next)

    @app.exception_handler(ResourceNotFoundException)
    async def _not_found(request: Request, exc: ResourceNotFoundException):
        return JSONResponse(
            status_code=404,
            content={"success": False, "message": str(exc)},
        )

    @app.exception_handler(ForbiddenException)
    async def _forbidden(request: Request, exc: ForbiddenException):
        return JSONResponse(
            status_code=403,
            content={"success": False, "message": exc.message},
        )

    @app.exception_handler(BadRequestException)
    async def _bad_request(request: Request, exc: BadRequestException):
        return JSONResponse(
            status_code=400,
            content={"success": False, "message": exc.message},
        )

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError):
        parts = [f"{e.get('loc', ['?'])[-1]}: {e.get('msg')}" for e in exc.errors()]
        return JSONResponse(
            status_code=400,
            content={"success": False, "message": "; ".join(parts) or "Validation failed"},
        )

    @app.exception_handler(Exception)
    async def _general(request: Request, exc: Exception):
        logger.exception("Unhandled exception")
        return JSONResponse(
            status_code=500,
            content={"success": False, "message": "Internal server error"},
        )

    from fryfrog.routers import (
        auth,
        audiobook,
        comic,
        ebook,
        logs,
        media_libraries,
        music,
        settings,
        users,
        video,
    )

    app.include_router(auth.router)
    app.include_router(users.router)
    app.include_router(media_libraries.router)
    app.include_router(settings.router)
    app.include_router(logs.router)
    app.include_router(video.router)
    app.include_router(music.router)
    app.include_router(music.subsonic_router)
    app.include_router(audiobook.router)
    app.include_router(comic.router)
    app.include_router(ebook.router)

    return app


app = create_app()


def main() -> None:
    import uvicorn

    settings = get_settings()
    uvicorn.run(
        "fryfrog.main:app",
        host="0.0.0.0",
        port=settings.server_port,
        reload=False,
    )


if __name__ == "__main__":
    main()
