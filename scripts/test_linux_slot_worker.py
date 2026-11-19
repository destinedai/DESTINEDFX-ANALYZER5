from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# The slot worker imports owner lifecycle helpers and the Windows/Linux shared
# worker implementation from these source directories when loaded as a module.
sys.path.insert(0, str(ROOT / "bridge"))
sys.path.insert(0, str(ROOT / "managed-worker"))
sys.path.insert(0, str(ROOT / "scripts" / "linux-host"))
MODULE = ROOT / "scripts" / "linux-host" / "linux-slot-worker.py"
SPEC = importlib.util.spec_from_file_location("linux_slot_worker", MODULE)
linux_slot_worker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(linux_slot_worker)


def test_slot_capacity_is_zero_when_total_ram_cannot_reserve_os_and_terminal(monkeypatch):
    monkeypatch.setattr(linux_slot_worker, "_memory_total_bytes", lambda: 4 * 1024**3)
    monkeypatch.setattr(linux_slot_worker.os, "cpu_count", lambda: 8)
    assert linux_slot_worker.capacity() == 0


def test_slot_capacity_counts_complete_three_gib_slots_after_os_reserve(monkeypatch):
    monkeypatch.setattr(linux_slot_worker, "_memory_total_bytes", lambda: 8 * 1024**3)
    monkeypatch.setattr(linux_slot_worker.os, "cpu_count", lambda: 8)
    monkeypatch.setattr(linux_slot_worker, "MAX_SLOT_CONFIG", 4)
    assert linux_slot_worker.capacity() == 2


def test_slot_capacity_is_bounded_by_cpu_config_and_total_ram(monkeypatch):
    monkeypatch.setattr(linux_slot_worker, "_memory_total_bytes", lambda: 24 * 1024**3)
    monkeypatch.setattr(linux_slot_worker.os, "cpu_count", lambda: 2)
    monkeypatch.setattr(linux_slot_worker, "MAX_SLOT_CONFIG", 6)
    assert linux_slot_worker.capacity() == 2


def test_low_available_ram_pauses_new_provisioning_claims(monkeypatch):
    calls = []
    monkeypatch.setattr(linux_slot_worker, "_active_manifests", lambda: [{"status": "READY"}])
    monkeypatch.setattr(linux_slot_worker, "capacity", lambda: 2)
    monkeypatch.setattr(linux_slot_worker, "_memory_available_bytes", lambda: 2 * 1024**3)
    monkeypatch.setattr(linux_slot_worker, "_api", lambda method, path, payload, timeout=20: calls.append(payload) or {"job": None})
    assert linux_slot_worker._run_once_unlocked() == "NO_JOB"
    assert calls[0]["pause_provisioning"] is True
    assert calls[0]["active_terminals"] == 1
    assert calls[0]["max_terminals"] == 2


def test_slot_worker_internal_agent_request_strips_health_ready_suffix(monkeypatch):
    requests = []

    class Response:
        def __enter__(self):
            return self
        def __exit__(self, *_args):
            return False
        def read(self, _limit):
            return b'{"verified":true}'

    def fake_open(request, timeout):
        requests.append(request.full_url)
        return Response()

    monkeypatch.setattr(linux_slot_worker, "HOST_AGENT_URL", "http://127.0.0.1:8787/health/ready")
    monkeypatch.setattr(linux_slot_worker, "HOST_AGENT_TOKEN", "fixture-host-agent-token-123456789012")
    monkeypatch.setattr(linux_slot_worker.urllib.request, "urlopen", fake_open)
    assert linux_slot_worker._agent("/internal/slot/reservation", {}) == {"verified": True}
    assert requests == ["http://127.0.0.1:8787/internal/slot/reservation"]


def test_slot_file_writer_atomically_replaces_existing_marker(monkeypatch, tmp_path):
    target = tmp_path / "terminal" / ".destinedfx-worker.json"
    target.parent.mkdir(parents=True)

    class LocalSlotRunner:
        def run_as_slot(self, _reservation, argv, *, input_text=None, timeout=20):
            return subprocess.run(
                [sys.executable, *argv[1:]],
                input=input_text,
                text=True,
                capture_output=True,
                timeout=timeout,
                check=True,
            )

    monkeypatch.setattr(linux_slot_worker, "_MANAGER", LocalSlotRunner(), raising=False)
    linux_slot_worker._write_slot_file(object(), target, '{"status":"LAUNCH_PENDING"}')
    linux_slot_worker._write_slot_file(object(), target, '{"status":"AUTHENTICATING"}')
    assert target.read_text(encoding="utf-8") == '{"status":"AUTHENTICATING"}'
    assert target.stat().st_mode & 0o777 == 0o600
    assert not list(target.parent.glob(".*.tmp"))


def test_worker_token_is_loaded_from_owner_only_file(monkeypatch, tmp_path):
    token_file = tmp_path / "worker.token"
    token_file.write_text("named-worker.fixture-token")
    token_file.chmod(0o600)
    monkeypatch.setattr(linux_slot_worker, "WORKER_TOKEN_FILE", str(token_file))
    assert linux_slot_worker._load_worker_token() == "named-worker.fixture-token"


def test_worker_token_file_with_group_or_other_access_is_rejected(monkeypatch, tmp_path):
    token_file = tmp_path / "worker.token"
    token_file.write_text("named-worker.fixture-token")
    token_file.chmod(0o644)
    monkeypatch.setattr(linux_slot_worker, "WORKER_TOKEN_FILE", str(token_file))
    try:
        linux_slot_worker._load_worker_token()
    except linux_slot_worker.SlotWorkerError as exc:
        assert exc.code == "WORKER_TOKEN_FILE_PERMISSIONS_INVALID"
    else:
        raise AssertionError("worker credential files must be private")


def test_codespaces_start_script_fails_closed_when_memory_floor_not_met():
    script = (ROOT / "deploy" / "codespaces" / "start.sh").read_text(encoding="utf-8")
    assert "memory_slots = max(0, (total - 2 * 1024**3) // (3 * 1024**3))" in script
    assert "if available < 3 * 1024**3:" in script
    assert "DESTINEDFX_WORKER_TOKEN_FILE" in script
    assert "owner_ctl.py ensure-worker" in script
    assert "DESTINEDFX_SLOT_MAX_TERMINALS < 1" in script
    assert "No broker credentials will be provisioned" in script
