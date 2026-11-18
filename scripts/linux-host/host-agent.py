#!/usr/bin/env python3
"""Linux MT5 host health agent. No credentials are read or emitted."""
import argparse
import hmac
import json
import os
import re
import subprocess
import shutil
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_REPO_ROOT / "managed-worker"))
from mt5_journal import (  # noqa: E402
    classify_mt5_journal,
    latest_journal_file,
    read_attempt_start,
    read_recent_mt5_journal,
)

MAX_HEARTBEAT_AGE = int(os.getenv("EA_HEARTBEAT_MAX_AGE_SECONDS", "15"))
TERMINAL_PATTERN = os.getenv("MT5_TERMINAL_PATTERN", r"(?i)(terminal64|terminal\.exe)")
HEARTBEAT_PATH = os.getenv("EA_HEARTBEAT_JSON", "")
JOURNAL_PATH = os.getenv("MT5_JOURNAL_PATH", "")
JOURNAL_DIR = os.getenv("MT5_JOURNAL_DIR", "/var/lib/destinedfx/terminal/Logs")
WINE_BIN = os.getenv("WINE_BIN", "wine")
CONNECT_JOB_START_FILE = os.getenv(
    "CONNECT_JOB_START_FILE", "/var/lib/destinedfx/connect-job-start.epoch"
)
SLOT_ISOLATION_ENABLED = os.getenv("DESTINEDFX_ISOLATED_SLOTS", "").lower() == "true"
SLOT_ROOT = Path(os.getenv("DESTINEDFX_SLOT_ROOT", "/home/destinedfx-slots"))
SLOT_RUNTIME = Path(os.getenv("DESTINEDFX_SLOT_RUNTIME", "/var/lib/destinedfx-slot-runtime"))
HOST_AGENT_TOKEN = os.getenv("HOST_AGENT_TOKEN", "")
HOST_OWNER_UID = int(os.getenv("DESTINEDFX_HOST_OWNER_UID", "1000"))
SLOT_MAX_TERMINALS = max(1, min(8, int(os.getenv("DESTINEDFX_SLOT_MAX_TERMINALS", "1"))))


def _num(path, key):
    try:
        for line in Path(path).read_text().splitlines():
            if line.startswith(key):
                return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    return 0


def _norm_path(value):
    return os.path.normcase(os.path.realpath(os.path.expanduser(str(value))))


def _norm_arg(value):
    return value.replace("\\", "/").rstrip("/").lower()


def _expected_terminal_args(exe, prefix):
    exe_path = Path(exe).expanduser()
    exe_norm = _norm_path(exe_path)
    expected = {_norm_arg(exe_norm)}
    if prefix:
        prefix_path = Path(prefix).expanduser()
        try:
            relative = exe_path.resolve().relative_to((prefix_path / "drive_c").resolve())
        except (OSError, ValueError):
            relative = None
        if relative is not None:
            expected.add(_norm_arg("c:/" + str(relative)))
        expected.add(_norm_arg("z:/" + exe_norm.lstrip("/")))
    return expected


def terminal_process_alive(exe=None, prefix=None, proc_root=None):
    """Return true only when the configured terminal and Wine prefix are proven."""
    exe_value = exe if exe is not None else os.getenv("MT5_TERMINAL_EXE", "")
    if not exe_value:
        return False
    prefix_value = prefix if prefix is not None else os.getenv("WINEPREFIX", "")
    expected = _expected_terminal_args(exe_value, prefix_value)
    root = Path(proc_root) if proc_root is not None else Path("/proc")
    prefix_norm = _norm_path(prefix_value) if prefix_value else None
    for entry in root.glob("[0-9]*"):
        try:
            raw = (entry / "cmdline").read_bytes()
        except OSError:
            continue
        args = [a.decode("utf-8", "ignore") for a in raw.split(b"\0") if a]
        if not any(_norm_arg(arg) in expected for arg in args):
            continue
        if prefix_norm is not None:
            try:
                values = (entry / "environ").read_bytes().split(b"\0")
            except OSError:
                continue
            actual = next(
                (value[len(b"WINEPREFIX="):].decode("utf-8", "ignore")
                 for value in values if value.startswith(b"WINEPREFIX=")),
                None,
            )
            if actual is None or _norm_path(actual) != prefix_norm:
                continue
        return True
    return False


def wine_report():
    try:
        result = subprocess.run(
            [WINE_BIN, "--version"],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired):
        return "unknown", False
    version = (result.stdout or result.stderr).strip().splitlines()
    value = version[0][:128] if version else "unknown"
    match = re.match(r"^wine-?([0-9]+)\.([0-9]+)(?:\s|$)", value)
    if result.returncode != 0 or not match:
        return "unknown", False
    major, minor = match.groups()
    return f"wine-{major}.{minor}", major == "9" or (major == "10" and minor in {"0", "1", "2"}) or (major == "11" and minor == "21")


