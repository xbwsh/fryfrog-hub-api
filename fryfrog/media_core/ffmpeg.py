from __future__ import annotations

import json
import logging
import os
import platform
import shutil
import subprocess
from pathlib import Path

from fryfrog.config import get_settings

logger = logging.getLogger(__name__)


class FFmpegRuntime:
    """FFmpeg/ffprobe 路径定位与可用性探测。"""

    def __init__(self) -> None:
        self.ffmpeg_path = "ffmpeg"
        self.ffprobe_path = "ffprobe"
        self.library_dir: str | None = None
        self.available = False
        self.init()

    def init(self) -> None:
        settings = get_settings()
        configured = settings.ffmpeg_path
        if configured:
            self.ffmpeg_path = configured
            self.ffprobe_path = configured.replace("ffmpeg", "ffprobe")
            logger.info("Using configured FFmpeg: %s", self.ffmpeg_path)
        else:
            system_ffmpeg = shutil.which("ffmpeg")
            system_ffprobe = shutil.which("ffprobe")
            if system_ffmpeg:
                self.ffmpeg_path = system_ffmpeg
                self.ffprobe_path = system_ffprobe or "ffprobe"
                logger.info("Using system FFmpeg: %s", self.ffmpeg_path)
            else:
                logger.warning("No FFmpeg found, trying PATH names")
                self.ffmpeg_path = "ffmpeg"
                self.ffprobe_path = "ffprobe"

        self.available = self._check_available()
        if self.available:
            logger.info("FFmpeg transcoding available: %s", self.ffmpeg_path)
        else:
            logger.warning("FFmpeg not available, transcoding disabled")

    def _check_available(self) -> bool:
        try:
            proc = subprocess.run(
                [self.ffmpeg_path, "-version"],
                capture_output=True,
                timeout=5,
                check=False,
            )
            return proc.returncode == 0
        except Exception:
            return False

    def apply_library_env(self, env: dict[str, str] | None = None) -> dict[str, str]:
        env = dict(env or os.environ)
        if self.library_dir:
            env[self.library_path_env()] = self.library_dir
        return env

    def library_path_env(self) -> str:
        system = platform.system().lower()
        if system == "darwin":
            return "DYLD_LIBRARY_PATH"
        if system == "windows":
            return "PATH"
        return "LD_LIBRARY_PATH"

    def is_available(self) -> bool:
        return self.available


