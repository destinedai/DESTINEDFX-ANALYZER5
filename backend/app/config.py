from functools import lru_cache
from pydantic import SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    database_url: SecretStr
    session_ttl_seconds: int = 60 * 60 * 24 * 30
    pool_size: int = 5
    max_overflow: int = 10
    admin_emails: str = ""
    max_request_bytes: int = 32_768
    rate_limit_per_minute: int = 120
    cors_origins: str = ""

    @field_validator("database_url")
    @classmethod
    def postgres_only(cls, value: SecretStr) -> SecretStr:
        if not value.get_secret_value().startswith(("postgresql://", "postgresql+psycopg://")):
            raise ValueError("DATABASE_URL must use PostgreSQL (postgresql:// or postgresql+psycopg://)")
        return value

    @property
    def admins(self) -> set[str]:
        return {item.strip().lower() for item in self.admin_emails.split(",") if item.strip()}


@lru_cache
def get_settings() -> Settings:
    return Settings()
