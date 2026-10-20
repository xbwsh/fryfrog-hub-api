"""喜马拉雅网页刮削及绑定后重新扫描的回归检查，不访问外网。"""

from types import SimpleNamespace

import httpx
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from fryfrog.db import Base
from fryfrog.core.exceptions import BadRequestException
from fryfrog.models.audiobook import Audiobook
from fryfrog.models.library import MediaLibrary
from fryfrog.services import assets, audiobook_scan, audiobook_scrape as scrape


def _mock_web(monkeypatch, handler):
    transport = httpx.MockTransport(handler)
    monkeypatch.setattr(scrape, "make_client", lambda **_: httpx.Client(transport=transport))


def test_keyword_search_parses_highlights_and_cover(monkeypatch):
    def handle(request):
        assert request.url.path == "/revision/search/main"
        assert request.url.params["kw"] == "三体"
        assert request.url.params["core"] == "album"
        return httpx.Response(200, json={"ret": 200, "data": {"album": {"docs": [
            {"albumId": 123, "title": "<em>三体</em> &amp; 故事", "intro": "简介",
             "coverPath": "//imagev2.xmcdn.com/test.jpg", "nickname": "上传账号"},
            {"title": "缺少专辑 ID"},
        ]}}})

    _mock_web(monkeypatch, handle)
    assert "ximalaya" in {p["source"] for p in scrape.list_providers()}
    items = scrape.search("三体", "ximalaya")
    assert len(items) == 1
    assert items[0]["sourceId"] == "123"
    assert items[0]["title"] == "三体 & 故事"
    assert items[0]["coverUrl"] == "https://imagev2.xmcdn.com/test.jpg"
    assert items[0]["author"] is None
    assert items[0]["narrator"] is None, "上传账号不能直接当成演播者"


def test_restricted_search_does_not_break_aggregate(monkeypatch):
    _mock_web(monkeypatch, lambda request: httpx.Response(200, json={
        "ret": 200, "data": {"reason": "risk invalid", "riskLevel": 5},
    }))
    with pytest.raises(BadRequestException, match="专辑链接或 ID"):
        scrape.search("三体", "ximalaya")
    monkeypatch.setattr(scrape, "_search_bangumi", lambda _: [{"title": "其他来源"}])
    for name in ("_search_openlib", "_search_google", "_search_audiobooker"):
        monkeypatch.setattr(scrape, name, lambda _: [])
    assert scrape.search("三体") == [{"title": "其他来源"}]


@pytest.mark.parametrize("reply", [
    httpx.Response(403),
    httpx.Response(200, text="<html>请稍后重试</html>"),
    httpx.Response(200, json={"ret": 200, "data": {"album": []}}),
    httpx.Response(200, json=[]),
])
def test_unavailable_search_returns_readable_error(monkeypatch, reply):
    _mock_web(monkeypatch, lambda request: reply)
    with pytest.raises(BadRequestException, match="喜马拉雅"):
        scrape.search("三体", "ximalaya")


@pytest.mark.parametrize("value, expected", [
    ("123", "123"),
    ("https://www.ximalaya.com/album/123?from=share", "123"),
    ("https://m.ximalaya.com/youshengshu/123/", "123"),
    ("www.ximalaya.com/album/123.html", "123"),
    ("https://www.ximalaya.com.evil.example/album/123", None),
    ("https://www.ximalaya.com@evil.example/album/123", None),
    ("https://www.ximalaya.com/sound/123", None),
    ("[三体]", None),
    ("１２３", None),
    ("9" * 100, None),
])
def test_album_id_only_accepts_album_links(value, expected):
    assert scrape._ximalaya_album_id(value) == expected


@pytest.mark.parametrize("metadata_source", ["scrape", "scrape:ximalaya", "scrape:bangumi"])
def test_bind_cover_and_rescan_preserve_metadata(tmp_path, monkeypatch, metadata_source):
    folder = tmp_path / "local-title"
    folder.mkdir()
    audio = folder / "track.mp3"
    audio.write_bytes(b"audio")
    cover = tmp_path / "covers" / "123.jpg"
    monkeypatch.setattr(assets, "audiobook_cover_path", lambda _: cover)

    def handle(request):
        if request.url.host == "imagev2.xmcdn.com":
            return httpx.Response(200, content=b"cover-bytes")
        assert request.url.path == "/revision/album/v1/simple"
        assert request.url.params["albumId"] == "123"
        return httpx.Response(200, json={"ret": 200, "data": {"albumPageMainInfo": {
            "albumTitle": "远程书名", "cover": "//imagev2.xmcdn.com/cover.jpg",
            "detailRichIntro": "<p>故事简介 &amp; 介绍</p><p>作者：作者甲，代表作</p><p>演播：演员乙</p>",
            "anchorName": "上传账号", "createDate": "2020-01-01",
        }}})

    _mock_web(monkeypatch, handle)
    # 不指定来源时，专辑链接应直接查详情，不调用其他源的关键词搜索。
    items = scrape.search("https://www.ximalaya.com/album/123")
    assert items[0]["year"] is None, "专辑创建年份不是作品出版年份"

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        library = MediaLibrary(name="有声书", type="AUDIOBOOK", path=str(tmp_path))
        db.add(library)
        db.flush()
        book = Audiobook(title="本地书名", book_path=str(folder), play_type="SINGLE")
        db.add(book)
        db.flush()
        scrape.bind(db, book.id, "ximalaya", "https://www.ximalaya.com/album/123")
        assert book.metadata_source == "scrape:ximalaya"
        assert book.source_id == "123"
        assert book.overview == "故事简介 & 介绍\n作者：作者甲，代表作\n演播：演员乙"
        assert cover.read_bytes() == b"cover-bytes"
        assert book.cover_art_path == str(cover)

        book.metadata_source = metadata_source
        probe = SimpleNamespace(probe_audio_info=lambda _: {
            "tags": {"album": "本地标签", "artist": "本地作者", "narrator": "本地演播"},
            "duration": 100,
        })
        monkeypatch.setattr(audiobook_scan, "get_media_probe", lambda: probe)
        audiobook_scan._upsert_book(db, str(folder), [audio], tmp_path, library.id)
        assert (book.title, book.author, book.narrator) == ("远程书名", "作者甲", "演员乙")
        assert book.total_duration_seconds == 100
    engine.dispose()
