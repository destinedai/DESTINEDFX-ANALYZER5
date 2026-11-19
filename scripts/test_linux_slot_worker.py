from __future__ import annotations

import importlib.util
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# The slot worker imports owner lifecycle helpers and the Windows/Linux shared
# worker implementation from these source directories when loaded as a module.
sys.path.insert(0, str(ROOT / "deploy" / "codespaces"))
sys.path.insert(0, str(ROOT / "managed-worker"))
sys.path.insert(0, str(ROOT / "scripts" / "linux-host"))
MODULE = ROOT / "scripts" / "linux-host" / "linux-slot-worker.py"
SPEC = importlib.util.spec_from_file_location("linux_slot_worker", MODULE)
linux_slot_worker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(linux_slot_worker)


def test_slot_capacity_is_zero_below_three_gib_available(monkeypatch):
    monkeypatch.setattr(linux_slot_worker, "_memory_available_bytes", lambda: 2 * 1024**3)
    monkeypatch.setattr(linux_slot_worker.os, "cpu_count", lambda: 8)
    assert linux_slot_worker.capacity() == 0


def test_slot_capacity_counts_only_complete_three_gib_budgets(monkeypatch):
    monkeypatch.setattr(linux_slot_worker, "_memory_available_bytes", lambda: 7 * 1024**3)
    monkeypatch.setattr(linux_slot_worker.os, "cpu_count", lambda: 8)
    monkeypatch.setattr(linux_slot_worker, "MAX_SLOT_CONFIG", 4)
    assert linux_slot_worker.capacity() == 2


def test_slot_capacity_is_bounded_by_cpu_config_and_ram(monkeypatch):
    monkeypatch.setattr(linux_slot_worker, "_memory_available_bytes", lambda: 24 * 1024**3)
    monkeypatch.setattr(linux_slot_worker.os, "cpu_count", lambda: 2)
    monkeypatch.setattr(linux_slot_worker, "MAX_SLOT_CONFIG", 6)
    assert linux_slot_worker.capacity() == 2


def test_codespaces_start_script_fails_closed_when_memory_floor_not_met():
    script = (ROOT / "deploy" / "codespaces" / "start.sh").read_text(encoding="utf-8")
    assert "memory_slots = available // (3 * 1024**3)" in script
    assert "DESTINEDFX_SLOT_MAX_TERMINALS < 1" in script
    assert "No broker credentials will be provisioned" in script