def journal_classification():
    """Classify only failures newer than the active connect-attempt marker."""
    started_at = read_attempt_start(CONNECT_JOB_START_FILE)
    if started_at is None:
        return {"status": "EMPTY", "class": "NO_ACTIVE_CONNECT_JOB"}
    path = Path(JOURNAL_PATH) if JOURNAL_PATH else latest_journal_file(JOURNAL_DIR)
    if path is None or not path.is_file():
        return {"status": "UNAVAILABLE", "class": "NO_JOURNAL"}
    try:
        text = read_recent_mt5_journal(path)
    except OSError:
        return {"status": "UNAVAILABLE", "class": "NO_JOURNAL"}
    code = classify_mt5_journal(text, started_at)
    return {
        "status": "PRESENT" if code == "LOGIN_FAILED_UNCLASSIFIED" else "CLASSIFIED",
        "class": code,
    }


def heartbeat_report(now=None):
    import time

    now = time.time() if now is None else now
    if not HEARTBEAT_PATH:
        return {"present": False, "fresh": False, "reason": "MISSING_CONFIG"}
    try:
        value = json.loads(Path(HEARTBEAT_PATH).read_text())
        timestamp = value.get("timestamp")
        if isinstance(timestamp, str):
            from datetime import datetime
            timestamp = datetime.fromisoformat(timestamp.replace("Z", "+00:00")).timestamp()
        age = now - float(timestamp)
        account = value.get("account") if isinstance(value.get("account"), dict) else {}
        safe_account = {
            key: account.get(key)
            for key in ("login", "server", "company", "account_mode")
            if account.get(key) is not None
        }
        return {
            "present": True,
            "fresh": 0 <= age <= MAX_HEARTBEAT_AGE,
            "age_seconds": round(age, 3),
            "account": safe_account,
            "state": value.get("state"),
            "terminal_connected": bool(value.get("terminal_connected")),
        }
    except (OSError, ValueError, TypeError, KeyError):
        return {"present": False, "fresh": False, "reason": "INVALID"}



def slot_isolation_report():
    """Sanitized capability check; no account identities or credentials are returned."""
    if not SLOT_ISOLATION_ENABLED:
        return {"enabled": False, "ready": False, "active_slots": 0,
                "max_slots": SLOT_MAX_TERMINALS, "reason": "ISOLATION_DISABLED"}
    checks = {
        "root_agent": os.geteuid() == 0,
        "host_token": bool(HOST_AGENT_TOKEN and len(HOST_AGENT_TOKEN) >= 32),
        "slots_root": SLOT_ROOT.is_dir(),
        "runtime_root": SLOT_RUNTIME.is_dir(),
        "sudo": shutil.which("sudo") is not None,
        "useradd": shutil.which("useradd") is not None,
        "userdel": shutil.which("userdel") is not None,
        "xvfb": Path("/usr/bin/Xvfb").is_file(),
        "xauth": Path("/usr/bin/xauth").is_file(),
        "wine": shutil.which(WINE_BIN) is not None,
    }
    if checks["slots_root"]:
        info = SLOT_ROOT.stat()
        checks["slots_root"] = info.st_uid == 0 and (info.st_mode & 0o777) == 0o711
    if checks["runtime_root"]:
        info = SLOT_RUNTIME.stat()
        checks["runtime_root"] = info.st_uid == 0 and (info.st_mode & 0o077) == 0
    active = 0
    if checks["slots_root"]:
        for marker in SLOT_ROOT.glob("*/**/.destinedfx-worker.json"):
            try:
                value = json.loads(marker.read_text(encoding="utf-8"))
                if isinstance(value, dict) and value.get("status") in {
                    "LAUNCH_PENDING", "AUTHENTICATING", "READY", "OPERATOR_REVIEW"
                }:
                    active += 1
            except (OSError, ValueError, TypeError):
                active += 1
    return {
        "enabled": True, "ready": all(checks.values()), "checks": checks,
        "active_slots": min(active, SLOT_MAX_TERMINALS), "max_slots": SLOT_MAX_TERMINALS,
    }


def _slot_module():
    from linux_isolation import verify_slot_reservation, verify_slot_process, find_slot_process
    return verify_slot_reservation, verify_slot_process, find_slot_process


def _authorized_internal(request):
    supplied = request.headers.get("Authorization", "")
    prefix = "Bearer "
    return bool(HOST_AGENT_TOKEN and supplied.startswith(prefix) and
                hmac.compare_digest(supplied[len(prefix):], HOST_AGENT_TOKEN))


