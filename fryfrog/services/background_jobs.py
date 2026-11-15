"""进程内后台任务队列，排队与执行中的任务共用去重键。"""
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from threading import Lock

from fastapi import HTTPException

from fryfrog.config import get_settings

_lock = Lock()
_pending: set[str] = set()
_executor: ThreadPoolExecutor | None = None
QUEUE_RETRY_AFTER_SECONDS = 5


class JobQueueFull(HTTPException):
    def __init__(self, *, batch_too_large: bool = False) -> None:
        super().__init__(
            status_code=503,
            detail=(
                "提交的任务数超过后台队列总容量，请分批提交"
                if batch_too_large else "后台任务队列已满，请稍后重试"
            ),
            headers={"Retry-After": str(QUEUE_RETRY_AFTER_SECONDS)},
        )


def enqueue_job(key: str, work, *, on_queued) -> bool:
    return bool(enqueue_jobs({key: (work, on_queued)}))


def enqueue_jobs(
    jobs: dict[str, tuple[Callable[[], None], Callable[[], None]]],
    *,
    allow_partial: bool = False,
) -> list[str]:
    """先检查整批新任务的容量，重复请求继续使用原任务的进度。"""
    global _executor

    def run(key: str, work: Callable[[], None]) -> None:
        try:
            work()
        finally:
            with _lock:
                _pending.remove(key)

    with _lock:
        new_keys = [key for key in jobs if key not in _pending]
        if not new_keys:
            return []
        settings = get_settings()
        capacity = settings.background_job_workers + settings.background_job_queue_limit
        available = max(0, capacity - len(_pending))
        if allow_partial:
            new_keys = new_keys[:available]
            if not new_keys:
                return []
        elif len(new_keys) > available:
            raise JobQueueFull(batch_too_large=len(new_keys) > capacity)
        if _executor is None:
            _executor = ThreadPoolExecutor(
                max_workers=settings.background_job_workers, thread_name_prefix="media-job"
            )
        for key in new_keys:
            work, on_queued = jobs[key]
            _pending.add(key)
            try:
                on_queued()
                _executor.submit(run, key, work)
            except Exception:
                _pending.remove(key)
                raise
        return new_keys
