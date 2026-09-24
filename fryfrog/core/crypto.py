from __future__ import annotations

import base64
import logging

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

logger = logging.getLogger(__name__)

ENCRYPTED_PREFIX = "enc2:"


class SubsonicPasswordEncryptor:
    """AES-256-GCM 加解密，密钥来自 SUBSONIC_ENCRYPT_KEY（Base64 32 字节）。"""

    def __init__(self, base64_key: str | None):
        self.key: bytes | None = None
        if base64_key:
            try:
                key = base64.b64decode(base64_key, validate=True)
            except Exception:
                key = base64.b64decode(base64_key + "=" * (-len(base64_key) % 4))
            if len(key) != 32:
                logger.error(
                    "SUBSONIC_ENCRYPT_KEY 必须是 Base64 编码的 32 字节密钥，当前无效，已回退明文存储"
                )
                self.key = None
            else:
                self.key = key
                logger.info("Subsonic password encryption enabled (AES-256-GCM)")
        else:
            logger.info("Subsonic password encryption disabled (no key configured)")

    def encrypt(self, plain_text: str | None) -> str | None:
        if plain_text is None or self.key is None:
            return plain_text
        try:
            import os

            iv = os.urandom(12)
            aes = AESGCM(self.key)
            encrypted = aes.encrypt(iv, plain_text.encode("utf-8"), None)
            return (
                ENCRYPTED_PREFIX
                + base64.b64encode(iv).decode()
                + ":"
                + base64.b64encode(encrypted).decode()
            )
        except Exception:
            logger.exception("Failed to encrypt subsonic password, storing plaintext")
            return plain_text

    def decrypt(self, cipher_text: str | None) -> str | None:
        if cipher_text is None or self.key is None or not cipher_text.startswith(ENCRYPTED_PREFIX):
            return cipher_text
        try:
            payload = cipher_text[len(ENCRYPTED_PREFIX) :]
            iv_b64, data_b64 = payload.split(":", 1)
            iv = base64.b64decode(iv_b64)
            encrypted = base64.b64decode(data_b64)
            aes = AESGCM(self.key)
            return aes.decrypt(iv, encrypted, None).decode("utf-8")
        except Exception:
            logger.exception("Failed to decrypt subsonic password")
            return cipher_text

    def is_encryption_enabled(self) -> bool:
        return self.key is not None
