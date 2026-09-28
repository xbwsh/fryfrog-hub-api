from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from fryfrog.models.video import Video
from fryfrog.services.video_service import _clean_folder, _select_show_name


def _folder(**kw) -> str:
    return _clean_folder(_select_show_name(Video(**kw)))


def test_series_folder_uses_series_name():
    """剧集刮削后 title 是分集名，目录名要用 series_name，否则整季被拆散。"""
    assert _folder(
        title="第1集 鹰峰同学",
        series_name="拜托请穿上，鹰峰同学",
        original_title="履いてください、鷹峰さん",
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
    ) == "拜托请穿上，鹰峰同学"
    assert _folder(
        title="第2集 别的东西", series_name="拜托请穿上，鹰峰同学", media_type="tv", is_series=True
    ) == "拜托请穿上，鹰峰同学"


def test_folder_strips_episode_mark():
    """发布名残留的 SxxExx 不应进目录名（季集由「第 N 季/第 M 集」表达）。"""
    messy = "拜托请穿上，鹰峰同学 Haite Kudasai, Takamine-san 2025"
    assert _folder(title=f"{messy} S01E01", series_name=messy, is_series=True) == messy


def test_movie_folder_unaffected():
    assert _folder(title="银翼杀手2049", original_title="Blade Runner 2049", media_type="movie") == "银翼杀手2049"
    assert _folder(title="Breaking Bad", media_type="movie") == "Breaking Bad"


def test_unscraped_episode_gets_season_episode_dir(tmp_path):
    """未刮削/刮削失败的分集（media_type 为空）也要进「第 N 季/第 M 集」。"""
    from fryfrog.services.video_service import get_metadata_dir

    video = Video(
        file_path=str(tmp_path / "x.mkv"),
        file_name="x.mkv",
        series_name="某剧",
        is_series=True,
        season_number=2,
        episode_number=5,
    )
    assert video.is_episode is True
    # library_id 为空时 get_metadata_dir 不使用 db
    assert get_metadata_dir(None, video) == tmp_path / "某剧" / "第 2 季" / "第 5 集"
