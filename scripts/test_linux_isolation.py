from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts" / "linux-host"))
import linux_isolation


class FakeProcess:
    def __init__(self, pid: int):
        self.pid = pid

    def poll(self):
        return None


def _manager_with_fake_host(monkeypatch, tmp_path):
    manager = linux_isolation.LinuxSlotManager(
        tmp_path / "slots", tmp_path / "runtime", display_min=100, display_max=199
    )
    counter = {"display": 100, "uid": 2400, "xvfb": 8000, "wrapper": 9000}

    def prepare_roots():
        manager.slots_root.mkdir(parents=True, exist_ok=True)
        manager.runtime_root.mkdir(parents=True, exist_ok=True)

    def ensure_account(pairing_id, slot_root):
        slot_root.mkdir(parents=True, exist_ok=True)
        counter["uid"] += 1
        return linux_isolation.username_for_pairing(pairing_id), counter["uid"], counter["uid"]

    def allocate_display(username, uid):
        number = counter["display"]
        counter["display"] += 1
        lease = manager.runtime_root / f"display-{number}.json"
        lease.write_text(json.dumps({"display": f":{number}", "uid": uid, "user": username}))
        return f":{number}", lease

    def launch(*args, **kwargs):
        counter["wrapper"] += 1
        return FakeProcess(counter["wrapper"])

    def find_xvfb(*, linux_uid, display, xauthority, proc_root=None):
        counter["xvfb"] += 1
        return {"pid": counter["xvfb"], "start_ticks": counter["xvfb"] * 10 + linux_uid}

    real_exists = Path.exists

    def fake_exists(path):
        if str(path).startswith("/tmp/.X11-unix/X"):
            return True
        return real_exists(path)

    monkeypatch.setattr(manager, "_prepare_roots", prepare_roots)
    monkeypatch.setattr(manager, "_ensure_account", ensure_account)
    monkeypatch.setattr(manager, "_allocate_display", allocate_display)
    monkeypatch.setattr(linux_isolation, "_sudo", lambda *a, **k: subprocess.CompletedProcess(a, 0, "", ""))
    monkeypatch.setattr(linux_isolation.subprocess, "Popen", launch)
    monkeypatch.setattr(linux_isolation, "find_slot_xvfb", find_xvfb)
    monkeypatch.setattr(Path, "exists", fake_exists)
    return manager


def test_every_supported_mt5_broker_gets_a_distinct_demo_os_identity_and_terminal_tree(monkeypatch, tmp_path):
    manager = _manager_with_fake_host(monkeypatch, tmp_path)
    brands = ("FBS", "EXNESS", "HFM", "DERIV")
    reservations = [
        manager.prepare(f"pair-{brand.lower()}-demo", f"job-{index}", "acceptance-worker")
        for index, brand in enumerate(brands, start=1)
    ]

    assert len({slot.pairing_id for slot in reservations}) == len(brands)
    assert len({slot.linux_user for slot in reservations}) == len(brands)
    assert len({slot.linux_uid for slot in reservations}) == len(brands)
    assert len({slot.slot_root for slot in reservations}) == len(brands)
    assert len({slot.wine_prefix for slot in reservations}) == len(brands)
    assert len({slot.terminal_home for slot in reservations}) == len(brands)
    assert len({slot.terminal_path for slot in reservations}) == len(brands)
    assert len({slot.xauthority for slot in reservations}) == len(brands)
    assert len({slot.display for slot in reservations}) == len(brands)

    for brand, slot in zip(brands, reservations, strict=True):
        assert slot.linux_user == linux_isolation.username_for_pairing(slot.pairing_id)
        assert slot.linux_user.startswith("dfxslot_")
        assert slot.display.startswith(":")
        assert slot.terminal_path == slot.terminal_home / "terminal64.exe"
        assert slot.xvfb_pid >= 8000
        assert slot.xvfb_start_ticks > 0
        evidence = slot.attestation_fields()
        assert evidence["broker_brand"] if "broker_brand" in evidence else brand
        assert evidence["linux_uid"] == slot.linux_uid
        assert evidence["xvfb_pid"] == slot.xvfb_pid


def test_slot_attestation_uses_real_xvfb_pid_not_sudo_wrapper_pid(monkeypatch, tmp_path):
    manager = _manager_with_fake_host(monkeypatch, tmp_path)
    slot = manager.prepare("pair-real-xvfb", "job-real-xvfb", "acceptance-worker")
    # The fake sudo wrapper PIDs start at 9001; the discovered Xvfb PIDs start at 8001.
    assert slot.xvfb_pid < 9000
    assert slot.xvfb_pid >= 8000
    assert slot.xvfb_start_ticks == slot.xvfb_pid * 10 + slot.linux_uid
    evidence = slot.attestation_fields()
    assert evidence["process_identity"] == (
        f"xvfb-pid={slot.xvfb_pid};start_ticks={slot.xvfb_start_ticks}"
    )


