"""鉴权路径规则：写操作不能被「静态资源」正则提前放行。

实测故障：`POST /api/v1/video/{id}/covers` 被 `STATIC_RESOURCE_PATTERNS` 里的
`.*/cover` 命中，中间件直接放行且不写入当前用户，路由里的 `_require_admin`
读到匿名身份 → 永远 403（任何账号、任何版本都调不通）。
现该端点已改名为 `/refresh-covers`，本测试防止同类问题再次出现。
"""

from __future__ import annotations

import os
from collections.abc import Iterator

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest

from fryfrog.core.deps import (
    ADMIN_ONLY_READS,
    SIGNED_MEDIA_PATTERNS,
    STATIC_RESOURCE_PATTERNS,
    _matches_any,
    _requires_admin,
)
from fryfrog.main import app


def _walk_routes(routes, prefix: str = "") -> Iterator[tuple[list[str], str]]:
    """递归枚举路由（带前缀累积）。

    FastAPI 0.141 起用 `_IncludedRouter` 惰性挂载：`app.routes` / 各子 router 的
    顶层只有包装对象（`path` 为 None），真实路由在 `original_router.routes` 里，
    且自身不带前缀。不递归、不累积前缀就会扫到 0 条或相对路径（测试假通过）。
    """
    for route in routes:
        path = getattr(route, "path", "") or ""
        methods = sorted(getattr(route, "methods", None) or [])
        full = f"{prefix}{path}"
        if methods and path:
            yield methods, full
        nested = getattr(route, "original_router", None)
        if nested is not None:
            child_prefix = f"{prefix}{getattr(nested, 'prefix', '') or ''}"
            yield from _walk_routes(getattr(nested, "routes", []), child_prefix)


def _write_routes() -> list[tuple[list[str], str]]:
    out: list[tuple[list[str], str]] = []
    for methods, path in _walk_routes(app.routes):
        if not path.startswith("/api/"):
            continue
        if set(methods) & {"GET", "HEAD", "OPTIONS"}:
            continue
        out.append((methods, path))
    return out


def test_route_enumeration_is_not_empty():
    """防止路由枚举失效导致下面的护栏测试空跑（曾因 FastAPI 惰性挂载假通过）。"""
    writes = _write_routes()
    assert len(writes) > 20, f"只枚举到 {len(writes)} 个写路由，枚举逻辑可能失效"
    paths = {p for _, p in writes}
    # 路径模板带转换器后缀（如 {id:int}），用后缀匹配
    assert any(p.endswith("/refresh-covers") for p in paths), paths
    assert any(p.endswith("/cover") and "/video/" in p for p in paths), paths


def test_static_looking_write_routes_are_still_admin_guarded():
    """路径像静态资源（`.*/cover` 等）的写路由，必须仍然强制鉴权。

    中间件现在只对读请求按静态资源放行；写请求一律走鉴权，所以
    `POST /video/{id}/cover` 这类自然命名可以保留，但仍需管理员。
    """
    guarded = []
    for methods, path in _write_routes():
        if not _matches_any(path, STATIC_RESOURCE_PATTERNS):
            continue
        for m in methods:
            assert _requires_admin(m, path) is True, f"{m} {path} 未被鉴权拦截"
        guarded.append(path)
    # 至少要有 POST /cover 这条（新增的选图接口），否则说明枚举又失效了
    assert any(p.endswith("/cover") for p in guarded), guarded


def test_cover_refresh_route_is_admin_protected():
    """封面重拉必须走鉴权（改名前 /covers 被静态规则吞掉）。"""
    assert _requires_admin("POST", "/api/v1/video/1/refresh-covers") is True
    assert _matches_any("/api/v1/video/1/refresh-covers", STATIC_RESOURCE_PATTERNS) is False
    # 选图接口路径带 /cover，会命中静态规则——但中间件只对读请求放行
    assert _matches_any("/api/v1/video/1/cover", STATIC_RESOURCE_PATTERNS) is True
    assert _requires_admin("POST", "/api/v1/video/1/cover") is True
    assert _requires_admin("GET", "/api/v1/video/1/cover") is False  # 读：走签名


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


def test_cover_options_is_not_swallowed_by_static_rules():
    """回归：`.*/cover` 前缀匹配把 `cover-options` 当成静态图片放行，管理员也 403。

    同源故障此前逼得 `/covers` 改名；`cover-options`（GET，管理员选图用）
    一直没能用——中间件不写当前用户，路由里的 _require_admin 读到匿名身份。
    """
    path = "/api/v1/video/1/cover-options"
    assert _matches_any(path, STATIC_RESOURCE_PATTERNS) is False, "不能再被前缀吃掉"
    assert _matches_any(path, SIGNED_MEDIA_PATTERNS) is False, "也不是签名资源"
    # 该路径确实是已注册的 GET 路由：走正常鉴权后，路由内的 _require_admin 才拿得到用户
    registered = {p for _, p in _walk_routes(app.routes)}
    assert any(p.endswith("/cover-options") for p in registered), registered


def test_static_rules_are_anchored_at_the_end():
    """静态资源规则必须锚定结尾，否则任何新端点只要以这些词开头就会被误吞。"""
    for path in (
        "/api/v1/video/1/cover",
        "/api/v1/video/1/stream",
        "/api/v1/video/1/subtitles/a.srt",
        "/api/v1/music/songs/1/lyrics",
    ):
        assert _matches_any(path, STATIC_RESOURCE_PATTERNS), path
    for path in (
        "/api/v1/video/1/cover-options",
        "/api/v1/video/1/cover-upload",
        "/api/v1/video/1/covers",
        "/api/v1/video/1/stream-info",
    ):
        assert not _matches_any(path, STATIC_RESOURCE_PATTERNS), path
