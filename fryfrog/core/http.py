from __future__ import annotations

import logging

import httpx

from fryfrog.config import get_settings

logger = logging.getLogger(__name__)


def make_client(timeout: float = 20.0) -> httpx.Client:
    """刮削/下载用 HTTP 客户端，自动带代理（若有）。"""
    settings = get_settings()
    proxy = settings.scraper_proxy_url
    kwargs: dict = {"timeout": timeout, "follow_redirects": True}
    if proxy:
        kwargs["proxy"] = proxy
    if settings.scraper_bypass_ssl:
        kwargs["verify"] = False
    return httpx.Client(**kwargs)


def make_async_client(timeout: float = 20.0) -> httpx.AsyncClient:
    settings = get_settings()
    proxy = settings.scraper_proxy_url
    kwargs: dict = {"timeout": timeout, "follow_redirects": True}
    if proxy:
        kwargs["proxy"] = proxy
    if settings.scraper_bypass_ssl:
        kwargs["verify"] = False
    return httpx.AsyncClient(**kwargs)
