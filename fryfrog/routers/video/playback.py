"""播放域：原码流、转码流、播放列表、字幕。"""
from __future__ import annotations

import os
import socket
import subprocess
from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import Response, StreamingResponse
from sqlalchemy import select

from fryfrog.core.api_response import ApiResponse
from fryfrog.core.deps import DbSession
from fryfrog.core.exceptions import BadRequestException, ResourceNotFoundException
from fryfrog.core.signer import sign
from fryfrog.media_core import get_ffmpeg_runtime
from fryfrog.models.video import Video
from fryfrog.services import video_assets as assets
from fryfrog.services import video_service as vs

router = APIRouter()
@router.get("/{id:int}/stream")
def stream_video(db: DbSession, id: int, request: Request):
    video = vs.get_video(db, id)
    path = Path(video.file_path)
    if not path.exists():
        raise ResourceNotFoundException("VideoFile", "id", id)
    file_size = path.stat().st_size
    content_type = _video_content_type(path.name)
    rng = _parse_range(request.headers.get("range"), file_size)
    headers = {"Accept-Ranges": "bytes"}
    if rng == (-1, -1):
        # 范围不可满足：显式回 416，不能静默把整文件当 200 吐给客户端
        # （会破坏播放器/下载器的断点续传判断）
        return Response(
            status_code=416,
            headers={"Content-Range": f"bytes */{file_size}"},
        )
    if rng is None:
        headers["Content-Length"] = str(file_size)
        return StreamingResponse(
            _file_iterator(path, 0, file_size), media_type=content_type, headers=headers
        )
    start, end = rng
    length = end - start + 1
    headers["Content-Length"] = str(length)
    headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
    return StreamingResponse(
        _file_iterator(path, start, length),
        status_code=206,
        media_type=content_type,
        headers=headers,
    )


@router.get("/{id:int}/stream/transcode")
def stream_transcode(
    db: DbSession,
    id: int,
    quality: str = "1080p",
    maxBitrate: str | None = None,
    subtitle: str | None = None,
):
    runtime = get_ffmpeg_runtime()
    if not runtime.is_available():
        return Response(status_code=503, content=b"Transcoding not available")
    video = vs.get_video(db, id)
    path = Path(video.file_path)
    if not path.exists():
        raise ResourceNotFoundException("VideoFile", "id", id)

    subtitle_path = None
    if subtitle:
        video_dir = path.parent.resolve()
        sub = (video_dir / subtitle).resolve()
        # 分隔符边界校验：裸 startswith 会放过 /data/shows-backup 这类同级
        # 同前缀目录（经符号链接可达时读到目录外文件）
        if not sub.is_relative_to(video_dir) or not sub.is_file():
            return Response(status_code=400, content=b"Invalid subtitle")
        subtitle_path = str(sub)

    height = {"1080p": 1080, "720p": 720, "480p": 480}.get(quality, 1080)
    vf = f"scale=-2:{height}"
    if subtitle_path:
        vf = f"{vf},subtitles={_escape_filter_path(subtitle_path)}"
    cmd = [
        runtime.ffmpeg_path,
        "-i",
        str(path),
        "-vf",
        vf,
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-c:a",
        "aac",
        "-f",
        "mp4",
        "-movflags",
        "frag_keyframe+empty_moov",
    ]
    if maxBitrate:
        cmd.extend(["-b:v", maxBitrate])
    cmd.append("pipe:1")
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

    def gen():
        try:
            assert proc.stdout is not None
            while True:
                chunk = proc.stdout.read(8192)
                if not chunk:
                    break
                yield chunk
        finally:
            proc.kill()
            # 只 kill 不 wait 会留僵尸进程；ffmpeg 可能派生子进程树，wait 主进程
            # 至少保证不堆积（子进程交给进程组系统清理）。timeout 防 wait 阻塞。
            try:
                proc.wait(timeout=10)
            except Exception:
                pass

    return StreamingResponse(
        gen(),
        media_type="video/mp4",
        headers={"Accept-Ranges": "none", "Cache-Control": "no-cache"},
    )


@router.get("/{id:int}/playlist.m3u")
def get_playlist(db: DbSession, id: int, request: Request):
    video = vs.get_video(db, id)
    if video.series_id:
        siblings = vs.series_videos(db, video.series_id)
    elif video.tmdb_id:
        siblings = list(db.scalars(select(Video).where(Video.tmdb_id == video.tmdb_id)).all())
        siblings.sort(key=lambda v: (vs.season_of(v), vs.episode_of(v)))
    else:
        siblings = [video]

    base_url = _server_base_url(request)
    lines = ["#EXTM3U"]
    series_title = video.series_name or video.title
    for v in siblings:
        title = v.title
        if v.season_number is not None and v.episode_number is not None:
            title = f"S{v.season_number:02d}E{v.episode_number:02d} - {v.title}"
        lines.append(f"#EXTINF:-1,{title}")
        lines.append(f"{base_url}{sign(f'/api/v1/video/{v.id}/stream')}")
    content = ("\n".join(lines) + "\n").encode("utf-8")
    return Response(
        content=content,
        media_type="audio/x-mpegurl; charset=utf-8",
        headers={
            "Content-Disposition": f'attachment; filename="{series_title}.m3u"',
            "Content-Length": str(len(content)),
        },
    )


