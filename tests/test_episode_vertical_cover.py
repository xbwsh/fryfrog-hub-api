"""分集竖封面不得回退成 16:9 单集剧照（实测「慎重勇者」全 12 集）。

分集的 `poster_url` 按设计就是 TMDB 单集**剧照**（`video_scrape.
_apply_episode_detail` 写 w500 still），拿它当竖封面返回，前端在 2:3 区域里
裁出来就是一张横图。修复前：季目录/剧根目录的竖海报都找不到时直接返回它。

同时覆盖落盘侧：`download_all_covers` 不得把这张横图写进 `poster.jpg`
（那是竖封面槽位）。
"""
from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.routers.video import assets as assets_router
from fryfrog.routers.video.assets import get_cover
from fryfrog.services.video_assets import download_all_covers


def _layout(tmp_path):
    """剧名/第 1 季/第 1 集/x.mkv：分集 poster_url 是剧照，剧集行有竖海报。

    刻意让季目录/剧根目录都没有 tvshow-poster.jpg，复现「慎重勇者」的处境：
    本地竖图全落空，只剩 TMDB 兜底可走。
    """
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    media = tmp_path / "media"
    show = media / "某剧"
    lib = MediaLibrary(name="剧库", path=str(media), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()
    series = VideoSeries(
        title="某剧", media_type="tv", poster_url="http://img/series-poster.jpg"
    )
    db.add(series)
    db.flush()
    ep_dir = show / "第 1 季" / "第 1 集"
    ep_dir.mkdir(parents=True)
    media_file = ep_dir / "x.mkv"
    media_file.write_bytes(b"x" * 16)
    video = Video(
        file_path=str(media_file),
        file_name=media_file.name,
        title="第 1 集",
        library_id=lib.id,
        series_id=series.id,
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
        poster_url="http://img/episode-still.jpg",  # 16:9 单集剧照
    )
    db.add(video)
    db.commit()
    return db, series, video


def test_episode_cover_falls_back_to_series_poster(tmp_path, monkeypatch):
    """本地竖图都缺失时，TMDB 兜底要取**剧集**竖海报，不能取分集剧照。"""
    db, _series, video = _layout(tmp_path)
    asked: list[str] = []

    def fake_fetch(url: str) -> bytes:
        asked.append(url)
        return b"SERIES-POSTER"

    monkeypatch.setattr(assets_router.assets, "fetch_tmdb_image", fake_fetch)

    resp = get_cover(db, video.id)

    assert asked == ["http://img/series-poster.jpg"]
    assert resp.body == b"SERIES-POSTER"


def _fake_download(size):
    from PIL import Image

    def download(url, dest, force=False):
        Path(dest).parent.mkdir(parents=True, exist_ok=True)
        Image.new("RGB", size).save(dest)
        return True

    return download


def test_download_all_covers_skips_landscape_episode_poster(tmp_path, monkeypatch):
    """分集只拿到横图时不留 poster.jpg，也不回填 cover_art_path。"""
    db, _series, video = _layout(tmp_path)
    target = Path(video.file_path).parent / "poster.jpg"
    monkeypatch.setitem(
        download_all_covers.__globals__, "download_image", _fake_download((500, 281))
    )

    assert download_all_covers(db, video) is False
    assert not target.exists()
    assert video.cover_art_path is None


def test_download_all_covers_keeps_portrait_episode_poster(tmp_path, monkeypatch):
    """真·竖图照旧落盘并回填（上传/TMDB 海报走的就是这条路）。"""
    db, _series, video = _layout(tmp_path)
    target = Path(video.file_path).parent / "poster.jpg"
    monkeypatch.setitem(
        download_all_covers.__globals__, "download_image", _fake_download((500, 750))
    )

    assert download_all_covers(db, video) is True
    assert target.is_file()
    assert video.cover_art_path == str(target)
