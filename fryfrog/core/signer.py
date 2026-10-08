from __future__ import annotations

import hashlib
import hmac
import logging
import os
import secrets
import shutil
import time
from pathlib import Path
from urllib.parse import unquote

logger = logging.getLogger(__name__)

_SECRET: bytes | None = None
DEFAULT_TTL_MS = 7 * 24 * 3600 * 1000

# 旧位置：相对 WORKDIR（Docker 下是 /app/data），**在容器可写层里**。
# 每次 `docker compose up -d` 换镜像重建容器都会丢，于是密钥重生、
# 此前签发的所有图片/流 URL 全部 401（实测前端封面大量 401）。
_LEGACY_RELATIVE = Path("data/media_secret.key")
_SECRET_NAME = "media_secret.key"


def secret_path() -> Path:
    """签名密钥路径：与数据库同目录，保证跟着持久化卷走。

    之前写死相对路径 `data/media_secret.key`，容器里落到 /app/data（可写层），
    而 compose 只挂了 ./db:/data —— 密钥不在卷里，重建容器即失效。
    SQLITE_PATH 是运维已正确持久化的路径，用它的目录最稳。
    """
    from fryfrog.config import get_settings

    return Path(get_settings().sqlite_path).parent / _SECRET_NAME


def _env_secret() -> bytes | None:
    raw = os.environ.get("MEDIA_SECRET_KEY", "").strip()
    if not raw:
        return None
    try:
        return bytes.fromhex(raw)
    except ValueError:
        logger.warning("MEDIA_SECRET_KEY 不是合法 hex，已忽略")
        return None


def _load_secret() -> bytes:
    global _SECRET
    if _SECRET is not None:
        return _SECRET

    env = _env_secret()
    if env is not None:
        _SECRET = env
        return _SECRET

    path = secret_path()

    # 迁移：新位置没有但旧位置有 → 搬过去（保住已签发 URL 的有效性）
    if not path.exists() and _LEGACY_RELATIVE.exists() and _LEGACY_RELATIVE != path:
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(_LEGACY_RELATIVE), str(path))
            logger.info("已把签名密钥迁移到持久化目录: %s", path)
        except Exception:
            logger.warning("签名密钥迁移失败，继续尝试按新位置处理", exc_info=True)

    try:
        if path.exists():
            hex_text = path.read_text(encoding="utf-8").strip()
            if hex_text:
                _SECRET = bytes.fromhex(hex_text)
                return _SECRET
        secret = secrets.token_bytes(32)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(secret.hex(), encoding="utf-8")
        # 收紧到仅属主可读：默认 umask 下是 0644，多用户主机上其他本地用户
        # 读到密钥即可伪造全部签名媒体 URL
        import os as _os

        try:
            _os.chmod(path, 0o600)
        except OSError:
            logger.warning("签名密钥文件权限收紧失败: %s", path, exc_info=True)
        _SECRET = secret
        logger.info("已生成签名密钥: %s", path)
        return _SECRET
    except Exception:
        # 兜底不炸，但要留痕：写不进去意味着每次重启都会换密钥，
        # 所有已签发的图片/流 URL 会失效——这个失败以前是完全静默的。
        logger.warning(
            "签名密钥无法持久化（%s），本次使用临时密钥；重启后已签发 URL 将失效",
            path,
            exc_info=True,
        )
        _SECRET = secrets.token_bytes(32)
        return _SECRET


def reset_cache() -> None:
    """仅供测试：清掉进程内缓存，强制重新读取。"""
    global _SECRET
    _SECRET = None


def _hmac_hex(data: str) -> str:
    return hmac.new(_load_secret(), data.encode("utf-8"), hashlib.sha256).hexdigest()


def _signable(path: str) -> str:
    """签名对象统一成「服务端解码后的 path」。

    uvicorn 会把 URL 里的 %XX 解码后再交给中间件校验（`scope["path"]`），
    而对编码后的串签名会让中文/带空格的文件名（字幕等）永远 401——
    签发端与校验端必须用同一种形态。
    """
    return unquote(path)


def sign(path: str, expires_at_ms: int | None = None) -> str:
    if expires_at_ms is None:
        expires_at_ms = int(time.time() * 1000) + DEFAULT_TTL_MS
    sig = _hmac_hex(f"{_signable(path)}|{expires_at_ms}")
    sep = "&" if "?" in path else "?"
    return f"{path}{sep}exp={expires_at_ms}&sig={sig}"


def verify(path: str, expires_at_ms: int, sig: str | None) -> bool:
    if expires_at_ms <= int(time.time() * 1000):
        return False
    # 非 ASCII 的 sig 必然不合法，直接判失败：compare_digest 对非 ASCII str
    # 会抛 TypeError，让任意匿名请求（?sig=中文）都能打出 500
    if not sig or not sig.isascii():
        return False
    expected = _hmac_hex(f"{_signable(path)}|{expires_at_ms}")
    return hmac.compare_digest(expected, sig)
