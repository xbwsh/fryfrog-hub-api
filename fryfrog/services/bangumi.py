from __future__ import annotations

import logging
from typing import Any

import httpx

from fryfrog.config import get_settings
from fryfrog.core.http import make_client

logger = logging.getLogger(__name__)

USER_AGENT = "fryfrog-hub/1.0 (https://github.com/xiamu/fryfrog-hub-api)"
TYPE_BOOK = 1
TYPE_REAL = 6


class BangumiClient:
    def __init__(self) -> None:
        settings = get_settings()
        self.base_url = (settings.bangumi_base_url or "https://api.bgm.tv").rstrip("/")

    def _headers(self) -> dict[str, str]:
        return {
            "User-Agent": USER_AGENT,
            "Content-Type": "application/json",
            "Accept": "application/json",
        }

    def search_subjects(self, keyword: str, types: list[int] | None = None) -> list:
        body: dict[str, Any] = {"keyword": keyword}
        if types:
            body["filter"] = {"type": types}
        try:
            with make_client() as client:
                resp = client.post(
                    f"{self.base_url}/v0/search/subjects?limit=20",
                    json=body,
                    headers=self._headers(),
                )
                resp.raise_for_status()
                return resp.json().get("data") or []
        except Exception:
            logger.exception("Bangumi search failed: %s", keyword)
            return []

    def get_subject(self, source_id: str) -> dict | None:
        if not source_id or not source_id.isdigit():
            return None
        try:
            with make_client() as client:
                resp = client.get(
                    f"{self.base_url}/v0/subjects/{source_id}",
                    headers=self._headers(),
                )
                if resp.status_code == 404:
                    return None
                resp.raise_for_status()
                return resp.json()
        except Exception:
            logger.exception("Bangumi subject failed: %s", source_id)
            return None

    @staticmethod
    def infobox_value(subject: dict, *keys: str) -> str | None:
        for key in keys:
            for item in subject.get("infobox") or []:
                if item.get("key") != key:
                    continue
                text = BangumiClient._infobox_text(item.get("value"))
                if text:
                    return text
        return None

    @staticmethod
    def _infobox_text(value: Any) -> str | None:
        if value is None:
            return None
        if isinstance(value, str):
            return value or None
        if isinstance(value, list):
            parts = []
            for el in value:
                if isinstance(el, dict) and el.get("v"):
                    parts.append(str(el["v"]))
                elif isinstance(el, str):
                    parts.append(el)
            return "、".join(parts) if parts else None
        return str(value)
