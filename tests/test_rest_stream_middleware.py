"""`/rest/stream` 必须由 Subsonic 自身鉴权，不能被中间件的签名要求拦截。

实测故障：auth_middleware 先做静态资源/签名判断，`/rest/stream` 以 "stream"
结尾命中 `.*stream$` → 未带 exp/sig 的 Subsonic 客户端（协议本身就是 u/p/t/s
鉴权）一律 401，音乐播放全部失效；ping 等不以静态词结尾的方法不受影响，
所以问题长期只表现为「客户端播不了」而非「整个 /rest 挂了」。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest
from fastapi.testclient import TestClient

from fryfrog.main import app


@pytest.fixture()
def client():
    return TestClient(app, raise_server_exceptions=False)


def test_rest_stream_not_blocked_by_signature_check(client):
    """不带 sig 的 /rest/stream 要进 Subsonic 分发：错误也应返回协议信封
    （HTTP 200 + subsonic-response error），而不是中间件的裸 401 JSON。"""
    r = client.get(
        "/rest/stream",
        params={"u": "nobody", "p": "wrong", "id": "tr-1", "v": "1.16.1", "c": "pytest"},
    )
    assert r.status_code == 200
    assert "subsonic-response" in r.text
    assert '"success":false' not in r.text  # 不是中间件的 ApiResponse 错误体

    r2 = client.get("/rest/stream")
    assert r2.status_code == 200
    assert "subsonic-response" in r2.text


def test_signed_media_paths_still_require_signature(client):
    """修复不得放宽真正的签名媒体路径：/api/v1/video/N/stream 未签名仍 401。"""
    r = client.get("/api/v1/video/1/stream")
    assert r.status_code == 401
