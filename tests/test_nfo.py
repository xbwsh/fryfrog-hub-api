from __future__ import annotations

from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services.video_assets import find_series_nfo, parse_nfo, parse_series_nfo


class _FakeDB:
    """仅够 parse_nfo（只 flush）用。凡是会查库的路径都必须用真 session。"""

    def flush(self) -> None:
        pass


@pytest.fixture()
def lib(tmp_path: Path):
    """真实存在的媒体库。库根必须指向本用例目录：剧名根判定会拿「同名媒体旁根」
    比对，路径写死（如 /media）会让候选推不出来，于是回退到季目录那份文件。"""
    media = tmp_path / "media"
    media.mkdir(parents=True, exist_ok=True)
    return MediaLibrary(name="L", path=str(media), type="VIDEO", enabled=True)


@pytest.fixture()
def db(lib):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    session.add(lib)
    session.flush()
    yield session
    session.close()


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


def _episode_with_tvshow_nfo(
    tmp_path: Path, series: VideoSeries, *, where: str = "season", library_id: int | None = None
) -> tuple[Video, Path]:
    """造出真实布局 <库根>/剧名/第 1 季/第 1 集/，并在指定层级放 tvshow.nfo。

    字段要像真实扫描那样设全，否则推不出剧名根：
    - `library_id` 必须有：`get_metadata_dir` 没有它时会退回「文件的父目录」当库根，
      再拼一层剧名，得到 `…/第 1 集/剧名/第 1 季/第 1 集` 这种重复路径；
    - `is_series` / `season_number` / `episode_number` 决定 `is_episode`，
      漏设会被当成电影走另一个分支。
    返回 (video, 剧名根目录)。
    """
    show = tmp_path / "media" / "剧名"
    season = show / "第 1 季"
    ep_dir = season / "第 1 集"
    ep_dir.mkdir(parents=True, exist_ok=True)
    vf = ep_dir / "show S01E01.mkv"
    vf.write_bytes(b"x")
    if where == "season":
        (season / "tvshow.nfo").write_text(_TVSHOW_NFO, encoding="utf-8")
    elif where == "root":
        (show / "tvshow.nfo").write_text(_TVSHOW_NFO, encoding="utf-8")
    video = Video(
        file_path=str(vf),
        file_name=vf.name,
        title="剧名 S01E01",
        library_id=library_id,
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
    )
    video.series = series
    return video, show


def test_parse_series_nfo_fills_from_season_tvshow(tmp_path: Path, db, lib):
    """季目录的 tvshow.nfo → 系列行简介/评分/年份等（剧根没有时的回退路径）。"""
    series = VideoSeries(title="剧名")
    video, _show = _episode_with_tvshow_nfo(tmp_path, series, where="season", library_id=lib.id)

    ok = parse_series_nfo(db, video)

    assert ok
    assert series.overview == "很长的剧情简介"
    assert abs((series.rating or 0) - 8.7) < 0.01
    assert series.year == 2023
    assert series.original_title == "Show Name"
    assert series.release_date == "2023-01-01"
    assert series.status == "Ended"
    assert series.is_adult is True


def test_find_series_nfo_prefers_show_root_over_season(tmp_path: Path, db, lib):
    """剧根与季目录都有时，必须优先剧根的规范文件。

    外部刮削工具常把 tvshow.nfo 放进季目录（位置错位）。就近查找会先命中那份，
    导致剧根的规范文件被忽略。
    """
    series = VideoSeries(title="剧名")
    video, show = _episode_with_tvshow_nfo(tmp_path, series, where="season", library_id=lib.id)
    (show / "tvshow.nfo").write_text(
        _TVSHOW_NFO.replace("很长的剧情简介", "剧根简介"), encoding="utf-8"
    )

    assert find_series_nfo(db, video) == show / "tvshow.nfo"


def test_parse_series_nfo_reads_show_root_when_both_exist(tmp_path: Path, db, lib):
    """两边内容不同时，读到的必须是剧根那份。"""
    series = VideoSeries(title="剧名")
    video, show = _episode_with_tvshow_nfo(tmp_path, series, where="season", library_id=lib.id)
    (show / "tvshow.nfo").write_text(
        _TVSHOW_NFO.replace("很长的剧情简介", "剧根简介"), encoding="utf-8"
    )

    assert parse_series_nfo(db, video)
    assert series.overview == "剧根简介", "应读剧根的规范文件"


def test_find_series_nfo_falls_back_to_season(tmp_path: Path, db, lib):
    """剧根没有时仍能读到季目录那份（兼容只有季目录的历史数据）。"""
    series = VideoSeries(title="剧名")
    video, show = _episode_with_tvshow_nfo(tmp_path, series, where="season", library_id=lib.id)

    assert find_series_nfo(db, video) == show / "第 1 季" / "tvshow.nfo"


def test_find_series_nfo_returns_none_when_absent(tmp_path: Path, db, lib):
    series = VideoSeries(title="剧名")
    video, _show = _episode_with_tvshow_nfo(tmp_path, series, where="none", library_id=lib.id)
    assert find_series_nfo(db, video) is None


def test_parse_series_nfo_respects_existing_fields(tmp_path: Path, db, lib):
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
    video, _show = _episode_with_tvshow_nfo(tmp_path, series, where="season", library_id=lib.id)

    ok = parse_series_nfo(db, video)

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
