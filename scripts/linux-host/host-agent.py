#!/usr/bin/env python3
"""Linux MT5 host health agent. No credentials are read or emitted."""
import argparse
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


def _num(path, key):
    try:
        for line in Path(path).read_text().splitlines():
            if line.startswith(key):
                return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    return 0


def terminal_process_alive():
    for entry in Path("/proc").glob("[0-9]*"):
        try:
            cmd = (entry / "cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "ignore")
        except OSError:
            continue
        if re.search(TERMINAL_PATTERN, cmd):
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
    terminal = terminal_process_alive()
    wine_version, wine_ok = wine_report()
    heartbeat = heartbeat_report()
    low_memory = mem_available > 0 and mem_available < int(
        os.getenv("HOST_AGENT_MIN_AVAILABLE_BYTES", str(256 * 1024 * 1024))
    )
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
        "healthy": bool(wine_ok and terminal and not low_memory),
    }


class Handler(BaseHTTPRequestHandler):
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
