"""TMDB 分层图片：总览 / 季 / 单集 × 海报 / 背景图 / 剧照。

重点是**落盘位置**：实际媒体目录是 `<库>/<剧名>/第 N 季/`，而 get_metadata_dir
重建的是 `<库>/<剧名>/第 N 季/第 M 集/`——两者层级不同，写错就会把图丢到
不存在或错误的目录里。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services import video_assets as assets


@pytest.fixture()
def env(tmp_path, monkeypatch):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    lib_root = tmp_path / "media"
    show = lib_root / "某剧"
    season_dir = show / "第 1 季"
    season_dir.mkdir(parents=True)
    (season_dir / "某剧 - S01E01.mp4").write_bytes(b"x" * 64)
    (season_dir / "某剧 - S01E02.mp4").write_bytes(b"x" * 64)

    lib = MediaLibrary(name="L", path=str(lib_root), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()
    series = VideoSeries(title="某剧", tmdb_id=555)
    db.add(series)
    db.flush()
    eps = []
    for i in (1, 2):
        v = Video(
            file_path=str(season_dir / f"某剧 - S01E0{i}.mp4"),
            file_name=f"某剧 - S01E0{i}.mp4",
            title=f"某剧 S01E0{i}",
            library_id=lib.id,
            series_id=series.id,
            tmdb_id=555,
            media_type="tv",
            is_series=True,
            season_number=1,
            episode_number=i,
        )
        db.add(v)
        eps.append(v)
    db.commit()

    # 不真的联网下载，只记录目标路径
    written: list[Path] = []

    def fake_download(url, target: Path, force: bool = False):
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"img")
        written.append(target)
        return True

    monkeypatch.setattr(assets, "download_image", fake_download)
    return db, lib, series, eps, season_dir, show, written


def test_series_level_writes_to_show_root(env):
    db, _lib, series, eps, _season_dir, show, written = env
    target = assets.apply_tmdb_image(db, eps[0], eps, "series", "poster", "/p.jpg")
    assert target == show / "tvshow-poster.jpg", target
    assert target.read_bytes() == b"img"
    assert str(series.poster_local_path) == str(target)

    target2 = assets.apply_tmdb_image(db, eps[0], eps, "series", "backdrop", "/b.jpg")
    assert target2 == show / "tvshow-fanart.jpg", target2
    assert str(series.backdrop_local_path) == str(target2)


def test_season_level_writes_to_season_dir(env):
    db, _lib, _series, eps, season_dir, _show, _written = env
    target = assets.apply_tmdb_image(db, eps[0], eps, "season", "poster", "/sp.jpg")
    assert target == season_dir / "tvshow-poster.jpg", target
    assert target.is_file()


def test_episode_still_writes_to_fanart_not_poster(env):
    """回归：分集层的剧照（still）是横版图，必须落 fanart.jpg。

    实测 bug：原判断写成 `if kind == "backdrop" else get_poster_path(...)`，
    而分集层只提供 `still`（见 tmdb_image_options），于是剧照掉进 poster 分支：
    存成竖版 poster.jpg、DB 也只更新 poster_url —— 结果竖封面仍走季海报、
    横屏位置没变，用户感觉"设了没生效"。
    """
    db, _lib, _series, eps, season_dir, _show, _written = env
    target = assets.apply_tmdb_image(db, eps[0], eps, "episode", "still", "/still.jpg")

    assert target == season_dir / "fanart.jpg", f"剧照应落 fanart.jpg，实际 {target}"
    assert not (season_dir / "poster.jpg").exists(), "不该写出竖版 poster.jpg"
    assert str(eps[0].backdrop_local_path) == str(target), "应更新 backdrop 字段"
    assert eps[0].backdrop_url == "/still.jpg"
    assert not eps[0].poster_url, "不该把剧照塞进 poster_url"


def test_episode_level_writes_next_to_the_episode(env):
    db, _lib, _series, eps, season_dir, _show, _written = env
    poster = assets.apply_tmdb_image(db, eps[0], eps, "episode", "poster", "/ep.jpg")
    assert poster == season_dir / "poster.jpg", poster
    assert str(eps[0].cover_art_path) == str(poster)

    fanart = assets.apply_tmdb_image(db, eps[0], eps, "episode", "backdrop", "/ef.jpg")
    assert fanart == season_dir / "fanart.jpg", fanart
    assert str(eps[0].backdrop_local_path) == str(fanart)
    # 只影响当前分集，不污染同季其他集
    assert eps[1].backdrop_local_path is None


def test_apply_rejects_bad_level_or_kind(env):
    db, _lib, _series, eps, _season_dir, _show, _written = env
    assert assets.apply_tmdb_image(db, eps[0], eps, "bogus", "poster", "/x.jpg") is None
    assert assets.apply_tmdb_image(db, eps[0], eps, "series", "bogus", "/x.jpg") is None
    assert assets.apply_tmdb_image(db, eps[0], eps, "series", "poster", "") is None


def test_image_options_empty_without_tmdb_id():
    """没有 TMDB id 时不发请求，直接空结果。"""
    assert assets.tmdb_image_options(None, "series") == {}
    assert assets.tmdb_image_options(0, "episode", season=1, episode=1) == {}
    assert assets.tmdb_image_options(123, "bogus") == {}


def test_image_options_normalizes_and_sorts(monkeypatch):
    """候选按票数降序，并带上代理预览 URL 与尺寸信息。"""
    class _FakeClient:
        def get_tv_images(self, tmdb_id):
            return {
                "posters": [
                    {"file_path": "/low.jpg", "vote_count": 1, "width": 500, "height": 750},
                    {"file_path": "/high.jpg", "vote_count": 9, "width": 1000, "height": 1500},
                    {"file_path": None, "vote_count": 99},
                ],
                "backdrops": [
                    {"file_path": "/bd.jpg", "vote_count": 3, "width": 1920, "height": 1080},
                ],
            }

    monkeypatch.setattr(
        "fryfrog.services.tmdb.TmdbClient", lambda: _FakeClient()
    )
    out = assets.tmdb_image_options(555, "series")
    assert [o["filePath"] for o in out["poster"]] == ["/high.jpg", "/low.jpg"]
    assert out["poster"][0]["kind"] == "poster"
    assert "/tmdb-image-proxy" in out["poster"][0]["url"]
    assert "size=w342" in out["poster"][0]["url"]  # 竖图用小尺寸预览
    assert [o["filePath"] for o in out["backdrop"]] == ["/bd.jpg"]
    assert "size=w780" in out["backdrop"][0]["url"]  # 横图用大尺寸预览


def test_season_and_episode_levels_need_numbers(monkeypatch):
    monkeypatch.setattr("fryfrog.services.tmdb.TmdbClient", lambda: object())
    assert assets.tmdb_image_options(555, "season") == {}
    assert assets.tmdb_image_options(555, "episode", season=1) == {}


def test_episode_stills_fall_back_to_episode_still_path(monkeypatch):
    """`/images` 返回空时，回退用单集详情自带的 still_path。"""

    class _FakeClient:
        def get_episode_images(self, tv_id, season, episode):
            return {"id": 1, "stills": []}  # 空

        def get_episode(self, tv_id, season, episode):
            return {"still_path": "/real-still.jpg", "vote_count": 3}

        def get_tv_images(self, tmdb_id):
            return {"backdrops": [{"file_path": "/tv-backdrop.jpg", "vote_count": 5}]}

    monkeypatch.setattr("fryfrog.services.tmdb.TmdbClient", lambda: _FakeClient())
    out = assets.tmdb_image_options(555, "episode", season=1, episode=2)
    paths = [o["filePath"] for o in out["still"]]
    assert paths[0] == "/real-still.jpg", f"没回退到单集 still_path: {paths}"
    assert out["still"][0]["kind"] == "still"
    assert "size=w780" in out["still"][0]["url"]
    # 剧集主横图作为备选排在后面（单集层最终也落 fanart.jpg）
    assert "/tv-backdrop.jpg" in paths
    assert paths.index("/tv-backdrop.jpg") > 0


def test_episode_uses_all_real_stills_and_sorts_by_votes(monkeypatch):
    """`/images` 有剧照时全部用上、按票数降序，且不再塞 still_path 兜底。"""

    class _FakeClient:
        def get_episode_images(self, tv_id, season, episode):
            # `/images` 顶层直接是 stills（实测形状）
            return {
                "id": 1,
                "stills": [
                    {"file_path": "/a.jpg", "vote_count": 1, "width": 1280, "height": 720},
                    {"file_path": "/b.jpg", "vote_count": 9, "width": 1920, "height": 1080},
                    {"file_path": "/c.jpg", "vote_count": 4, "width": 1024, "height": 576},
                ],
            }

        def get_episode(self, tv_id, season, episode):
            raise AssertionError("有剧照时不该再去取单集详情兜底")

        def get_tv_images(self, tmdb_id):
            return {}

    monkeypatch.setattr("fryfrog.services.tmdb.TmdbClient", lambda: _FakeClient())
    out = assets.tmdb_image_options(555, "episode", season=1, episode=2)
    paths = [o["filePath"] for o in out["still"]]
    assert paths == ["/b.jpg", "/c.jpg", "/a.jpg"], paths  # 票数降序
    assert out["still"][0]["width"] == 1920


def test_episode_images_request_carries_language_filter():
    """回归：不传 include_image_language 时 TMDB 会把剧照全部过滤掉。

    实测 `/tv/73281/season/1/episode/1/images` 无参数返回 `{"stills": []}`，
    带 `zh-CN,zh,en,null,ja` 返回 32 张。绝大多数 still 的 iso_639_1 为 null，
    所以语言列表里必须显式含 `null`。
    """
    import inspect

    from fryfrog.services import tmdb as tmdb_mod

    src = inspect.getsource(tmdb_mod.TmdbClient.get_episode_images)
    assert "/images" in src, "单集剧照要用 /images 端点"
    assert "IMAGE_LANGS" in src, "必须带语言过滤参数"
    assert "null" in tmdb_mod.IMAGE_LANGS, "语言列表必须包含 null"
    for name in (
        "get_tv_images",
        "get_season_images",
        "get_movie_images",
    ):
        body = inspect.getsource(getattr(tmdb_mod.TmdbClient, name))
        assert "IMAGE_LANGS" in body, f"{name} 漏了语言过滤参数"
