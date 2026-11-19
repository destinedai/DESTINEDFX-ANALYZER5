#!/usr/bin/env python3
"""Verify the local authenticated, loopback-only noVNC stack."""
from __future__ import annotations

import os
import socket
import time
from pathlib import Path

STATE = Path(os.environ.get(
    "DESTINEDFX_CODESPACES_STATE_DIR",
    Path.home() / ".local/share/destinedfx-codespaces",
))
RUNTIME = STATE / "runtime"
PASSWORD = RUNTIME / "vnc.pass"
UID = os.getuid()
failures: list[str] = []


def process(pid_file: str, expected: str) -> tuple[int, list[str]] | None:
    path = RUNTIME / pid_file
    try:
        pid = int(path.read_text(encoding="ascii").strip())
        status = Path(f"/proc/{pid}/status").read_text(encoding="ascii")
        owner = int(next(line for line in status.splitlines() if line.startswith("Uid:")).split()[2])
        args = [
            item.decode("utf-8", "replace")
            for item in Path(f"/proc/{pid}/cmdline").read_bytes().split(bytes([0]))
            if item
        ]
        if owner != UID or expected not in " ".join(args):
            return None
        return pid, args
    except (OSError, ValueError, StopIteration):
        return None


def loopback_listeners(port: int) -> set[str]:
    found: set[str] = set()
    for table, family in (("/proc/net/tcp", socket.AF_INET), ("/proc/net/tcp6", socket.AF_INET6)):
        try:
            rows = Path(table).read_text(encoding="ascii").splitlines()[1:]
        except OSError:
            continue
        for row in rows:
            fields = row.split()
            if len(fields) < 10 or fields[3] != "0A":
                continue
            address, port_hex = fields[1].split(":")
            if int(port_hex, 16) != port:
                continue
            raw = bytes.fromhex(address)
            if family == socket.AF_INET:
                host = socket.inet_ntop(family, raw[::-1])
            else:
                host = socket.inet_ntop(family, b"".join(raw[i:i + 4][::-1] for i in range(0, 16, 4)))
            found.add(host)
    return found


if not PASSWORD.is_file() or PASSWORD.stat().st_mode & 0o077:
    failures.append("password_file_permissions")

xvfb = process("xvfb.pid", "Xvfb :99")
if not xvfb:
    failures.append("xvfb_process_identity")
else:
    lock = Path("/tmp/.X99-lock")
    display_socket = Path("/tmp/.X11-unix/X99")
    try:
        if int(lock.read_text(encoding="ascii").strip()) != xvfb[0]:
            failures.append("xvfb_lock_identity")
        if (
            not display_socket.is_socket()
            or display_socket.stat().st_uid != UID
            or lock.stat().st_uid != UID
        ):
            failures.append("xvfb_socket_ownership")
    except (OSError, ValueError):
        failures.append("xvfb_display_artifacts")

x11vnc = process("x11vnc.pid", "x11vnc")
if not x11vnc:
    failures.append("x11vnc_process_identity")
else:
    args = x11vnc[1]
    required = ("-localhost", "-display", ":99", "-rfbport", "5900", "-passwdfile", str(PASSWORD))
    if not all(value in args for value in required):
        failures.append("x11vnc_authentication_or_bind")

novnc = process("novnc.pid", "websockify")
if not novnc:
    failures.append("novnc_process_identity")
else:
    args = novnc[1]
    try:
        listen_index = args.index("--listen")
        loopback_bind = args[listen_index + 1] == "127.0.0.1"
    except (ValueError, IndexError):
        loopback_bind = False
    if not loopback_bind or not all(value in args for value in ("6080", "127.0.0.1:5900")):
        failures.append("novnc_loopback_bind")

for port in (5900, 6080):
    addresses = loopback_listeners(port)
    if not addresses or not addresses.issubset({"127.0.0.1", "::1"}):
        failures.append(f"port_{port}_not_loopback_only")
        continue
    ready = False
    for _ in range(20):
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                ready = True
                break
        except OSError:
            time.sleep(0.1)
    if not ready:
        failures.append(f"port_{port}_unreachable")

if failures:
    print("FAIL: VNC self-check failed: " + ",".join(failures))
    raise SystemExit(1)
print("PASS: authenticated VNC and noVNC are bound only to loopback; Xvfb identity is verified")
