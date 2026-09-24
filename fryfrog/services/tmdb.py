from __future__ import annotations

import logging
from typing import Any

from fryfrog.config import get_settings
from fryfrog.core.http import make_client

logger = logging.getLogger(__name__)

TMDB_BASE = "https://api.themoviedb.org/3"
IMAGE_BASE = "https://image.tmdb.org/t"


class TmdbClient:
    """兼容 TMDB v3 api_key 与 v4 Bearer（JWT）两种凭证。"""

    def __init__(self) -> None:
        settings = get_settings()
        self.api_key = (settings.tmdb_api_key or "").strip()
        self.language = settings.tmdb_language or "zh-CN"
        self.include_adult = settings.tmdb_include_adult
        self.image_size = settings.tmdb_image_size or "original"

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
        if not self.api_key:
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
                return resp.json().get("results") or []
        except Exception:
            logger.exception("TMDB search failed")
            return []

    def get_movie(self, tmdb_id: int) -> dict | None:
        return self._get(f"/movie/{tmdb_id}", {"append_to_response": "credits,images"})

    def get_tv(self, tmdb_id: int) -> dict | None:
        return self._get(f"/tv/{tmdb_id}", {"append_to_response": "credits,images"})

    def get_season(self, tmdb_id: int, season: int) -> dict | None:
        return self._get(f"/tv/{tmdb_id}/season/{season}")

    def get_episode(self, tv_id: int, season: int, episode: int) -> dict | None:
        return self._get(f"/tv/{tv_id}/season/{season}/episode/{episode}")

    def get_person(self, person_id: int) -> dict | None:
        return self._get(f"/person/{person_id}", {"append_to_response": "combined_credits"})

    def get_tv_images(self, tmdb_id: int) -> dict | None:
        return self._get(f"/tv/{tmdb_id}/images")

    def image_url(self, path: str | None, size: str | None = None) -> str | None:
        if not path:
            return None
        return f"{IMAGE_BASE}/{size or self.image_size}{path}"

    def _get(self, path: str, extra: dict | None = None) -> dict | None:
        if not self.api_key:
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
                return resp.json()
        except Exception:
            logger.exception("TMDB get failed: %s", path)
            return None
