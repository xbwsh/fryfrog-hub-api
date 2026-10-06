"""fileMissing 标记：文件已不在磁盘上的残留行要能被前端识别。

场景：用户把 `S01E03.mp4` 改名为 `S02E01.mp4` 后，扫描会建新行并给旧行打
missing_since，旧行要等宽限期（30 分钟）才删。这期间新旧条目并存，旧的那条
点开会失败。DTO 暴露 fileMissing，前端据此置灰。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video, VideoSeries
from fryfrog.schemas.video import SeriesListDTO, VideoDTO


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

    def make(name: str, *, exists: bool = True, series=None):
        path = media / name
        if exists:
            path.write_bytes(b"x" * 32)
        v = Video(
            file_path=str(path),
            file_name=name,
            title=name,
            library_id=lib.id,
            series_id=series.id if series else None,
            is_series=series is not None,
            season_number=1,
            episode_number=1,
        )
        db.add(v)
        db.flush()
        return v

    yield db, media, make
    db.close()


def test_video_dto_flags_missing_file(env):
    db, media, make = env
    alive = make("alive.mp4")
    gone = make("gone.mp4", exists=False)
    db.commit()

    assert VideoDTO.from_entity(alive).fileMissing is False
    assert VideoDTO.from_entity(gone).fileMissing is True, "文件不存在应标记 fileMissing"


def test_series_list_dto_flags_when_all_episodes_missing(env):
    db, media, make = env
    series = VideoSeries(title="剧")
    db.add(series)
    db.flush()
    make("gone1.mp4", exists=False, series=series)
    make("gone2.mp4", exists=False, series=series)
    db.commit()

    listed = SeriesListDTO.from_entity(series, [v for v in db.query(Video).all()], False)
    assert listed.fileMissing is True, "分集全缺失时剧卡应标记失效"


def test_series_list_dto_not_flagged_when_some_episodes_remain(env):
    """只要还有一集在，剧卡就不算失效（点进去仍可正常看剩下的）。"""
    db, media, make = env
    series = VideoSeries(title="剧")
    db.add(series)
    db.flush()
    keep = make("keep.mp4", exists=True, series=series)
    make("gone.mp4", exists=False, series=series)
    db.commit()

    listed = SeriesListDTO.from_entity(series, [keep], False)
    assert listed.fileMissing is False, "还有可用分集时不该标记整卡失效"


def test_missing_flag_does_not_break_cover_urls(env):
    """置灰只是提示，DTO 其余字段仍要正常生成（封面 URL 等）。"""
    db, media, make = env
    gone = make("gone.mp4", exists=False)
    db.commit()

    dto = VideoDTO.from_entity(gone)
    assert dto.fileMissing is True
    assert dto.coverUrl, "封面 URL 仍应生成（前端置灰但结构完整）"
    assert Path(gone.file_path).exists() is False
