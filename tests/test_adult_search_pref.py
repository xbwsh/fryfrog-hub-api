"""同名多条时的取舍：成人库优先成人条目。

实测问题：`search_tmdb_best` 只看 mediaType，同名的「普通版 + 成人版」两条
记录里选哪条**完全取决于 TMDB 的返回顺序**。这类作品在 TMDB 上常见双条目
（有成人标识的与普通的），按返回顺序取有绑错的风险。

注意 `tmdb_include_adult` 默认 True，成人条目本来就能搜到；这里补的是**取舍**。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from unittest import mock

from fryfrog.services import video_scrape


def _fake(results):
    return mock.patch.object(video_scrape, "search_tmdb", return_value=results)


NORMAL_FIRST = [
    {"id": 111, "mediaType": "tv", "adult": False, "title": "xxxx"},
    {"id": 222, "mediaType": "tv", "adult": True, "title": "xxxx"},
]
ADULT_FIRST = list(reversed(NORMAL_FIRST))


def test_adult_library_prefers_adult_even_when_normal_comes_first():
    """核心回归：成人库 + 普通版排在前面 → 仍要选成人条目。"""
    with _fake(NORMAL_FIRST):
        pick = video_scrape.search_tmdb_best(["xxxx"], "tv", adult_pref=True)
    assert pick["id"] == 222, f"应选成人条目，实际 {pick}"


def test_adult_library_prefers_adult_when_adult_first():
    with _fake(ADULT_FIRST):
        pick = video_scrape.search_tmdb_best(["xxxx"], "tv", adult_pref=True)
    assert pick["id"] == 222


def test_normal_library_prefers_non_adult():
    """普通库反过来：成人版排在前面也不能选它。"""
    with _fake(ADULT_FIRST):
        pick = video_scrape.search_tmdb_best(["xxxx"], "tv", adult_pref=False)
    assert pick["id"] == 111, f"应选非成人条目，实际 {pick}"


def test_no_preference_keeps_tmdb_order():
    """不传偏好时保持 TMDB 原顺序（手动搜索场景不该被改）。"""
    with _fake(NORMAL_FIRST):
        assert video_scrape.search_tmdb_best(["xxxx"], "tv")["id"] == 111
    with _fake(ADULT_FIRST):
        assert video_scrape.search_tmdb_best(["xxxx"], "tv")["id"] == 222


def test_preference_only_within_same_media_type():
    """不能为了成人标记而跨类型选错：mediaType 优先。"""
    results = [
        {"id": 1, "mediaType": "movie", "adult": True, "title": "xxxx"},
        {"id": 2, "mediaType": "tv", "adult": False, "title": "xxxx"},
    ]
    with _fake(results):
        pick = video_scrape.search_tmdb_best(["xxxx"], "tv", adult_pref=True)
    assert pick["id"] == 2, "同类型优先于成人偏好"


def test_falls_back_when_preferred_kind_absent():
    """库里只有普通版时不能返回 None，要退到现有候选。"""
    only_normal = [{"id": 111, "mediaType": "tv", "adult": False, "title": "xxxx"}]
    with _fake(only_normal):
        pick = video_scrape.search_tmdb_best(["xxxx"], "tv", adult_pref=True)
    assert pick["id"] == 111, "没有成人条目时应退到普通条目"


def test_tries_next_query_when_first_has_no_results():
    """首个查询无结果时继续试下一个（保持原行为）。"""
    calls = []

    def fake_search(q):
        calls.append(q)
        return NORMAL_FIRST if q == "第二个" else []

    with mock.patch.object(video_scrape, "search_tmdb", side_effect=fake_search):
        pick = video_scrape.search_tmdb_best(["第一个", "第二个"], "tv", adult_pref=True)

    assert calls == ["第一个", "第二个"]
    assert pick["id"] == 222