def _internal_slot_action(path, payload):
    verify_reservation, verify_process, find_process = _slot_module()
    if not isinstance(payload, dict):
        return 400, {"verified": False, "code": "INVALID_SLOT_PAYLOAD"}
    if not verify_reservation(payload, SLOT_ROOT, HOST_OWNER_UID):
        return 409, {"verified": False, "code": "SLOT_RESERVATION_UNVERIFIED"}
    if path == "/internal/slot/reservation":
        return 200, {"verified": True, "linux_uid": int(payload["linux_uid"]),
                     "display": str(payload["display"])}
    if path == "/internal/slot/discover":
        found = find_process(
            linux_uid=int(payload["linux_uid"]),
            wine_prefix=str(payload["wine_prefix"]),
            terminal_path=str(payload["terminal_path"]),
            display=str(payload["display"]),
        )
        if not found:
            return 409, {"verified": False, "code": "TERMINAL_PROCESS_NOT_FOUND"}
        return 200, {"verified": True, **found}
    if path == "/internal/slot/process":
        if not verify_process(payload):
            return 409, {"verified": False, "code": "TERMINAL_PROCESS_IDENTITY_UNVERIFIED"}
        return 200, {"verified": True}
    return 404, {"verified": False, "code": "NOT_FOUND"}


def report():
    import time

    mem_total = _num("/proc/meminfo", "MemTotal:")
    mem_available = _num("/proc/meminfo", "MemAvailable:")
    swap_total = _num("/proc/meminfo", "SwapTotal:")
    swap_free = _num("/proc/meminfo", "SwapFree:")
    try:
        uptime = float(Path("/proc/uptime").read_text().split()[0])
    except (OSError, ValueError, IndexError):
        uptime = 0.0
    disk = shutil.disk_usage(os.getenv("HOST_AGENT_DISK_PATH", "/"))
    isolated = slot_isolation_report()
    terminal = terminal_process_alive() if not SLOT_ISOLATION_ENABLED else bool(isolated["active_slots"])
    wine_version, wine_ok = wine_report()
    heartbeat = heartbeat_report()
    low_memory = mem_available > 0 and mem_available < int(
        os.getenv("HOST_AGENT_MIN_AVAILABLE_BYTES", str(256 * 1024 * 1024))
    )
    if SLOT_ISOLATION_ENABLED:
        host_ready = bool(wine_ok and isolated["ready"] and not low_memory and disk.free >= 2 * 1024**3)
        account_ready = bool(host_ready and isolated["active_slots"] > 0)
    else:
        host_ready = bool(wine_ok and terminal and not low_memory)
        account_ready = bool(host_ready and heartbeat.get("fresh") and heartbeat.get("terminal_connected"))
    return {
        "schema": "destinedfx.host-agent.v1",
        "timestamp": time.time(),
        "terminal_process_alive": terminal,
        "wine_version": wine_version,
        "wine_ok": wine_ok,
        "memory": {"total_bytes": mem_total, "available_bytes": mem_available, "low": low_memory},
        "swap": {"total_bytes": swap_total, "free_bytes": swap_free, "configured": swap_total >= 2 * 1024 * 1024 * 1024},
        "disk": {"free_bytes": disk.free, "total_bytes": disk.total},
        "uptime_seconds": uptime,
        "journal": journal_classification(),
        "ea_heartbeat": heartbeat,
        "host_ready": host_ready,
        "account_ready": account_ready,
        "healthy": host_ready if SLOT_ISOLATION_ENABLED else account_ready,
        "slot_isolation": isolated,
    }


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if not self.path.startswith("/internal/slot/"):
            self.send_error(404)
            return
        if not _authorized_internal(self):
            self.send_error(401)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 32768:
                self.send_error(413)
                return
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            status, body = _internal_slot_action(self.path, payload)
        except (OSError, ValueError, TypeError, KeyError):
            status, body = 400, {"verified": False, "code": "INVALID_SLOT_PAYLOAD"}
        encoded = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self):
        if self.path not in ("/health", "/health/ready"):
            self.send_error(404)
            return
        payload = report()
        status = 200 if payload["healthy"] or self.path == "/health" else 503
        body = json.dumps(payload, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        return


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--health", action="store_true")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=int(os.getenv("HOST_AGENT_PORT", "8787")))
    args = parser.parse_args()
    if args.once or args.health:
        result = report()
        print(json.dumps(result, separators=(",", ":")))
        return 0 if result["healthy"] else 1 if args.health else 0
    HTTPServer((args.host, args.port), Handler).serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
