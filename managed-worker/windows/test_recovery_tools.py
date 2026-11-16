import json
import hashlib
import importlib.util
import sqlite3
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).with_name("restore-bridge.py")
SPEC = importlib.util.spec_from_file_location("restore_bridge", MODULE_PATH)
restore_bridge = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(restore_bridge)
restore = restore_bridge.restore
validate_snapshot = restore_bridge.validate_snapshot


def make_snapshot(tmp_path: Path) -> Path:
    snapshot = tmp_path / "backup-20261116-000000"
    snapshot.mkdir()
    with sqlite3.connect(snapshot / "bridge.sqlite3") as connection:
        connection.execute("CREATE TABLE fixture (value INTEGER)")
        connection.execute("INSERT INTO fixture VALUES (42)")
        connection.commit()
    (snapshot / "owner-secrets.dpapi").write_text("DPAPI-CIPHERTEXT", encoding="utf-8")
    (snapshot / "worker-id.txt").write_text("worker-test-1", encoding="utf-8")
    (snapshot / "worker.toml").write_text("control_plane_url = \"https://host.example.ts.net\"\n", encoding="utf-8")
    manifest = {
        "format": 1,
        "files": {
            path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in snapshot.iterdir()
        },
    }
    (snapshot / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return snapshot


def test_restore_validates_integrity_and_preserves_state(tmp_path):
    snapshot = make_snapshot(tmp_path)
    target = tmp_path / "restored"
    restore(snapshot, target)
    with sqlite3.connect(target / "data" / "bridge.sqlite3") as connection:
        assert connection.execute("SELECT value FROM fixture").fetchone() == (42,)
    assert (target / "config" / "worker-id.txt").read_text() == "worker-test-1"


def test_restore_refuses_checksum_mismatch(tmp_path):
    snapshot = make_snapshot(tmp_path)
    (snapshot / "worker.toml").write_text("tampered", encoding="utf-8")
    with pytest.raises(ValueError, match="checksum"):
        validate_snapshot(snapshot)


def test_restore_does_not_overwrite_existing_target_without_explicit_replace(tmp_path):
    snapshot = make_snapshot(tmp_path)
    target = tmp_path / "restored"
    (target / "data").mkdir(parents=True)
    (target / "data" / "bridge.sqlite3").write_text("existing", encoding="utf-8")
    with pytest.raises(FileExistsError):
        restore(snapshot, target)
