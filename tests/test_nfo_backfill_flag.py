"""本地 NFO 回填标识：nfo_backfilled_at。

需求背景：未刮削/未识别不会进库总览，所以真正需要区分的是**已进总览的条目里**
「本地 NFO 回填」还是「TMDB 刮削绑定」。

`metadata_source` 做不到这点——它会被后续 TMDB 刮削覆盖成 "tmdb"，那份来源就丢了。
`nfo_backfilled_at` 一旦写入就保留。
"""

from __future__ import annotations

import os
from datetime import datetime

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.schemas.video import SeriesListDTO, VideoDTO
from fryfrog.services.video_assets import parse_nfo

NFO_WITH_UID = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<episodedetails>
  <title>外部刮削的标题</title>
  <plot>来自本地 NFO 的简介</plot>
  <uniqueid type="tmdb" default="true">12345</uniqueid>
</episodedetails>
"""

NFO_WITHOUT_UID = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<episodedetails>
  <title>只有简介</title>
  <plot>没带唯一标识</plot>
</episodedetails>
"""


@pytest.fixture()
def env(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    media = tmp_path / "media"
    media.mkdir()
    lib = MediaLibrary(name="L", path=str(media), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()

    def make(nfo_text: str | None, name: str = "剧 - S01E01"):
        path = media / f"{name}.mp4"
        path.write_bytes(b"x" * 64)
        if nfo_text is not None:
            (media / f"{name}.nfo").write_text(nfo_text, encoding="utf-8")
        series = VideoSeries(title="剧")
        db.add(series)
        db.flush()
        v = Video(
            file_path=str(path),
            file_name=f"{name}.mp4",
            title=name,
            library_id=lib.id,
            series_id=series.id,
            is_series=True,
            season_number=1,
            episode_number=1,
        )
        db.add(v)
        db.flush()
        return v, series

    yield db, media, make
    db.close()


def test_nfo_with_uniqueid_marks_backfill(env):
    db, media, make = env
    video, _ = make(NFO_WITH_UID)

    assert video.nfo_backfilled_at is None
    assert parse_nfo(db, video) is True

    assert video.tmdb_id == 12345, "应从 NFO 回填 tmdbId"
    assert video.metadata_source == "nfo"
    assert video.nfo_backfilled_at is not None, "应标记为 NFO 回填"
    assert isinstance(video.nfo_backfilled_at, datetime)


def test_nfo_without_uniqueid_does_not_mark(env):
    """没带 uniqueid 的 NFO 只补简介，不算「回填绑定」。"""
    db, media, make = env
    video, _ = make(NFO_WITHOUT_UID)

    parse_nfo(db, video)

    assert video.overview == "没带唯一标识"
    assert video.nfo_backfilled_at is None, "没有唯一标识就不该标记为 NFO 回填"


def test_scrape_does_not_erase_backfill_mark(env):
    """关键回归：后续 TMDB 刮削会把 metadata_source 改成 tmdb，
    但 nfo_backfilled_at 必须保留——否则来源信息丢失（这正是旧实现的缺口）。"""
    db, media, make = env
    video, _ = make(NFO_WITH_UID)
    parse_nfo(db, video)
    marked = video.nfo_backfilled_at
    assert marked is not None

    # 模拟 TMDB 刮削（_apply_movie_detail / _apply_tv_detail 的行为）
    video.metadata_source = "tmdb"
    video.metadata_updated_at = datetime.now()
    video.tmdb_id = 99999
    db.flush()

    assert video.metadata_source == "tmdb", "当前来源确实变成 tmdb"
    assert video.nfo_backfilled_at == marked, "但 NFO 回填标识必须还在"


def test_reparse_does_not_reset_mark(env):
    """重复扫描不会把标记时间刷新（保持首次回填时间）。"""
    db, media, make = env
    video, _ = make(NFO_WITH_UID)
    parse_nfo(db, video)
    first = video.nfo_backfilled_at

    parse_nfo(db, video)
    assert video.nfo_backfilled_at == first


def test_dto_exposes_nfo_backfill(env):
    db, media, make = env
    video, series = make(NFO_WITH_UID)
    parse_nfo(db, video)
    db.flush()

    video_dto = VideoDTO.from_entity(video)
    assert video_dto.nfoBackfilledAt is not None, "分集 DTO 要暴露该字段"

    # 剧卡：剧行没标记时看分集
    listed = SeriesListDTO.from_entity(series, [video], False)
    assert listed.nfoBackfilledAt is not None, "剧卡要能反映分集来自 NFO 回填"


def test_dto_is_null_for_tmdb_scraped(env):
    db, media, make = env
    video, series = make(None)  # 无 NFO
    video.metadata_source = "tmdb"
    video.tmdb_id = 555
    db.flush()

    assert VideoDTO.from_entity(video).nfoBackfilledAt is None
    assert SeriesListDTO.from_entity(series, [video], False).nfoBackfilledAt is None


# ── 第三方刮削器（JavDB 类）生成的 NFO：无 uniqueid、用 outline/maker ──

JAVDB_NFO = """<?xml version="1.0" encoding="UTF-8" ?>
<movie>
  <title><![CDATA[KATU-128 大屁股×巨乳×荡妇，捡到一个调皮的大屁股超短裙辣妹]]></title>
  <originaltitle><![CDATA[KATU-128 でか尻×でか乳×痴女]]></originaltitle>
  <outline><![CDATA[超ミニスカでパンティラインと肉尻丸見え…]]></outline>
  <maker>かつお物産/妄想族</maker>
  <year>2024</year>
  <runtime>168</runtime>
  <num>KATU-128</num>
  <premiered>2024-06-24</premiered>
  <actor><name>九井珠奈绪</name><type>Actor</type></actor>
  <genre>巨乳</genre>
  <ratings>
    <rating name="javdb" max="5" default="true"><value>4.48</value><votes>4420</votes></rating>
  </ratings>
</movie>
"""


def test_third_party_nfo_title_replaces_filename_title(env):
    """实测缺口：扫描先把标题填成文件名（KATU-128），旧逻辑「字段为空才写」
    导致 NFO 里的完整标题永远进不来，库里一直显示编号。"""
    db, media, make = env
    video, _ = make(JAVDB_NFO, name="KATU-128")
    # 模拟扫描：标题先被填成文件名
    video.title = "KATU-128"
    db.flush()

    parse_nfo(db, video)

    assert video.title.startswith("KATU-128 大屁股"), f"标题没被 NFO 覆盖: {video.title!r}"


def test_third_party_nfo_outline_and_maker(env):
    db, media, make = env
    video, _ = make(JAVDB_NFO, name="KATU-128")
    parse_nfo(db, video)

    assert video.overview and "超ミニスカ" in video.overview, "outline 应作为 plot 的回退"
    assert video.studio == "かつお物産/妄想族", f"maker 应作 studio: {video.studio!r}"
    assert video.year == 2024
    assert video.duration_minutes == 168


def test_third_party_nfo_marks_source_but_not_binding(env):
    """无 uniqueid → 不算「绑定回填」（nfoBackfilledAt 保持空），
    但确实贡献了元数据 → metadata_source 应为 nfo（旧逻辑这里是空，判不出来源）。"""
    db, media, make = env
    video, _ = make(JAVDB_NFO, name="KATU-128")
    video.title = "KATU-128"
    db.flush()

    parse_nfo(db, video)

    assert video.metadata_source == "nfo", "本地 NFO 贡献了元数据就该标 nfo"
    assert video.nfo_backfilled_at is None, "没有唯一标识就不算绑定回填"
    assert video.tmdb_id is None, "NFO 没给绑定标识，仍应留在未刮削"


def test_meaningful_title_is_not_overwritten(env):
    """已经刮削出正式标题时，NFO 不该覆盖它。"""
    db, media, make = env
    video, _ = make(JAVDB_NFO, name="KATU-128")
    video.title = "正式刮削标题"
    db.flush()

    parse_nfo(db, video)

    assert video.title == "正式刮削标题", "已有正式标题不该被 NFO 覆盖"
