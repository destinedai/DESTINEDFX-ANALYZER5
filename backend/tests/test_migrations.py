from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect


def test_initial_migration_creates_and_downgrades_schema(tmp_path, monkeypatch):
    database_path = tmp_path / "migration-test.sqlite3"
    monkeypatch.setenv("DATABASE_URL", f"sqlite+pysqlite:///{database_path}")
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))

    command.upgrade(config, "head")
    engine = create_engine(f"sqlite+pysqlite:///{database_path}")
    assert set(inspect(engine).get_table_names()) >= {
        "alembic_version", "users", "sessions", "preferences", "devices", "audit_events"
    }
    engine.dispose()

    command.downgrade(config, "base")
    engine = create_engine(f"sqlite+pysqlite:///{database_path}")
    assert not set(inspect(engine).get_table_names()) & {
        "users", "sessions", "preferences", "devices", "audit_events"
    }
    engine.dispose()
