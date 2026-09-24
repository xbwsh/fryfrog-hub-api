from __future__ import annotations

import zipfile
from pathlib import Path

from fryfrog.services.comic_pages import is_image_name, list_page_names, read_page
from fryfrog.services.video_assets import _prune_empty_dirs


class _Ch:
    def __init__(self, path, type, id=1):
        self.file_path = str(path)
        self.type = type
        self.id = id


def test_comic_zip_and_misnamed_cbr(tmp_path: Path):
    zpath = tmp_path / "vol1.cbr"
    with zipfile.ZipFile(zpath, "w") as zf:
        zf.writestr("02.png", b"\x89PNG")
        zf.writestr("01.jpg", b"\xff\xd8")
        zf.writestr("cover.jpg", b"xx")
    names = list_page_names(_Ch(zpath, "ARCHIVE"))
    assert names == ["01.jpg", "02.png"]
    data, mime = read_page(_Ch(zpath, "ARCHIVE"), 0)
    assert data == b"\xff\xd8" and mime == "image/jpeg"


def test_is_image_skips_cover():
    assert is_image_name("01.jpg")
    assert not is_image_name("cover.jpg")


def test_prune_empty_dirs(tmp_path: Path):
    root = tmp_path / "lib"
    deep = root / "show" / "第 1 季" / "第 1 集"
    deep.mkdir(parents=True)
    removed = _prune_empty_dirs(deep, root)
    assert removed >= 3
    assert root.exists()
    assert not (root / "show").exists()
