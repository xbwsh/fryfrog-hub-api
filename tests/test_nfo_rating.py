"""分集/电影 NFO 的评分输出：为 0 时省略标签。

实测 `直到夏日结束之前` 的 S01E01 与 S02E02 在 TMDB 上 vote_average=0.0 /
vote_count=0（无人评分），照原样写 `<rating>0.0</rating>` 会被播放器显示成
0 分（像差评），比"无评分"更糟。
"""

from __future__ import annotations

import os
import xml.etree.ElementTree as ET

os.environ.setdefault("AUTH_ENABLED", "false")

from fryfrog.models.video import Video
from fryfrog.services.video_assets import _build_nfo, _rating_tags


def _episode(**kwargs) -> Video:
    base = dict(
        file_path="/m/S01E01.mp4",
        file_name="S01E01.mp4",
        title="被威胁的女经理",
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
    )
    base.update(kwargs)
    return Video(**base)


def test_zero_rating_is_omitted():
    """无评分 → 两个标签都不写。"""
    xml = _build_nfo(_episode(rating=0.0, vote_count=0))
    root = ET.fromstring(xml)

    assert root.find("rating") is None, "评分为 0 不该写 <rating>"
    assert root.find("votes") is None, "票数为 0 不该写 <votes>"
    # 其余字段照常
    assert root.findtext("title") == "被威胁的女经理"
    assert root.findtext("season") == "1"
    assert root.findtext("episode") == "1"


def test_none_rating_is_omitted():
    assert _rating_tags(None, None) == []
    assert ET.fromstring(_build_nfo(_episode(rating=None))).find("rating") is None


def test_real_rating_is_written():
    xml = _build_nfo(_episode(rating=7.5, vote_count=12))
    root = ET.fromstring(xml)

    assert root.findtext("rating") == "7.5"
    assert root.findtext("votes") == "12"


def test_rating_without_votes_still_written():
    """有评分但票数为 0：评分要写，票数不写。"""
    root = ET.fromstring(_build_nfo(_episode(rating=8.0, vote_count=0)))

    assert root.findtext("rating") == "8.0"
    assert root.find("votes") is None


def test_movie_rating_kept():
    movie = Video(
        file_path="/m/铃芽之旅.mkv",
        file_name="铃芽之旅.mkv",
        title="铃芽之旅",
        rating=7.89,
        vote_count=100,
    )
    root = ET.fromstring(_build_nfo(movie))

    assert root.tag == "movie"
    assert root.findtext("rating") == "7.89"
    assert root.findtext("votes") == "100"


def test_negative_or_garbage_rating_omitted():
    """异常值不炸，也不写。"""
    assert _rating_tags(-1, 5) == []
    assert _rating_tags("abc", 5) == []
