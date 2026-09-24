"""音乐库扫描与整理：ffprobe 标签建库 + root/artist/album/track 文件整理。"""

from __future__ import annotations

import logging
import re
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from fryfrog.media_core import get_media_probe
from fryfrog.models.library import MediaLibrary
from fryfrog.models.music import MusicAlbum, MusicArtist, MusicSong
from fryfrog.services.fsutil import IMAGE_EXTS, MUSIC_EXTS, iter_files

logger = logging.getLogger(__name__)

UNKNOWN_ARTIST = "未知歌手"
UNKNOWN_ALBUM = "未知专辑"
COVER_NAMES = ("cover", "folder", "front", "album")
COVER_EXTS = {".jpg", ".jpeg", ".png", ".webp"}


def scan_music_library(db: Session, library: MediaLibrary) -> dict:
    root = Path(library.path)
    files = iter_files(root, MUSIC_EXTS)
    saved = 0
    failed = 0
    for path in files:
        try:
            _save_song(db, path, library.id, root)
            saved += 1
        except Exception:
            failed += 1
            logger.warning("Failed to scan %s", path, exc_info=True)
    _cleanup_missing(db, library.id)
    db.flush()
    return {"libraryId": library.id, "total": len(files), "saved": saved, "failed": failed}


def organize_music_library(db: Session, library: MediaLibrary, dry_run: bool) -> dict:
    if not library.is_music_type():
        raise ValueError("指定资源库不是 MUSIC 类型")
    root = Path(library.path).resolve()
    songs = list(
        db.scalars(
            select(MusicSong).where(MusicSong.library_id == library.id).order_by(MusicSong.file_path.asc())
        ).all()
    )
    items: list[dict] = []
    moved = unchanged = failed = 0
    for song in songs:
        row: dict = {"songId": song.id, "title": song.title}
        try:
            source = _require_source(song, root)
            target = _unique_target(root, song, source)
            row["source"] = str(source)
            row["target"] = str(target)
            if source == target:
                row["status"] = "unchanged"
                unchanged += 1
            elif dry_run:
                row["status"] = "planned"
                moved += 1
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                source.replace(target)
                song.file_path = str(target)
                song.file_size = target.stat().st_size
                db.flush()
                row["status"] = "moved"
                moved += 1
        except Exception as exc:
            row["status"] = "failed"
            row["message"] = str(exc)
            failed += 1
        items.append(row)
    return {
        "libraryId": library.id,
        "dryRun": dry_run,
        "total": len(songs),
        "movedOrPlanned": moved,
        "unchanged": unchanged,
        "failed": failed,
        "items": items,
    }


# ── 扫描内部 ──────────────────────────────────────────────


def _save_song(db: Session, path: Path, library_id: int | None, root: Path) -> MusicSong:
    absolute = str(path.resolve())
    existing = db.scalar(select(MusicSong).where(MusicSong.file_path == absolute))
    if existing is not None and not _is_changed(existing, path):
        _ensure_lyrics(existing, path)
        return existing

    info = get_media_probe().probe_audio_info(absolute)
    tags = _lower_tags(info.get("tags") or {})
    parent = path.parent
    dir_album = parent.name if parent else None
    dir_artist = parent.parent.name if parent and parent.parent else None

    title = _first(_sanitize(tags.get("title")), path.stem)
    artist_name = _first(
        _sanitize(tags.get("artist")), _sanitize(tags.get("album_artist")), dir_artist, UNKNOWN_ARTIST
    )
    album_name = _first(_sanitize(tags.get("album")), dir_album, UNKNOWN_ALBUM)

    from_name = _parse_artist_title(path.stem)
    if from_name:
        if artist_name == UNKNOWN_ARTIST:
            artist_name = from_name[0]
        if title == path.stem and from_name[1]:
            title = from_name[1]

    artist = _get_or_create_artist(db, artist_name, library_id, parent.parent if parent else None)
    album = _get_or_create_album(db, album_name, artist, tags, library_id)
    _ensure_album_cover(db, album, parent, source_audio=path)

    song = existing or MusicSong()
    song.title = title
    song.artist_name = artist_name
    song.album_name = album_name
    song.artist_id = artist.id if artist else None
    song.album_id = album.id if album else None
    song.track_number = _parse_int(tags.get("track"), tags.get("tracknumber"))
    song.disc_number = _parse_int(tags.get("disc"), tags.get("discnumber"))
    song.duration_seconds = float(info["duration"]) if info.get("duration") is not None else None
    song.bit_rate = _to_kilobits(info.get("bitrate"))
    song.sample_rate = int(info["sampleRate"]) if info.get("sampleRate") is not None else None
    song.format = path.suffix.lstrip(".").upper() or None
    song.genre = _first(tags.get("genre"), album.genre if album else None)
    song.year = _parse_year(tags.get("date"), tags.get("year"))
    song.file_path = absolute
    try:
        song.file_size = path.stat().st_size
    except OSError:
        pass
    song.lyrics_content = _extract_embedded_lyrics(tags)
    song.lyrics_path = _find_lyrics(parent, path)
    song.library_id = library_id
    if existing is None:
        db.add(song)
    db.flush()
    return song


