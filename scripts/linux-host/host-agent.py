#!/usr/bin/env python3
"""Read-only Linux MT5 host health agent. No credentials are read or emitted."""
import argparse, json, os, re, shutil, subprocess, time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

MAX_HEARTBEAT_AGE = int(os.getenv("EA_HEARTBEAT_MAX_AGE_SECONDS", "15"))
TERMINAL_PATTERN = os.getenv("MT5_TERMINAL_PATTERN", r"(?i)(terminal64|terminal\.exe)")
HEARTBEAT_PATH = os.getenv("EA_HEARTBEAT_JSON", "")
JOURNAL_UNIT = os.getenv("MT5_JOURNAL_UNIT", "mt5-terminal.service")


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


def journal_classification():
    try:
        raw = subprocess.run(
            ["journalctl", "-u", JOURNAL_UNIT, "-n", "40", "--no-pager", "-o", "cat"],
            capture_output=True, text=True, timeout=2, check=False,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return {"status": "UNAVAILABLE", "class": "NO_JOURNAL"}
    text = raw.lower()
    classes = (
        ("WRONG_CREDENTIALS", ("invalid account", "invalid password", "wrong password", "authorization failed")),
        ("ACCOUNT_OR_SERVER_MISMATCH", ("account or server", "login failed", "invalid account")),
        ("NO_CONNECTION", ("no connection", "connection failed", "network")),
        ("SERVER_NOT_FOUND", ("server not found", "unknown server")),
        ("TERMINAL_OFFLINE", ("terminal offline", "terminal stopped")),
        ("EA_OFFLINE", ("expert removed", "expert advisor stopped", "ea offline")),
        ("ALGO_TRADING_DISABLED", ("algo trading disabled", "autotrading disabled")),
        ("WEBREQUEST_NOT_ALLOWED", ("webrequest", "not allowed")),
    )
    for code, needles in classes:
        if any(needle in text for needle in needles):
            return {"status": "CLASSIFIED", "class": code}
    if raw.strip():
        return {"status": "PRESENT", "class": "UNCLASSIFIED"}
    return {"status": "EMPTY", "class": "NO_LOGIN_ATTEMPT"}


def heartbeat_report(now=None):
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
        safe_account = {key: account.get(key) for key in ("login", "server", "company", "account_mode") if account.get(key) is not None}
        return {"present": True, "fresh": 0 <= age <= MAX_HEARTBEAT_AGE, "age_seconds": round(age, 3), "account": safe_account, "state": value.get("state"), "terminal_connected": bool(value.get("terminal_connected"))}
    except (OSError, ValueError, TypeError, KeyError):
        return {"present": False, "fresh": False, "reason": "INVALID"}


def report():
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
    heartbeat = heartbeat_report()
    low_memory = mem_available > 0 and mem_available < int(os.getenv("HOST_AGENT_MIN_AVAILABLE_BYTES", str(256 * 1024 * 1024)))
    return {"schema": "destinedfx.host-agent.v1", "timestamp": time.time(), "terminal_process_alive": terminal,
            "memory": {"total_bytes": mem_total, "available_bytes": mem_available, "low": low_memory},
            "swap": {"total_bytes": swap_total, "free_bytes": swap_free, "configured": swap_total >= 2 * 1024 * 1024 * 1024},
            "disk": {"free_bytes": disk.free, "total_bytes": disk.total}, "uptime_seconds": uptime,
            "journal": journal_classification(), "ea_heartbeat": heartbeat,
            "healthy": bool(terminal and not low_memory and heartbeat.get("fresh") and heartbeat.get("terminal_connected"))}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/health", "/health/ready"):
            self.send_error(404); return
        payload = report()
        status = 200 if payload["healthy"] or self.path == "/health" else 503
        body = json.dumps(payload, separators=(",", ":")).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *_):
        return


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--once", action="store_true"); parser.add_argument("--health", action="store_true"); parser.add_argument("--host", default="127.0.0.1"); parser.add_argument("--port", type=int, default=int(os.getenv("HOST_AGENT_PORT", "8787")))
    args = parser.parse_args()
    if args.once or args.health:
        result = report(); print(json.dumps(result, separators=(",", ":"))); return 0 if result["healthy"] else 1 if args.health else 0
    HTTPServer((args.host, args.port), Handler).serve_forever(); return 0

if __name__ == "__main__":
    raise SystemExit(main())