@router.get("/{id:int}/subtitles")
def list_subtitles(db: DbSession, id: int):
    video = vs.get_video(db, id)
    video_dir = Path(video.file_path).parent
    subtitles = []
    if video_dir.is_dir():
        for f in sorted(video_dir.iterdir()):
            if not f.is_file() or f.suffix.lower() not in assets.SUBTITLE_EXTS:
                continue
            name = f.name
            encoded = quote(name, safe="")
            subtitles.append(
                {
                    "filename": name,
                    "language": _subtitle_lang(name),
                    "url": sign(f"/api/v1/video/{id}/subtitles/{encoded}"),
                }
            )
    return ApiResponse.ok(subtitles)


@router.get("/{id:int}/subtitles/{filename}")
def get_subtitle(db: DbSession, id: int, filename: str):
    video = vs.get_video(db, id)
    video_dir = Path(video.file_path).parent.resolve()
    sub = (video_dir / filename).resolve()
    # is_relative_to 才有分隔符边界：startswith 会放过同前缀的同级目录
    if not sub.is_relative_to(video_dir):
        raise BadRequestException("Invalid subtitle path")
    if not sub.exists():
        raise ResourceNotFoundException("Subtitle", "filename", filename)
    lower = filename.lower()
    media = "text/vtt" if lower.endswith(".vtt") else "text/plain; charset=utf-8"
    return Response(content=sub.read_bytes(), media_type=media)


VIDEO_CONTENT_TYPES = {
    ".mkv": "video/x-matroska",
    ".mp4": "video/mp4",
    ".avi": "video/x-msvideo",
    ".mov": "video/quicktime",
    ".wmv": "video/x-ms-wmv",
    ".flv": "video/x-flv",
    ".webm": "video/webm",
    ".ts": "video/mp2t",
    ".m4v": "video/x-m4v",
    ".m2ts": "video/mp2t",
}

ALLOWED_SIZES = {"w92", "w154", "w185", "w342", "w500", "w780", "original"}
TMDB_CDN = "https://image.tmdb.org/t/p"


def _video_content_type(name: str) -> str:
    return VIDEO_CONTENT_TYPES.get(Path(name).suffix.lower(), "application/octet-stream")


def _escape_filter_path(path: str) -> str:
    """把文件路径转义成 ffmpeg 滤镜参数的安全字面量。

    滤镜参数以 `:` 分隔、`'` 为引号、反斜杠为转义符——字幕文件名含撇号
    （Bob's Burgers.srt）、冒号或反斜杠时裸拼会让滤镜解析失败、ffmpeg
    退出（客户端拿到空流）。包一层单引号并转义内部特殊字符。
    """
    escaped = path.replace("\\", "\\\\").replace("'", "\\'").replace(":", "\\:")
    return f"'{escaped}'"


def _parse_range(range_header: str | None, file_size: int) -> tuple[int, int] | None:
    if not range_header or not range_header.startswith("bytes="):
        return None
    spec = range_header[6:].split(",")[0].strip()
    if "-" not in spec:
        return None
    start_s, end_s = spec.split("-", 1)
    try:
        if start_s == "":
            suffix = int(end_s)
            start = max(file_size - suffix, 0)
            end = file_size - 1
        else:
            start = int(start_s)
            end = int(end_s) if end_s else file_size - 1
    except ValueError:
        return None
    if start >= file_size or start > end:
        # 不可满足的范围（起点越界等）：HTTP 要求回 416 + Content-Range: bytes */size。
        # 用 (-1,-1) 哨兵区分「没有 Range 头/格式错」与「范围不合法」，前者回 200 全量。
        return (-1, -1)
    return start, min(end, file_size - 1)


def _file_iterator(path: Path, start: int, length: int, chunk: int = 64 * 1024):
    with open(path, "rb") as f:
        f.seek(start)
        remaining = length
        while remaining > 0:
            data = f.read(min(chunk, remaining))
            if not data:
                break
            remaining -= len(data)
            yield data


def _subtitle_lang(filename: str) -> str:
    stem = filename.rsplit(".", 1)[0] if "." in filename else filename
    if "." in stem:
        lang = stem.rsplit(".", 1)[-1]
        if lang:
            return lang
    return "und"


def _server_base_url(request: Request) -> str:
    env_base = os.environ.get("VIDEO_BASE_URL", "")
    if env_base:
        return env_base.rstrip("/")
    scheme = request.headers.get("x-forwarded-proto") or request.url.scheme
    forwarded_host = request.headers.get("x-forwarded-host")
    if forwarded_host:
        return f"{scheme}://{forwarded_host}"
    host = request.url.hostname or "localhost"
    if host in ("localhost", "127.0.0.1"):
        lan = _detect_lan_ip()
        if lan:
            host = lan
    xf_port = request.headers.get("x-forwarded-port")
    port = int(xf_port) if xf_port and xf_port.isdigit() else (request.url.port or 80)
    return f"{scheme}://{host}:{port}"


def _detect_lan_ip() -> str | None:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return None
