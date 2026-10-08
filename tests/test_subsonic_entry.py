"""Subsonic 入口：同步重活必须挪出事件循环。

实测风险：`/rest/{method}` 是 async 入口，但整个分发都是同步的（SQLite 查询、
getCoverArt 还会起 ffmpeg 子进程，超时 10s）。直接在事件循环里跑，几个并发的
封面请求就能让整台服务停摆——而 /rest 恰恰是客户端最常并发调用的入口。
"""

from __future__ import annotations

import asyncio
import os
import threading

os.environ.setdefault("AUTH_ENABLED", "false")

from starlette.requests import Request

from fryfrog.routers import music


def _request(query: bytes = b"f=json", method: str = "GET") -> Request:
    return Request(
        {
            "type": "http",
            "method": method,
            "path": "/rest/ping.view",
            "headers": [(b"host", b"testserver")],
            "query_string": query,
            "scheme": "http",
            "server": ("testserver", 80),
            "client": ("127.0.0.1", 12345),
        }
    )


class _Db:
    """占位 session：ping 路径不查库，线程池改动只关心「在哪跑」。"""

    def close(self):
        pass


def test_subsonic_dispatch_runs_off_the_event_loop(monkeypatch):
    """分发必须在线程池里执行：主线程（事件循环）不能被同步重活占用。"""
    calls: list[str] = []
    original = music._ss_dispatch

    def spy(db, method, params, request):
        calls.append(threading.current_thread().name)
        return original(db, method, params, request)

    monkeypatch.setattr(music, "_ss_dispatch", spy)

    response = asyncio.run(music.subsonic_entry("ping.view", _request(), _Db()))

    assert calls, "分发没有被调用"
    assert calls[0] != threading.main_thread().name, "同步分发不能跑在事件循环线程上"
    assert response.status_code == 200
    assert b'"status"' in response.body


def test_subsonic_auth_errors_return_error_envelope(monkeypatch):
    """线程池包裹不能把 SubsonicApiError 吞掉/变成 500：仍要返回错误信封。"""
    def boom(db, method, params, request):
        raise music.SubsonicApiError(music.ERROR_AUTH, "Wrong username or password")

    monkeypatch.setattr(music, "_ss_dispatch", boom)
    response = asyncio.run(music.subsonic_entry("ping.view", _request(), _Db()))
    assert response.status_code == 200
    assert b'"failed"' in response.body
    assert b'"code":40' in response.body
