import os
os.environ.setdefault("DATABASE_URL", "postgresql+psycopg://test:test@localhost/test")
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from app.db import Base
from app.main import app, get_db

@pytest.fixture
def client(monkeypatch):
    # SQLite exists solely in this isolated test fixture; production rejects its URL.
    engine = create_engine("sqlite+pysqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=__import__("sqlalchemy").pool.StaticPool)
    Base.metadata.create_all(engine)
    TestingSession = sessionmaker(bind=engine, expire_on_commit=False)
    def override_db():
        db = TestingSession()
        try: yield db
        finally: db.close()
    app.dependency_overrides[get_db] = override_db
    with TestClient(app) as test:
        yield test
    app.dependency_overrides.clear()
    Base.metadata.drop_all(engine)
    engine.dispose()
