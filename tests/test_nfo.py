from __future__ import annotations

from pathlib import Path

from fryfrog.models.video import Video, VideoSeries
from fryfrog.services.video_assets import parse_nfo, parse_series_nfo


class _FakeDB:
    def flush(self) -> None:
        pass


def test_parse_nfo_restores_tmdb_id(tmp_path: Path):
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<movie>
  <title>铃芽之旅</title>
  <originaltitle>すずめの戸締まり</originaltitle>
  <year>2022</year>
  <ratings><rating max="10"><value>7.89</value></rating></ratings>
  <uniqueid type="tmdb">916224</uniqueid>
  <uniqueid type="imdb">tt16428256</uniqueid>
  <genre>动画</genre>
  <genre>冒险</genre>
</movie>
"""
    vf = tmp_path / "铃芽之旅.mkv"
    vf.write_bytes(b"x")
    (tmp_path / "铃芽之旅.nfo").write_text(xml, encoding="utf-8")
    video = Video(file_path=str(vf), file_name=vf.name, title="铃芽之旅")
    ok = parse_nfo(_FakeDB(), video)
    assert video.tmdb_id == 916224
    assert video.imdb_id == "tt16428256"
    assert video.year == 2022
    assert abs((video.rating or 0) - 7.89) < 0.01
    assert "动画" in (video.genre or "")
    assert ok



_TVSHOW_NFO = """<?xml version="1.0" encoding="UTF-8"?>
<tvshow>
  <title>剧名</title>
  <originaltitle>Show Name</originaltitle>
  <plot>很长的剧情简介</plot>
  <year>2023</year>
  <rating>8.7</rating>
  <premiered>2023-01-01</premiered>
  <status>Ended</status>
  <adult>true</adult>
</tvshow>
"""


def _episode_with_tvshow_nfo(tmp_path: Path, series: VideoSeries) -> Video:
    season = tmp_path / "第 1 季"
    ep_dir = season / "第 1 集"
    ep_dir.mkdir(parents=True)
    vf = ep_dir / "show S01E01.mkv"
    vf.write_bytes(b"x")
    (season / "tvshow.nfo").write_text(_TVSHOW_NFO, encoding="utf-8")
    video = Video(file_path=str(vf), file_name=vf.name, title="剧名 S01E01")
    video.series = series
    return video




def test_parse_series_nfo_fills_from_season_tvshow(tmp_path: Path):
    """季目录的 tvshow.nfo → 系列行简介/评分/年份等（此前从未解析）。"""
    series = VideoSeries(title="剧名")
    video = _episode_with_tvshow_nfo(tmp_path, series)

    ok = parse_series_nfo(_FakeDB(), video)

    assert ok
    assert series.overview == "很长的剧情简介"
    assert abs((series.rating or 0) - 8.7) < 0.01
    assert series.year == 2023
    assert series.original_title == "Show Name"
    assert series.release_date == "2023-01-01"
    assert series.status == "Ended"
    assert series.is_adult is True


def test_parse_series_nfo_respects_existing_fields(tmp_path: Path):
    """已有值不覆盖（fill-if-empty 语义）。"""
    series = VideoSeries(
        title="剧名",
        overview="原有简介",
        rating=9.9,
        year=2020,
        original_title="已有原名",
        release_date="2020-05-01",
        status="Ended",
        is_adult=True,
    )
    video = _episode_with_tvshow_nfo(tmp_path, series)

    ok = parse_series_nfo(_FakeDB(), video)

    assert series.overview == "原有简介"
    assert series.rating == 9.9
    assert series.year == 2020
    assert not ok


def test_parse_nfo_adult_upgrade_only(tmp_path: Path):
    """分集 NFO 的 <adult>true> 只升不降。"""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<movie>
  <title>某片</title>
  <adult>true</adult>
</movie>
"""
    vf = tmp_path / "某片.mkv"
    vf.write_bytes(b"x")
    (tmp_path / "某片.nfo").write_text(xml, encoding="utf-8")

    v1 = Video(file_path=str(vf), file_name=vf.name, title="某片")
    assert parse_nfo(_FakeDB(), v1)
    assert v1.is_adult is True

    v2 = Video(file_path=str(vf), file_name=vf.name, title="某片", is_adult=True)
    (tmp_path / "某片.nfo").write_text(
        xml.replace("<adult>true</adult>", "<adult>false</adult>"),
        encoding="utf-8",
    )
    parse_nfo(_FakeDB(), v2)
    assert v2.is_adult is True
