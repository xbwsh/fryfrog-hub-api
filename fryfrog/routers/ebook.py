"""电子书 API。"""

from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Depends
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel
from sqlalchemy import func, select

from fryfrog.core.api_response import ApiResponse, PageResponse
from fryfrog.core.deps import DbSession, get_media_library_service
from fryfrog.core.exceptions import BadRequestException, ForbiddenException, ResourceNotFoundException
from fryfrog.core.security import AuthManager, UserService, current_user_id
from fryfrog.core.signer import sign
from fryfrog.models.ebook import Ebook, EbookProgress
from fryfrog.services import ebook_scan, ebook_scrape
from fryfrog.services.assets import cover_bytes
from fryfrog.services.media_library import MediaLibraryService

router = APIRouter(prefix="/api/v1/ebooks", tags=["电子书"])

COMPLETED_THRESHOLD = 95.0
FORMAT_MEDIA = {
    "EPUB": "application/epub+zip",
    "PDF": "application/pdf",
    "MOBI": "application/x-mobipocket-ebook",
}


class ProgressBody(BaseModel):
    positionPercent: float | None = None
    chapterIndex: int | None = None


class CompletedBody(BaseModel):
    completed: bool = False


class MetadataBody(BaseModel):
    title: str | None = None
    author: str | None = None
    publisher: str | None = None
    overview: str | None = None
    series: str | None = None
    seriesPart: int | None = None


class ScrapeBindBody(BaseModel):
    source: str | None = None
    sourceId: str | None = None


def _require_admin(db) -> None:
    if not AuthManager.from_settings().enabled:
        return
    if not UserService().is_admin(db, current_user_id()):
        raise ForbiddenException("需要管理员权限")


def _cover_url(book: Ebook) -> str | None:
    if not book.cover_art_path or book.id is None:
        return None
    return sign(f"/api/v1/ebooks/{book.id}/cover")


def _require_visible(db, media_lib: MediaLibraryService, book_id: int) -> Ebook:
    book = db.get(Ebook, book_id)
    if book is None:
        raise ResourceNotFoundException("Ebook", "id", book_id)
    allowed = media_lib.get_allowable_library_ids(db)
    if book.library_id is not None and book.library_id not in allowed:
        raise ResourceNotFoundException("Ebook", "id", book_id)
    return book


def _progress_dto(progress: EbookProgress | None) -> dict | None:
    if progress is None:
        return None
    return {
        "positionPercent": progress.position_percent,
        "chapterIndex": progress.chapter_index,
        "completed": bool(progress.completed),
    }


def _list_dto(book: Ebook, progress: EbookProgress | None) -> dict:
    return {
        "id": book.id,
        "title": book.title,
        "author": book.author,
        "series": book.series,
        "format": book.format,
        "coverUrl": _cover_url(book),
        "completed": bool(progress.completed) if progress else False,
        "progressPercent": progress.position_percent if progress else None,
    }


def _detail_dict(book: Ebook, progress: EbookProgress | None) -> dict:
    return {
        "id": book.id,
        "title": book.title,
        "author": book.author,
        "publisher": book.publisher,
        "language": book.language,
        "pubYear": book.pub_year,
        "rating": book.rating,
        "overview": book.overview,
        "series": book.series,
        "seriesPart": book.series_part,
        "metadataSource": book.metadata_source,
        "format": book.format,
        "fileSize": book.file_size,
        "totalChapters": book.total_chapters,
        "coverUrl": _cover_url(book),
        "fileUrl": sign(f"/api/v1/ebooks/{book.id}/file") if book.id else None,
        "progress": _progress_dto(progress),
    }


def _progress(db, book_id: int) -> EbookProgress | None:
    return db.scalar(
        select(EbookProgress).where(
            EbookProgress.user_id == current_user_id(),
            EbookProgress.ebook_id == book_id,
        )
    )


