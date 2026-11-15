import os
import sys
from types import SimpleNamespace

import anyio
import pytest

from fryfrog.media_core import transcode
from fryfrog.routers.video import playback


SCOPE = {"type": "http", "asgi": {"spec_version": "2.4"}}


def _response(script: str, video_id: int = 42):
    return transcode.TranscodeResponse([sys.executable, "-u", "-c", script], video_id, env=os.environ.copy())


@pytest.mark.asyncio
@pytest.mark.parametrize("disconnect_on", ["receive", "send"])
async def test_disconnect_reaps_process_and_frees_slot(monkeypatch, disconnect_on):
    monkeypatch.setattr(transcode, "get_settings", lambda: SimpleNamespace(transcode_max_concurrent=1))
    script = "import sys,time; sys.stdout.write('frame'); sys.stdout.flush(); time.sleep(60)"
    if disconnect_on == "receive":
        script = "import time; time.sleep(60)"
    response = _response(script)
    started = anyio.Event()
    disconnect = anyio.Event()
    chunks = []

    async def receive():
        if disconnect_on == "send":
            await anyio.sleep_forever()
        await disconnect.wait()
        return {"type": "http.disconnect"}

    async def send(message):
        if message["type"] == "http.response.start":
            started.set()
        elif message.get("body"):
            chunks.append(message["body"])
            await disconnect.wait()
            raise BrokenPipeError

    async def collect_response(other):
        messages = []

        async def collect(message):
            messages.append(message)

        await other(SCOPE, anyio.sleep_forever, collect)
        return messages

    with anyio.fail_after(10):
        async with anyio.create_task_group() as group:
            group.start_soon(response, SCOPE, receive, send)
            await started.wait()
            rejected = _response("raise AssertionError('must not start')")
            messages = await collect_response(rejected)
            assert messages[0]["status"] == 503
            assert dict(messages[0]["headers"])[b"retry-after"] == b"5"
            assert rejected.process is None
            disconnect.set()

        assert response.process.poll() is not None
        assert response.process.stdout.closed
        assert response.process.stderr.closed
        assert chunks == ([b"frame"] if disconnect_on == "send" else [])
        messages = await collect_response(_response("import sys; sys.stdout.buffer.write(b'next')"))
        assert messages[0]["status"] == 200
        assert b"".join(message.get("body", b"") for message in messages) == b"next"


@pytest.mark.asyncio
async def test_ffmpeg_failure_logs_stderr_and_exit_code(caplog):
    response = _response(
        "import sys; sys.stderr.write('x'*100000 + '\\ncodec failed\\n'); sys.exit(7)",
    )

    async def send(_):
        pass

    with anyio.fail_after(10):
        await response(SCOPE, anyio.sleep_forever, send)

    assert "video_id=42 exit_code=7" in caplog.text
    assert "codec failed" in caplog.text
    assert len(caplog.text) < 10000


@pytest.mark.asyncio
async def test_route_logs_spawn_failure_and_returns_503(tmp_path, monkeypatch, caplog):
    video = tmp_path / "video.mp4"
    video.touch()
    monkeypatch.setattr(playback, "get_visible_video", lambda db, id: SimpleNamespace(file_path=video))
    runtime = SimpleNamespace(
        ffmpeg_path=str(tmp_path / "missing-ffmpeg"),
        is_available=lambda: True,
        apply_library_env=os.environ.copy,
    )
    monkeypatch.setattr(playback, "get_ffmpeg_runtime", lambda: runtime)
    messages = []

    async def send(message):
        messages.append(message)

    response = playback.stream_transcode(None, 42)
    await response(SCOPE, anyio.sleep_forever, send)
    assert messages[0]["status"] == 503
    assert "启动视频转码失败 video_id=42" in caplog.text
    assert transcode._active == 0
