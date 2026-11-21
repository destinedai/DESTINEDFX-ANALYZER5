import os
import stat
from functools import lru_cache
from pathlib import Path
from urllib.parse import quote

from pydantic import SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


def _read_systemd_credential(env_name: str, *, max_bytes: int = 16384) -> str:
    """Read a service-scoped credential without following links or accepting public files."""
    path_value = os.environ.get(env_name, "").strip()
    if not path_value:
        return ""
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
    try:
        fd = os.open(path_value, flags)
        try:
            info = os.fstat(fd)
            if (
                not stat.S_ISREG(info.st_mode)
                or info.st_nlink != 1
                or stat.S_IMODE(info.st_mode) & 0o077
                or info.st_uid not in (0, os.geteuid())
                or info.st_size > max_bytes
            ):
                raise ValueError(f"{env_name} is not a protected regular credential file")
            chunks = []
            remaining = max_bytes + 1
            while remaining:
                block = os.read(fd, min(4096, remaining))
                if not block:
                    break
                chunks.append(block)
                remaining -= len(block)
            value = b"".join(chunks)
            if len(value) > max_bytes:
                raise ValueError(f"{env_name} exceeds the credential size limit")
            return value.decode("utf-8").strip()
        finally:
            os.close(fd)
    except OSError as exc:
        raise ValueError(f"{env_name} could not be read securely") from exc


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", validate_default=True)
    database_url: SecretStr = SecretStr("")
    session_ttl_seconds: int = 60 * 60 * 24 * 30
    pool_size: int = 5
    max_overflow: int = 10
    admin_emails: str = ""
    max_request_bytes: int = 32_768
    rate_limit_per_minute: int = 120
    cors_origins: str = ""
    step_up_private_key_pem: SecretStr = SecretStr("")
    owner_setup_token: SecretStr = SecretStr("")
    step_up_issuer: str = "destinedfx-management"
    step_up_audience: str = "destinedfx-bridge"

    @field_validator("database_url", mode="before")
    @classmethod
    def load_database_url_from_credentials(cls, value):
        if isinstance(value, SecretStr):
            value = value.get_secret_value()
        if isinstance(value, str) and value.strip():
            return value
        direct_file = _read_systemd_credential("DATABASE_URL_FILE")
        if direct_file:
            return direct_file
        password = _read_systemd_credential("POSTGRES_PASSWORD_FILE")
        if not password:
            raise ValueError("DATABASE_URL or a protected POSTGRES_PASSWORD_FILE is required")
        username = os.environ.get("POSTGRES_USER", "destinedfx_management").strip()
        database = os.environ.get("POSTGRES_DB", "app_management").strip()
        host = os.environ.get("POSTGRES_HOST", "127.0.0.1").strip()
        port = os.environ.get("POSTGRES_PORT", "5433").strip()
        if not username or not database or not host or not port.isdigit() or not 1 <= int(port) <= 65535:
            raise ValueError("PostgreSQL connection settings are invalid")
        return (
            "postgresql+psycopg://"
            + quote(username, safe="")
            + ":"
            + quote(password, safe="")
            + "@"
            + host
            + ":"
            + port
            + "/"
            + quote(database, safe="")
        )

    @field_validator("database_url")
    @classmethod
    def postgres_only(cls, value: SecretStr) -> SecretStr:
        raw = value.get_secret_value()
        if not raw.startswith(("postgresql://", "postgresql+psycopg://")):
            raise ValueError("DATABASE_URL must use PostgreSQL (postgresql:// or postgresql+psycopg://)")
        return value

    @field_validator("step_up_private_key_pem", mode="before")
    @classmethod
    def load_step_up_key_from_credential(cls, value):
        if isinstance(value, SecretStr):
            value = value.get_secret_value()
        if isinstance(value, str) and value.strip():
            return value
        return _read_systemd_credential("STEP_UP_PRIVATE_KEY_PEM_FILE")

    @field_validator("owner_setup_token", mode="before")
    @classmethod
    def load_owner_setup_token_from_credential(cls, value):
        if isinstance(value, SecretStr):
            value = value.get_secret_value()
        if isinstance(value, str) and value.strip():
            token = value.strip()
        else:
            token = _read_systemd_credential("OWNER_SETUP_TOKEN_FILE")
        if token and (len(token) < 40 or len(token) > 512 or any(ord(ch) < 33 or ord(ch) > 126 for ch in token)):
            raise ValueError("OWNER_SETUP_TOKEN_FILE must contain a bounded printable token")
        return token

    @property
    def admins(self) -> set[str]:
        return {item.strip().lower() for item in self.admin_emails.split(",") if item.strip()}


@lru_cache
def get_settings() -> Settings:
    return Settings()
