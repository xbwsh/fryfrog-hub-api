from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # Server
    server_port: int = 20058

    # SQLite
    sqlite_path: str = Field(
        default="data/fryfrog.db", validation_alias=AliasChoices("SQLITE_PATH", "DB_PATH")
    )

    # Auth
    auth_enabled: bool = True
    auth_password: str = ""
    auth_token_ttl: int = 604800
    auth_login_max_failures: int = 5
    auth_login_lock_minutes: int = 15

    # Media / FFmpeg
    video_root_paths: str = ""
    ffmpeg_path: str = ""

    # TMDB
    tmdb_api_key: str = ""
    tmdb_language: str = "zh-CN"
    tmdb_image_size: str = "original"
    tmdb_include_adult: bool = True
    # 元数据服务连续失败这么多次后熔断，冷却期内直接跳过（代理抖动时不要刷爆日志/拖垮扫描）
    tmdb_failure_threshold: int = 5
    tmdb_cooldown_seconds: int = 120

    # Bangumi
    bangumi_base_url: str = "https://api.bgm.tv"

    # Subsonic
    subsonic_encrypt_key: str = ""

    # Watcher
    watcher_enabled: bool = True
    watcher_periodic_scan: bool = True
    periodic_scan_interval: int = 30
    # 文件消失后保留记录多久（秒）；宽限期满仍缺失才删行
    scan_missing_grace_seconds: int = 1800
    # 本轮文件数低于上轮该比例时视为疑似挂载异常，暂缓删除
    scan_guard_min_ratio: float = 0.5
    # 单次扫描超过多久视为卡死，允许新的触发抢占（防网络卡死永久占位）
    scan_stale_after_seconds: int = 1800

    # Proxy for scrapers（兼容 Java 的 PROXY_HOST / PROXY_PORT）
    scraper_proxy_host: str = Field(
        default="", validation_alias=AliasChoices("PROXY_HOST", "SCRAPER_PROXY_HOST")
    )
    scraper_proxy_port: int = Field(
        default=0, validation_alias=AliasChoices("PROXY_PORT", "SCRAPER_PROXY_PORT")
    )
    scraper_bypass_ssl: bool = Field(
        default=False, validation_alias=AliasChoices("SCRAPER_BYPASS_SSL")
    )

    @property
    def scraper_proxy_url(self) -> str | None:
        if self.scraper_proxy_host and self.scraper_proxy_port:
            return f"http://{self.scraper_proxy_host}:{self.scraper_proxy_port}"
        return None

    # Logging
    log_home: str = "data/logs"
    # app.log 按大小轮转，避免长期运行无限增长
    log_max_bytes: int = 10 * 1024 * 1024
    log_backup_count: int = 5

    @property
    def data_dir(self) -> Path:
        return Path("data")


@lru_cache
def get_settings() -> Settings:
    return Settings()
