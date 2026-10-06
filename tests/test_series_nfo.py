"""剧级 / 季级 NFO 生成。

NFO 分三层，根元素各不相同，播放器按「固定文件名 + 固定根标签」解析，
所以不能用单个文件装下所有季集：
    <剧根>/tvshow.nfo      <tvshow>
    <季目录>/season.nfo    <season>
    <分集目录>/<片名>.nfo  <episodedetails>   （既有 generate_nfo）
"""

from __future__ import annotations

import os
import xml.etree.ElementTree as ET

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services import video_assets as assets

TV_DETAIL = {
    "id": 106882,
    "name": "直到夏日结束之前",
    "original_name": "夏が終わるまで The Animation",
    "overview": "某天，青梅竹马兼恋人的由比与浩发生了亲密关系。",
    "first_air_date": "2020-07-31",
    "vote_average": 9.0,
    "vote_count": 2,
    "status": "Returning Series",
    "genres": [{"name": "动画"}],
    "production_companies": [{"name": "Showten"}],
    "external_ids": {"imdb_id": "tt1234567"},
}

SEASON_2 = {
    "season_number": 2,
    "name": "夏日的结束",
    "overview": "第二季简介",
    "air_date": "2024-06-28",
    "poster_path": "/ppQn5BzRuJA5Fe6zlndkQB67rCy.jpg",
}


