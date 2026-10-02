from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from fryfrog.core.exceptions import ResourceNotFoundException
from fryfrog.db import Base
from fryfrog.models.comic import Comic, ComicChapter
from fryfrog.models.library import MediaLibrary
from fryfrog.services import comic_pages, comic_scan


def _make_pdf(path: Path, pages: int = 2) -> Path:
    import pypdfium2 as pdfium

    doc = pdfium.PdfDocument.new()
    for _ in range(pages):
        doc.new_page(595, 842)  # A4
    path.parent.mkdir(parents=True, exist_ok=True)
    doc.save(str(path))
    doc.close()
    return path


def _chapter(path: Path) -> ComicChapter:
    return ComicChapter(file_path=str(path), type="ARCHIVE", title=path.stem)


def test_pdf_page_count_and_names(tmp_path):
    pdf = _make_pdf(tmp_path / "vol1.pdf", pages=3)
    chapter = _chapter(pdf)
    assert comic_pages.page_count(chapter) == 3
    assert comic_pages.list_page_names(chapter) == [
        "page_00000.jpg",
        "page_00001.jpg",
        "page_00002.jpg",
    ]


def test_pdf_read_page_renders_jpeg(tmp_path):
    pdf = _make_pdf(tmp_path / "vol1.pdf", pages=2)
    chapter = _chapter(pdf)
    data, media = comic_pages.read_page(chapter, 0)
    assert media == "image/jpeg"
    assert data[:2] == b"\xff\xd8"  # JPEG SOI
    assert len(data) > 1000


def test_pdf_page_index_out_of_range(tmp_path):
    pdf = _make_pdf(tmp_path / "vol1.pdf", pages=2)
    with pytest.raises(ResourceNotFoundException):
        comic_pages.read_page(_chapter(pdf), 5)


def test_scan_picks_up_pdf(tmp_path):
    """库根与作品子目录里的 pdf 都要被识别为章节。"""
    _make_pdf(tmp_path / "作品A" / "01.pdf", pages=2)
    _make_pdf(tmp_path / "系列B 第01卷.pdf", pages=2)

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="漫画", path=str(tmp_path), type="COMIC")
    db.add(lib)
    db.flush()

    result = comic_scan.scan_comic_library(db, lib)
    db.commit()

    assert result["series"] == 2
    assert result["chapters"] == 2

    comics = db.scalars(select(Comic).order_by(Comic.title)).all()
    assert sorted(c.title for c in comics) == ["作品A", "系列B"]
    for comic in comics:
        chapters = db.scalars(
            select(ComicChapter).where(ComicChapter.comic_id == comic.id)
        ).all()
        assert len(chapters) == 1
        assert chapters[0].type == "ARCHIVE"
        assert chapters[0].page_count == 2
        assert Path(chapters[0].file_path).suffix == ".pdf"
        assert comic.cover_art_path  # 首页渲染为封面
