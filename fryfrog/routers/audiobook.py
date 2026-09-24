"""有声书 API。"""

from __future__ import annotations

import threading
from pathlib import Path

from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel
from sqlalchemy import func, select

from fryfrog.core.api_response import ApiResponse, PageResponse
from fryfrog.core.deps import DbSession, get_media_library_service
from fryfrog.core.exceptions import BadRequestException, ForbiddenException, ResourceNotFoundException
from fryfrog.core.security import AuthManager, UserService, current_user_id
from fryfrog.core.signer import sign
from fryfrog.models.audiobook import (
    Audiobook,
    AudiobookChapter,
    AudiobookProgress,
    AudiobookTrack,
)
from fryfrog.services import audiobook_organize, audiobook_scan, audiobook_scrape
from fryfrog.services.assets import cover_bytes
from fryfrog.services.media_library import MediaLibraryService

router = APIRouter(prefix="/api/v1/audiobooks", tags=["有声书"])

AUDIO_MIME = {
    "mp3": "audio/mpeg",
    "m4a": "audio/mp4",
    "m4b": "audio/mp4",
    "m4r": "audio/mp4",
    "mp4": "audio/mp4",
    "flac": "audio/flac",
    "wav": "audio/wav",
    "ogg": "audio/ogg",
    "oga": "audio/ogg",
    "aac": "audio/aac",
    "opus": "audio/opus",
    "wma": "audio/x-ms-wma",
    "ape": "audio/x-ape",
    "mka": "audio/x-matroska",
}

COMPLETED_THRESHOLD = 0.95


class ProgressBody(BaseModel):
    trackIndex: int | None = None
    positionSeconds: float | None = None


class CompletedBody(BaseModel):
    completed: bool = False


class MetadataBody(BaseModel):
    title: str | None = None
    author: str | None = None
    narrator: str | None = None
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


def _cover_url(book: Audiobook) -> str | None:
    if not book.cover_art_path or book.id is None:
        return None
    return sign(f"/api/v1/audiobooks/{book.id}/cover")


def _require_visible(db, media_lib: MediaLibraryService, book_id: int) -> Audiobook:
    book = db.get(Audiobook, book_id)
    if book is None:
        raise ResourceNotFoundException("Audiobook", "id", book_id)
    allowed = media_lib.get_allowable_library_ids(db)
    if book.library_id is not None and book.library_id not in allowed:
        raise ResourceNotFoundException("Audiobook", "id", book_id)
    return book


def _tracks(db, book_id: int) -> list[AudiobookTrack]:
    return list(
        db.scalars(
            select(AudiobookTrack)
            .where(AudiobookTrack.audiobook_id == book_id)
            .order_by(AudiobookTrack.track_index)
        ).all()
    )


def _global_seconds(progress: AudiobookProgress | None, tracks: list[AudiobookTrack]) -> float:
    if progress is None or progress.track_index is None:
        return 0.0
    done = 0.0
    for track in tracks:
        if track.track_index >= progress.track_index:
            break
        done += track.duration_seconds or 0
    return done + (progress.position_seconds or 0)


def _progress_dto(
    progress: AudiobookProgress | None, book: Audiobook | None, global_seconds: float
) -> dict | None:
    if progress is None:
        return None
    total = book.total_duration_seconds if book else None
    percent = 0.0
    if total and total > 0:
        percent = min(100.0, global_seconds / total * 100)
    return {
        "trackIndex": progress.track_index,
        "positionSeconds": progress.position_seconds,
        "completed": bool(progress.completed),
        "percent": round(percent, 1),
    }


def _track_dto(track: AudiobookTrack) -> dict:
    return {
        "id": track.id,
        "trackIndex": track.track_index,
        "title": track.title,
        "durationSeconds": track.duration_seconds,
        "fileSize": track.file_size,
        "streamUrl": sign(f"/api/v1/audiobooks/tracks/{track.id}/stream"),
    }


def _chapters(db, book: Audiobook, tracks: list[AudiobookTrack]) -> list[dict]:
    if book.play_type == "SINGLE":
        rows = list(
            db.scalars(
                select(AudiobookChapter)
                .where(AudiobookChapter.audiobook_id == book.id)
                .order_by(AudiobookChapter.chapter_index)
            ).all()
        )
        chapters = [
            {
                "chapterIndex": c.chapter_index,
                "title": c.title,
                "startSeconds": c.start_seconds,
                "endSeconds": c.end_seconds,
                "trackIndex": 0,
                "startInTrack": c.start_seconds,
            }
            for c in rows
        ]
        if not chapters and tracks:
            dur = tracks[0].duration_seconds
            chapters = [
                {
                    "chapterIndex": 0,
                    "title": book.title,
                    "startSeconds": 0,
                    "endSeconds": dur,
                    "trackIndex": 0,
                    "startInTrack": 0,
                }
            ]
        return chapters
    # MULTI：音轨累计为全局时间轴
    chapters = []
    global_s = 0.0
    for track in tracks:
        duration = track.duration_seconds or 0
        chapters.append(
            {
                "chapterIndex": track.track_index,
                "title": track.title,
                "startSeconds": global_s,
                "endSeconds": global_s + duration,
                "trackIndex": track.track_index,
                "startInTrack": 0,
            }
        )
        global_s += duration
    return chapters


