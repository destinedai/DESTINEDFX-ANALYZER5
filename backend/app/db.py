from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker
from .config import get_settings


class Base(DeclarativeBase):
    pass


def make_engine(url: str, pool_size: int = 5, max_overflow: int = 10):
    return create_engine(url, pool_pre_ping=True, pool_size=pool_size, max_overflow=max_overflow, pool_timeout=30, pool_recycle=1800)


settings = get_settings()
engine = make_engine(settings.database_url.get_secret_value(), settings.pool_size, settings.max_overflow)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
