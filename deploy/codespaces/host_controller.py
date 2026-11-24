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
USAGE_FILE = STATE / "codespaces-usage.json"
AUTO_IDLE_TIMEOUT_SECONDS = max(120, min(86_400, int(os.environ.get("DESTINEDFX_AUTO_SLEEP_IDLE_SECONDS", "120"))))


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


def _record_usage_stop() -> bool:
    """Close the local Codespaces estimate only after the engine safely stops."""
    try:
        data = json.loads(USAGE_FILE.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or not isinstance(data.get("sessions"), list):
            return False
    except (OSError, ValueError):
        return False
    codespace = os.environ.get("CODESPACE_NAME", "unknown")
    now = time.time()
    active = next((row for row in reversed(data["sessions"])
                   if isinstance(row, dict) and row.get("codespace") == codespace
                   and row.get("stopped_at") is None), None)
    if active is None:
        return False
    active["stopped_at"] = max(float(active.get("started_at", now)), now)
    _atomic_json(USAGE_FILE, data)
    return True


def _proc_start_ticks(pid: int) -> int | None:
    try:
        raw = Path(f"/proc/{pid}/stat").read_text(encoding="ascii")
        tail = raw[raw.rfind(")") + 2 :].split()
        return int(tail[19])
    except (OSError, ValueError, IndexError):
        return None


def _proc_cmdline(pid: int) -> str:
    try:
        return Path(f"/proc/{pid}/cmdline").read_bytes().replace(bytes([0]), b" ").decode(
            "utf-8", errors="replace"
        )
    except OSError:
        return ""


def _proc_identity(pid: int) -> dict[str, Any] | None:
    """Capture PID, start ticks, effective UID, executable and argv from /proc."""
    base = Path("/proc") / str(pid)
    try:
        status = (base / "status").read_text(encoding="ascii")
        uid_line = next(line for line in status.splitlines() if line.startswith("Uid:"))
        effective_uid = int(uid_line.split()[2])
        raw_stat = (base / "stat").read_text(encoding="ascii")
        tail = raw_stat[raw_stat.rfind(")") + 2 :].split()
        args = [
            item.decode("utf-8", errors="replace")
            for item in (base / "cmdline").read_bytes().split(bytes([0]))
            if item
        ]
        return {
            "pid": pid,
            "start_ticks": int(tail[19]),
            "state": tail[0],
            "uid": effective_uid,
            "exe": os.path.realpath(base / "exe"),
            "args": args,
        }
    except (OSError, ValueError, IndexError, StopIteration):
        return None


def _supervisor_identity_matches(identity: dict[str, Any] | None, expected: str) -> bool:
    if not identity or identity["state"] in {"Z", "X"}:
        return False
    args = identity["args"]
    return (
        identity["uid"] == os.geteuid()
        and identity["exe"] == os.path.realpath(sys.executable)
        and len(args) == 3
        and os.path.realpath(args[0]) == os.path.realpath(sys.executable)
        and os.path.realpath(args[1]) == os.path.realpath(expected)
        and args[2] == "supervise"
    )


def _record_matches(record: dict[str, Any] | None, expected: str) -> bool:
    if not record:
        return False
    try:
        pid = int(record["pid"])
        ticks = int(record["start_ticks"])
    except (KeyError, TypeError, ValueError):
        return False
    identity = _proc_identity(pid)
    if not identity or identity["start_ticks"] != ticks:
        return False
    if record.get("uid") is not None and int(record["uid"]) != identity["uid"]:
        return False
    if record.get("exe") and os.path.realpath(str(record["exe"])) != identity["exe"]:
        return False
    return _supervisor_identity_matches(identity, expected)


def signal_supervisor_record(record: dict[str, Any] | None, expected: str) -> bool:
    """Send SIGTERM only to the full identity pinned by a Linux pidfd.

    The complete supervisor identity is revalidated after pidfd_open so PID reuse
    between discovery and signalling cannot redirect shutdown to another process.
    """
    if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
        return False
    if not _record_matches(record, expected):
        return False
    try:
        pid = int(record["pid"])  # type: ignore[index]
        pidfd = os.pidfd_open(pid, 0)
    except (KeyError, TypeError, ValueError, ProcessLookupError, OSError):
        return False
    try:
        if not _record_matches(record, expected):
            return False
        try:
            signal.pidfd_send_signal(pidfd, signal.SIGTERM)
        except (ProcessLookupError, OSError):
            return False
        return True
    finally:
        os.close(pidfd)


def _supervisor_record(pid: int, identity: dict[str, Any]) -> dict[str, Any]:
    return {
        "pid": pid,
        "start_ticks": identity["start_ticks"],
        "uid": identity["uid"],
        "exe": identity["exe"],
        "cmdline": identity["args"],
        "script": str(Path(__file__).resolve()),
    }


def _find_supervisors(expected: str) -> list[dict[str, Any]]:
    found: list[dict[str, Any]] = []
    try:
        entries = list(Path("/proc").iterdir())
    except OSError:
        return found
    for entry in entries:
        if not entry.name.isdigit():
            continue
        identity = _proc_identity(int(entry.name))
        if _supervisor_identity_matches(identity, expected):
            found.append(identity)
    return found


def _start_manager_identity_matches(identity: dict[str, Any] | None, expected_script: str) -> bool:
    return bool(
        identity
        and identity["state"] not in {"Z", "X"}
        and identity["uid"] == os.geteuid()
        and identity["exe"] == os.path.realpath("/usr/bin/bash")
        and identity["args"] == ["bash", str(Path(expected_script).resolve())]
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
    identity = _proc_identity(child.pid)
    if (
        not identity
        or identity["start_ticks"] != ticks
        or identity["uid"] != os.geteuid()
        or identity["exe"] != os.path.realpath("/usr/bin/bash")
        or identity["args"] != ["bash", str(START_SCRIPT)]
    ):
        try:
            child.terminate()
        except OSError:
            pass
        raise RuntimeError("Startup manager process identity could not be verified")
    _atomic_json(
        START_PID,
        {
            "pid": child.pid, "start_ticks": ticks, "uid": identity["uid"],
            "exe": identity["exe"], "cmdline": identity["args"], "script": str(START_SCRIPT),
        },
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
    expected_script = str(Path(__file__).resolve())
    current = _read_json(SUPERVISOR_PID)
    if _record_matches(current, expected_script):
        identity = _proc_identity(int(current["pid"]))
        if identity:
            _atomic_json(SUPERVISOR_PID, _supervisor_record(int(current["pid"]), identity))
        return {"status": "ALREADY_SUPERVISED", "pid": current["pid"]}

    discovered = _find_supervisors(expected_script)
    if len(discovered) > 1:
        raise RuntimeError("Multiple verified host supervisors are running; refusing duplicate startup")
    if len(discovered) == 1:
        identity = discovered[0]
        _atomic_json(SUPERVISOR_PID, _supervisor_record(int(identity["pid"]), identity))
        return {"status": "RECOVERED_SUPERVISOR", "pid": identity["pid"]}

    if current:
        try:
            old_pid = int(current.get("pid", 0))
            old_ticks = int(current.get("start_ticks", 0))
        except (TypeError, ValueError):
            old_pid, old_ticks = 0, 0
        if old_pid > 1 and old_ticks > 0 and _proc_start_ticks(old_pid) == old_ticks:
            raise RuntimeError("Supervisor PID record points to a live process with mismatched identity")
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
    identity = _proc_identity(process.pid)
    if not _supervisor_identity_matches(identity, expected_script) or identity["start_ticks"] != ticks:
        try:
            process.terminate()
        except OSError:
            pass
        raise RuntimeError("Host supervisor process identity could not be verified")
    _atomic_json(SUPERVISOR_PID, _supervisor_record(process.pid, identity))
    return {"status": "SUPERVISOR_STARTED", "pid": process.pid}


def _graceful_stop(
    child: subprocess.Popen[bytes],
    backup_database,
    inspect_shutdown_safety,
    *,
    reason: str,
    require_ea_stopped: bool = False,
) -> bool:
    """Recheck live and backed-up broker state before stopping owned services."""
    if child.poll() is not None:
        return True
    start_identity = _proc_identity(child.pid)
    start_ticks = _proc_start_ticks(child.pid)
    if not _start_manager_identity_matches(start_identity, str(START_SCRIPT)) or start_identity["start_ticks"] != start_ticks:
        _write_status("STOP_BLOCKED", reason=reason, safety_reasons=["START_PROCESS_IDENTITY_MISMATCH"])
        return False
    try:
        safety = inspect_shutdown_safety(DB_PATH, heartbeat_stale_seconds=15,
                                         require_ea_stopped=require_ea_stopped)
        if not safety["safe"]:
            _write_status("STOP_BLOCKED", reason=reason, safety_reasons=safety["reasons"])
            return False
        backup = backup_database(DB_PATH, STATE / "backups")
        backup_safety = inspect_shutdown_safety(backup, heartbeat_stale_seconds=15,
                                                require_ea_stopped=require_ea_stopped)
        current_safety = inspect_shutdown_safety(DB_PATH, heartbeat_stale_seconds=15,
                                                 require_ea_stopped=require_ea_stopped)
        if not backup_safety["safe"] or not current_safety["safe"]:
            reasons = sorted(set(backup_safety["reasons"]) | set(current_safety["reasons"]))
            _write_status("STOP_BLOCKED", reason=reason, safety_reasons=reasons)
            return False
        _write_status("SHUTTING_DOWN", reason=reason, backup=backup.name, backup_verified=True)
    except Exception as exc:
        _write_status("STOP_FAILED", reason="SHUTDOWN_PREFLIGHT_FAILED", detail=type(exc).__name__)
        return False
    try:
        with (RUNTIME / "host-stop.log").open("ab") as stop_log:
            result = subprocess.run(
                ["bash", str(STOP_SCRIPT)],
                cwd=ROOT,
                env=os.environ.copy(),
                stdin=subprocess.DEVNULL,
                stdout=stop_log,
                stderr=subprocess.STDOUT,
                timeout=45,
                check=False,
            )
    except (OSError, subprocess.TimeoutExpired):
        _write_status("STOP_FAILED", reason="STOP_SCRIPT_FAILED")
        return False
    if result.returncode != 0:
        _write_status("STOP_FAILED", reason="STOP_SCRIPT_FAILED")
        return False

    current_identity = _proc_identity(child.pid)
    if (
        not _start_manager_identity_matches(current_identity, str(START_SCRIPT))
        or current_identity["start_ticks"] != start_ticks
    ):
        _write_status("STOP_FAILED", reason="START_PROCESS_IDENTITY_CHANGED")
        return False
    if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
        _write_status("STOP_FAILED", reason="PIDFD_SIGNALING_UNAVAILABLE")
        return False
    try:
        pidfd = os.pidfd_open(child.pid, 0)
    except ProcessLookupError:
        return True
    except OSError:
        _write_status("STOP_FAILED", reason="START_PROCESS_PIDFD_OPEN_FAILED")
        return False
    try:
        # Revalidate the whole start-manager identity after pidfd_open, then
        # signal the pinned process descriptor to close the PID-reuse window.
        pinned_identity = _proc_identity(child.pid)
        if (
            not _start_manager_identity_matches(pinned_identity, str(START_SCRIPT))
            or pinned_identity["start_ticks"] != start_ticks
        ):
            _write_status("STOP_FAILED", reason="START_PROCESS_IDENTITY_CHANGED")
            return False
        signal.pidfd_send_signal(pidfd, signal.SIGTERM)
    except ProcessLookupError:
        return True
    except OSError:
        _write_status("STOP_FAILED", reason="START_PROCESS_PIDFD_SIGNAL_FAILED")
        return False
    finally:
        os.close(pidfd)

    try:
        child.wait(timeout=15)
        return True
    except subprocess.TimeoutExpired:
        if child.poll() is not None:
            return True
        current_identity = _proc_identity(child.pid)
        if (
            not _start_manager_identity_matches(current_identity, str(START_SCRIPT))
            or current_identity["start_ticks"] != start_ticks
        ):
            _write_status("STOP_FAILED", reason="START_PROCESS_IDENTITY_CHANGED")
            return False
        # Never SIGKILL a process group: its descendants are not individually
        # attested here. Leave them untouched and require operator inspection.
        _write_status("STOP_FAILED", reason="START_MANAGER_UNRESPONSIVE_AFTER_SIGTERM")
        return False


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
        last_idle_check = 0.0
        while True:
            if stop_requested:
                return 0 if _graceful_stop(
                    child, backup_database, inspect_shutdown_safety,
                    reason="CODESPACES_SIGNAL",
                ) else 2

            if child.poll() is not None:
                _write_status("FAILED", reason="START_SCRIPT_EXITED",
                              exit_code=child.returncode)
                return int(child.returncode or 1)

            now = time.time()
            try:
                last_activity = ACTIVITY_FILE.stat().st_mtime
            except OSError:
                last_activity = now
            idle_seconds = max(0.0, now - last_activity)
            status_written = False
            if idle_seconds >= AUTO_IDLE_TIMEOUT_SECONDS:
                if now - last_idle_check >= 10.0:
                    last_idle_check = now
                    safety = inspect_shutdown_safety(
                        DB_PATH, heartbeat_stale_seconds=15, require_ea_stopped=True
                    )
                    if not safety.get("safe"):
                        reasons = safety.get("reasons") or ["BROKER_STATE_UNVERIFIED"]
                        _write_status("SLEEP_BLOCKED", reason="AUTO_IDLE",
                                      safety_reasons=reasons,
                                      idle_seconds=int(idle_seconds),
                                      idle_timeout_seconds=AUTO_IDLE_TIMEOUT_SECONDS)
                        status_written = True
                    else:
                        _write_status("AUTO_SLEEP_CHECKING", reason="AUTO_IDLE",
                                      idle_seconds=int(idle_seconds),
                                      idle_timeout_seconds=AUTO_IDLE_TIMEOUT_SECONDS)
                        stopped = _graceful_stop(
                            child, backup_database, inspect_shutdown_safety,
                            reason="AUTO_IDLE", require_ea_stopped=True,
                        )
                        if stopped:
                            final_status = _read_json(STATUS_FILE) or {}
                            usage_saved = _record_usage_stop()
                            _write_status(
                                "ENGINE_ASLEEP", reason="AUTO_IDLE",
                                backup=final_status.get("backup"),
                                backup_verified=bool(final_status.get("backup_verified")),
                                daily_usage_saved=usage_saved,
                                idle_timeout_seconds=AUTO_IDLE_TIMEOUT_SECONDS,
                            )
                            return 0
                        status_written = True
                elif idle_seconds >= AUTO_IDLE_TIMEOUT_SECONDS:
                    # Keep the blocker visible; retry the safety check every 10 seconds.
                    status_written = (_read_json(STATUS_FILE) or {}).get("status") == "SLEEP_BLOCKED"
            if not status_written:
                _write_status("RUNNING", start_pid=child.pid,
                              idle_timeout_seconds=AUTO_IDLE_TIMEOUT_SECONDS,
                              idle_seconds=int(idle_seconds))
            time.sleep(2)
    except Exception as exc:
        # Only the exception type is recorded; never persist command output,
        # account identifiers, bridge tokens, or other environment secrets.
        _write_status("FAILED", reason=type(exc).__name__)
        if child is not None and child.poll() is None:
            _graceful_stop(
                child, backup_database, inspect_shutdown_safety,
                reason="SUPERVISOR_FAILURE",
            )
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
            cancel_stop(RUNTIME, DB_PATH, os.environ.get("HOST_GENERATION"))
            _write_status("RUNNING", shutdown_cancelled=True)
            return 0
    except Exception as exc:
        print(f"FAIL: host controller ({type(exc).__name__})", file=sys.stderr)
        return 2
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
