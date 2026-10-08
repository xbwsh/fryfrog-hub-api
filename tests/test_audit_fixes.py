"""本轮审计修复的回归测试。

覆盖：剧名目录改名前缀边界（P0）、get_series 跨库可见性、歌词按文件名匹配、
natural_key 超长数字、用户管理端点 admin 门禁、有声书 Range 416。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.core.deps import _requires_admin
from fryfrog.core.natural_order import natural_key
from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video
from fryfrog.services import video_service as vs


def _db():
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)()


def test_rename_show_dir_respects_prefix_boundary(tmp_path):
    """`/data/Show` 改名不能连带改写 `/data/Show2024` 的记录。

    无边界版本会把 Show2024 的 file_path 改成指向不存在的路径，下一轮
    扫描判缺失、宽限期满删行（连带进度/收藏）。
    """
    lib_path = tmp_path / "a"
    (lib_path / "Show").mkdir(parents=True)
    (lib_path / "Show" / "movie.mkv").write_bytes(b"x")
    (lib_path / "Show2024").mkdir(parents=True)
    (lib_path / "Show2024" / "other.mkv").write_bytes(b"x")
    db = _db()
    lib = MediaLibrary(name="库", path=str(lib_path), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()
    va = Video(
        file_path=str(lib_path / "Show" / "movie.mkv"),
        file_name="movie.mkv",
        title="新名",
        library_id=lib.id,
    )
    vb = Video(
        file_path=str(lib_path / "Show2024" / "other.mkv"),
        file_name="other.mkv",
        title="另一部",
        library_id=lib.id,
    )
    db.add_all([va, vb])
    db.commit()

    assert vs.rename_show_dir(db, va) is True
    db.expire_all()
    assert (lib_path / "新名" / "movie.mkv").exists()
    assert str(lib_path / "新名" / "movie.mkv") == db.get(Video, va.id).file_path
    # 关键断言：同前缀的 Show2024 记录必须原封不动
    assert db.get(Video, vb.id).file_path == str(lib_path / "Show2024" / "other.mkv")
    assert (lib_path / "Show2024" / "other.mkv").exists()


def test_get_series_visible_when_any_episode_in_allowed_library():
    """跨库系列（merge_duplicate_series 会按 tmdb_id 跨库合并）：只要有一集
    落在可见库，该系列对受限用户可见——不能取决于任意一行的 library_id。"""
    db = _db()
    allowed = MediaLibrary(name="可见", path="/tmp/a", type="VIDEO", enabled=True)
    hidden = MediaLibrary(name="隐藏", path="/tmp/b", type="VIDEO", enabled=True)
    db.add_all([allowed, hidden])
    db.flush()
    from fryfrog.models.video import VideoSeries

    series = VideoSeries(title="跨库剧", media_type="TV")
    db.add(series)
    db.flush()
    db.add_all(
        [
            Video(file_path="/tmp/a/e1.mkv", file_name="e1.mkv", title="E1",
                  library_id=allowed.id, series_id=series.id),
            Video(file_path="/tmp/b/e2.mkv", file_name="e2.mkv", title="E2",
                  library_id=hidden.id, series_id=series.id),
        ]
    )
    from fryfrog.models.user import User, UserRole
    from fryfrog.models.library import UserLibrary
    from fryfrog.core.security import set_current_user_id

    user = User(username="limited", password_hash="x", role=UserRole.USER)
    db.add(user)
    db.flush()
    db.add(UserLibrary(user_id=user.id, library_id=allowed.id))
    db.commit()

    set_current_user_id(user.id)
    try:
        got = vs.get_series(db, series.id)
        assert got is not None and got.id == series.id
    finally:
        set_current_user_id(None)


def test_lyrics_require_same_stem(tmp_path):
    """目录里 track01.lrc 不得配给 track05.mp3（原实现任意 .lrc 命中）。"""
    from fryfrog.services.music_scan import _find_lyrics

    d = tmp_path / "album"
    d.mkdir()
    for i in range(1, 11):
        (d / f"track{i:02d}.lrc").write_text(f"[00:00.00] {i}", encoding="utf-8")
    song = d / "track05.mp3"
    song.write_bytes(b"x")
    assert _find_lyrics(d, song) == str(d / "track05.lrc")


def test_natural_key_survives_long_digit_runs():
    """>=4300 位数字段曾让 int() 抛 ValueError，拖垮所有按 natural_key
    排序的接口。"""
    huge = "page_" + "9" * 5000 + ".jpg"
    key = natural_key(huge)  # 不应抛异常
    assert natural_key("page_2.jpg") < key


def test_users_endpoints_require_admin():
    """/users 列表与 /users/{id}/* 只对 admin 开放；/users/me 系列保持开放。"""
    assert _requires_admin("GET", "/api/v1/users")
    assert _requires_admin("GET", "/api/v1/users/3/libraries")
    assert not _requires_admin("GET", "/api/v1/users/me")
    assert _requires_admin("PUT", "/api/v1/users/3")  # 非 USER_OWNED 写操作
    assert not _requires_admin("PUT", "/api/v1/users/me/password")


def test_audiobook_range_unsatisfiable_is_416(tmp_path):
    """start 越界必须 416（旧实现 clamp 后回 206 空体 + 非法 Content-Range）。"""
    from fryfrog.routers.audiobook import _file_stream

    f = tmp_path / "book.m4b"
    f.write_bytes(b"x" * 100)
    resp = _file_stream(f, "audio/mp4", "bytes=1000000-")
    assert resp.status_code == 416
    assert resp.headers["content-range"] == "bytes */100"
    # 正常范围仍然 206
    ok = _file_stream(f, "audio/mp4", "bytes=10-19")
    assert ok.status_code == 206