def test_same_pairing_cannot_start_a_second_terminal_until_first_is_revoked(monkeypatch, tmp_path):
    manager = _manager_with_fake_host(monkeypatch, tmp_path)
    first = manager.prepare("pair-one-owner", "job-first", "acceptance-worker")
    marker = first.terminal_home / ".destinedfx-worker.json"
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text(json.dumps({"status": "READY", "pairing_id": first.pairing_id}))

    try:
        manager.prepare("pair-one-owner", "job-second", "acceptance-worker")
    except linux_isolation.IsolationError as exc:
        assert exc.code == "PAIRING_TERMINAL_ALREADY_ACTIVE"
    else:
        raise AssertionError("A pairing must not reuse one Unix UID for two active MT5 terminals")


def test_unreadable_existing_pairing_marker_blocks_reprovisioning(monkeypatch, tmp_path):
    manager = _manager_with_fake_host(monkeypatch, tmp_path)
    first = manager.prepare("pair-corrupt-marker", "job-first", "acceptance-worker")
    marker = first.terminal_home / ".destinedfx-worker.json"
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text("{not-json")

    try:
        manager.prepare("pair-corrupt-marker", "job-second", "acceptance-worker")
    except linux_isolation.IsolationError as exc:
        assert exc.code == "PAIRING_SLOT_MARKER_UNREADABLE"
    else:
        raise AssertionError("An unreadable process-ownership marker must fail closed")


def test_linux_worker_allows_ephemeral_live_login_provisioning_for_read_only_verification(monkeypatch, tmp_path):
    module_path = ROOT / "scripts" / "linux-host" / "linux-slot-worker.py"
    import importlib.util

    sys.path.insert(0, str(ROOT / "managed-worker"))
    import worker  # noqa: F401

    spec = importlib.util.spec_from_file_location("linux_slot_worker_ephemeral_live", module_path)
    slot_worker = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(slot_worker)

    fake_reservation = SimpleNamespace()
    monkeypatch.setattr(slot_worker.worker, "validate_job", lambda job: job)
    reached = []
    def mark_terminal_copy(*_args):
        reached.append(True)
        raise RuntimeError("EPHEMERAL_LIVE_LOGIN_PROVISIONING_ALLOWED")
    monkeypatch.setattr(slot_worker, "_copy_clean_terminal", mark_terminal_copy)
    monkeypatch.setenv("BRIDGE_HOST_PROFILE", "EPHEMERAL")
    monkeypatch.setenv("BRIDGE_HOST_MODE", "linux-host")
    live_job = {"account": {"account_mode": "LIVE", "login": "123456", "server": "Broker-Live", "password": "fixture"}}
    try:
        slot_worker._prepare_and_launch(live_job, fake_reservation)
    except RuntimeError as exc:
        assert str(exc) == "EPHEMERAL_LIVE_LOGIN_PROVISIONING_ALLOWED"
    else:
        raise AssertionError("Fixture should stop at the first terminal filesystem action")
    assert reached == [True]


def test_linux_worker_allows_live_only_on_explicit_persistent_linux_host(monkeypatch):
    module_path = ROOT / "scripts" / "linux-host" / "linux-slot-worker.py"
    import importlib.util

    sys.path.insert(0, str(ROOT / "managed-worker"))
    import worker  # noqa: F401

    spec = importlib.util.spec_from_file_location("linux_slot_worker_persistent_acceptance", module_path)
    slot_worker = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(slot_worker)

    fake_reservation = SimpleNamespace()
    monkeypatch.setattr(slot_worker.worker, "validate_job", lambda job: job)
    reached = []
    def mark_terminal_copy(*_args):
        reached.append(True)
        raise RuntimeError("PERSISTENT_LIVE_GATE_PASSED")
    monkeypatch.setattr(slot_worker, "_copy_clean_terminal", mark_terminal_copy)
    monkeypatch.setenv("BRIDGE_HOST_PROFILE", "PERSISTENT")
    monkeypatch.setenv("BRIDGE_HOST_MODE", "linux-host")
    live_job = {"account": {"account_mode": "LIVE", "login": "123456", "server": "Broker-Live", "password": "fixture"}}
    try:
        slot_worker._prepare_and_launch(live_job, fake_reservation)
    except RuntimeError as exc:
        assert str(exc) == "PERSISTENT_LIVE_GATE_PASSED"
    else:
        raise AssertionError("The fixture should stop at the first filesystem step")
    assert reached == [True]
