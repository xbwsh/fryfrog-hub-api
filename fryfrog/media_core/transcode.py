"""FFmpeg 进程的并发控制和流式响应生命周期。"""
from collections import deque
import logging
import subprocess
from threading import Lock, Thread

import anyio
from starlette.responses import Response, StreamingResponse

from fryfrog.config import get_settings

logger = logging.getLogger(__name__)
_lock = Lock()
_active = 0


class TranscodeResponse(StreamingResponse):
    def __init__(self, command: list[str], video_id: int, *, env: dict[str, str]) -> None:
        super().__init__(
            self._chunks(), media_type="video/mp4",
            headers={"Accept-Ranges": "none", "Cache-Control": "no-cache"},
        )
        self.command = command
        self.video_id = video_id
        self.env = env
        self.process = None
        self.stderr_tail: deque[bytes] = deque(maxlen=4)
        self.stderr_thread = None
        self.eof = False
        self.produced_output = False

    async def __call__(self, scope, receive, send) -> None:
        global _active
        with _lock:
            admitted = _active < get_settings().transcode_max_concurrent
            if admitted:
                _active += 1
        if not admitted:
            await Response(
                "Transcoding capacity reached", status_code=503, headers={"Retry-After": "5"},
            )(scope, receive, send)
            return

        try:
            try:
                # 延迟到 ASGI 响应开始才启动 FFmpeg，确保所有退出路径进入 finally。
                self.process = subprocess.Popen(
                    self.command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE, env=self.env,
                )
                thread = Thread(target=self._drain_stderr, daemon=True)
                thread.start()
                self.stderr_thread = thread
            except (OSError, RuntimeError):
                logger.exception("启动视频转码失败 video_id=%s", self.video_id)
                await Response("Transcoding not available", status_code=503)(scope, receive, send)
                return

            async with anyio.create_task_group() as group:
                async def stream():
                    try:
                        await self.stream_response(send)
                    except OSError:
                        # ASGI 2.4+ 在客户端断开后可由 send 抛出 OSError。
                        pass
                    finally:
                        group.cancel_scope.cancel()

                group.start_soon(stream)
                await self.listen_for_disconnect(receive)
                group.cancel_scope.cancel()
        finally:
            try:
                if self.process is not None:
                    with anyio.CancelScope(shield=True):
                        await anyio.to_thread.run_sync(self._reap)
            finally:
                with _lock:
                    _active -= 1

    async def _chunks(self):
        assert self.process is not None and self.process.stdout is not None
        while True:
            try:
                chunk = await anyio.to_thread.run_sync(
                    self.process.stdout.read1, 64 * 1024, abandon_on_cancel=True,
                )
            except OSError:
                logger.exception("读取转码流失败 video_id=%s", self.video_id)
                raise
            if not chunk:
                self.eof = True
                return
            self.produced_output = True
            yield chunk

    def _drain_stderr(self) -> None:
        assert self.process is not None and self.process.stderr is not None
        while chunk := self.process.stderr.read1(2048):
            self.stderr_tail.append(chunk)

    def _reap(self) -> None:
        proc = self.process
        assert proc is not None and proc.stdout is not None and proc.stderr is not None
        interrupted = not self.eof and proc.poll() is None
        try:
            if interrupted:
                proc.terminate()
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        finally:
            if self.stderr_thread is not None:
                self.stderr_thread.join()
            proc.stderr.close()
            proc.stdout.close()
        if not interrupted and (proc.returncode != 0 or not self.produced_output):
            logger.warning(
                "视频转码失败 video_id=%s exit_code=%s stderr=%s",
                self.video_id, proc.returncode, b"".join(self.stderr_tail).decode(errors="replace").strip(),
            )