@router.post("/scan")
def scan(
    db: DbSession,
    libraryId: int | None = None,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_admin(db)
    if libraryId is not None:
        lib = media_lib.get_library_by_id(db, libraryId)
        if not media_lib.is_visible_to_current_user(db, libraryId):
            raise ResourceNotFoundException("MediaLibrary", "id", libraryId)
        libraries = [lib]
    else:
        libraries = [x for x in media_lib.get_visible_libraries(db) if x.is_ebook_type()]

    def run():
        from fryfrog.db import get_session_factory

        session = get_session_factory()()
        try:
            for lib in libraries:
                try:
                    ebook_scan.scan_ebook_library(session, lib)
                    session.commit()
                except Exception:
                    session.rollback()
        finally:
            session.close()

    import threading

    threading.Thread(target=run, daemon=True).start()
    return ApiResponse.ok(
        {"status": "started", "libraryCount": len(libraries)}, message="扫描任务已启动"
    )


@router.get("")
def list_books(
    db: DbSession,
    q: str | None = None,
    page: int = 0,
    size: int = 20,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    allowed = media_lib.get_allowable_library_ids(db)
    if not allowed:
        return ApiResponse.ok(PageResponse.of([], page, size, 0))
    base = select(Ebook).where(Ebook.library_id.in_(allowed))
    if q and q.strip():
        base = base.where(Ebook.title.ilike(f"%{q.strip()}%"))
    total = int(db.scalar(select(func.count()).select_from(base.subquery())) or 0)
    rows = list(db.scalars(base.order_by(Ebook.title.asc()).offset(page * size).limit(size)).all())
    progress_map = {}
    if rows:
        for p in db.scalars(
            select(EbookProgress).where(
                EbookProgress.user_id == current_user_id(),
                EbookProgress.ebook_id.in_([b.id for b in rows]),
            )
        ).all():
            progress_map[p.ebook_id] = p
    content = [_list_dto(b, progress_map.get(b.id)) for b in rows]
    return ApiResponse.ok(PageResponse.of(content, page, size, total))


@router.get("/authors")
def authors(
    db: DbSession,
    page: int = 0,
    size: int = 20,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    allowed = media_lib.get_allowable_library_ids(db)
    if not allowed:
        return ApiResponse.ok(PageResponse.of([], page, size, 0))
    base = (
        select(Ebook.author, func.count(Ebook.id))
        .where(Ebook.library_id.in_(allowed), Ebook.author.is_not(None), Ebook.author != "")
        .group_by(Ebook.author)
    )
    total = len(list(db.execute(base).all()))
    rows = list(db.execute(base.order_by(Ebook.author.asc()).offset(page * size).limit(size)).all())
    content = [{"author": r[0], "bookCount": int(r[1])} for r in rows]
    return ApiResponse.ok(PageResponse.of(content, page, size, total))


@router.get("/scrape/providers")
def scrape_providers():
    return ApiResponse.ok(ebook_scrape.list_providers())


@router.get("/scrape/search")
def scrape_search(db: DbSession, q: str, source: str | None = None):
    _require_admin(db)
    return ApiResponse.ok(ebook_scrape.search(q, source))


@router.get("/{book_id}")
def detail(
    book_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    book = _require_visible(db, media_lib, book_id)
    return ApiResponse.ok(_detail_dict(book, _progress(db, book_id)))


@router.get("/{book_id}/cover")
def cover(
    book_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    book = _require_visible(db, media_lib, book_id)
    data, media = cover_bytes(book.cover_art_path, label=book.title or "")
    return Response(content=data, media_type=media)


@router.get("/{book_id}/file")
def download_file(
    book_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    book = _require_visible(db, media_lib, book_id)
    path = Path(book.file_path)
    if not path.is_file():
        raise ResourceNotFoundException("File", "path", book.file_path)
    media = FORMAT_MEDIA.get(book.format or "", "application/octet-stream")
    return FileResponse(path, media_type=media, filename=path.name)


@router.put("/{book_id}/progress")
def save_progress(
    book_id: int,
    body: ProgressBody,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_visible(db, media_lib, book_id)
    uid = current_user_id()
    progress = db.scalar(
        select(EbookProgress).where(
            EbookProgress.user_id == uid, EbookProgress.ebook_id == book_id
        )
    )
    if progress is None:
        progress = EbookProgress(user_id=uid, ebook_id=book_id, completed=False)
        db.add(progress)
    if body.positionPercent is not None:
        progress.position_percent = max(0.0, min(100.0, body.positionPercent))
    if body.chapterIndex is not None:
        progress.chapter_index = body.chapterIndex
    if progress.position_percent is not None and progress.position_percent >= COMPLETED_THRESHOLD:
        progress.completed = True
    db.flush()
    return ApiResponse.ok(_progress_dto(progress))


@router.put("/{book_id}/completed")
def set_completed(
    book_id: int,
    body: CompletedBody,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_visible(db, media_lib, book_id)
    uid = current_user_id()
    progress = db.scalar(
        select(EbookProgress).where(
            EbookProgress.user_id == uid, EbookProgress.ebook_id == book_id
        )
    )
    if progress is None:
        progress = EbookProgress(
            user_id=uid, ebook_id=book_id, position_percent=0.0, chapter_index=0, completed=False
        )
        db.add(progress)
    progress.completed = body.completed
    if body.completed and progress.position_percent is None:
        progress.position_percent = 100.0
    db.flush()
    return ApiResponse.ok(None)


@router.delete("/{book_id}/progress")
def delete_progress(
    book_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_visible(db, media_lib, book_id)
    for row in db.scalars(
        select(EbookProgress).where(
            EbookProgress.user_id == current_user_id(),
            EbookProgress.ebook_id == book_id,
        )
    ).all():
        db.delete(row)
    db.flush()
    return ApiResponse.ok(None)


@router.put("/{book_id}/metadata")
def update_metadata(
    book_id: int,
    body: MetadataBody,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_admin(db)
    book = _require_visible(db, media_lib, book_id)
    updated = False
    if body.title and body.title.strip():
        book.title = body.title.strip()
        updated = True
    if body.author and body.author.strip():
        book.author = body.author.strip()
        updated = True
    if body.publisher is not None:
        book.publisher = body.publisher.strip() or None
        updated = True
    if body.overview is not None:
        book.overview = body.overview.strip() or None
        updated = True
    if body.series is not None:
        book.series = body.series.strip() or None
        updated = True
    if body.seriesPart is not None:
        book.series_part = body.seriesPart
        updated = True
    if updated:
        book.metadata_source = "manual"
        book.source_id = None
        db.flush()
    return ApiResponse.ok(_detail_dict(book, _progress(db, book_id)))


@router.post("/{book_id}/scrape/bind")
def scrape_bind(
    book_id: int,
    body: ScrapeBindBody,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_admin(db)
    _require_visible(db, media_lib, book_id)
    if not body.source or not body.sourceId:
        raise BadRequestException("source 与 sourceId 不能为空")
    book = ebook_scrape.bind(db, book_id, body.source, body.sourceId)
    return ApiResponse.ok(ebook_scrape.bind_summary(book))


@router.post("/{book_id}/scrape/unbind")
def scrape_unbind(
    book_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_admin(db)
    _require_visible(db, media_lib, book_id)
    ebook_scrape.unbind(db, book_id)
    return ApiResponse.ok(None)
