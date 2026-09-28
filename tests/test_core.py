from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from fryfrog.core.natural_order import natural_compare
from fryfrog.core.signer import sign, verify
from fryfrog.core.security import hash_password, verify_password
from fryfrog.core.utils import clean_title


def test_natural_compare():
    assert natural_compare("2.mp3", "10.mp3") < 0
    assert natural_compare("10.mp3", "2.mp3") > 0
    assert natural_compare("a1", "a1") == 0


def test_media_url_sign_roundtrip():
    path = "/api/v1/video/1/cover"
    signed = sign(path)
    assert "exp=" in signed and "sig=" in signed
    qs = signed.split("?", 1)[1]
    params = dict(p.split("=", 1) for p in qs.split("&"))
    assert verify(path, int(params["exp"]), params["sig"])
    assert not verify(path, int(params["exp"]) - 10_000_000_000, params["sig"])


def test_password_hash():
    h = hash_password("secret123")
    assert verify_password("secret123", h)
    assert not verify_password("wrong", h)


def test_clean_title():
    assert "Inception" in clean_title("Inception.2010.1080p.BluRay.x264")


def test_primary_title():
    """发布名取主标题：中文名.英文名.年份.SxxExx → 中文名。"""
    from fryfrog.core.utils import primary_title

    assert primary_title(
        "拜托请穿上，鹰峰同学.Haite Kudasai, Takamine-san.2025."
        "S01E01.2160p.BDRip.HEVC.10bit.FLAC.mkv"
    ) == "拜托请穿上，鹰峰同学"
    assert primary_title("间谍过家家.SPY×FAMILY.2022.S01E01.1080p.WEB-DL.mkv") == "间谍过家家"
    # 无中文段则取整名，且标题自带的年份要保留
    assert primary_title("Show.Name.2025.S01E01.1080p.BluRay.x264.mkv") == "Show Name"
    assert primary_title("Blade Runner 2049.2017.2160p.BluRay.x265.mkv") == "Blade Runner 2049"
    assert primary_title("Inception.2010.1080p.BluRay.x264.mkv") == "Inception"
