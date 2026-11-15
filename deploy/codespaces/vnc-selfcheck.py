#!/usr/bin/env python3
"""Verify the local authenticated noVNC stack without exposing its password."""
import os
import socket
import time
from pathlib import Path

state = Path(os.environ.get("DESTINEDFX_CODESPACES_STATE_DIR", Path.home() / ".local/share/destinedfx-codespaces"))
runtime = state / "runtime"
password = runtime / "vnc.pass"
failures = []
if not password.is_file() or password.stat().st_mode & 0o077:
    failures.append("password_file")
for name in ("xvfb", "x11vnc", "novnc"):
    path = runtime / f"{name}.pid"
    try:
        pid = int(path.read_text().strip())
        os.kill(pid, 0)
    except (OSError, ValueError):
        failures.append(f"{name}_process")
for port in (5900, 6080):
    ready = False
    for _ in range(20):
        with socket.socket() as sock:
            sock.settimeout(1)
            try:
                sock.connect(("127.0.0.1", port))
                ready = True
                break
            except OSError:
                time.sleep(0.1)
    if not ready:
        failures.append(f"port_{port}")
if failures:
    print("FAIL: VNC self-check failed: " + ",".join(failures))
else:
    print("PASS: VNC stack is ready")
raise SystemExit(bool(failures))
