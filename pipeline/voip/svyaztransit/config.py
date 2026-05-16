"""
Stranzit Audio Downloader - Configuration module (pydantic-settings).

DOWNLOAD_DIR: use ../../input for ASR integration when running from voip/svyaztransit.
"""

import os
from pathlib import Path
from typing import Optional

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class StranzitConfig(BaseSettings):
    """Stranzit LK credentials."""

    model_config = SettingsConfigDict(env_prefix="STRANZIT_", extra="ignore")
    username: Optional[str] = None
    password: Optional[str] = None


class DownloadConfig(BaseSettings):
    """Download settings."""

    model_config = SettingsConfigDict(env_prefix="", extra="ignore")
    download_dir: Path = Field(default=Path("./downloads"))

    @field_validator("download_dir", mode="before")
    @classmethod
    def validate_download_dir(cls, v):
        if v is None or (isinstance(v, Path) and str(v) == "."):
            v = os.getenv("DOWNLOAD_DIR", "./downloads")
        if isinstance(v, str):
            path = Path(v)
            path.mkdir(parents=True, exist_ok=True)
            return path
        return v

    check_interval: int = 300
    # Расписание: "HH:MM,HH:MM" в локальном часовом поясе контейнера (TZ env var).
    # Если задано — CHECK_INTERVAL игнорируется.
    schedule_times: str = ""


class FilterConfig(BaseSettings):
    """Call record filters."""

    model_config = SettingsConfigDict(env_prefix="CALL_FILTER_", extra="ignore")
    start: str = "today_start"
    end: str = "now"
    direction: str = "any"
    duration_op: str = ">="
    duration: str = "00:00:30"
    records_per_page: int = 50
    phone_number: Optional[str] = None


class DatabaseConfig(BaseSettings):
    """Database path."""

    model_config = SettingsConfigDict(env_prefix="", extra="ignore")
    database_path: Path = Field(default=Path("./stranzit_calls.db"), alias="DATABASE_PATH")


class LoggingConfig(BaseSettings):
    """Logging settings."""

    model_config = SettingsConfigDict(env_prefix="LOG_", extra="ignore")
    level: str = Field(default="INFO", alias="LEVEL")
    format: str = "%(asctime)s - %(levelname)s - %(pathname)s:%(lineno)d - %(message)s"
    log_file: Optional[str] = Field(default="watcher.log", alias="FILE")


class ScanovichConfig(BaseSettings):
    """Scanovich backend integration (optional).

    If all three fields are set, each downloaded recording is automatically
    POSTed to ``POST /api/v1/calls/bulk-upload`` after download.
    The endpoint performs filename-based manager matching via PhoneParser,
    so filenames must preserve the PBX-extension format (e.g. «1586 (504750)»).
    """

    model_config = SettingsConfigDict(env_prefix="SCANOVICH_", extra="ignore")

    url: Optional[str] = None
    email: Optional[str] = None
    password: Optional[str] = None
    delete_after_upload: bool = False

    @property
    def enabled(self) -> bool:
        return bool(self.url and self.email and self.password)


class AppConfig(BaseSettings):
    """Main application config."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_nested_delimiter="__",
        case_sensitive=False,
        extra="ignore",
    )

    stranzit: StranzitConfig = Field(default_factory=StranzitConfig)
    download: DownloadConfig = Field(default_factory=DownloadConfig)
    filters: FilterConfig = Field(default_factory=FilterConfig)
    database: DatabaseConfig = Field(default_factory=DatabaseConfig)
    logging: LoggingConfig = Field(default_factory=LoggingConfig)
    scanovich: ScanovichConfig = Field(default_factory=ScanovichConfig)

    @model_validator(mode="after")
    def apply_download_dir_from_env(self):
        if "DOWNLOAD_DIR" in os.environ:
            path = Path(os.environ["DOWNLOAD_DIR"])
            path.mkdir(parents=True, exist_ok=True)
            self.download.download_dir = path
        return self


_config_instance: Optional[AppConfig] = None


def get_config() -> AppConfig:
    """Singleton config instance."""
    global _config_instance
    if _config_instance is None:
        _config_instance = AppConfig()
    return _config_instance
