"""扫描删除的两重保护：宽限期 + 磁盘异常护栏（comic/ebook/audiobook 共用）。

视频库与音乐库各自内联了同款逻辑；这三类媒体的删除路径较薄，共用一份，
避免三份拷贝将来走样。语义与 video_scan._guard_allows 一致：

1. 宽限期（scan_missing_grace_seconds）：拷入中/挂载抖动不立刻删行；
2. 磁盘异常护栏（scan_guard_min_ratio）：本轮实见文件数低于上轮存量的
   比例时整轮暂缓删除（seen == 0 的空库也拦），且冻结 last_count 基线
   ——否则下一轮基线变小、护栏失效，挂载掉线一轮就清空整个库。
   确认磁盘正常后可临时把 SCAN_GUARD_MIN_RATIO 设为 0 放行。
"""

from __future__ import annotations

from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from fryfrog.config import get_settings
from fryfrog.models.library import SystemSetting


def _to_int(value: str | None) -> int | None:
    try:
        return int(value) if value not in (None, "") else None
    except ValueError:
        return None


def read_last_count(db: Session, prefix: str, library_id: int) -> int | None:
    row = db.scalar(
        select(SystemSetting.value).where(SystemSetting.key == f"{prefix}.last_count.{library_id}")
    )
    return _to_int(row)


def write_last_count(db: Session, prefix: str, library_id: int, count: int) -> None:
    key = f"{prefix}.last_count.{library_id}"
    row = db.scalar(select(SystemSetting).where(SystemSetting.key == key))
    if row is None:
        db.add(SystemSetting(key=key, value=str(count), description="扫描簿记"))
    else:
        row.value = str(count)
    db.flush()


def grace_threshold() -> datetime:
    """早于该时刻的缺失标记才允许删除；现在 - 宽限期。"""
    grace = max(float(get_settings().scan_missing_grace_seconds), 0.0)
    return datetime.now() - timedelta(seconds=grace)


def guard_allows(seen: int, previous: int | None) -> bool:
    """本轮是否放行删除；拦截时调用方必须冻结 last_count 基线。"""
    ratio = max(min(float(get_settings().scan_guard_min_ratio), 1.0), 0.0)
    return not (previous and ratio > 0 and seen < previous * ratio)
