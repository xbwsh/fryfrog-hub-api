from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from fryfrog.services.video_scrape import search_queries


def test_search_queries_release_name():
    """中文名.英文名.年份.SxxExx.压制信息 这类发布名应能退到各标题段。"""
    q = search_queries(
        "拜托请穿上，鹰峰同学.Haite Kudasai, Takamine-san.2025."
        "S01E01.2160p.BDRip.HEVC.10bit.FLAC.mkv"
    )
    assert q[0] == "拜托请穿上，鹰峰同学 Haite Kudasai, Takamine-san"
    assert "拜托请穿上，鹰峰同学" in q


def test_search_queries_keeps_title_year():
    """标题自带年份不能被当成发布年份截掉。"""
    assert search_queries("Blade Runner 2049.mkv")[0] == "Blade Runner 2049"
    assert search_queries("Blade Runner 2049.2017.2160p.BluRay.x265.mkv")[0] == "Blade Runner 2049"
    assert search_queries("1917.2019.2160p.BluRay.x265.mkv")[0] == "1917"


def test_search_queries_latin_not_split():
    """纯拉丁名不拆段，避免把 Show.Name 拆成 Show 搜到无关作品。"""
    assert search_queries("Show.Name.2025.S01E01.1080p.BluRay.x264.mkv") == ["Show Name"]
    assert search_queries("Inception.2010.1080p.BluRay.x264.mkv") == ["Inception"]


def test_search_queries_fallback_and_empty():
    assert search_queries("", "拜托请穿上，鹰峰同学") == ["拜托请穿上，鹰峰同学"]
    assert search_queries(None) == []