def _detail_dict(db, book: Audiobook) -> dict:
    tracks = _tracks(db, book.id)
    progress = db.scalar(
        select(AudiobookProgress).where(
            AudiobookProgress.user_id == current_user_id(),
            AudiobookProgress.audiobook_id == book.id,
        )
    )
    return {
        "id": book.id,
        "title": book.title,
        "author": book.author,
        "narrator": book.narrator,
        "overview": book.overview,
        "metadataSource": book.metadata_source,
        "series": book.series,
        "seriesPart": book.series_part,
        "playType": book.play_type,
        "coverUrl": _cover_url(book),
        "totalDurationSeconds": book.total_duration_seconds,
        "trackCount": book.track_count,
        "tracks": [_track_dto(t) for t in tracks],
        "chapters": _chapters(db, book, tracks),
        "progress": _progress_dto(progress, book, _global_seconds(progress, tracks)),
    }


def _list_dto(book: Audiobook, progress: AudiobookProgress | None) -> dict:
    item = {
        "id": book.id,
        "title": book.title,
        "author": book.author,
        "narrator": book.narrator,
        "series": book.series,
        "coverUrl": _cover_url(book),
        "playType": book.play_type,
        "totalDurationSeconds": book.total_duration_seconds,
        "trackCount": book.track_count,
        "completed": bool(progress.completed) if progress else False,
        "progressPercent": None,
    }
    if progress and progress.position_seconds is not None and book.total_duration_seconds:
        done = 0.0
        if book.track_count and book.track_count > 0 and progress.track_index is not None:
            done = progress.track_index / book.track_count * book.total_duration_seconds
        item["progressPercent"] = round(
            min(100.0, (done + progress.position_seconds) / book.total_duration_seconds * 100),
            1,
        )
    return item


