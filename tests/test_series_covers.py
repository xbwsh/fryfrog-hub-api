from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from fryfrog.models.video import Video
from fryfrog.services.video_assets import download_all_covers, find_shared_vertical_poster, prune_private_vertical_cover
from fryfrog.services.video_service import (
    find_series_root_file,
    get_series_root_dir,
    series_root_candidates,
)


def _ep(path) -> Video:
    return Video(
        file_path=str(path),
        file_name=str(path),
        series_name="某剧",
        is_series=True,
        season_number=1,
        episode_number=1,
    )


def test_series_root_dir_is_show_folder(tmp_path):
    """剧名根目录 = 与季文件夹同级的 剧名/（由 metadata 路径推算）。"""
    ep = _ep(tmp_path / "x.mkv")
    assert get_series_root_dir(None, [ep]) == tmp_path / "某剧"


def test_root_file_found_in_show_folder(tmp_path):
    """未整理结构（剧名/第1季/x.mkv）：总封面在剧名根，不误取季海报。"""
    season = tmp_path / "某剧" / "第1季"
    season.mkdir(parents=True)
    media = season / "x.mkv"
    media.touch()
    (tmp_path / "某剧" / "tvshow-poster.jpg").write_bytes(b"ROOT")
    (season / "tvshow-poster.jpg").write_bytes(b"SEASON")

    found = find_series_root_file(None, [_ep(media)], "tvshow-poster.jpg")
    assert found is not None
    assert found.read_bytes() == b"ROOT"


def test_season_folder_not_treated_as_root(tmp_path):
    """整理后结构（剧名/第 1 季/第 1 集/x.mkv）：季目录不得当剧根，
    根目录没有总封面时返回 None，而不是拿到季海报。"""
    ep_dir = tmp_path / "某剧" / "第 1 季" / "第 1 集"
    ep_dir.mkdir(parents=True)
    media = ep_dir / "x.mkv"
    media.touch()
    (tmp_path / "某剧" / "第 1 季" / "tvshow-poster.jpg").write_bytes(b"SEASON")

    assert find_series_root_file(None, [_ep(media)], "tvshow-poster.jpg") is None
    # 同名媒体旁根不匹配时也不作为候选
    roots = series_root_candidates(None, [_ep(media)])
    assert all(r.name == "某剧" or r == roots[0] for r in roots)


class _FakeDB:
    def get(self, *args, **kwargs):
        return None

    def flush(self):
        pass


def _vertical_video(tmp_path, **kwargs):
    base = tmp_path / "库根"
    base.mkdir(exist_ok=True)
    media = base / "x.mkv"
    media.touch()
    defaults = dict(
        file_path=str(media),
        file_name="x.mkv",
        series_name="某剧",
        is_series=True,
        season_number=1,
        episode_number=1,
    )
    defaults.update(kwargs)
    return Video(**defaults)


def test_find_shared_vertical_poster_prefers_season_then_root(tmp_path):
    """共享竖图：季海报优先，缺失回退剧根目录总海报，都没有返回 None。"""
    base = tmp_path / "库根" / "某剧"
    base.mkdir(parents=True)
    season = base / "第 1 季"

    ep = _vertical_video(tmp_path)
    assert find_shared_vertical_poster(_FakeDB(), ep) is None

    (base / "tvshow-poster.jpg").write_bytes(b"ROOT")
    assert find_shared_vertical_poster(_FakeDB(), ep) == base / "tvshow-poster.jpg"

    season.mkdir(parents=True)
    (season / "tvshow-poster.jpg").write_bytes(b"SEASON")
    assert find_shared_vertical_poster(_FakeDB(), ep) == season / "tvshow-poster.jpg"

    solo = Video(
        file_path=str(tmp_path / "库根" / "m.mkv"),
        file_name="m.mkv",
        title="独立电影",
    )
    assert find_shared_vertical_poster(_FakeDB(), solo) is None


def test_prune_removes_private_vertical_when_shared(tmp_path):
    """共享图存在时：分集私有竖图文件删除、字段清空；无共享时不动。"""
    base = tmp_path / "库根" / "某剧"
    base.mkdir(parents=True)
    stale = base / "stale-poster.jpg"
    stale.write_bytes(b"OLD")

    ep = _vertical_video(tmp_path, cover_art_path=str(stale))
    assert prune_private_vertical_cover(_FakeDB(), ep) is False  # 共享图还不存在

    (base / "tvshow-poster.jpg").write_bytes(b"ROOT")
    assert prune_private_vertical_cover(_FakeDB(), ep) is True
    assert ep.cover_art_path is None
    assert not stale.exists()


def test_download_all_covers_shares_vertical_keeps_fanart(tmp_path, monkeypatch):
    """共享图在：分集只下横屏 still；私有竖图被清理。独立片照常两张都下。"""
    base = tmp_path / "库根" / "某剧"
    base.mkdir(parents=True)
    (base / "tvshow-poster.jpg").write_bytes(b"ROOT")
    stale = base / "stale2.jpg"
    stale.write_bytes(b"OLD")

    ep = _vertical_video(
        tmp_path,
        poster_url="http://img/p.jpg",
        backdrop_url="http://img/b.jpg",
        cover_art_path=str(stale),
    )
    calls = []
    monkeypatch.setitem(
        download_all_covers.__globals__,
        "download_image",
        lambda *a, **k: calls.append(a) or True,
    )

    ok = download_all_covers(_FakeDB(), ep)

    assert ok
    assert len(calls) == 1  # 只剩横屏
    assert ep.cover_art_path is None
    assert not stale.exists()

    calls.clear()
    solo = Video(
        file_path=str(tmp_path / "库根" / "m2.mkv"),
        file_name="m2.mkv",
        title="独立电影2",
        poster_url="http://img/p2.jpg",
        backdrop_url="http://img/b2.jpg",
    )
    ok = download_all_covers(_FakeDB(), solo)
    assert ok
    assert len(calls) == 2
    assert solo.cover_art_path is not None

