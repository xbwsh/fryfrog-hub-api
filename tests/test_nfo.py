from __future__ import annotations

from pathlib import Path

from fryfrog.models.video import Video
from fryfrog.services.video_assets import parse_nfo


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