class MediaProbeService:
    """封装 ffprobe 音频探测与标签乱码修复。"""

    def __init__(self, runtime: FFmpegRuntime):
        self.runtime = runtime

    def probe_audio_info(self, input_path: str) -> dict:
        try:
            cmd = [
                self.runtime.ffprobe_path,
                "-v",
                "error",
                "-print_format",
                "json",
                "-show_format",
                "-show_streams",
                input_path,
            ]
            proc = subprocess.run(
                cmd,
                capture_output=True,
                timeout=10,
                check=False,
                env=self.runtime.apply_library_env(),
            )
            if proc.returncode != 0 or not proc.stdout:
                return {}
            raw = proc.stdout
            result = self._parse_probe_output(raw)
            if self._tags_contain_replacement(result):
                for charset in ("gbk", "big5", "shift_jis", "euc-kr"):
                    try:
                        alt_output = raw.decode(charset)
                    except Exception:
                        continue
                    try:
                        alt_result = self._parse_probe_output(alt_output.encode("utf-8"))
                    except Exception:
                        continue
                    self._merge_cleaner_tags(result, alt_result)
                    if not self._tags_contain_replacement(result):
                        break
            return result
        except Exception:
            logger.debug("Failed to probe audio %s", input_path, exc_info=True)
            return {}

    def probe_chapters(self, input_path: str) -> list[dict]:
        try:
            cmd = [
                self.runtime.ffprobe_path,
                "-v",
                "error",
                "-show_chapters",
                "-print_format",
                "json",
                input_path,
            ]
            proc = subprocess.run(
                cmd,
                capture_output=True,
                timeout=10,
                check=False,
                env=self.runtime.apply_library_env(),
            )
            if proc.returncode != 0:
                return []
            data = json.loads(proc.stdout.decode("utf-8", errors="replace") or "{}")
            chapters = []
            for ch in data.get("chapters") or []:
                try:
                    start = float(ch.get("start_time") or 0)
                    end = float(ch.get("end_time") or 0)
                except (TypeError, ValueError):
                    continue
                tags = ch.get("tags") or {}
                chapters.append(
                    {
                        "start": start,
                        "end": end,
                        "title": tags.get("title") or "",
                    }
                )
            return chapters
        except Exception:
            return []

    def probe_video_duration(self, input_path: str) -> float | None:
        try:
            cmd = [
                self.runtime.ffprobe_path,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                input_path,
            ]
            proc = subprocess.run(
                cmd,
                capture_output=True,
                timeout=15,
                check=False,
                env=self.runtime.apply_library_env(),
            )
            text = proc.stdout.decode("utf-8", errors="replace").strip()
            return float(text) if text else None
        except Exception:
            return None

    def probe_video_resolution(self, input_path: str) -> tuple[int, int] | None:
        try:
            cmd = [
                self.runtime.ffprobe_path,
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=width,height",
                "-of",
                "json",
                input_path,
            ]
            proc = subprocess.run(
                cmd,
                capture_output=True,
                timeout=15,
                check=False,
                env=self.runtime.apply_library_env(),
            )
            data = json.loads(proc.stdout.decode("utf-8", errors="replace") or "{}")
            streams = data.get("streams") or []
            if streams:
                return int(streams[0].get("width") or 0), int(streams[0].get("height") or 0)
        except Exception:
            return None
        return None

    def _parse_probe_output(self, raw: bytes | str) -> dict:
        if isinstance(raw, bytes):
            text = raw.decode("utf-8", errors="replace").strip()
        else:
            text = raw.strip()
        root = json.loads(text)
        result: dict = {}
        fmt = root.get("format") or {}
        if fmt.get("duration") is not None:
            result["duration"] = float(fmt["duration"])
        if fmt.get("bit_rate") is not None:
            result["bitrate"] = int(fmt["bit_rate"])
        if fmt.get("format_name"):
            result["format"] = fmt["format_name"]
        if fmt.get("tags"):
            result["tags"] = dict(fmt["tags"])
        for stream in root.get("streams") or []:
            if stream.get("codec_type") == "audio":
                if stream.get("codec_name"):
                    result["codec"] = stream["codec_name"]
                if stream.get("sample_rate"):
                    result["sampleRate"] = int(stream["sample_rate"])
                if stream.get("tags") and "tags" not in result:
                    result["tags"] = dict(stream["tags"])
                break
        return result

    def _tags_contain_replacement(self, result: dict) -> bool:
        tags = result.get("tags") or {}
        return any("�" in str(v) for v in tags.values())

    def _merge_cleaner_tags(self, base: dict, alt: dict) -> None:
        base_tags = base.get("tags")
        alt_tags = alt.get("tags")
        if not base_tags or not alt_tags:
            return
        for key, value in alt_tags.items():
            if key in base_tags and "�" in str(base_tags[key]) and "�" not in str(value):
                base_tags[key] = value


_ffmpeg_runtime: FFmpegRuntime | None = None
_media_probe: MediaProbeService | None = None


def get_ffmpeg_runtime() -> FFmpegRuntime:
    global _ffmpeg_runtime
    if _ffmpeg_runtime is None:
        _ffmpeg_runtime = FFmpegRuntime()
    return _ffmpeg_runtime


def get_media_probe() -> MediaProbeService:
    global _media_probe
    if _media_probe is None:
        _media_probe = MediaProbeService(get_ffmpeg_runtime())
    return _media_probe
