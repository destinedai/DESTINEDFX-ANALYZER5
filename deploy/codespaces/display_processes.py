#!/usr/bin/env python3
"""Safe discovery and cleanup for the single-user Codespaces display stack.

This module only terminates positively identified legacy DestinedFX VNC
processes. It never searches for or signals MT5/Wine terminal processes.
"""
from __future__ import annotations

import argparse
import os
import signal
import time
from pathlib import Path
from typing import Any


def _snapshot(pid: int, proc_root: Path = Path("/proc")) -> dict[str, Any] | None:
    base = proc_root / str(pid)
    try:
        status = (base / "status").read_text(encoding="ascii")
        uid_line = next(line for line in status.splitlines() if line.startswith("Uid:"))
        effective_uid = int(uid_line.split()[2])
        args = [
            item.decode("utf-8", "replace")
            for item in (base / "cmdline").read_bytes().split(b"\0")
            if item
        ]
        exe = os.path.realpath(base / "exe")
        raw_stat = (base / "stat").read_text(encoding="ascii")
        tail = raw_stat[raw_stat.rfind(")") + 2 :].split()
        return {
            "pid": pid,
            "uid": effective_uid,
            "args": args,
            "exe": exe,
            "state": tail[0],
            "start_ticks": int(tail[19]),
        }
    except (OSError, ValueError, IndexError, StopIteration):
        return None


def matches_xvfb(exe: str, args: list[str], uid: int, expected_uid: int) -> bool:
    return (
        uid == expected_uid
        and os.path.realpath(exe) == "/usr/bin/Xvfb"
        and len(args) >= 2
        and args[1:] == [":99", "-screen", "0", "1280x800x24", "-nolisten", "tcp"]
    )


def matches_legacy_x11vnc(
    exe: str, args: list[str], uid: int, expected_uid: int
) -> bool:
    """Match only the old unauthenticated :99/5900 DestinedFX service."""
    return (
        uid == expected_uid
        and os.path.realpath(exe) == "/usr/bin/x11vnc"
        and "-display" in args
        and args[args.index("-display") + 1 : args.index("-display") + 2] == [":99"]
        and "-rfbport" in args
        and args[args.index("-rfbport") + 1 : args.index("-rfbport") + 2] == ["5900"]
        and "-localhost" in args
        and "-nopw" in args
        and "-passwdfile" not in args
    )


def matches_legacy_websockify(
    exe: str, args: list[str], uid: int, expected_uid: int
) -> bool:
    """Match the old wildcard-bound noVNC service, never a loopback service."""
    script_present = any(os.path.realpath(arg) == "/usr/bin/websockify" for arg in args)
    python_executable = Path(exe).name.startswith("python")
    required = {
        "--web=/usr/share/novnc",
        "--heartbeat=30",
        "--idle-timeout=0",
        "6080",
        "127.0.0.1:5900",
    }
    if uid != expected_uid or not python_executable or not script_present or not required.issubset(set(args)):
        return False
    if "--listen" in args:
        index = args.index("--listen")
        listen_host = args[index + 1] if index + 1 < len(args) else ""
        if listen_host in {"127.0.0.1", "localhost", "::1"}:
            return False
    if any(arg in {"--listen=127.0.0.1", "--listen=localhost", "--listen=::1"} for arg in args):
        return False
    return True


def _terminate_verified(pid: int, start_ticks: int, expected_uid: int) -> bool:
    """Send TERM only to the same owned process; never escalate to SIGKILL."""
    current = _snapshot(pid)
    if not current or current["uid"] != expected_uid or current["start_ticks"] != start_ticks:
        return True
    if current["state"] == "Z":
        return True
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return True
    except PermissionError:
        return False

    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        current = _snapshot(pid)
        if not current or current["start_ticks"] != start_ticks:
            return True
        if current["uid"] != expected_uid or current["state"] == "Z":
            return True
        time.sleep(0.1)
    return False


def _find_legacy(kind: str, proc_root: Path, expected_uid: int) -> list[dict[str, Any]]:
    matcher = matches_legacy_x11vnc if kind == "x11vnc" else matches_legacy_websockify
    found: list[dict[str, Any]] = []
    for entry in proc_root.iterdir():
        if not entry.name.isdigit():
            continue
        item = _snapshot(int(entry.name), proc_root)
        if item and matcher(item["exe"], item["args"], item["uid"], expected_uid):
            found.append(item)
    return found


def cleanup_legacy(proc_root: Path = Path("/proc"), expected_uid: int | None = None) -> list[str]:
    uid = os.getuid() if expected_uid is None else expected_uid
    stopped: list[str] = []
    for kind in ("x11vnc", "websockify"):
        matches = _find_legacy(kind, proc_root, uid)
        if len(matches) > 1:
            raise RuntimeError(f"multiple untracked legacy {kind} processes; refusing ambiguous cleanup")
        if not matches:
            continue
        item = matches[0]
        if not _terminate_verified(item["pid"], item["start_ticks"], uid):
            raise RuntimeError(f"legacy {kind} process did not stop after SIGTERM")
        stopped.append(f"{kind}:{item['pid']}")
    return stopped


def check_xvfb(pid: int, proc_root: Path = Path("/proc")) -> None:
    item = _snapshot(pid, proc_root)
    if not item or not matches_xvfb(item["exe"], item["args"], item["uid"], os.getuid()):
        raise RuntimeError("Xvfb process identity does not match this Codespaces owner")
    if proc_root != Path("/proc"):
        return
    lock = Path("/tmp/.X99-lock")
    display_socket = Path("/tmp/.X11-unix/X99")
    try:
        lock_pid = int(lock.read_text(encoding="ascii").strip())
        lock_stat = lock.stat()
        socket_stat = display_socket.stat()
    except (OSError, ValueError):
        raise RuntimeError("Xvfb lock or display socket is missing") from None
    if lock_pid != pid or lock_stat.st_uid != os.getuid():
        raise RuntimeError("Xvfb lock does not belong to the verified process")
    if not display_socket.is_socket() or socket_stat.st_uid != os.getuid():
        raise RuntimeError("Xvfb display socket is not owned by this Codespaces user")


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    check = sub.add_parser("check-xvfb")
    check.add_argument("pid", type=int)
    sub.add_parser("cleanup-legacy")
    args = parser.parse_args()
    try:
        if args.command == "check-xvfb":
            check_xvfb(args.pid)
            print("PASS: existing Xvfb process, lock and socket identity verified")
        else:
            stopped = cleanup_legacy()
            if stopped:
                for item in stopped:
                    print(f"PASS: gracefully stopped identified legacy VNC process {item}")
            else:
                print("PASS: no untracked legacy VNC processes found")
    except (OSError, RuntimeError) as exc:
        print(f"FAIL: display process check ({exc})")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
