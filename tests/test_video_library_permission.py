"""视频域库隔离：受限用户不能靠 id 越过授权访问隐藏库。

实测故障：playlist.m3u / stream / subtitles 等播放域端点只调 get_video，
不做任何可见性检查——受限用户遍历 id 就能拿到隐藏库全部分集的签名流 URL。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from starlette.requests import Request

from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.core.security import set_current_user_id
from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary, UserLibrary
from fryfrog.models.user import User, UserRole
from fryfrog.models.video import Video, VideoSeries
from fryfrog.routers.video import playback
from fryfrog.services import video_service as vs


def _setup(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    allowed = MediaLibrary(name="可见库", path=str(tmp_path / "a"), type="VIDEO", enabled=True)
    hidden = MediaLibrary(name="隐藏库", path=str(tmp_path / "b"), type="VIDEO", enabled=True)
    db.add_all([allowed, hidden])
    db.flush()

    visible = Video(file_path=str(tmp_path / "a" / "v.mkv"), file_name="v.mkv", title="可见片", library_id=allowed.id)
    secret = Video(file_path=str(tmp_path / "b" / "s.mkv"), file_name="s.mkv", title="隐藏片", library_id=hidden.id)
    db.add_all([visible, secret])
    db.flush()

    secret_series = VideoSeries(title="隐藏剧", media_type="TV")
    db.add(secret_series)
    db.flush()
    db.add(Video(file_path=str(tmp_path / "b" / "e1.mkv"), file_name="e1.mkv", title="隐藏剧E1", library_id=hidden.id, series_id=secret_series.id))
    db.flush()

    user = User(username="limited", password_hash="x", role=UserRole.USER)
    db.add(user)
    db.flush()
    db.add(UserLibrary(user_id=user.id, library_id=allowed.id))
    db.commit()
    return db, allowed, hidden, visible, secret, secret_series, user.id


def _request() -> Request:
    return Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/api/v1/video/1/playlist.m3u",
            "headers": [],
            "query_string": b"",
            "scheme": "http",
            "server": ("testserver", 80),
            "client": ("127.0.0.1", 12345),
        }
    )


def test_hidden_video_denied_across_playback_endpoints(tmp_path):
    db, _allowed, _hidden, visible, secret, _series, uid = _setup(tmp_path)
    set_current_user_id(uid)
    try:
        assert vs.get_video(db, visible.id).id == visible.id

        for call in (
            lambda: vs.get_video(db, secret.id),
            lambda: playback.get_playlist(db, secret.id, _request()),
            lambda: playback.list_subtitles(db, secret.id),
            lambda: playback.get_subtitle(db, secret.id, "x.srt"),
            lambda: vs.get_series(db, _series.id),
        ):
            with pytest.raises(ResourceNotFoundException):
                call()
    finally:
        set_current_user_id(None)


def test_admin_and_background_are_unaffected(tmp_path):
    db, _allowed, _hidden, _visible, secret, series, uid = _setup(tmp_path)

    # 后台线程/未认证（无当前用户）照常可读：扫描与刮削依赖这条路径
    assert vs.get_video(db, secret.id).id == secret.id
    assert vs.get_series(db, series.id).id == series.id

    admin = User(username="root", password_hash="x", role=UserRole.ADMIN)
    db.add(admin)
    db.commit()
    set_current_user_id(admin.id)
    try:
        assert vs.get_video(db, secret.id).id == secret.id
    finally:
        set_current_user_id(None)

    set_current_user_id(0)  # ANONYMOUS_ID 之外的无效用户按受限处理
    try:
        with pytest.raises(ResourceNotFoundException):
            vs.get_video(db, secret.id)
    finally:
        set_current_user_id(None)
