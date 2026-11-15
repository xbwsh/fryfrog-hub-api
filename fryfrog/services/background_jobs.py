"""进程内后台任务队列，排队与执行中的任务共用去重键。"""
from concurrent.futures import ThreadPoolExecutor
from threading import Lock

from fryfrog.config import get_settings

_lock = Lock()
_pending: set[str] = set()
_executor: ThreadPoolExecutor | None = None


def enqueue_job(key: str, work, *, on_queued) -> bool:
    """只有首次提交会初始化进度；重复请求继续使用原任务的进度。"""
    global _executor

    def run():
        try:
            work()
        finally:
            with _lock:
                _pending.remove(key)

    with _lock:
        if key in _pending:
            return False
        if _executor is None:
            _executor = ThreadPoolExecutor(
                max_workers=get_settings().background_job_workers, thread_name_prefix="media-job"
            )
        _pending.add(key)
        try:
            on_queued()
            _executor.submit(run)
        except Exception:
            _pending.remove(key)
            raise
        return True
