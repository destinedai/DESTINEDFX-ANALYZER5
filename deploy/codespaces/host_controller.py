#!/usr/bin/env python3
"""Supervise the Codespaces MT5 host without blocking devcontainer startup.

The supervisor owns a detached start.sh process, validates its PID identity,
backs up SQLite through the online backup API, and refuses shutdown whenever
open positions, active/pending orders, unresolved commands, or unknown broker
state are present. It never liquidates or modifies trades.
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
STATE = Path(os.environ.get(
    "DESTINEDFX_CODESPACES_STATE_DIR",
    str(Path.home() / ".local/share/destinedfx-codespaces"),
))
RUNTIME = STATE / "runtime"
START_SCRIPT = ROOT / "deploy/codespaces/start.sh"
STOP_SCRIPT = ROOT / "deploy/codespaces/stop.sh"
DB_PATH = Path(os.environ.get("BRIDGE_DB_PATH", str(STATE / "bridge.sqlite3")))
SUPERVISOR_PID = RUNTIME / "supervisor.json"
START_PID = RUNTIME / "start-manager.json"
STATUS_FILE = RUNTIME / "host-status.json"
STOP_REQUEST = RUNTIME / "stop.request"
ACTIVITY_FILE = RUNTIME / "last-activity"


def _atomic_json(path: Path, value: dict[str, Any]) -> None:
    RUNTIME.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(RUNTIME, 0o700)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        os.chmod(temporary, 0o600)
        json.dump(value, stream, sort_keys=True, separators=(",", ":"))
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def _read_json(path: Path) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return value if isinstance(value, dict) else None


def _proc_start_ticks(pid: int) -> int | None:
    try:
        raw = Path(f"/proc/{pid}/stat").read_text(encoding="ascii")
        tail = raw[raw.rfind(")") + 2 :].split()
        return int(tail[19])
    except (OSError, ValueError, IndexError):
        return None


def _proc_cmdline(pid: int) -> str:
    try:
        return Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode(
            "utf-8", errors="replace"
        )
    except OSError:
        return ""


def _record_matches(record: dict[str, Any] | None, expected: str) -> bool:
    if not record:
        return False
    try:
        pid = int(record["pid"])
        ticks = int(record["start_ticks"])
    except (KeyError, TypeError, ValueError):
        return False
    return (
        pid > 1
        and _proc_start_ticks(pid) == ticks
        and expected in _proc_cmdline(pid)
    )


def _write_status(status: str, **extra: Any) -> None:
    _atomic_json(
        STATUS_FILE,
        {"status": status, "updated_at": time.time(), **extra},
    )


def _helper_imports():
    sys.path.insert(0, str(ROOT / "bridge"))
    from host_lifecycle import (  # type: ignore
        backup_database,
        cancel_stop,
        inspect_shutdown_safety,
    )
    return backup_database, cancel_stop, inspect_shutdown_safety


def _start_command() -> subprocess.Popen[bytes]:
    if not START_SCRIPT.is_file():
        raise FileNotFoundError(f"Missing Codespaces startup script: {START_SCRIPT}")
    log_path = RUNTIME / "supervisor-start.log"
    log = log_path.open("ab")
    try:
        child = subprocess.Popen(
            ["bash", str(START_SCRIPT)],
            cwd=ROOT,
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            env=os.environ.copy(),
            start_new_session=True,
            close_fds=True,
        )
    finally:
        log.close()
    ticks = _proc_start_ticks(child.pid)
    if ticks is None:
        try:
            child.terminate()
        except OSError:
            pass
        raise RuntimeError("Could not establish startup process identity")
    _atomic_json(
        START_PID,
        {"pid": child.pid, "start_ticks": ticks, "script": str(START_SCRIPT)},
    )
    return child


def ensure_started() -> dict[str, Any]:
    if os.name != "posix" or not Path("/proc").is_dir():
        raise RuntimeError("The Codespaces supervisor requires Linux /proc process identity")
    if not os.environ.get("CODESPACE_NAME"):
        raise RuntimeError("CODESPACE_NAME is missing; run this inside GitHub Codespaces")
    RUNTIME.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(STATE, 0o700)
    os.chmod(RUNTIME, 0o700)
    current = _read_json(SUPERVISOR_PID)
    if _record_matches(current, str(Path(__file__).resolve())):
        return {"status": "ALREADY_SUPERVISED", "pid": current["pid"]}

    if current:
        # Never signal a PID whose start time or command no longer matches.
        try:
            SUPERVISOR_PID.unlink()
        except FileNotFoundError:
            pass

    log = (RUNTIME / "supervisor.log").open("ab")
    try:
        process = subprocess.Popen(
            [sys.executable, str(Path(__file__).resolve()), "supervise"],
            cwd=ROOT,
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            env=os.environ.copy(),
            start_new_session=True,
            close_fds=True,
        )
    finally:
        log.close()

    ticks = _proc_start_ticks(process.pid)
    if ticks is None:
        raise RuntimeError("Host supervisor failed to establish its process identity")
    _atomic_json(
        SUPERVISOR_PID,
        {"pid": process.pid, "start_ticks": ticks, "script": str(Path(__file__).resolve())},
    )
    return {"status": "SUPERVISOR_STARTED", "pid": process.pid}


def _graceful_stop(child: subprocess.Popen[bytes]) -> bool:
    """Stop only the process group created by this supervisor."""
    try:
        subprocess.run(
            ["bash", str(STOP_SCRIPT)],
            cwd=ROOT,
            env=os.environ.copy(),
            stdin=subprocess.DEVNULL,
            stdout=(RUNTIME / "host-stop.log").open("ab"),
            stderr=subprocess.STDOUT,
            timeout=45,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        _write_status("STOP_FAILED", reason="STOP_SCRIPT_FAILED")
        return False

    try:
        os.killpg(child.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    except OSError:
        _write_status("STOP_FAILED", reason="START_PROCESS_STOP_FAILED")
        return False

    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if child.poll() is not None:
            return True
        time.sleep(0.25)

    # SIGKILL is confined to the unique session/process group this supervisor
    # created; it is never sent to a PID read from an unverified legacy file.
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    except OSError:
        _write_status("STOP_FAILED", reason="OWNED_PROCESS_GROUP_UNRESPONSIVE")
        return False
    try:
        child.wait(timeout=5)
    except subprocess.TimeoutExpired:
        _write_status("STOP_FAILED", reason="OWNED_PROCESS_GROUP_UNRESPONSIVE")
        return False
    return True


def _queue_idle_stop() -> None:
    from host_lifecycle import request_stop  # type: ignore
    request_stop(RUNTIME, reason="IDLE_TIMEOUT", grace_seconds=30)


def supervise() -> int:
    backup_database, cancel_stop, inspect_shutdown_safety = _helper_imports()
    stop_requested = False

    def on_signal(_signum: int, _frame: Any) -> None:
        nonlocal stop_requested
        stop_requested = True

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)
    child: subprocess.Popen[bytes] | None = None
    try:
        _write_status("STARTING")
        child = _start_command()
        ACTIVITY_FILE.touch(mode=0o600, exist_ok=True)
        _write_status("STARTING", start_pid=child.pid)
        idle_minutes = int(os.environ.get("DESTINEDFX_IDLE_TIMEOUT_MINUTES", "20"))
        idle_minutes = max(5, min(240, idle_minutes))
        while True:
            if stop_requested:
                safety = inspect_shutdown_safety(DB_PATH)
                if safety["safe"]:
                    backup = backup_database(DB_PATH, STATE / "backups")
                    _write_status("SHUTTING_DOWN", reason="CODESPACES_SIGNAL",
                                  backup=backup.name, backup_verified=True)
                    return 0 if _graceful_stop(child) else 2
                _write_status("STOP_BLOCKED", reason="CODESPACES_SIGNAL",
                              safety_reasons=safety["reasons"])
                # If the platform is already stopping the container, do not
                # attempt to keep trading services alive or liquidate positions.
                return 2

            if child.poll() is not None:
                _write_status("FAILED", reason="START_SCRIPT_EXITED",
                              exit_code=child.returncode)
                return int(child.returncode or 1)

            request = _read_json(STOP_REQUEST)
            if request:
                requested_at = float(request.get("requested_at", 0) or 0)
                grace = max(5, min(180, int(request.get("grace_seconds", 30) or 30)))
                if time.time() - requested_at >= grace:
                    safety = inspect_shutdown_safety(DB_PATH)
                    if not safety["safe"]:
                        cancel_stop(RUNTIME)
                        _write_status("STOP_BLOCKED", reason="BROKER_SAFETY_CHECK",
                                      safety_reasons=safety["reasons"])
                        touch = ACTIVITY_FILE
                        touch.touch(mode=0o600, exist_ok=True)
                    else:
                        backup = backup_database(DB_PATH, STATE / "backups")
                        _write_status("SHUTTING_DOWN", reason=request.get("reason", "OWNER_REQUEST"),
                                      backup=backup.name, backup_verified=True)
                        if not _graceful_stop(child):
                            return 2
                        cancel_stop(RUNTIME)
                        _write_status("STOPPED", reason=request.get("reason", "OWNER_REQUEST"),
                                      backup=backup.name, backup_verified=True)
                        return 0

            try:
                idle_for = time.time() - ACTIVITY_FILE.stat().st_mtime
            except OSError:
                idle_for = 0
            if idle_for >= idle_minutes * 60 and not request:
                safety = inspect_shutdown_safety(DB_PATH)
                if safety["safe"]:
                    _queue_idle_stop()
                    _write_status("IDLE_STOP_QUEUED", idle_minutes=idle_minutes)
                else:
                    # Unknown/open broker state suppresses automatic idle shutdown.
                    _write_status("IDLE_STOP_SUPPRESSED",
                                  safety_reasons=safety["reasons"])
                    ACTIVITY_FILE.touch(mode=0o600, exist_ok=True)
            _write_status("RUNNING", start_pid=child.pid)
            time.sleep(2)
    except Exception as exc:
        # Only the exception type is recorded; never persist command output,
        # account identifiers, bridge tokens, or other environment secrets.
        _write_status("FAILED", reason=type(exc).__name__)
        if child is not None and child.poll() is None:
            _graceful_stop(child)
        return 2
    finally:
        try:
            SUPERVISOR_PID.unlink()
        except FileNotFoundError:
            pass
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("ensure-started", "supervise", "status", "cancel-stop"))
    args = parser.parse_args()
    try:
        if args.command == "ensure-started":
            result = ensure_started()
            print(json.dumps(result, sort_keys=True))
            return 0
        if args.command == "supervise":
            return supervise()
        if args.command == "status":
            print(json.dumps(_read_json(STATUS_FILE) or {"status": "UNKNOWN"}, sort_keys=True))
            return 0
        if args.command == "cancel-stop":
            _, cancel_stop, _ = _helper_imports()
            cancel_stop(RUNTIME)
            _write_status("RUNNING")
            return 0
    except Exception as exc:
        print(f"FAIL: host controller ({type(exc).__name__})", file=sys.stderr)
        return 2
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
