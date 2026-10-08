"""签名密钥必须持久化，否则每次重建容器都会换密钥、已签发 URL 全失效。

实测故障：容器重建后前端封面大量 401。根因是 `_load_secret` 写死相对路径
`data/media_secret.key`，Docker 下落到 `/app/data`（**容器可写层**），
而 compose 只挂了 `./db:/data` —— 密钥不在卷里，重建即丢。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest

from fryfrog.core import signer


@pytest.fixture(autouse=True)
def _clean(monkeypatch, tmp_path):
    """每个用例隔离：密钥路径指向临时目录，并清掉进程内缓存。"""
    db = tmp_path / "vol" / "fryfrog.db"
    db.parent.mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("SQLITE_PATH", str(db))
    monkeypatch.delenv("MEDIA_SECRET_KEY", raising=False)
    import importlib

    import fryfrog.config as config

    config.get_settings.cache_clear()
    signer.reset_cache()
    yield
    config.get_settings.cache_clear()
    signer.reset_cache()
    importlib.reload(config)


def test_secret_follows_database_directory(tmp_path):
    """密钥与数据库同目录 —— 即跟着持久化卷走。"""
    path = signer.secret_path()
    assert path.parent == tmp_path / "vol", f"应在库同目录，实际 {path}"
    assert path.name == "media_secret.key"


def test_secret_is_stable_across_reloads(tmp_path):
    """核心回归：清掉进程内缓存（等价于重启进程）后密钥必须不变。

    旧实现下这一步会得到新密钥 → 旧签名 URL 全部失效。
    """
    signer.reset_cache()
    sig1 = signer.sign("/api/v1/video/series/1/cover", 9999999999999)

    signer.reset_cache()  # 模拟容器重启
    assert signer.verify("/api/v1/video/series/1/cover", 9999999999999, sig1.split("sig=")[1]), (
        "重启后旧签名应仍然有效——密钥没持久化时这里会失败"
    )


def test_legacy_secret_is_migrated(tmp_path, monkeypatch):
    """旧位置有密钥时搬过去，保住既有的已签发 URL。"""
    legacy = tmp_path / "app" / "data" / "media_secret.key"
    legacy.parent.mkdir(parents=True, exist_ok=True)
    legacy.write_text("ab" * 32, encoding="utf-8")
    monkeypatch.setattr(signer, "_LEGACY_RELATIVE", legacy)
    signer.reset_cache()

    loaded = signer._load_secret()
    assert loaded == bytes.fromhex("ab" * 32), "应沿用旧密钥而不是新生成"
    assert signer.secret_path().is_file(), "旧密钥应被搬到新位置"


def test_legacy_not_overwritten_when_new_exists(tmp_path, monkeypatch):
    """新位置已有密钥时，不被旧位置的覆盖。"""
    legacy = tmp_path / "app" / "data" / "media_secret.key"
    legacy.parent.mkdir(parents=True, exist_ok=True)
    legacy.write_text("cd" * 32, encoding="utf-8")
    target = signer.secret_path()
    target.write_text("ef" * 32, encoding="utf-8")
    monkeypatch.setattr(signer, "_LEGACY_RELATIVE", legacy)
    signer.reset_cache()

    assert signer._load_secret() == bytes.fromhex("ef" * 32)
    assert legacy.is_file(), "旧文件不该被删"


def test_env_secret_wins(tmp_path, monkeypatch):
    """显式配置 MEDIA_SECRET_KEY 时直接用（多副本部署共用密钥）。"""
    monkeypatch.setenv("MEDIA_SECRET_KEY", "12" * 32)
    signer.reset_cache()
    assert signer._load_secret() == bytes.fromhex("12" * 32)
    assert not signer.secret_path().exists(), "用环境变量时不该再落盘"


def test_invalid_env_secret_ignored(tmp_path, monkeypatch):
    """非 hex 的环境变量忽略并留痕，不因此崩掉。"""
    monkeypatch.setenv("MEDIA_SECRET_KEY", "not-hex!!")
    signer.reset_cache()
    loaded = signer._load_secret()
    assert len(loaded) == 32
    assert signer.secret_path().is_file(), "应回退到落盘方式"


def test_signed_url_for_encoded_path_verifies_decoded():
    """中文/空格文件名的签名回归：签发用编码 URL，校验用解码后的 path。

    uvicorn 会把 %XX 解码成 scope["path"]，若签名覆盖编码后的串，
    `/subtitles/{中文}.srt` 这类链接永远 401（Apple 端直接把 url 喂给播放器）。
    """
    from urllib.parse import quote

    name = "第 01 集.srt"
    encoded = quote(name, safe="")
    signed = signer.sign(f"/api/v1/video/1/subtitles/{encoded}")
    query = signed.split("?", 1)[1]
    exp = int(dict(part.split("=", 1) for part in query.split("&"))["exp"])
    sig = dict(part.split("=", 1) for part in query.split("&"))["sig"]

    assert signer.verify(f"/api/v1/video/1/subtitles/{name}", exp, sig) is True
    assert signer.verify(f"/api/v1/video/1/subtitles/{encoded}", exp, sig) is True


def test_non_ascii_sig_is_rejected_without_500():
    """`?sig=中文` 之前会让 compare_digest 抛 TypeError → 匿名可打 500。"""
    exp = 9999999999999
    assert signer.verify("/api/v1/video/1/cover", exp, "中文签名") is False
    assert signer.verify("/api/v1/video/1/cover", exp, "") is False
    assert signer.verify("/api/v1/video/1/cover", exp, None) is False
