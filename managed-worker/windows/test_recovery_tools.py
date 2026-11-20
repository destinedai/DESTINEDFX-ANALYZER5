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
HEALTH_SPEC = importlib.util.spec_from_file_location(
    "health_monitor", Path(__file__).with_name("health-monitor.py")
)
health_monitor = importlib.util.module_from_spec(HEALTH_SPEC)
assert HEALTH_SPEC and HEALTH_SPEC.loader
HEALTH_SPEC.loader.exec_module(health_monitor)


def make_snapshot(tmp_path: Path) -> Path:
    snapshot = tmp_path / "backup-20261116-000000"
    snapshot.mkdir()
    with sqlite3.connect(snapshot / "bridge.sqlite3") as connection:
        connection.execute("CREATE TABLE fixture (value INTEGER)")
        connection.execute("INSERT INTO fixture VALUES (42)")
        connection.commit()
    (snapshot / "owner-secrets.dpapi").write_text("DPAPI-CIPHERTEXT", encoding="utf-8")
    (snapshot / "worker-id.txt").write_text("worker-test-1", encoding="utf-8")
    (snapshot / "worker.toml").write_text(
        'control_plane_url = "https://host.example.ts.net"\ndata_root = "C:/ProgramData/DestinedFX/accounts"\n',
        encoding="utf-8",
    )
    accounts = snapshot / "accounts" / "job_123" / "terminal"
    accounts.mkdir(parents=True)
    (accounts / ".destinedfx-worker.json").write_text('{"job_id":"job_123"}', encoding="utf-8")
    manifest = {
        "format": 2,
        "worker_id": "worker-test-1",
        "host_generation": "synthetic-generation",
        "files": {
            path.relative_to(snapshot).as_posix(): {
                "size": path.stat().st_size,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
            for path in snapshot.rglob("*")
            if path.is_file()
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
    assert (target / "accounts" / "job_123" / "terminal" / ".destinedfx-worker.json").is_file()


def test_restore_refuses_checksum_mismatch(tmp_path):
    snapshot = make_snapshot(tmp_path)
    (snapshot / "worker.toml").write_text("tampered", encoding="utf-8")
    with pytest.raises(ValueError, match="checksum"):
        validate_snapshot(snapshot)


def test_restore_refuses_unsafe_inventory_path(tmp_path):
    snapshot = make_snapshot(tmp_path)
    manifest = json.loads((snapshot / "manifest.json").read_text())
    manifest["files"]["../escape"] = {"size": 0, "sha256": ""}
    (snapshot / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="unsafe backup path"):
        validate_snapshot(snapshot)


def test_restore_does_not_overwrite_existing_target_without_explicit_replace(tmp_path):
    snapshot = make_snapshot(tmp_path)
    target = tmp_path / "restored"
    (target / "data").mkdir(parents=True)
    (target / "data" / "bridge.sqlite3").write_text("existing", encoding="utf-8")
    with pytest.raises(FileExistsError):
        restore(snapshot, target)


def test_restore_is_atomic_when_activation_cannot_replace_target(tmp_path, monkeypatch):
    snapshot = make_snapshot(tmp_path)
    target = tmp_path / "restored"
    target.mkdir()
    original = target / "sentinel.txt"
    original.write_text("original", encoding="utf-8")
    real_rename = Path.rename
    monkeypatch.setattr(
        Path,
        "rename",
        lambda self, destination: (_ for _ in ()).throw(OSError("cutover failed"))
        if self == target else real_rename(self, destination),
    )
    with pytest.raises(OSError, match="cutover failed"):
        restore(snapshot, target, replace=True)
    assert original.read_text(encoding="utf-8") == "original"


def test_health_monitor_separates_infrastructure_from_account_readiness(tmp_path):
    result = health_monitor.check(
        "http://127.0.0.1:1",
        timeout=1,
        data_root=tmp_path,
        backup_root=tmp_path / "backups",
    )
    assert result["infrastructure_healthy"] is False
    assert result["account_ready"] is False
    assert "BRIDGE_UNHEALTHY" in result["states"]
    assert "BACKUP_MISSING" in result["states"]
