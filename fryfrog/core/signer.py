from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import time
from pathlib import Path

_SECRET: bytes | None = None
DEFAULT_TTL_MS = 7 * 24 * 3600 * 1000


def _load_secret() -> bytes:
    global _SECRET
    if _SECRET is not None:
        return _SECRET
    path = Path("data/media_secret.key")
    try:
        if path.exists():
            hex_text = path.read_text(encoding="utf-8").strip()
            if hex_text:
                _SECRET = bytes.fromhex(hex_text)
                return _SECRET
        secret = secrets.token_bytes(32)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(secret.hex(), encoding="utf-8")
        _SECRET = secret
        return _SECRET
    except Exception:
        _SECRET = secrets.token_bytes(32)
        return _SECRET


def _hmac_hex(data: str) -> str:
    return hmac.new(_load_secret(), data.encode("utf-8"), hashlib.sha256).hexdigest()


def sign(path: str, expires_at_ms: int | None = None) -> str:
    if expires_at_ms is None:
        expires_at_ms = int(time.time() * 1000) + DEFAULT_TTL_MS
    sig = _hmac_hex(f"{path}|{expires_at_ms}")
    sep = "&" if "?" in path else "?"
    return f"{path}{sep}exp={expires_at_ms}&sig={sig}"


def verify(path: str, expires_at_ms: int, sig: str | None) -> bool:
    if expires_at_ms <= int(time.time() * 1000):
        return False
    if not sig:
        return False
    expected = _hmac_hex(f"{path}|{expires_at_ms}")
    return hmac.compare_digest(expected, sig)
