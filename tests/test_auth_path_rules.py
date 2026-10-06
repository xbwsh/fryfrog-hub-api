"""鉴权路径规则：写操作不能被「静态资源」正则提前放行。

实测故障：`POST /api/v1/video/{id}/covers` 被 `STATIC_RESOURCE_PATTERNS` 里的
`.*/cover` 命中，中间件直接放行且不写入当前用户，路由里的 `_require_admin`
读到匿名身份 → 永远 403（任何账号、任何版本都调不通）。
现该端点已改名为 `/refresh-covers`，本测试防止同类问题再次出现。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest

from fryfrog.core.deps import (
    ADMIN_ONLY_READS,
    STATIC_RESOURCE_PATTERNS,
    _matches_any,
    _requires_admin,
)
from fryfrog.main import app


def _write_routes() -> list[tuple[list[str], str]]:
    out: list[tuple[list[str], str]] = []
    for route in app.routes:
        methods = getattr(route, "methods", None) or set()
        path = getattr(route, "path", "")
        if not path.startswith("/api/") or not methods:
            continue
        if methods & {"GET", "HEAD", "OPTIONS"}:
            continue
        out.append((sorted(methods), path))
    return out


def test_no_write_route_looks_like_a_static_resource():
    """任何写操作路由都不能匹配静态资源规则（否则会被跳过鉴权）。"""
    offenders = [
        (methods, path)
        for methods, path in _write_routes()
        if _matches_any(path, STATIC_RESOURCE_PATTERNS)
    ]
    assert offenders == [], f"这些写操作会被当作静态资源放行: {offenders}"


def test_cover_refresh_route_is_admin_protected():
    """封面重拉必须走鉴权（改名前 /covers 被静态规则吞掉）。"""
    assert _requires_admin("POST", "/api/v1/video/1/refresh-covers") is True
    assert _matches_any("/api/v1/video/1/refresh-covers", STATIC_RESOURCE_PATTERNS) is False

    # 旧的 /covers 路径确实属于静态资源规则命中范围——记录这个坑
    assert _matches_any("/api/v1/video/1/covers", STATIC_RESOURCE_PATTERNS) is True


def test_static_media_reads_are_skipped_only_when_signed():
    """静态图片/流：签名由中间件校验，未签名直接 401（行为不变）。"""
    assert _matches_any("/api/v1/video/1/cover", STATIC_RESOURCE_PATTERNS) is True
    signed = [
        p
        for p in ("/api/v1/video/1/cover", "/api/v1/video/1/stream", "/api/v1/music/songs/1/cover")
        if _matches_any(p, STATIC_RESOURCE_PATTERNS)
    ]
    assert len(signed) == 3


def test_admin_only_reads_still_guarded():
    """GET 类管理员专属读取仍然受限。"""
    for path in ("/api/v1/settings", "/api/v1/logs"):
        assert _requires_admin("GET", path) is True
    assert any(p.startswith("^/api/v1/settings") for p in ADMIN_ONLY_READS)


@pytest.mark.parametrize(
    "path",
    [
        "/api/v1/video/1/favorite",
        "/api/v1/video/1/progress",
        "/api/v1/video/1/watched",
        "/api/v1/music/scrobble",
    ],
)
def test_user_owned_mutations_stay_allowed_for_normal_users(path: str):
    """普通用户自己的数据写操作不能被收紧。"""
    assert _requires_admin("POST", path) is False