def _file_stream(path: Path, media_type: str, range_header: str | None):
    if not path.is_file():
        raise ResourceNotFoundException("File", "path", str(path))
    file_len = path.stat().st_size
    if not range_header or not range_header.startswith("bytes="):
        def full():
            with path.open("rb") as f:
                yield from f

        return StreamingResponse(
            full(),
            media_type=media_type,
            headers={"Accept-Ranges": "bytes", "Content-Length": str(file_len)},
        )
    parts = range_header[6:].split("-")
    try:
        start = int(parts[0]) if parts[0] else 0
        end = int(parts[1]) if len(parts) > 1 and parts[1] else file_len - 1
    except ValueError:
        start, end = 0, file_len - 1
    start = max(0, start)
    end = min(file_len - 1, end)
    length = end - start + 1

    def ranged():
        with path.open("rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(8192, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk

    return StreamingResponse(
        ranged(),
        status_code=206,
        media_type=media_type,
        headers={
            "Accept-Ranges": "bytes",
            "Content-Range": f"bytes {start}-{end}/{file_len}",
            "Content-Length": str(length),
        },
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
        libraries = [x for x in media_lib.get_visible_libraries(db) if x.is_audiobook_type()]

    def run():
        from fryfrog.db import get_session_factory

        session = get_session_factory()()
        try:
            for lib in libraries:
                try:
                    audiobook_scan.scan_audiobook_library(session, lib)
                    session.commit()
                except Exception:
                    session.rollback()
        finally:
            session.close()

    threading.Thread(target=run, daemon=True).start()
    return ApiResponse.ok(
        {"status": "started", "libraryCount": len(libraries)}, message="扫描任务已启动"
    )


@router.post("/organize")
def organize(
    db: DbSession,
    libraryId: int,
    dryRun: bool = True,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_admin(db)
    lib = media_lib.get_library_by_id(db, libraryId)
    if not media_lib.is_visible_to_current_user(db, libraryId):
        raise ResourceNotFoundException("MediaLibrary", "id", libraryId)
    return ApiResponse.ok(audiobook_organize.organize(db, lib, dryRun))


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
    base = select(Audiobook).where(Audiobook.library_id.in_(allowed))
    if q and q.strip():
        base = base.where(Audiobook.title.ilike(f"%{q.strip()}%"))
    total = int(db.scalar(select(func.count()).select_from(base.subquery())) or 0)
    rows = list(
        db.scalars(base.order_by(Audiobook.title.asc()).offset(page * size).limit(size)).all()
    )
    progress_map = {}
    if rows:
        for p in db.scalars(
            select(AudiobookProgress).where(
                AudiobookProgress.user_id == current_user_id(),
                AudiobookProgress.audiobook_id.in_([b.id for b in rows]),
            )
        ).all():
            progress_map[p.audiobook_id] = p
    content = [_list_dto(b, progress_map.get(b.id)) for b in rows]
    return ApiResponse.ok(PageResponse.of(content, page, size, total))


@router.get("/authors")
def authors(db: DbSession, page: int = 0, size: int = 20,
            media_lib: MediaLibraryService = Depends(get_media_library_service)):
    allowed = media_lib.get_allowable_library_ids(db)
    if not allowed:
        return ApiResponse.ok(PageResponse.of([], page, size, 0))
    base = (
        select(Audiobook.author, func.count(Audiobook.id))
        .where(Audiobook.library_id.in_(allowed), Audiobook.author.is_not(None), Audiobook.author != "")
        .group_by(Audiobook.author)
    )
    total = len(list(db.execute(base).all()))
    rows = list(db.execute(base.order_by(Audiobook.author.asc()).offset(page * size).limit(size)).all())
    content = [{"author": r[0], "bookCount": int(r[1])} for r in rows]
    return ApiResponse.ok(PageResponse.of(content, page, size, total))


@router.get("/scrape/providers")
def scrape_providers():
    return ApiResponse.ok(audiobook_scrape.list_providers())


@router.get("/scrape/search")
def scrape_search(db: DbSession, q: str, source: str | None = None):
    _require_admin(db)
    return ApiResponse.ok(audiobook_scrape.search(q, source))


@router.get("/{book_id}")
def detail(
    book_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    book = _require_visible(db, media_lib, book_id)
    return ApiResponse.ok(_detail_dict(db, book))


@router.get("/{book_id}/cover")
def cover(
    book_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    book = _require_visible(db, media_lib, book_id)
    data, media = cover_bytes(book.cover_art_path, label=book.title or "")
    return Response(content=data, media_type=media)


@router.get("/tracks/{track_id}/stream")
def stream_track(
    track_id: int,
    request: Request,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    track = db.get(AudiobookTrack, track_id)
    if track is None:
        raise ResourceNotFoundException("AudiobookTrack", "id", track_id)
    _require_visible(db, media_lib, track.audiobook_id)
    path = Path(track.file_path)
    media = AUDIO_MIME.get((track.format or path.suffix.lstrip(".")).lower(), "audio/mpeg")
    return _file_stream(path, media, request.headers.get("Range"))


@router.put("/{book_id}/progress")
def save_progress(
    book_id: int,
    body: ProgressBody,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    book = _require_visible(db, media_lib, book_id)
    uid = current_user_id()
    progress = db.scalar(
        select(AudiobookProgress).where(
            AudiobookProgress.user_id == uid, AudiobookProgress.audiobook_id == book_id
        )
    )
    if progress is None:
        progress = AudiobookProgress(user_id=uid, audiobook_id=book_id, completed=False)
        db.add(progress)
    if body.trackIndex is not None:
        progress.track_index = body.trackIndex
    if body.positionSeconds is not None:
        progress.position_seconds = body.positionSeconds

    tracks = _tracks(db, book_id)
    if progress.track_index is not None and progress.position_seconds is not None and tracks:
        last = tracks[-1]
        last_dur = last.duration_seconds or 0
        if (
            progress.track_index >= last.track_index
            and last_dur > 0
            and progress.position_seconds / last_dur >= COMPLETED_THRESHOLD
        ):
            progress.completed = True
    db.flush()
    return ApiResponse.ok(_progress_dto(progress, book, _global_seconds(progress, tracks)))


@router.put("/{book_id}/completed")
def set_completed(
    book_id: int,
    body: CompletedBody,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    book = _require_visible(db, media_lib, book_id)
    uid = current_user_id()
    progress = db.scalar(
        select(AudiobookProgress).where(
            AudiobookProgress.user_id == uid, AudiobookProgress.audiobook_id == book_id
        )
    )
    if progress is None:
        progress = AudiobookProgress(user_id=uid, audiobook_id=book_id, track_index=0, position_seconds=0)
        db.add(progress)
    progress.completed = body.completed
    if body.completed and book.play_type == "MULTI":
        tracks = _tracks(db, book_id)
        if tracks:
            last = tracks[-1]
            progress.track_index = last.track_index
            progress.position_seconds = last.duration_seconds
    db.flush()
    return ApiResponse.ok(None)


@router.delete("/{book_id}/progress")
def delete_progress(
    book_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_visible(db, media_lib, book_id)
    rows = db.scalars(
        select(AudiobookProgress).where(
            AudiobookProgress.user_id == current_user_id(),
            AudiobookProgress.audiobook_id == book_id,
        )
    ).all()
    for row in rows:
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
    if body.narrator is not None:
        book.narrator = body.narrator.strip() or None
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
    return ApiResponse.ok(_detail_dict(db, book))


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
    book = audiobook_scrape.bind(db, book_id, body.source, body.sourceId)
    return ApiResponse.ok(audiobook_scrape.bind_summary(book))


@router.post("/{book_id}/scrape/unbind")
def scrape_unbind(
    book_id: int,
    db: DbSession,
    media_lib: MediaLibraryService = Depends(get_media_library_service),
):
    _require_admin(db)
    _require_visible(db, media_lib, book_id)
    audiobook_scrape.unbind(db, book_id)
    return ApiResponse.ok(None)
