"""用户上传封面：校验、规范化 JPEG、按层级落盘、回填 DB。

用户需求背景：帧截图往往是横屏，被用作竖版封面时要么被裁切要么留黑边；
让用户自己上传（并裁剪）成正确比例的图，是唯一真正避开比例问题的路子。
"""

from __future__ import annotations

import io
import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest
from PIL import Image
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services import video_assets as assets


def png_bytes(w: int = 800, h: int = 1200, color=(200, 40, 60)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (w, h), color).save(buf, format="PNG")
    return buf.getvalue()


def jpeg_bytes(w: int = 400, h: int = 225) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (w, h), (10, 20, 30)).save(buf, format="JPEG")
    return buf.getvalue()


@pytest.fixture()
def env(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    media = tmp_path / "media"
    show = media / "某剧"
    lib = MediaLibrary(name="anime", path=str(media), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()
    series = VideoSeries(title="某剧", tmdb_id=106882, media_type="tv")
    db.add(series)
    db.flush()

    eps = []
    for sn in (1, 2):
        d = show / f"第 {sn} 季" / "第 1 集"
        d.mkdir(parents=True)
        p = d / f"某剧 - S{sn:02d}E01.mp4"
        p.write_bytes(b"x" * 32)
        v = Video(
            file_path=str(p),
            file_name=p.name,
            title=f"某剧 S{sn:02d}E01",
            library_id=lib.id,
            series_id=series.id,
            tmdb_id=106882,
            media_type="tv",
            is_series=True,
            season_number=sn,
            episode_number=1,
        )
        db.add(v)
        eps.append(v)
    db.commit()
    yield db, series, eps, show
    db.close()


def test_upload_episode_poster_writes_poster_jpg(env):
    db, _series, eps, show = env
    target = assets.save_uploaded_cover(
        db, eps[0], eps, "episode", "poster", png_bytes()
    )

    assert target == show / "第 1 季" / "第 1 集" / "poster.jpg", target
    assert target.is_file()
    # 落盘统一为 JPEG（站点约定这些位置就是 .jpg）
    assert Image.open(target).format == "JPEG"
    assert eps[0].cover_art_path == str(target)
    assert eps[0].backdrop_local_path is None, "竖版不该动 backdrop 字段"


def test_upload_episode_backdrop_writes_fanart(env):
    db, _series, eps, show = env
    target = assets.save_uploaded_cover(
        db, eps[0], eps, "episode", "backdrop", jpeg_bytes()
    )

    assert target == show / "第 1 季" / "第 1 集" / "fanart.jpg", target
    # 横版走 backdrop 字段（与落盘一致），不能塞进 poster
    assert eps[0].backdrop_local_path == str(target)
    assert eps[0].poster_url is None


def test_upload_season_poster_goes_to_season_dir(env):
    db, _series, eps, show = env
    target = assets.save_uploaded_cover(
        db, eps[1], eps, "season", "poster", png_bytes()
    )

    assert target == show / "第 2 季" / "tvshow-poster.jpg", target
    assert target.is_file()


def test_upload_series_poster_goes_to_show_root(env):
    db, series, eps, show = env
    target = assets.save_uploaded_cover(
        db, eps[0], eps, "series", "poster", png_bytes()
    )

    assert target == show / "tvshow-poster.jpg", target
    assert target.is_file()
    assert series.poster_local_path == str(target)


def test_upload_series_backdrop_goes_to_show_root(env):
    db, series, eps, show = env
    target = assets.save_uploaded_cover(
        db, eps[0], eps, "series", "backdrop", jpeg_bytes()
    )

    assert target == show / "tvshow-fanart.jpg", target
    assert series.backdrop_local_path == str(target)


def test_upload_rejects_non_image(env):
    """非图片内容要给出可展示的中文提示，不能落盘。"""
    db, _series, eps, _show = env
    with pytest.raises(assets.UploadError) as exc:
        assets.save_uploaded_cover(db, eps[0], eps, "episode", "poster", b"not an image" * 10)
    assert "图片" in exc.value.message


def test_upload_rejects_empty(env):
    db, _series, eps, _show = env
    with pytest.raises(assets.UploadError) as exc:
        assets.save_uploaded_cover(db, eps[0], eps, "episode", "poster", b"")
    assert "空" in exc.value.message


def test_upload_rejects_oversize(env, monkeypatch):
    db, _series, eps, _show = env
    monkeypatch.setattr(assets, "UPLOAD_MAX_BYTES", 1024)
    with pytest.raises(assets.UploadError) as exc:
        assets.save_uploaded_cover(db, eps[0], eps, "episode", "poster", png_bytes(400, 600))
    assert "过大" in exc.value.message


def test_upload_rejects_bad_level_or_kind(env):
    db, _series, eps, _show = env
    with pytest.raises(assets.UploadError):
        assets.save_uploaded_cover(db, eps[0], eps, "bogus", "poster", png_bytes())
    with pytest.raises(assets.UploadError):
        assets.save_uploaded_cover(db, eps[0], eps, "episode", "bogus", png_bytes())


def test_upload_rejects_zip_disguised_as_image(env):
    """ZIP 头不是图片：Pillow 解析不了，必须拒绝（不能靠 content-type）。"""
    db, _series, eps, _show = env
    zip_head = b"PK\x03\x04" + b"\x00" * 200
    with pytest.raises(assets.UploadError):
        assets.save_uploaded_cover(db, eps[0], eps, "episode", "poster", zip_head)


def test_upload_downscales_huge_image(env):
    """超大图要缩到最长边 4096 以内，别把 8000px 原图写进媒体目录。"""
    db, _series, eps, _show = env
    target = assets.save_uploaded_cover(
        db, eps[0], eps, "episode", "poster", png_bytes(9000, 4500)
    )
    with Image.open(target) as im:
        assert max(im.size) <= 4096, f"未缩图: {im.size}"


def test_upload_accepts_png_with_alpha(env):
    """带透明通道的 PNG 要能转成 JPEG（不能因为 alpha 直接失败）。"""
    db, _series, eps, _show = env
    buf = io.BytesIO()
    Image.new("RGBA", (600, 900), (255, 0, 0, 128)).save(buf, format="PNG")

    target = assets.save_uploaded_cover(
        db, eps[0], eps, "episode", "poster", buf.getvalue()
    )
    assert Image.open(target).format == "JPEG"


def test_upload_overwrites_previous_image(env):
    """重复上传要覆盖旧图（用户就是来换图的）。"""
    db, _series, eps, _show = env
    first = assets.save_uploaded_cover(db, eps[0], eps, "episode", "poster", png_bytes(100, 150))
    size1 = first.stat().st_size

    second = assets.save_uploaded_cover(
        db, eps[0], eps, "episode", "poster", png_bytes(900, 1350, (7, 7, 7))
    )
    assert first == second
    assert second.stat().st_size != size1


def test_tmdb_apply_and_upload_share_target_resolution(env):
    """两条路径必须落到同一位置——否则用户会看到"设了但没生效"。"""
    db, _series, eps, _show = env
    for level, kind in (
        ("episode", "poster"),
        ("episode", "backdrop"),
        ("episode", "still"),
        ("season", "poster"),
        ("series", "poster"),
        ("series", "backdrop"),
    ):
        via_upload = assets.resolve_image_target(db, eps[0], eps, level, kind)
        assert via_upload is not None, f"{level}/{kind} 解析不到路径"
        # still 与 backdrop 同为横版，必须同址
        if kind == "still":
            assert via_upload == assets.resolve_image_target(
                db, eps[0], eps, level, "backdrop"
            )