def _get_or_create_artist(db: Session, name: str, library_id: int | None, artist_dir: Path | None) -> MusicArtist:
    artist = db.scalar(
        select(MusicArtist).where(MusicArtist.name == name, MusicArtist.library_id == library_id).limit(1)
    )
    if artist:
        return artist
    artist = MusicArtist(name=name, sort_name=_sort_name(name), library_id=library_id)
    if artist_dir and artist_dir.is_dir():
        cover = _find_cover(artist_dir, name) or _find_any_cover(artist_dir)
        if cover:
            artist.cover_art_path = str(cover)
    db.add(artist)
    db.flush()
    return artist


def _get_or_create_album(
    db: Session, title: str, artist: MusicArtist | None, tags: dict, library_id: int | None
) -> MusicAlbum:
    artist_name = artist.name if artist else UNKNOWN_ARTIST
    artist_id = artist.id if artist else None
    album = db.scalar(
        select(MusicAlbum)
        .where(
            MusicAlbum.title == title,
            MusicAlbum.artist_name == artist_name,
            MusicAlbum.library_id == library_id,
        )
        .limit(1)
    )
    if album:
        return album
    album = MusicAlbum(
        title=title,
        artist_id=artist_id,
        artist_name=artist_name,
        library_id=library_id,
        genre=_first(tags.get("genre")),
        year=_parse_year(tags.get("date"), tags.get("year")),
    )
    db.add(album)
    db.flush()
    return album


def _ensure_album_cover(
    db: Session, album: MusicAlbum | None, album_dir: Path | None, source_audio: Path | None = None
) -> None:
    if album is None:
        return
    current = album.cover_art_path
    if current and ".metadata/music-covers" in current:
        album.cover_art_path = None
        db.flush()
        return
    if album.cover_art_path:
        return
    if album_dir and album_dir.is_dir():
        cover = _find_cover(album_dir, album.title) or _find_any_cover(album_dir)
        if cover:
            album.cover_art_path = str(cover)
            db.flush()
            return
    # 目录无封面文件时，尝试从音轨内嵌封面提取
    if source_audio and album_dir and album_dir.is_dir():
        extracted = _extract_embedded_cover(source_audio, album_dir)
        if extracted:
            album.cover_art_path = str(extracted)
            db.flush()


def _extract_embedded_cover(audio_path: Path, album_dir: Path) -> Path | None:
    """用 ffmpeg 抽出内嵌 APIC 为 cover.jpg。"""
    from fryfrog.media_core import get_ffmpeg_runtime

    runtime = get_ffmpeg_runtime()
    if not runtime.is_available():
        return None
    dest = album_dir / "cover.jpg"
    if dest.exists():
        return dest
    cmd = [
        runtime.ffmpeg_path,
        "-i",
        str(audio_path),
        "-an",
        "-vcodec",
        "copy",
        "-y",
        str(dest),
    ]
    try:
        import subprocess

        proc = subprocess.run(cmd, capture_output=True, timeout=15, check=False)
        if proc.returncode == 0 and dest.exists() and dest.stat().st_size > 0:
            return dest
        if dest.exists():
            dest.unlink(missing_ok=True)
    except Exception:
        logger.debug("提取内嵌封面失败: %s", audio_path, exc_info=True)
    return None


def _ensure_lyrics(song: MusicSong, path: Path) -> None:
    lyrics_path = _find_lyrics(path.parent, path)
    if (song.lyrics_path or None) != (lyrics_path or None):
        song.lyrics_path = lyrics_path


def _cleanup_missing(db: Session, library_id: int | None) -> None:
    songs = db.scalars(select(MusicSong).where(MusicSong.library_id == library_id)).all()
    for song in songs:
        if song.file_path and not Path(song.file_path).is_file():
            db.delete(song)
    db.flush()
    for album in db.scalars(select(MusicAlbum).where(MusicAlbum.library_id == library_id)).all():
        if db.scalar(select(MusicSong.id).where(MusicSong.album_id == album.id).limit(1)) is None:
            db.delete(album)
    db.flush()
    for artist in db.scalars(select(MusicArtist).where(MusicArtist.library_id == library_id)).all():
        if db.scalar(select(MusicSong.id).where(MusicSong.artist_id == artist.id).limit(1)) is None:
            db.delete(artist)
    db.flush()


def _is_changed(song: MusicSong, path: Path) -> bool:
    try:
        size = path.stat().st_size
    except OSError:
        return True
    if song.file_size != size:
        return True
    if _is_mojibake(song.title) or _is_mojibake(song.artist_name) or _is_mojibake(song.album_name):
        return True
    return _is_placeholder(song.artist_name) or _is_placeholder(song.album_name)


def _is_mojibake(value: str | None) -> bool:
    return bool(value) and ("\ufffd" in value or "锟" in value)


_PLACEHOLDERS = {"data", "music", "media", "library", "vol1", "1000", UNKNOWN_ARTIST, UNKNOWN_ALBUM}


