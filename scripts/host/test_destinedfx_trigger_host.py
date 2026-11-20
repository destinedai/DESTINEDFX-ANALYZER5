from __future__ import annotations

import importlib.util
import json
from pathlib import Path

MODULE = Path(__file__).with_name("destinedfx_trigger_host.py")
SPEC = importlib.util.spec_from_file_location("destinedfx_trigger_host", MODULE)
trigger = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(trigger)


def test_usage_tracker_counts_core_hours_and_daily_wall_time(tmp_path, monkeypatch):
    monkeypatch.setattr(trigger, "USAGE_FILE", tmp_path / "usage.json")
    monkeypatch.setenv("CODESPACE_NAME", "test-space")
    monkeypatch.setattr(trigger, "CORES", 2)
    start = 1_800_000_000.0
    trigger.record_usage_start(at=start)
    # A repeated start for the same Codespace must not double-count the session.
    trigger.record_usage_start(at=start + 10)
    summary = trigger.usage_summary(at=start + 90 * 60)
    assert summary["used_today_minutes"] == 90
    assert summary["used_month_core_hours"] == 3.0
    assert summary["remaining_month_core_hours"] == 117.0
    assert summary["daily_target_remaining_minutes"] == 0


def test_usage_tracker_closes_session_without_charging_after_stop(tmp_path, monkeypatch):
    monkeypatch.setattr(trigger, "USAGE_FILE", tmp_path / "usage.json")
    monkeypatch.setenv("CODESPACE_NAME", "test-space")
    monkeypatch.setattr(trigger, "CORES", 2)
    start = 1_800_000_000.0
    trigger.record_usage_start(at=start)
    trigger.record_usage_stop(at=start + 30 * 60)
    summary = trigger.usage_summary(at=start + 3 * 60 * 60)
    assert summary["used_today_minutes"] == 30
    assert summary["used_month_core_hours"] == 1.0


def test_start_all_delegates_to_existing_supervisor_and_records_usage(monkeypatch):
    called = []
    monkeypatch.setattr(trigger.host_controller, "ensure_started", lambda: {"status": "SUPERVISOR_STARTED", "pid": 123})
    monkeypatch.setattr(trigger, "record_usage_start", lambda: called.append("usage"))
    monkeypatch.setattr(trigger, "check_health", lambda: {"status": "STARTING"})
    result = trigger.start_all()
    assert result["status"] == "SUPERVISOR_STARTED"
    assert result["pid"] == 123
    assert called == ["usage"]


def test_idle_shutdown_refuses_unknown_or_active_broker_state(monkeypatch, tmp_path):
    database = tmp_path / "bridge.sqlite3"
    database.touch()
    monkeypatch.setattr(trigger, "DB_PATH", database)
    monkeypatch.setattr(trigger, "inspect_shutdown_safety", lambda *args, **kwargs: {
        "safe": False, "reasons": ["EA_ACTIVE", "OPEN_POSITIONS"]
    })
    called = []
    monkeypatch.setattr(trigger.os, "kill", lambda *args: called.append(args))
    result = trigger.idle_shutdown()
    assert result["status"] == "STOP_BLOCKED"
    assert result["reasons"] == ["EA_ACTIVE", "OPEN_POSITIONS"]
    assert called == []


def test_idle_shutdown_only_signals_verified_supervisor_after_backup_and_rechecks(monkeypatch, tmp_path):
    database = tmp_path / "bridge.sqlite3"
    database.touch()
    monkeypatch.setattr(trigger, "DB_PATH", database)
    monkeypatch.setattr(trigger, "inspect_shutdown_safety", lambda *args, **kwargs: {"safe": True, "reasons": []})
    monkeypatch.setattr(trigger, "backup_database", lambda *args, **kwargs: Path("/tmp/verified-test-backup.sqlite3"))
    record = {"pid": 555, "start_ticks": 12345}
    read_calls = iter([record, None])
    monkeypatch.setattr(trigger.host_controller, "_read_json", lambda path: next(read_calls))
    monkeypatch.setattr(trigger.host_controller, "_record_matches", lambda *args: True)
    monkeypatch.setattr(trigger.host_controller, "_proc_identity", lambda pid: {"pid": pid, "uid": 1000, "start_ticks": 12345, "state": "S", "exe": "/usr/bin/python3.12", "args": ["python3", "host_controller.py", "supervise"]})
    monkeypatch.setattr(trigger.host_controller, "_supervisor_identity_matches", lambda *args: True)
    signals = []
    monkeypatch.setattr(trigger.os, "kill", lambda pid, sig: signals.append((pid, sig)))
    stopped = []
    monkeypatch.setattr(trigger, "record_usage_stop", lambda: stopped.append(True))
    result = trigger.idle_shutdown()
    assert result["status"] == "STOPPED"
    assert signals and signals[0][0] == 555
    assert stopped == [True]
