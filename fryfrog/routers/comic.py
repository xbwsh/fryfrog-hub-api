"""漫画 API。"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from fastapi.responses import Response
from pydantic import BaseModel
from sqlalchemy import func, select

from fryfrog.core.api_response import ApiResponse, PageResponse
from fryfrog.core.deps import DbSession, get_media_library_service
from fryfrog.core.exceptions import BadRequestException, ForbiddenException, ResourceNotFoundException
from fryfrog.core.security import AuthManager, UserService, current_user_id
from fryfrog.core.signer import sign
from fryfrog.models.comic import Comic, ComicChapter, ComicProgress
from fryfrog.services import comic_organize, comic_pages, comic_scan, comic_scrape
from fryfrog.services.assets import cover_bytes
from fryfrog.services.media_library import MediaLibraryService

router = APIRouter(prefix="/api/v1/comics", tags=["漫画"])


class ProgressBody(BaseModel):
    chapterIndex: int | None = None
    pageIndex: int | None = None


class CompletedBody(BaseModel):
    completed: bool = False


class MetadataBody(BaseModel):
    title: str | None = None
    author: str | None = None
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


def _cover_url(comic: Comic) -> str | None:
    if not comic.cover_art_path or comic.id is None:
        return None
    return sign(f"/api/v1/comics/{comic.id}/cover")


def _require_visible(db, media_lib: MediaLibraryService, comic_id: int) -> Comic:
    comic = db.get(Comic, comic_id)
    if comic is None:
        raise ResourceNotFoundException("Comic", "id", comic_id)
    allowed = media_lib.get_allowable_library_ids(db)
    if comic.library_id is not None and comic.library_id not in allowed:
        raise ResourceNotFoundException("Comic", "id", comic_id)
    return comic


def _require_visible_chapter(db, media_lib: MediaLibraryService, chapter_id: int) -> ComicChapter:
    chapter = db.get(ComicChapter, chapter_id)
    if chapter is None:
        raise ResourceNotFoundException("ComicChapter", "id", chapter_id)
    _require_visible(db, media_lib, chapter.comic_id)
    return chapter


def _chapters(db, comic_id: int) -> list[ComicChapter]:
    return list(
        db.scalars(
            select(ComicChapter)
            .where(ComicChapter.comic_id == comic_id)
            .order_by(ComicChapter.chapter_index)
        ).all()
    )


def _progress_percent(progress: ComicProgress | None, chapters: list[ComicChapter]) -> float:
    total_pages = sum(c.page_count or 0 for c in chapters)
    if total_pages <= 0 or progress is None or progress.chapter_index is None:
        return 0.0
    done = 0
    for chapter in chapters:
        if chapter.chapter_index < progress.chapter_index:
            done += chapter.page_count or 0
            continue
        if chapter.chapter_index == progress.chapter_index:
            pages = chapter.page_count or 0
            page = progress.page_index or 0
            done += min(pages, page + 1)
        break
    return min(100.0, done * 100.0 / total_pages)


def _round1(value: float) -> float:
    return round(value * 10) / 10


def _chapter_dto(chapter: ComicChapter) -> dict:
    return {
        "id": chapter.id,
        "chapterIndex": chapter.chapter_index,
        "title": chapter.title,
        "pageCount": chapter.page_count,
        "type": chapter.type,
    }


def _progress_dto(progress: ComicProgress | None, percent: float) -> dict | None:
    if progress is None:
        return None
    return {
        "chapterIndex": progress.chapter_index,
        "pageIndex": progress.page_index,
        "completed": bool(progress.completed),
        "progressPercent": _round1(percent),
    }


def _list_dto(comic: Comic, progress: ComicProgress | None, percent: float | None) -> dict:
    return {
        "id": comic.id,
        "title": comic.title,
        "author": comic.author,
        "series": comic.series,
        "totalChapters": comic.total_chapters,
        "coverUrl": _cover_url(comic),
        "completed": bool(progress.completed) if progress else False,
        "progressPercent": _round1(percent) if progress and percent is not None else None,
    }


def _detail_dict(db, comic: Comic) -> dict:
    chapters = _chapters(db, comic.id)
    progress = db.scalar(
        select(ComicProgress).where(
            ComicProgress.user_id == current_user_id(),
            ComicProgress.comic_id == comic.id,
        )
    )
    percent = _progress_percent(progress, chapters) if progress else None
    return {
        "id": comic.id,
        "title": comic.title,
        "author": comic.author,
        "overview": comic.overview,
        "series": comic.series,
        "seriesPart": comic.series_part,
        "metadataSource": comic.metadata_source,
        "pubYear": comic.pub_year,
        "rating": comic.rating,
        "totalChapters": comic.total_chapters,
        "coverUrl": _cover_url(comic),
        "chapters": [_chapter_dto(c) for c in chapters],
        "progress": _progress_dto(progress, percent if percent is not None else 0),
    }


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
        libraries = [x for x in media_lib.get_visible_libraries(db) if x.is_comic_type()]

    def run():
        from fryfrog.db import get_session_factory

        session = get_session_factory()()
        try:
            for lib in libraries:
                try:
                    comic_scan.scan_comic_library(session, lib)
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


@router.post("/organize")
def organize(
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
    libraryId: int | None = None,
    dryRun: bool = True,
):
    """库根压缩包整理到 作品名/ 目录；默认 dryRun 只预览。"""
    _require_admin(db)
    if libraryId is None:
        raise BadRequestException("libraryId 不能为空")
    lib = media_lib.get_library_by_id(db, libraryId)
    return ApiResponse.ok(comic_organize.organize_comics(db, lib, dry_run=dryRun))


@router.get("")
def list_comics(
    db: DbSession,
    q: str | None = None,
    page: int = 0,
    size: int = 20,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    allowed = media_lib.get_allowable_library_ids(db)
    if not allowed:
        return ApiResponse.ok(PageResponse.of([], page, size, 0))
    base = select(Comic).where(Comic.library_id.in_(allowed))
    if q and q.strip():
        base = base.where(Comic.title.ilike(f"%{q.strip()}%"))
    total = int(db.scalar(select(func.count()).select_from(base.subquery())) or 0)
    rows = list(db.scalars(base.order_by(Comic.title.asc()).offset(page * size).limit(size)).all())
    progress_map = {}
    if rows:
        for p in db.scalars(
            select(ComicProgress).where(
                ComicProgress.user_id == current_user_id(),
                ComicProgress.comic_id.in_([c.id for c in rows]),
            )
        ).all():
            progress_map[p.comic_id] = p
    content = []
    for c in rows:
        p = progress_map.get(c.id)
        percent = _progress_percent(p, _chapters(db, c.id)) if p else None
        content.append(_list_dto(c, p, percent))
    return ApiResponse.ok(PageResponse.of(content, page, size, total))


@router.get("/scrape/providers")
def scrape_providers():
    return ApiResponse.ok(comic_scrape.list_providers())


@router.get("/scrape/search")
def scrape_search(db: DbSession, q: str, source: str | None = None):
    _require_admin(db)
    return ApiResponse.ok(comic_scrape.search(q, source))


@router.get("/{comic_id}")
def detail(
    comic_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    comic = _require_visible(db, media_lib, comic_id)
    return ApiResponse.ok(_detail_dict(db, comic))


@router.get("/{comic_id}/cover")
def cover(
    comic_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    comic = _require_visible(db, media_lib, comic_id)
    from fryfrog.services.assets import comic_cover_path

    path = comic.cover_art_path or str(comic_cover_path(comic_id))
    data, media = cover_bytes(path, label=comic.title or "")
    return Response(content=data, media_type=media)


@router.get("/chapters/{chapter_id}/pages")
def chapter_pages(
    chapter_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    chapter = _require_visible_chapter(db, media_lib, chapter_id)
    try:
        names = comic_pages.list_page_names(chapter)
    except Exception:
        raise ResourceNotFoundException("ComicChapter", "id", chapter_id)
    urls = [sign(f"/api/v1/comics/chapters/{chapter_id}/pages/{i}") for i in range(len(names))]
    return ApiResponse.ok(urls)


@router.get("/chapters/{chapter_id}/pages/{index}")
def chapter_page(
    chapter_id: int,
    index: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    chapter = _require_visible_chapter(db, media_lib, chapter_id)
    data, media = comic_pages.read_page(chapter, index)
    return Response(content=data, media_type=media)


@router.put("/{comic_id}/progress")
def save_progress(
    comic_id: int,
    body: ProgressBody,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_visible(db, media_lib, comic_id)
    uid = current_user_id()
    progress = db.scalar(
        select(ComicProgress).where(
            ComicProgress.user_id == uid, ComicProgress.comic_id == comic_id
        )
    )
    if progress is None:
        progress = ComicProgress(user_id=uid, comic_id=comic_id, completed=False)
        db.add(progress)
    if body.chapterIndex is not None:
        progress.chapter_index = body.chapterIndex
    if body.pageIndex is not None:
        progress.page_index = body.pageIndex

    chapters = _chapters(db, comic_id)
    if progress.chapter_index is not None and progress.page_index is not None and chapters:
        last = chapters[-1]
        last_pages = last.page_count or 0
        if (
            progress.chapter_index >= last.chapter_index
            and last_pages > 0
            and progress.page_index >= last_pages - max(1, last_pages // 20)
        ):
            progress.completed = True
    db.flush()
    percent = _progress_percent(progress, chapters)
    return ApiResponse.ok(_progress_dto(progress, percent))


@router.put("/{comic_id}/completed")
def set_completed(
    comic_id: int,
    body: CompletedBody,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_visible(db, media_lib, comic_id)
    uid = current_user_id()
    progress = db.scalar(
        select(ComicProgress).where(
            ComicProgress.user_id == uid, ComicProgress.comic_id == comic_id
        )
    )
    if progress is None:
        progress = ComicProgress(user_id=uid, comic_id=comic_id, completed=False)
        db.add(progress)
    progress.completed = body.completed
    if body.completed and progress.chapter_index is None:
        progress.chapter_index = 0
        progress.page_index = 0
    db.flush()
    return ApiResponse.ok(None)


@router.delete("/{comic_id}/progress")
def delete_progress(
    comic_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_visible(db, media_lib, comic_id)
    for row in db.scalars(
        select(ComicProgress).where(
            ComicProgress.user_id == current_user_id(),
            ComicProgress.comic_id == comic_id,
        )
    ).all():
        db.delete(row)
    db.flush()
    return ApiResponse.ok(None)


@router.put("/{comic_id}/metadata")
def update_metadata(
    comic_id: int,
    body: MetadataBody,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_admin(db)
    comic = _require_visible(db, media_lib, comic_id)
    updated = False
    if body.title and body.title.strip():
        comic.title = body.title.strip()
        updated = True
    if body.author and body.author.strip():
        comic.author = body.author.strip()
        updated = True
    if body.overview is not None:
        comic.overview = body.overview.strip() or None
        updated = True
    if body.series is not None:
        comic.series = body.series.strip() or None
        updated = True
    if body.seriesPart is not None:
        comic.series_part = body.seriesPart
        updated = True
    if updated:
        comic.metadata_source = "manual"
        comic.source_id = None
        db.flush()
    return ApiResponse.ok(_detail_dict(db, comic))


@router.post("/{comic_id}/scrape/bind")
def scrape_bind(
    comic_id: int,
    body: ScrapeBindBody,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_admin(db)
    _require_visible(db, media_lib, comic_id)
    if not body.source or not body.sourceId:
        raise BadRequestException("source 与 sourceId 不能为空")
    comic = comic_scrape.bind(db, comic_id, body.source, body.sourceId)
    return ApiResponse.ok(comic_scrape.bind_summary(comic))


@router.post("/{comic_id}/scrape/unbind")
def scrape_unbind(
    comic_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_admin(db)
    _require_visible(db, media_lib, comic_id)
    comic_scrape.unbind(db, comic_id)
    return ApiResponse.ok(None)