def _is_placeholder(value: str | None) -> bool:
    if not value:
        return False
    return value.strip().lower() in _PLACEHOLDERS or value.strip() in _PLACEHOLDERS


# ── 组织内部 ──────────────────────────────────────────────


def _require_source(song: MusicSong, root: Path) -> Path:
    if not song.file_path:
        raise ValueError("歌曲没有文件路径")
    source = Path(song.file_path).resolve()
    if not source.is_relative_to(root):
        raise ValueError("文件不在音乐库目录内")
    if not source.is_file():
        raise ValueError("文件不存在")
    return source


def _unique_target(root: Path, song: MusicSong, source: Path) -> Path:
    artist = _safe_name(song.artist_name, UNKNOWN_ARTIST)
    album = _safe_name(song.album_name, UNKNOWN_ALBUM)
    title = _safe_name(song.title, source.stem)
    ext = source.suffix.lower()
    track = f"{song.track_number:02d} - " if song.track_number else ""
    album_dir = (root / artist / album).resolve()
    target = (album_dir / f"{track}{title}{ext}").resolve()
    if not target.is_relative_to(root) or target == source:
        return target
    suffix = 2
    while target.exists():
        target = (album_dir / f"{track}{title} ({suffix}){ext}").resolve()
        suffix += 1
    return target


def _safe_name(value: str | None, fallback: str) -> str:
    if not value or not value.strip():
        return fallback
    cleaned = re.sub(r'[\\/:*?"<>|]', "_", value)
    cleaned = re.sub(r"[\x00-\x1f]", "_", cleaned).strip()
    while cleaned.endswith("."):
        cleaned = cleaned[:-1].rstrip()
    if cleaned in ("", ".", ".."):
        return fallback
    return cleaned


# ── 标签 / 封面 / 歌词工具 ────────────────────────────────


def _lower_tags(tags: dict) -> dict:
    out: dict[str, str] = {}
    for key, value in tags.items():
        if value is None:
            continue
        out[str(key).lower().replace("-", "_")] = str(value)
    return out


def _sanitize(value: str | None) -> str | None:
    if value is None or not value.strip():
        return None
    trimmed = value.strip()
    bad = sum(1 for c in trimmed if c in "\ufffd?◆◇¤")
    if bad * 2 >= len(trimmed):
        return None
    return trimmed


def _first(*values: str | None) -> str | None:
    for value in values:
        if value and value.strip():
            return value.strip()
    return None


def _parse_artist_title(base: str) -> tuple[str, str] | None:
    idx = base.find("-")
    if idx <= 0 or idx >= len(base) - 1:
        return None
    artist = base[:idx].strip()
    title = base[idx + 1 :].strip()
    if not artist or not title:
        return None
    return artist, title


def _sort_name(name: str | None) -> str | None:
    if not name:
        return None
    return re.sub(r"^(The|A|An)\s+", "", name, flags=re.I)


def _parse_int(*values: str | None) -> int | None:
    for value in values:
        if not value or not value.strip():
            continue
        head = value.strip().split("/")[0].strip()
        try:
            return int(head)
        except ValueError:
            continue
    return None


def _parse_year(*values: str | None) -> int | None:
    for value in values:
        if not value:
            continue
        match = re.search(r"(19|20)\d{2}", value)
        if match:
            try:
                return int(match.group())
            except ValueError:
                continue
    return None


def _to_kilobits(value) -> int | None:
    if isinstance(value, (int, float)) and value > 0:
        return int(value) // 1000
    return None


def _extract_embedded_lyrics(tags: dict) -> str | None:
    for key, value in tags.items():
        norm = key.lower().replace("_", "").replace("-", "")
        if norm in ("lyrics", "lyric", "uslt", "©lyr") or "lyrics" in norm:
            if value and value.strip():
                return value.strip()
    return None


def _find_lyrics(song_dir: Path | None, song_path: Path) -> str | None:
    if song_dir is None or not song_dir.is_dir():
        return None
    stem = song_path.stem.lower()
    candidates = sorted(song_dir.glob("*.lrc")) + sorted(song_dir.glob("*.txt"))
    for candidate in candidates:
        if candidate.stem.lower() == stem or candidate.suffix.lower() == ".lrc":
            return str(candidate)
    return None


def _find_cover(directory: Path, title: str | None) -> Path | None:
    if not directory.is_dir():
        return None
    for base in COVER_NAMES:
        for ext in (".jpg", ".jpeg", ".png", ".webp"):
            candidate = directory / f"{base}{ext}"
            if candidate.is_file():
                return candidate
    if title:
        for candidate in directory.iterdir():
            if not candidate.is_file() or candidate.suffix.lower() not in COVER_EXTS:
                continue
            if candidate.stem.lower() == title.strip().lower():
                return candidate
    return None


def _find_any_cover(directory: Path) -> Path | None:
    if not directory.is_dir():
        return None
    images = sorted(
        p for p in directory.iterdir() if p.is_file() and p.suffix.lower() in (COVER_EXTS | IMAGE_EXTS)
    )
    return images[0] if images else None
