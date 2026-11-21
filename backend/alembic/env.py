import os

from alembic import context
from sqlalchemy import engine_from_config, pool

from app.db import Base
from app.config import Settings
from app import models

config = context.config
# Alembic is a migration process, not the cached request-time application.
# A direct DATABASE_URL is intentionally honored here for isolated SQLite
# migration tests; production does not set it and resolves the DB password
# from this service's protected systemd credential file instead.
url = os.environ.get("DATABASE_URL", "").strip()
if not url:
    url = Settings().database_url.get_secret_value()
config.set_main_option("sqlalchemy.url", url.replace("%", "%%"))
target_metadata = Base.metadata


def run_migrations_offline():
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online():
    connectable = engine_from_config(
        config.get_section(config.config_ini_section),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