@pytest.fixture()
def env(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    media = tmp_path / "media"
    show = media / "直到夏日结束之前"
    lib = MediaLibrary(name="L", path=str(media), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()
    series = VideoSeries(
        title="直到夏日结束之前",
        tmdb_id=106882,
        media_type="tv",
        overview="库里的简介",
        rating=9.0,
        year=2020,
    )
    db.add(series)
    db.flush()

    eps = []
    for sn in (1, 2):
        d = show / f"第 {sn} 季"
        d.mkdir(parents=True)
        path = d / f"直到夏日结束之前 - S{sn:02d}E01.mp4"
        path.write_bytes(b"x" * 32)
        v = Video(
            file_path=str(path),
            file_name=path.name,
            # 标题用真实刮削后的形态（剧名 + SxxExx）：get_metadata_dir 会用它
            # 反推剧名根目录，标题太随意（如 "E1"）会把根目录推成 media/E1/
            title=f"直到夏日结束之前 S{sn:02d}E01",
            library_id=lib.id,
            series_id=series.id,
            tmdb_id=106882,
            media_type="tv",
            is_series=True,
            season_number=sn,
            episode_number=1,
            genre="动画",
            actors="演员甲,演员乙",
            vote_count=2,
        )
        db.add(v)
        eps.append(v)
    db.commit()
    yield db, series, eps, show
    db.close()


def test_build_series_nfo_is_valid_xml_with_expected_fields(env):
    db, series, eps, _show = env
    text = assets.build_series_nfo(series, TV_DETAIL, sample=eps[0])
    root = ET.fromstring(text)  # 必须能解析

    assert root.tag == "tvshow"
    assert root.findtext("title") == "直到夏日结束之前"
    assert root.findtext("originaltitle") == "夏が終わるまで The Animation"
    assert root.findtext("year") == "2020"
    assert root.findtext("status") == "Returning Series"
    assert root.findtext("genre") == "动画"
    assert root.find('uniqueid[@type="tmdb"]').text == "106882"
    assert root.find('uniqueid[@type="imdb"]').text == "tt1234567"
    assert [a.findtext("name") for a in root.findall("actor")] == ["演员甲", "演员乙"]


def test_series_nfo_escapes_xml_specials(env):
    """简介里的 & < > 必须转义，否则整个 NFO 变成非法 XML、播放器解析失败。"""
    db, series, eps, _show = env
    series.overview = "A & B <tag> \"quoted\""
    text = assets.build_series_nfo(series, None, sample=eps[0])

    root = ET.fromstring(text)  # 未转义时这里会抛 ParseError
    assert root.findtext("plot") == "A & B <tag> \"quoted\""
    assert "&amp;" in text and "&lt;tag&gt;" in text


def test_series_nfo_falls_back_to_series_row_without_detail(env):
    """没有 TMDB 详情时用系列行 + 抽样分集兜底（actors/genre 只在分集行上）。"""
    db, series, eps, _show = env
    root = ET.fromstring(assets.build_series_nfo(series, None, sample=eps[0]))

    assert root.findtext("plot") == "库里的简介"
    assert root.findtext("genre") == "动画"
    assert root.findtext("votes") == "2"
    assert root.find('uniqueid[@type="tmdb"]').text == "106882"


def test_generate_series_nfo_writes_to_show_root(env):
    db, series, eps, show = env
    path = assets.generate_series_nfo(db, series, eps, TV_DETAIL)

    assert path == str(show / "tvshow.nfo"), f"应写在剧根，实际 {path}"
    assert Path(path).is_file()
    assert ET.fromstring(Path(path).read_text(encoding="utf-8")).tag == "tvshow"


def test_ensure_series_nfo_is_idempotent(env):
    """已有就不重写（扫描每集都会调，必须幂等且不做无谓写盘）。"""
    db, series, eps, show = env
    target = show / "tvshow.nfo"

    _path, created = assets.ensure_series_nfo(db, series, eps, TV_DETAIL)
    assert created is True
    first = target.read_text(encoding="utf-8")

    target.write_text("手工内容", encoding="utf-8")
    _path, created2 = assets.ensure_series_nfo(db, series, eps, TV_DETAIL)
    assert created2 is False
    assert target.read_text(encoding="utf-8") == "手工内容", "已存在时不该被覆盖"


def test_season_nfo_keeps_season_name(env):
    """季名（TMDB 的 `夏日的结束`）只存在这里——库里没有字段可放。"""
    db, _series, eps, show = env
    path = assets.generate_season_nfo(db, eps[1], SEASON_2)

    assert path == str(show / "第 2 季" / "season.nfo")
    root = ET.fromstring(Path(path).read_text(encoding="utf-8"))
    assert root.tag == "season"
    assert root.findtext("seasonnumber") == "2"
    assert root.findtext("title") == "夏日的结束"
    assert root.findtext("premiered") == "2024-06-28"
    assert root.findtext("poster") == "tvshow-poster.jpg"


def test_season_nfo_without_tmdb_data_still_records_number(env):
    """拿不到 TMDB 季信息也要写：至少留下季号，便于播放器识别。"""
    db, _series, eps, show = env
    path = assets.generate_season_nfo(db, eps[0], {"season_number": 1})
    root = ET.fromstring(Path(path).read_text(encoding="utf-8"))
    assert root.findtext("seasonnumber") == "1"
    assert root.findtext("poster") is None
    assert path == str(show / "第 1 季" / "season.nfo")


def test_generate_series_nfo_skips_without_episodes(env):
    """没有分集就定位不到剧名根目录，应安全返回 None。"""
    db, series, _eps, _show = env
    assert assets.generate_series_nfo(db, series, [], TV_DETAIL) is None
    assert assets.ensure_series_nfo(db, series, [], TV_DETAIL) == (None, False)


def test_download_series_root_art_writes_both_nfo_levels(env, monkeypatch):
    """集成：刮削走 download_series_root_art 时，剧级与季级 NFO 都要落地。

    季级用 detail 里已带的 seasons（零额外 TMDB 请求）——这是把 season.nfo 放在
    这里而不是扫描里的原因。
    """
    db, series, eps, show = env
    detail = dict(TV_DETAIL)
    detail["seasons"] = [
        {"season_number": 1, "name": "第 1 季", "poster_path": "/s1.jpg"},
        SEASON_2,
    ]
    detail["poster_path"] = "/p.jpg"
    detail["backdrop_path"] = "/b.jpg"

    monkeypatch.setattr(
        assets, "download_image", lambda url, target, force=False: False
    )
    result = assets.download_series_root_art(db, series, eps, detail=detail)

    assert result["nfo"] is True
    assert result["seasons"] == 2, f"两季都该写 season.nfo，实际 {result['seasons']}"

    root_nfo = show / "tvshow.nfo"
    assert root_nfo.is_file()
    assert ET.fromstring(root_nfo.read_text(encoding="utf-8")).tag == "tvshow"

    for sn in (1, 2):
        season_nfo = show / f"第 {sn} 季" / "season.nfo"
        assert season_nfo.is_file(), f"第 {sn} 季缺 season.nfo"
        assert ET.fromstring(season_nfo.read_text(encoding="utf-8")).tag == "season"

    s2 = ET.fromstring((show / "第 2 季" / "season.nfo").read_text(encoding="utf-8"))
    assert s2.findtext("title") == "夏日的结束", "季名必须写进去"
