#!/usr/bin/env python3
"""Fail-closed detection of the Codespaces isolated slot-worker process."""
from __future__ import annotations

import argparse
import os
from pathlib import Path


def _snapshot(pid: int, proc_root: Path) -> dict | None:
    base = proc_root / str(pid)
    try:
        status = (base / "status").read_text(encoding="ascii")
        uid_line = next(line for line in status.splitlines() if line.startswith("Uid:"))
        effective_uid = int(uid_line.split()[2])
        args = [
            os.fsdecode(item)
            for item in (base / "cmdline").read_bytes().split(bytes([0]))
            if item
        ]
        raw_stat = (base / "stat").read_text(encoding="ascii")
        tail = raw_stat[raw_stat.rfind(")") + 2 :].split()
        state = tail[0]
        start_ticks = int(tail[19])
        exe = os.path.realpath(base / "exe")
        return {
            "pid": pid,
            "uid": effective_uid,
            "args": args,
            "exe": exe,
            "state": state,
            "start_ticks": start_ticks,
        }
    except (OSError, ValueError, IndexError, StopIteration):
        return None


def matching_slot_workers(
    expected_script: Path,
    *,
    proc_root: Path = Path("/proc"),
    expected_uid: int | None = None,
) -> list[dict]:
    """Return only live Python processes executing the exact Codespaces slot loop."""
    expected_uid = os.geteuid() if expected_uid is None else expected_uid
    expected_path = os.path.realpath(expected_script)
    matches: list[dict] = []
    try:
        entries = list(proc_root.iterdir())
    except OSError:
        return []
    for entry in entries:
        if not entry.name.isdigit():
            continue
        item = _snapshot(int(entry.name), proc_root)
        if not item or item["state"] in {"Z", "X"} or item["start_ticks"] <= 0:
            continue
        args = item["args"]
        if (
            item["uid"] == expected_uid
            and Path(item["exe"]).name.startswith("python")
            and len(args) >= 5
            and os.path.realpath(args[1]) == expected_path
            and args[2:5] == ["--loop", "--interval", "3"]
        ):
            matches.append(item)
    return matches


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("script", type=Path)
    parser.add_argument("--proc-root", type=Path, default=Path("/proc"))
    args = parser.parse_args()
    matches = matching_slot_workers(args.script, proc_root=args.proc_root)
    if len(matches) == 1:
        print(f"PASS: isolated slot worker is running (pid={matches[0]['pid']})")
        return 0
    if not matches:
        print("FAIL: isolated slot worker is not running")
        return 1
    print(f"FAIL: ambiguous isolated slot workers detected (count={len(matches)})")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
