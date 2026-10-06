from __future__ import annotations

import logging
import time
from typing import Any

import httpx

from fryfrog.config import get_settings
from fryfrog.core.http import make_client

logger = logging.getLogger(__name__)

TMDB_BASE = "https://api.themoviedb.org/3"
IMAGE_BASE = "https://image.tmdb.org/t/p"

# 网络类瞬时故障：这类失败降级为单行 WARNING，不打完整堆栈——
# 代理抖动时每个条目都打 traceback 会把日志刷爆（实测两周涨到 203MB）。
_TRANSIENT_ERRORS = (
    httpx.ConnectError,
    httpx.ConnectTimeout,
    httpx.ReadTimeout,
    httpx.WriteTimeout,
    httpx.PoolTimeout,
    httpx.RemoteProtocolError,
    httpx.ProxyError,
)


class TmdbClient:
    """兼容 TMDB v3 api_key 与 v4 Bearer（JWT）两种凭证。"""

    # 熔断状态是进程级的：所有实例共享，避免每个条目各失败一遍
    _consecutive_failures = 0
    _tripped_until = 0.0

    def __init__(self) -> None:
        settings = get_settings()
        self.api_key = (settings.tmdb_api_key or "").strip()
        self.language = settings.tmdb_language or "zh-CN"
        self.include_adult = settings.tmdb_include_adult
        self.image_size = settings.tmdb_image_size or "original"

    # ── 熔断 ────────────────────────────────────────────────────────────
    @classmethod
    def _tripped(cls) -> bool:
        return time.monotonic() < cls._tripped_until

    @classmethod
    def _record_success(cls) -> None:
        cls._consecutive_failures = 0

    @classmethod
    def _record_failure(cls, where: str) -> None:
        settings = get_settings()
        threshold = max(int(settings.tmdb_failure_threshold), 1)
        cls._consecutive_failures += 1
        if cls._consecutive_failures >= threshold and not cls._tripped():
            cooldown = max(int(settings.tmdb_cooldown_seconds), 1)
            cls._tripped_until = time.monotonic() + cooldown
            logger.warning(
                "TMDB 连续失败 %d 次，熔断 %d 秒（期间跳过刮削）: %s",
                cls._consecutive_failures,
                cooldown,
                where,
            )

    @classmethod
    def reset_circuit(cls) -> None:
        cls._consecutive_failures = 0
        cls._tripped_until = 0.0

    def _failed(self, where: str, exc: Exception) -> None:
        if isinstance(exc, _TRANSIENT_ERRORS):
            logger.warning("TMDB 请求失败（网络）: %s: %s", where, type(exc).__name__)
        else:
            logger.exception("TMDB 请求失败: %s", where)
        self._record_failure(where)

    @property
    def _is_jwt(self) -> bool:
        return self.api_key.startswith("eyJ")

    def _auth(self) -> tuple[dict, dict]:
        """返回 (query_params, headers)。"""
        if self._is_jwt:
            return {}, {"Authorization": f"Bearer {self.api_key}"}
        return {"api_key": self.api_key}, {}

    def _params(self, extra: dict | None = None) -> dict:
        query, _ = self._auth()
        params = dict(query)
        params["language"] = self.language
        if extra:
            params.update(extra)
        return params

    def _headers(self) -> dict:
        _, headers = self._auth()
        return headers

    def search_multi(self, query: str) -> list[dict]:
        if not self.api_key or self._tripped():
            return []
        try:
            with make_client() as client:
                resp = client.get(
                    f"{TMDB_BASE}/search/multi",
                    params=self._params(
                        {"query": query, "include_adult": str(self.include_adult).lower()}
                    ),
                    headers=self._headers(),
                )
                resp.raise_for_status()
                result = resp.json().get("results") or []
        except Exception as exc:
            self._failed(f"search/multi?query={query[:60]}", exc)
            return []
        self._record_success()
        return result

    def get_movie(self, tmdb_id: int) -> dict | None:
        return self._get(f"/movie/{tmdb_id}", {"append_to_response": "credits,images"})

    def get_tv(self, tmdb_id: int) -> dict | None:
        return self._get(f"/tv/{tmdb_id}", {"append_to_response": "credits,images"})

    def get_season(self, tmdb_id: int, season: int) -> dict | None:
        return self._get(f"/tv/{tmdb_id}/season/{season}")

    def get_episode(self, tv_id: int, season: int, episode: int) -> dict | None:
        return self._get(f"/tv/{tv_id}/season/{season}/episode/{episode}")

    def get_episode_images(self, tv_id: int, season: int, episode: int) -> dict | None:
        """单集详情并附带 images.stills（本集剧照候选），一次请求。"""
        return self._get(
            f"/tv/{tv_id}/season/{season}/episode/{episode}",
            {"append_to_response": "images"},
        )

    def get_person(self, person_id: int) -> dict | None:
        return self._get(f"/person/{person_id}", {"append_to_response": "combined_credits"})

    def get_tv_images(self, tmdb_id: int) -> dict | None:
        return self._get(f"/tv/{tmdb_id}/images")

    def image_url(self, path: str | None, size: str | None = None) -> str | None:
        if not path:
            return None
        return f"{IMAGE_BASE}/{size or self.image_size}{path}"

    def _get(self, path: str, extra: dict | None = None) -> dict | None:
        if not self.api_key or self._tripped():
            return None
        try:
            with make_client() as client:
                resp = client.get(
                    f"{TMDB_BASE}{path}",
                    params=self._params(extra),
                    headers=self._headers(),
                )
                if resp.status_code == 404:
                    return None
                resp.raise_for_status()
                data = resp.json()
        except Exception as exc:
            self._failed(path, exc)
            return None
        self._record_success()
        return data
