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
