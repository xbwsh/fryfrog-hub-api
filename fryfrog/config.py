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

    # Bangumi
    bangumi_base_url: str = "https://api.bgm.tv"

    # Subsonic
    subsonic_encrypt_key: str = ""

    # Watcher
    watcher_periodic_scan: bool = True
    periodic_scan_interval: int = 30

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

    @property
    def data_dir(self) -> Path:
        return Path("data")


@lru_cache
def get_settings() -> Settings:
    return Settings()
