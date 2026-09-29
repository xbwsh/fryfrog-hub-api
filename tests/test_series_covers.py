from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from fryfrog.models.video import Video
from fryfrog.routers.video.series import _find_series_root_file, _series_root_candidates
from fryfrog.services.video_service import get_series_root_dir


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

    found = _find_series_root_file(None, [_ep(media)], "tvshow-poster.jpg")
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

    assert _find_series_root_file(None, [_ep(media)], "tvshow-poster.jpg") is None
    # 同名媒体旁根不匹配时也不作为候选
    roots = _series_root_candidates(None, [_ep(media)])
    assert all(r.name == "某剧" or r == roots[0] for r in roots)
