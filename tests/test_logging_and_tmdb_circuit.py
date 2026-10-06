"""日志轮转与 TMDB 熔断。

对应本机实测故障：
- app.log 两周涨到 203MB（无轮转）；
- 代理抖动时 TMDB 每次失败都打完整堆栈 + 无上限重试，132 次 ConnectError，
  配合高频扫描把日志刷爆、拖慢扫描。
"""

from __future__ import annotations

import logging
import logging.handlers
import os

os.environ.setdefault("AUTH_ENABLED", "false")

import httpx
import pytest

from fryfrog.services.tmdb import TmdbClient


@pytest.fixture(autouse=True)
def _reset_circuit():
    TmdbClient.reset_circuit()
    yield
    TmdbClient.reset_circuit()


def test_log_rotates_and_keeps_backups(tmp_path):
    """app.log 超过上限就轮转，最多保留 backupCount 份。"""
    handler = logging.handlers.RotatingFileHandler(
        tmp_path / "app.log", maxBytes=2048, backupCount=2, encoding="utf-8"
    )
    log = logging.getLogger("rotate-test")
    log.setLevel(logging.INFO)
    log.propagate = False
    log.addHandler(handler)
    try:
        for i in range(400):
            log.info("填充日志行 %d %s", i, "x" * 100)
    finally:
        handler.close()
        log.removeHandler(handler)

    files = sorted(p.name for p in tmp_path.iterdir())
    assert "app.log" in files
    assert len(files) <= 3, f"应为当前 + 2 份备份，实际 {files}"
    assert (tmp_path / "app.log").stat().st_size <= 2048


def test_tmdb_circuit_opens_after_repeated_failures(monkeypatch):
    """连续失败到阈值后熔断：冷却期内不再发起请求。"""
    client = TmdbClient()
    monkeypatch.setattr(client, "api_key", "fake-key")

    calls = {"n": 0}

    class _Boom:
        def __enter__(self):
            raise httpx.ConnectError("[SSL: UNEXPECTED_EOF_WHILE_READING]")

        def __exit__(self, *exc):
            return False

    def counting_client(*a, **k):
        calls["n"] += 1
        return _Boom()

    monkeypatch.setattr("fryfrog.services.tmdb.make_client", counting_client)

    for _ in range(5):  # 与默认 tmdb_failure_threshold 一致
        client.search_multi("test")

    assert calls["n"] == 5, "阈值内应逐次尝试"
    assert TmdbClient._tripped() is True, "达到阈值后应熔断"

    # 熔断期间：不再发请求
    before = calls["n"]
    assert client.search_multi("another") == []
    assert client.get_movie(123) is None
    assert calls["n"] == before, "熔断期间不得再发起请求"


def test_tmdb_success_resets_failure_counter(monkeypatch):
    """成功一次即清零，避免偶发抖动把服务打成熔断。"""
    client = TmdbClient()
    monkeypatch.setattr(client, "api_key", "fake-key")

    class _Boom:
        def __enter__(self):
            raise httpx.ReadTimeout("slow")

        def __exit__(self, *exc):
            return False

    monkeypatch.setattr("fryfrog.services.tmdb.make_client", lambda *a, **k: _Boom())
    for _ in range(3):
        client.search_multi("x")
    assert TmdbClient._consecutive_failures == 3

    class _Ok:
        """最小可用的假 httpx client：client.get(...) 返回带 json/raise_for_status 的响应。"""

        class _Resp:
            status_code = 200

            def raise_for_status(self):
                return None

            def json(self):
                return {"results": [{"id": 1}]}

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def get(self, *a, **k):
            return self._Resp()

    monkeypatch.setattr("fryfrog.services.tmdb.make_client", lambda *a, **k: _Ok())
    assert client.search_multi("good") == [{"id": 1}]
    assert TmdbClient._consecutive_failures == 0
    assert TmdbClient._tripped() is False


def test_transient_failure_logs_single_line(caplog, monkeypatch):
    """网络类失败只打一行 WARNING，不打完整堆栈（日志体积的主因）。"""
    client = TmdbClient()
    monkeypatch.setattr(client, "api_key", "fake-key")

    class _Boom:
        def __enter__(self):
            raise httpx.ConnectError("boom")

        def __exit__(self, *exc):
            return False

    monkeypatch.setattr("fryfrog.services.tmdb.make_client", lambda *a, **k: _Boom())

    with caplog.at_level(logging.WARNING, logger="fryfrog.services.tmdb"):
        client.search_multi("q")

    records = [r for r in caplog.records if r.name == "fryfrog.services.tmdb"]
    assert len(records) == 1, f"应只有一条日志，实际 {len(records)}"
    assert records[0].levelno == logging.WARNING
    assert records[0].exc_info is None, "瞬时网络错误不该带堆栈"
