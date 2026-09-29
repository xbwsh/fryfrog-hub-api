"""视频路由包：按域拆分为浏览 / 刮削 / 素材 / 播放 / 系列。"""
from __future__ import annotations

from fastapi import APIRouter

from . import assets, browse, playback, scrape, series

router = APIRouter(prefix="/api/v1/video", tags=["视频"])
router.include_router(browse.router)
router.include_router(scrape.router)
router.include_router(assets.router)
router.include_router(playback.router)
router.include_router(series.series_router)

__all__ = ["router"]
