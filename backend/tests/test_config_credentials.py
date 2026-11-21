import os

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from pydantic import ValidationError

from app.config import Settings


def _private_file(path, value):
    path.write_text(value, encoding="utf-8")
    path.chmod(0o600)
    return path


def test_settings_builds_postgres_url_from_protected_password_file(monkeypatch, tmp_path):
    password_file = _private_file(tmp_path / "postgres-password", "local-db:password/#")
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.delenv("DATABASE_URL_FILE", raising=False)
    monkeypatch.setenv("POSTGRES_PASSWORD_FILE", str(password_file))
    monkeypatch.setenv("POSTGRES_USER", "destinedfx_management")
    monkeypatch.setenv("POSTGRES_DB", "app_management")
    monkeypatch.setenv("POSTGRES_HOST", "127.0.0.1")
    monkeypatch.setenv("POSTGRES_PORT", "5433")

    settings = Settings(_env_file=None)
    url = settings.database_url.get_secret_value()
    assert url == (
        "postgresql+psycopg://destinedfx_management:local-db%3Apassword%2F%23"
        "@127.0.0.1:5433/app_management"
    )
    assert "local-db:password" not in url


def test_settings_rejects_world_readable_postgres_password_file(monkeypatch, tmp_path):
    password_file = tmp_path / "postgres-password"
    password_file.write_text("not-private", encoding="utf-8")
    password_file.chmod(0o644)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.delenv("DATABASE_URL_FILE", raising=False)
    monkeypatch.setenv("POSTGRES_PASSWORD_FILE", str(password_file))

    with pytest.raises(ValidationError):
        Settings(_env_file=None)


def test_settings_loads_management_signing_key_from_protected_credential(monkeypatch, tmp_path):
    key = Ed25519PrivateKey.generate()
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode("ascii")
    key_file = _private_file(tmp_path / "step-up-private-key", pem)
    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://test:test@127.0.0.1/test")
    monkeypatch.delenv("STEP_UP_PRIVATE_KEY_PEM", raising=False)
    monkeypatch.setenv("STEP_UP_PRIVATE_KEY_PEM_FILE", str(key_file))

    settings = Settings(_env_file=None)
    loaded = settings.step_up_private_key_pem.get_secret_value()
    restored = serialization.load_pem_private_key(loaded.encode("ascii"), password=None)
    assert restored.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    ) == key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )


def test_settings_rejects_public_management_signing_key_file(monkeypatch, tmp_path):
    key = Ed25519PrivateKey.generate()
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode("ascii")
    key_file = tmp_path / "private.pem"
    key_file.write_text(pem, encoding="ascii")
    key_file.chmod(0o644)
    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://test:test@127.0.0.1/test")
    monkeypatch.delenv("STEP_UP_PRIVATE_KEY_PEM", raising=False)
    monkeypatch.setenv("STEP_UP_PRIVATE_KEY_PEM_FILE", str(key_file))

    with pytest.raises(ValidationError):
        Settings(_env_file=None)
