"""同一部剧被拆成多个系列（每集一个）的历史数据自愈。

真实故障：`[TUDO&Ygm] Kono Yuusha ... [01][Ma10p_2160p][x265_flac_ass]`
这类命名，扫描按清洗后的英文名建/找系列，刮削把系列标题改成 TMDB 中文名
后，后续逐集扫描按英文名找不到旧系列，每集都新建一个——慎重勇者 12 集
在线上散成了 12 个同名系列。修复：扫描/刮削按 tmdb_id 归位 + 收敛空壳。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.services import video_scrape, video_scan


def _mk_db():
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)()


def _mk_video(db, path: str, episode: int, series_id: int) -> Video:
    v = Video(
        file_path=path,
        file_name=path.rsplit("/", 1)[-1],
        title=f"第{episode}话",
        tmdb_id=93256,
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=episode,
        series_id=series_id,
    )
    db.add(v)
    return v


def test_merge_duplicate_series_keeps_first_and_moves_episodes():
    db = _mk_db()
    keep = VideoSeries(title="慎重勇者 ～这个勇者明明超强却过分慎重～", tmdb_id=93256)
    victim = VideoSeries(title="慎重勇者 ～这个勇者明明超强却过分慎重～", tmdb_id=93256)
    other = VideoSeries(title="别的剧", tmdb_id=99999)
    db.add_all([keep, victim, other])
    db.flush()
    v1 = _mk_video(db, "/m/a/01.mkv", 1, keep.id)
    v2 = _mk_video(db, "/m/a/02.mkv", 2, victim.id)
    v3 = _mk_video(db, "/m/b/01.mkv", 1, other.id)
    db.flush()

    assert video_scan.merge_duplicate_series(db) == 1
    db.flush()

    assert db.get(VideoSeries, victim.id) is None, "副系列应被删除"
    assert db.get(Video, v1.id).series_id == keep.id
    assert db.get(Video, v2.id).series_id == keep.id, "分集应迁到保留系列"
    assert db.get(Video, v3.id).series_id == other.id, "无关系列不受影响"
    assert db.scalar(select(func.count(VideoSeries.id))) == 2


def _setup_scan(tmp_path, monkeypatch):
    class _FakeProbe:
        def probe_video_duration(self, path):
            return 600.0

        def probe_video_resolution(self, path):
            return (1920, 1080)

    monkeypatch.setattr(video_scan, "get_media_probe", lambda: _FakeProbe())
    db = _mk_db()
    lib = MediaLibrary(name="剧集", path=str(tmp_path), type="VIDEO", enable_scraping=False)
    db.add(lib)
    db.flush()
    return db, lib


def test_scan_rehomes_episodes_to_tmdb_series(tmp_path, monkeypatch):
    """已刮削分集扫描时按 tmdb_id 归位，拆分出的副系列被收敛删除。"""
    db, lib = _setup_scan(tmp_path, monkeypatch)
    f1 = tmp_path / "Kono ...[01][Ma10p_2160p][x265_flac_ass].mkv"
    f2 = tmp_path / "Kono ...[02][Ma10p_2160p][x265_flac_ass].mkv"
    f1.write_bytes(b"fake-video")
    f2.write_bytes(b"fake-video")

    series_a = VideoSeries(title="慎重勇者 ～这个勇者明明超强却过分慎重～", tmdb_id=93256)
    series_b = VideoSeries(title="Kono Yuusha ga Ore Tsueee Kuse ni Shinchou Sugiru", tmdb_id=93256)
    db.add_all([series_a, series_b])
    db.flush()
    # 历史分裂现场：E1 在 series A，E2 被拆到 series B
    v1 = Video(
        file_path=str(f1), file_name=f1.name, title="这个勇者过于傲慢", tmdb_id=93256,
        media_type="tv", is_series=True, season_number=1, episode_number=1,
        series_id=series_a.id, library_id=lib.id,
    )
    v2 = Video(
        file_path=str(f2), file_name=f2.name, title="这个勇者过于任性", tmdb_id=93256,
        media_type="tv", is_series=True, season_number=1, episode_number=2,
        series_id=series_b.id, library_id=lib.id,
    )
    db.add_all([v1, v2])
    db.flush()

    video_scan.scan_video_library(db, lib)
    db.flush()

    assert db.get(Video, v2.id).series_id == series_a.id, "E2 应归位到 tmdb 系列"
    assert db.get(VideoSeries, series_b.id) is None, "拆分出的副系列应被收敛"
    assert db.scalar(select(func.count(Video.id)).where(Video.series_id == series_a.id)) == 2


class _FakeClient:
    def image_url(self, path, size="w500"):
        return f"https://img/{path}"

    def get_episode(self, *args, **kwargs):
        return None


def test_apply_tv_detail_joins_existing_tmdb_series():
    """刮削时若英文空壳系列已存在同 tmdb_id 系列，分集应归并而非新建系列。"""
    db = _mk_db()
    series_a = VideoSeries(title="慎重勇者 ～这个勇者明明超强却过分慎重～", tmdb_id=93256)
    shell = VideoSeries(title="Kono Yuusha ga Ore Tsueee Kuse ni Shinchou Sugiru")
    db.add_all([series_a, shell])
    db.flush()
    video = Video(
        file_path="/m/a/03.mkv", file_name="03.mkv", title="占位",
        is_series=True, season_number=1, episode_number=3, series_id=shell.id,
    )
    db.add(video)
    db.flush()

    detail = {
        "id": 93256, "name": "慎重勇者 ～这个勇者明明超强却过分慎重～",
        "original_name": "慎重勇者～この勇者が俺TUEEEくせに慎重すぎる～",
        "overview": "", "vote_average": 8.0, "vote_count": 100,
        "first_air_date": "2019-10-02", "status": "Ended", "genres": [],
        "credits": {}, "created_by": [], "external_ids": {},
        "number_of_seasons": 1, "number_of_episodes": 12, "next_episode_to_air": None,
        "poster_path": None, "backdrop_path": None,
    }
    video_scrape._apply_tv_detail(db, video, detail, _FakeClient())
    db.flush()

    assert video.series_id == series_a.id, "应复用已有 tmdb 系列"
    # 空壳系列仍在（留给扫描末 merge_duplicate_series 收敛），但不再有分集
    assert db.get(VideoSeries, shell.id) is not None
    assert series_a.title == "慎重勇者 ～这个勇者明明超强却过分慎重～"
