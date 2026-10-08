"""stream 端点 Range 处理：不可满足的范围必须能识别出来（回 416），
不能把非法 Range 当「没有 Range」静默吐整文件 200（会破坏断点续传）。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from fryfrog.routers.video.playback import _parse_range


def test_missing_or_empty_range_is_none():
    """没有 Range 头/格式错 → None（调用方回 200 全量，不是 416）。"""
    assert _parse_range(None, 100) is None
    assert _parse_range("", 100) is None
    assert _parse_range("items=0-1", 100) is None  # 非 bytes 单位
    assert _parse_range("bytes=abc", 100) is None


def test_valid_ranges_parse():
    assert _parse_range("bytes=5-", 100) == (5, 99)
    assert _parse_range("bytes=0-0", 100) == (0, 0)
    assert _parse_range("bytes=-10", 100) == (90, 99)  # suffix
    assert _parse_range("bytes=50-200", 100) == (50, 99)  # end 越界裁剪
    # 多段取第一段
    assert _parse_range("bytes=0-4,20-29", 100) == (0, 4)


def test_unsatisfiable_range_marked_sentinel():
    """start 越界（>= file_size）或 start>end → 用哨兵 (-1,-1) 与「无 Range」区分开。"""
    assert _parse_range("bytes=100-", 100) == (-1, -1)  # start == size
    assert _parse_range("bytes=999999-", 100) == (-1, -1)  # start 远超 size
    assert _parse_range("bytes=20-10", 100) == (-1, -1)  # start > end
    assert _parse_range("bytes=999-888", 100) == (-1, -1)


def test_sentinel_never_produces_valid_stream():
    """哨兵不是合法范围：stream_video 必须先拦截 416，绝不落到 start/end 解包。"""
    rng = _parse_range("bytes=999999-", 100)
    assert rng == (-1, -1)
    # 与合法范围类型保持一致结构（元组），但值非法，调用方依赖这个显式判等
    assert rng[0] == -1 and rng[1] == -1