#!/usr/bin/env python3
"""Capture and safely stop one exact Linux process identity from /proc.

Records bind PID + start ticks + effective UID + executable + the complete argv.
A reused PID or a process that has exec'd into a different command is never signalled.
This helper only sends SIGTERM; it never escalates to SIGKILL.
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import tempfile
import time
from pathlib import Path
from typing import Any, Callable

VERSION = 1
IDENTITY_FIELDS = ("pid", "start_ticks", "uid", "exe", "args")


class IdentityError(RuntimeError):
    pass


def snapshot(pid: int, proc_root: Path = Path("/proc")) -> dict[str, Any] | None:
    if pid <= 1:
        return None
    base = proc_root / str(pid)
    try:
        status = (base / "status").read_text(encoding="ascii")
        uid_line = next(line for line in status.splitlines() if line.startswith("Uid:"))
        uid = int(uid_line.split()[2])
        raw_stat = (base / "stat").read_text(encoding="ascii")
        tail = raw_stat[raw_stat.rfind(")") + 2 :].split()
        args = [
            item.decode("utf-8", "replace")
            for item in (base / "cmdline").read_bytes().split(b"\0")
            if item
        ]
        exe = os.path.realpath(base / "exe")
        return {
            "pid": pid,
            "start_ticks": int(tail[19]),
            "uid": uid,
            "exe": exe,
            "args": args,
            "state": tail[0],
        }
    except (OSError, ValueError, IndexError, StopIteration):
        return None


def same_identity(record: dict[str, Any], current: dict[str, Any] | None) -> bool:
    if not current or current.get("state") in {"Z", "X"}:
        return False
    return all(record.get(key) == current.get(key) for key in IDENTITY_FIELDS)


def capture_identity(
    pid: int,
    *,
    expected_uid: int,
    expected_fragment: str = "",
    expected_exe: str = "",
    proc_root: Path = Path("/proc"),
    timeout_seconds: float = 5.0,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> dict[str, Any]:
    if pid <= 1 or expected_uid < 0 or timeout_seconds < 0:
        raise IdentityError("invalid process identity arguments")
    deadline = clock() + timeout_seconds
    while True:
        current = snapshot(pid, proc_root)
        if current and current["state"] not in {"Z", "X"}:
            args_text = " ".join(current["args"])
            exe_matches = not expected_exe or current["exe"] == os.path.realpath(expected_exe)
            fragment_matches = not expected_fragment or expected_fragment in args_text
            if current["uid"] == expected_uid and exe_matches and fragment_matches:
                return {key: current[key] for key in IDENTITY_FIELDS}
        if clock() >= deadline:
            raise IdentityError("process did not reach the expected owner/executable/argument identity")
        sleep(0.1)


def find_and_capture_identity(
    *,
    expected_uid: int,
    expected_exe: str,
    expected_args: list[str],
    proc_root: Path = Path("/proc"),
) -> dict[str, Any]:
    """Find exactly one process whose complete argv tail/exe/UID match."""
    if proc_root != Path("/proc"):
        raise IdentityError("process discovery requires the live Linux /proc filesystem")
    executable = os.path.realpath(expected_exe)
    matches: list[dict[str, Any]] = []
    try:
        entries = list(proc_root.iterdir())
    except OSError as exc:
        raise IdentityError("cannot enumerate Linux process table") from exc
    for entry in entries:
        if not entry.name.isdigit():
            continue
        current = snapshot(int(entry.name), proc_root)
        if not current or current["state"] in {"Z", "X"}:
            continue
        args = current["args"]
        if (
            current["uid"] == expected_uid
            and current["exe"] == executable
            and len(args) == len(expected_args) + 1
            and bool(args[0])
            and args[1:] == expected_args
        ):
            matches.append({key: current[key] for key in IDENTITY_FIELDS})
    if len(matches) != 1:
        raise IdentityError(f"expected exactly one matching process; found {len(matches)}")
    return matches[0]


def write_record(path: Path, identity: dict[str, Any]) -> None:
    if set(IDENTITY_FIELDS).difference(identity):
        raise IdentityError("process identity record is incomplete")
    if identity["pid"] <= 1 or identity["start_ticks"] <= 0 or identity["uid"] < 0:
        raise IdentityError("process identity record contains invalid numeric fields")
    if not isinstance(identity["exe"], str) or not identity["exe"].startswith("/"):
        raise IdentityError("process identity executable is invalid")
    if not isinstance(identity["args"], list) or not identity["args"] or not all(
        isinstance(arg, str) for arg in identity["args"]
    ):
        raise IdentityError("process identity argv is invalid")
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    payload = {"version": VERSION, **{key: identity[key] for key in IDENTITY_FIELDS}}
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", prefix=f".{path.name}.", suffix=".tmp",
            dir=path.parent, delete=False,
        ) as stream:
            temporary_name = stream.name
            os.fchmod(stream.fileno(), 0o600)
            json.dump(payload, stream, sort_keys=True, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, path)
        os.chmod(path, 0o600)
    finally:
        if temporary_name:
            Path(temporary_name).unlink(missing_ok=True)


def read_record(path: Path) -> dict[str, Any]:
    try:
        info = path.lstat()
        if (
            not path.is_file()
            or path.is_symlink()
            or info.st_mode & 0o077
            or (os.geteuid() != 0 and info.st_uid != os.geteuid())
        ):
            raise IdentityError("process identity record permissions, owner or file type are unsafe")
        payload = json.loads(path.read_text(encoding="utf-8"))
    except IdentityError:
        raise
    except (OSError, ValueError) as exc:
        raise IdentityError("process identity record is unavailable or malformed") from exc
    if not isinstance(payload, dict) or payload.get("version") != VERSION:
        raise IdentityError("unsupported process identity record")
    identity = {key: payload.get(key) for key in IDENTITY_FIELDS}
    if identity["pid"] is None or identity["start_ticks"] is None or identity["uid"] is None:
        raise IdentityError("process identity record is incomplete")
    if not isinstance(identity["exe"], str) or not isinstance(identity["args"], list):
        raise IdentityError("process identity record is malformed")
    return identity


def verify_record(path: Path, proc_root: Path = Path("/proc")) -> bool:
    try:
        record = read_record(path)
    except IdentityError:
        return False
    return same_identity(record, snapshot(int(record["pid"]), proc_root))


def _pidfd_send_term(pid: int, record: dict[str, Any], proc_root: Path) -> None:
    """Signal the process pinned by pidfd, after rechecking the complete identity."""
    if proc_root != Path("/proc"):
        raise IdentityError("pidfd signalling is disabled for synthetic proc roots")
    if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
        raise IdentityError("Linux pidfd signalling is unavailable; refusing a racy PID signal")
    pidfd = os.pidfd_open(pid, 0)
    try:
        current = snapshot(pid, proc_root)
        if not same_identity(record, current):
            raise IdentityError("process identity changed after pidfd open; no signal was sent")
        signal.pidfd_send_signal(pidfd, signal.SIGTERM)
    finally:
        os.close(pidfd)


def stop_record(
    path: Path,
    *,
    proc_root: Path = Path("/proc"),
    kill_process: Callable[[int, int], None] | None = None,
    timeout_seconds: float = 5.0,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> bool:
    """Stop only the process matching the complete saved identity with SIGTERM."""
    record = read_record(path)
    pid = int(record["pid"])
    current = snapshot(pid, proc_root)
    if current is None or current.get("state") in {"Z", "X"}:
        path.unlink(missing_ok=True)
        return True
    if not same_identity(record, current):
        raise IdentityError("PID no longer matches its recorded start-time/UID/executable/argv; left untouched")
    try:
        if kill_process is not None:
            # Dependency injection is used only by isolated unit tests.
            kill_process(pid, signal.SIGTERM)
        else:
            _pidfd_send_term(pid, record, proc_root)
    except ProcessLookupError:
        path.unlink(missing_ok=True)
        return True
    except PermissionError as exc:
        raise IdentityError("permission denied while stopping the verified process") from exc

    deadline = clock() + timeout_seconds
    while clock() < deadline:
        current = snapshot(pid, proc_root)
        if current is None or current.get("state") in {"Z", "X"}:
            path.unlink(missing_ok=True)
            return True
        if not same_identity(record, current):
            raise IdentityError("process identity changed after SIGTERM; no further signal was sent")
        sleep(0.1)
    raise IdentityError("verified process did not stop after SIGTERM; refusing SIGKILL escalation")

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    capture = commands.add_parser("capture")
    capture.add_argument("--pid", required=True, type=int)
    capture.add_argument("--record", required=True, type=Path)
    capture.add_argument("--expected-uid", type=int, default=os.geteuid())
    capture.add_argument("--expected-fragment", default="")
    capture.add_argument("--expected-exe", default="")
    verify = commands.add_parser("verify")
    verify.add_argument("--record", required=True, type=Path)
    stop = commands.add_parser("stop")
    stop.add_argument("--record", required=True, type=Path)
    stop.add_argument("--timeout", type=float, default=5.0)
    find_capture = commands.add_parser("find-capture")
    find_capture.add_argument("--record", required=True, type=Path)
    find_capture.add_argument("--expected-uid", type=int, required=True)
    find_capture.add_argument("--expected-exe", required=True)
    find_capture.add_argument("--expected-args", nargs="+", required=True)
    args = parser.parse_args()
    try:
        if args.command == "capture":
            identity = capture_identity(
                args.pid, expected_uid=args.expected_uid,
                expected_fragment=args.expected_fragment,
                expected_exe=args.expected_exe,
            )
            write_record(args.record, identity)
            print("PASS: complete process identity recorded")
        elif args.command == "find-capture":
            identity = find_and_capture_identity(
                expected_uid=args.expected_uid,
                expected_exe=args.expected_exe,
                expected_args=args.expected_args,
            )
            write_record(args.record, identity)
            print(identity["pid"])
        elif args.command == "verify":
            if not verify_record(args.record):
                raise IdentityError("recorded PID/start-time/UID/executable/argv does not match")
            print("PASS: complete process identity verified")
        else:
            stop_record(args.record, timeout_seconds=args.timeout)
            print("PASS: verified process stopped or was already absent")
    except (IdentityError, OSError, TypeError, ValueError) as exc:
        print(f"FAIL: process identity ({exc})")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
