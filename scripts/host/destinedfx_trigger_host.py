#!/usr/bin/env python3
"""Android-triggered Codespaces host adapter.

This is an adapter, not a second service implementation: startup delegates to
Deploy/Codespaces host_controller.py, which owns deploy/codespaces/start.sh and
deploy/codespaces/stop.sh. The adapter never launches gui.sh or a second MT5
worker. All shutdown requests recheck broker state and SQLite backups.
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "bridge"))
sys.path.insert(0, str(ROOT / "deploy" / "codespaces"))
import host_controller  # noqa: E402
from host_lifecycle import backup_database, cancel_stop, inspect_shutdown_safety  # noqa: E402

STATE = Path(os.environ.get(
    "DESTINEDFX_CODESPACES_STATE_DIR",
    str(Path.home() / ".local/share/destinedfx-codespaces"),
))
RUNTIME = STATE / "runtime"
DB_PATH = Path(os.environ.get("BRIDGE_DB_PATH", str(STATE / "bridge.sqlite3")))
USAGE_FILE = STATE / "codespaces-usage.json"
CORES = max(1, min(64, int(os.environ.get("DESTINEDFX_BILLABLE_CORES", "2"))))


def _now() -> float:
    return time.time()


def _atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, sort_keys=True, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        temporary.unlink(missing_ok=True)


def _usage_read() -> dict[str, Any]:
    try:
        value = json.loads(USAGE_FILE.read_text(encoding="utf-8"))
        if isinstance(value, dict) and isinstance(value.get("sessions"), list):
            return value
    except (OSError, ValueError):
        pass
    return {"version": 1, "sessions": []}


def record_usage_start(*, at: float | None = None) -> None:
    now = _now() if at is None else float(at)
    data = _usage_read()
    codespace = os.environ.get("CODESPACE_NAME", "unknown")
    sessions = data["sessions"]
    active = next((row for row in reversed(sessions)
                   if row.get("codespace") == codespace and row.get("stopped_at") is None), None)
    if active is None:
        sessions.append({
            "codespace": codespace,
            "started_at": now,
            "stopped_at": None,
            "cores": CORES,
            "source": "host-controller-estimate",
        })
    else:
        active["last_seen_at"] = now
    data["sessions"] = sessions[-500:]
    _atomic_json(USAGE_FILE, data)


def record_usage_stop(*, at: float | None = None) -> None:
    now = _now() if at is None else float(at)
    data = _usage_read()
    codespace = os.environ.get("CODESPACE_NAME", "unknown")
    active = next((row for row in reversed(data["sessions"])
                   if row.get("codespace") == codespace and row.get("stopped_at") is None), None)
    if active:
        active["stopped_at"] = max(float(active.get("started_at", now)), now)
    _atomic_json(USAGE_FILE, data)


def usage_summary(*, at: float | None = None) -> dict[str, Any]:
    now = _now() if at is None else float(at)
    instant = datetime.fromtimestamp(now, timezone.utc)
    month_start = datetime(instant.year, instant.month, 1, tzinfo=timezone.utc).timestamp()
    today_start = datetime(instant.year, instant.month, instant.day, tzinfo=timezone.utc).timestamp()
    month_core_seconds = 0.0
    today_wall_seconds = 0.0
    for row in _usage_read()["sessions"]:
        try:
            start = float(row["started_at"])
            stop_value = row.get("stopped_at")
            end = now if stop_value is None else float(stop_value)
            cores = max(1, min(64, int(row.get("cores", 2))))
        except (KeyError, TypeError, ValueError):
            continue
        month_overlap = max(0.0, min(end, now) - max(start, month_start))
        today_overlap = max(0.0, min(end, now) - max(start, today_start))
        month_core_seconds += month_overlap * cores
        today_wall_seconds += today_overlap
    used_core_hours = month_core_seconds / 3600.0
    remaining_core_hours = max(0.0, 120.0 - used_core_hours)
    return {
        "estimate_only": True,
        "billing_cycle_timezone": "UTC",
        "cores_assumed": CORES,
        "used_today_minutes": int(today_wall_seconds // 60),
        "used_month_core_hours": round(used_core_hours, 3),
        "remaining_month_core_hours": round(remaining_core_hours, 3),
        "monthly_core_hour_allowance_assumed": 120,
        "daily_wall_minute_target": 90,
        "daily_target_remaining_minutes": max(0, 90 - int(today_wall_seconds // 60)),
        "low_budget_warning": remaining_core_hours < 10.0,
    }


def _identity_for_record(record: dict[str, Any] | None) -> dict[str, Any] | None:
    if not isinstance(record, dict):
        return None
    try:
        pid = int(record["pid"])
        ticks = int(record["start_ticks"])
    except (KeyError, TypeError, ValueError):
        return None
    identity = host_controller._proc_identity(pid)
    if not identity or identity["start_ticks"] != ticks:
        return None
    if identity["state"] in {"Z", "X"}:
        return None
    if identity["uid"] != os.geteuid():
        return None
    if record.get("uid") is not None and int(record["uid"]) != identity["uid"]:
        return None
    if record.get("exe") and os.path.realpath(str(record["exe"])) != identity["exe"]:
        return None
    if record.get("cmdline") and list(record["cmdline"]) != identity["args"]:
        return None
    return identity


def _supervisor_health() -> dict[str, Any]:
    record = host_controller._read_json(host_controller.SUPERVISOR_PID)
    identity = _identity_for_record(record)
    expected = str(Path(host_controller.__file__).resolve())
    verified = bool(identity and host_controller._supervisor_identity_matches(identity, expected))
    return {
        "verified": verified,
        "pid": identity["pid"] if verified else None,
        "start_ticks": identity["start_ticks"] if verified else None,
    }


def _start_manager_health() -> dict[str, Any]:
    record = host_controller._read_json(host_controller.START_PID)
    identity = _identity_for_record(record)
    verified = bool(identity and host_controller._start_manager_identity_matches(
        identity, str(host_controller.START_SCRIPT)
    ))
    return {
        "verified": verified,
        "pid": identity["pid"] if verified else None,
        "start_ticks": identity["start_ticks"] if verified else None,
    }


def _probe(url: str) -> bool:
    try:
        request = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(request, timeout=2) as response:
            return 200 <= response.status < 300
    except (OSError, urllib.error.URLError, TimeoutError):
        return False


def check_health() -> dict[str, Any]:
    supervisor = _supervisor_health()
    manager = _start_manager_health()
    bridge = _probe("http://127.0.0.1:8000/health/live")
    host_agent = _probe("http://127.0.0.1:8787/health/ready")
    status = host_controller._read_json(host_controller.STATUS_FILE) or {"status": "UNKNOWN"}
    if not supervisor["verified"]:
        overall = "DEGRADED"
    elif status.get("status") in {"FAILED", "STOP_FAILED", "STOP_BLOCKED"}:
        overall = "ERROR"
    elif manager["verified"] and bridge and host_agent:
        overall = "ACTIVE"
    elif status.get("status") == "STARTING":
        overall = "STARTING"
    else:
        overall = "DEGRADED"
    return {
        "status": overall,
        "supervisor": supervisor,
        "start_manager": manager,
        "bridge_loopback": bridge,
        "host_agent_ready": host_agent,
        "controller": status.get("status", "UNKNOWN"),
        "updated_at": status.get("updated_at"),
        "usage": usage_summary(),
    }


def start_all() -> dict[str, Any]:
    result = host_controller.ensure_started()
    record_usage_start()
    return {"status": result.get("status", "STARTING"), "pid": result.get("pid"), "health": check_health()}


def host_status() -> dict[str, Any]:
    return check_health()


def save_state() -> dict[str, Any]:
    if not DB_PATH.is_file():
        raise RuntimeError("Bridge database is unavailable; no backup was created.")
    backup = backup_database(DB_PATH, STATE / "backups")
    return {
        "status": "SAVED",
        "backup": backup.name,
        "verified": True,
        "usage": usage_summary(),
    }


def idle_shutdown() -> dict[str, Any]:
    if not DB_PATH.is_file():
        return {"status": "STOP_BLOCKED", "reasons": ["BRIDGE_DATABASE_UNAVAILABLE"]}
    safety = inspect_shutdown_safety(DB_PATH, heartbeat_stale_seconds=15)
    if not safety.get("safe"):
        return {"status": "STOP_BLOCKED", "reasons": safety.get("reasons", ["UNKNOWN_STATE"])}
    try:
        backup = backup_database(DB_PATH, STATE / "backups")
        backup_safety = inspect_shutdown_safety(backup, heartbeat_stale_seconds=15)
        current_safety = inspect_shutdown_safety(DB_PATH, heartbeat_stale_seconds=15)
        if not backup_safety.get("safe") or not current_safety.get("safe"):
            reasons = sorted(set(backup_safety.get("reasons", [])) | set(current_safety.get("reasons", [])))
            return {"status": "STOP_BLOCKED", "reasons": reasons or ["BROKER_STATE_CHANGED"]}
    except Exception as exc:
        return {"status": "STOP_FAILED", "reason": type(exc).__name__}

    supervisor_record = host_controller._read_json(host_controller.SUPERVISOR_PID)
    if not host_controller._record_matches(supervisor_record, str(Path(host_controller.__file__).resolve())):
        return {"status": "STOP_BLOCKED", "reasons": ["SUPERVISOR_IDENTITY_UNVERIFIED"]}
    # The controller pins the complete saved supervisor identity with a pidfd,
    # revalidates it after pidfd_open, and only then sends SIGTERM. Numeric-PID
    # signalling is deliberately not used because it has a PID-reuse race.
    if not host_controller.signal_supervisor_record(
        supervisor_record, str(Path(host_controller.__file__).resolve())
    ):
        return {"status": "STOP_BLOCKED", "reasons": ["SUPERVISOR_PIDFD_SIGNAL_REFUSED"]}
    # SIGTERM is handled by the supervisor, which repeats the broker/backup checks
    # and then invokes the existing deploy/codespaces/stop.sh.
    deadline = time.monotonic() + 45.0
    while time.monotonic() < deadline:
        if not host_controller._read_json(host_controller.SUPERVISOR_PID):
            record_usage_stop()
            return {"status": "STOPPED", "backup": backup.name, "usage": usage_summary()}
        time.sleep(0.25)
    return {"status": "STOP_REQUESTED", "backup": backup.name, "usage": usage_summary()}


def recover() -> dict[str, Any]:
    cancel_stop(RUNTIME, DB_PATH, os.environ.get("HOST_GENERATION"))
    host_controller.STOP_REQUEST.unlink(missing_ok=True)
    started = host_controller.ensure_started()
    record_usage_start()
    return {"status": "RECOVERED", "host": started, "health": check_health()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("start_all", "check_health", "host_status", "save_state", "idle_shutdown", "recover"))
    args = parser.parse_args()
    try:
        functions = {
            "start_all": start_all,
            "check_health": check_health,
            "host_status": host_status,
            "save_state": save_state,
            "idle_shutdown": idle_shutdown,
            "recover": recover,
        }
        result = functions[args.command]()
        print(json.dumps(result, sort_keys=True))
        return 2 if result.get("status") in {"STOP_BLOCKED", "STOP_FAILED", "ERROR"} else 0
    except Exception as exc:
        # Exception details can contain paths but never include token values.
        print(json.dumps({"status": "ERROR", "reason": type(exc).__name__}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
