from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.db import Base
from fryfrog.models.ebook import Ebook
from fryfrog.models.library import MediaLibrary
from fryfrog.routers.ebook import FORMAT_MEDIA, router
from fryfrog.services import ebook_scan, ebook_text


def test_txt_scanned_into_library(tmp_path):
    """TXT 要能被扫描入库，文件名「作者 - 书名」拆出作者。"""
    (tmp_path / "sub").mkdir()
    book_file = tmp_path / "sub" / "刘慈欣 - 三体.txt"
    book_file.write_text("第一章\n科学边界\n", encoding="utf-8")
    (tmp_path / "notes.txt").write_text("随手记", encoding="utf-8")  # 无作者段

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="电子书", path=str(tmp_path), type="EBOOK")
    db.add(lib)
    db.flush()

    result = ebook_scan.scan_ebook_library(db, lib)
    db.commit()

    assert result["books"] == 2
    assert result["created"] == 2

    books = {b.title: b for b in db.scalars(select(Ebook)).all()}
    assert books["三体"].author == "刘慈欣"
    assert books["三体"].format == "TXT"
    assert books["notes"].format == "TXT"
    assert books["notes"].author is None
    for book in books.values():
        assert book.library_id == lib.id


def test_txt_download_media_type():
    assert FORMAT_MEDIA["TXT"].startswith("text/plain")
    assert "utf-8" in FORMAT_MEDIA["TXT"]


def test_rescan_is_incremental(tmp_path):
    """内容未变时不重复入库。"""
    (tmp_path / "a.txt").write_text("x", encoding="utf-8")

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="电子书", path=str(tmp_path), type="EBOOK")
    db.add(lib)
    db.flush()

    assert ebook_scan.scan_ebook_library(db, lib)["created"] == 1
    db.commit()
    assert ebook_scan.scan_ebook_library(db, lib)["created"] == 0


SAMPLE = """前言一些说明

第一章 觉醒
正文一。
正文二。

第二章:出发
另一段正文。
"""


def test_split_chapters_by_markers():
    chapters = ebook_text.split_chapters(SAMPLE)
    assert [c["title"] for c in chapters] == ["开头", "第一章 觉醒", "第二章:出发"]
    text = SAMPLE
    first = chapters[1]
    assert "正文一。" in text[first["start"] : first["end"]]
    assert "另一段正文。" in text[chapters[2]["start"] : chapters[2]["end"]]
    # 章节偏移首尾相接，不丢不重
    for prev, cur in zip(chapters, chapters[1:]):
        assert prev["end"] == cur["start"]


def test_split_without_markers_is_single_chapter():
    chapters = ebook_text.split_chapters("只有一段流水账\n第二行\n")
    assert len(chapters) == 1
    assert chapters[0]["title"] == "全文"


def test_decode_gbk_file(tmp_path):
    """中文 TXT 常见 GBK 编码要能正确解码。"""
    path = tmp_path / "gbk.txt"
    path.write_bytes("第一章\n三体游戏开始\n".encode("gb18030"))
    text = ebook_text.decode_text(path.read_bytes())
    assert "三体游戏开始" in text


def test_chapter_content_api(tmp_path):
    path = tmp_path / "book.txt"
    path.write_text(SAMPLE, encoding="utf-8")
    chapters = ebook_text.chapters_of(path)
    assert len(chapters) == 3
    content = ebook_text.chapter_content(path, 1)
    assert content["title"] == "第一章 觉醒"
    assert "正文一。" in content["text"]
    assert content["chapterCount"] == 3
    with pytest.raises(ResourceNotFoundException):
        ebook_text.chapter_content(path, 99)


def test_chapter_page_slices(tmp_path):
    """分页章节目录：总数/总页数正确，每页内容按偏移切片、互不重叠。"""
    path = tmp_path / "book.txt"
    path.write_text(SAMPLE, encoding="utf-8")

    page0 = ebook_text.chapter_page(path, 0, 2)
    assert page0["totalElements"] == 3
    assert page0["totalPages"] == 2
    assert [c["title"] for c in page0["content"]] == ["开头", "第一章 觉醒"]

    page1 = ebook_text.chapter_page(path, 1, 2)
    assert page1["page"] == 1
    assert [c["title"] for c in page1["content"]] == ["第二章:出发"]


def test_chapter_page_beyond_range_is_empty(tmp_path):
    path = tmp_path / "book.txt"
    path.write_text(SAMPLE, encoding="utf-8")
    page = ebook_text.chapter_page(path, 99, 10)
    assert page["content"] == []
    assert page["totalElements"] == 3
    assert page["totalPages"] == 1


def test_scan_fills_total_chapters(tmp_path):
    (tmp_path / "book.txt").write_text(SAMPLE, encoding="utf-8")

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="电子书", path=str(tmp_path), type="EBOOK")
    db.add(lib)
    db.flush()
    ebook_scan.scan_ebook_library(db, lib)
    db.commit()

    book = db.scalar(select(Ebook))
    assert book.format == "TXT"
    assert book.total_chapters == 3


def test_reading_routes_registered():
    paths = {route.path for route in router.routes}
    assert "/api/v1/ebooks/{book_id}/chapters" in paths
    assert "/api/v1/ebooks/{book_id}/content" in paths
