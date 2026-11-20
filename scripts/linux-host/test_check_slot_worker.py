from __future__ import annotations

import importlib.util
from pathlib import Path

MODULE = Path(__file__).with_name("check_slot_worker.py")
SPEC = importlib.util.spec_from_file_location("check_slot_worker", MODULE)
checker = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(checker)


def write_process(proc: Path, pid: int, *, uid: int = 1000, state: str = "S",
                  script: str = "/workspace/scripts/linux-host/linux-slot-worker.py",
                  args: list[str] | None = None, start_ticks: int = 987) -> None:
    base = proc / str(pid)
    base.mkdir(parents=True)
    (base / "status").write_text(f"Name:\tpython3\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\n")
    (base / "cmdline").write_bytes(
        b"\0".join(os_bytes(x) for x in (args or [
            "/usr/bin/python3", script, "--loop", "--interval", "3"
        ])) + b"\0"
    )
    # /proc/<pid>/stat starttime is field 22; after state (field 3), it is tail index 19.
    stat_tail = " ".join(["0"] * 19 + [str(start_ticks)])
    (base / "stat").write_text(f"{pid} (python3) {state} {stat_tail}")
    executable = proc / "python3.12"
    executable.write_text("fixture")
    (base / "exe").symlink_to(executable)


def os_bytes(value: str) -> bytes:
    import os
    return os.fsencode(value)


def test_slot_worker_identity_requires_exact_script_args_uid_and_live_state(tmp_path):
    proc = tmp_path / "proc"
    proc.mkdir()
    expected = Path("/workspace/scripts/linux-host/linux-slot-worker.py")
    write_process(proc, 101)
    assert [row["pid"] for row in checker.matching_slot_workers(
        expected, proc_root=proc, expected_uid=1000
    )] == [101]


def test_slot_worker_identity_rejects_legacy_command_zombie_and_wrong_uid(tmp_path):
    proc = tmp_path / "proc"
    proc.mkdir()
    expected = Path("/workspace/scripts/linux-host/linux-slot-worker.py")
    write_process(proc, 101, script="/workspace/scripts/linux-host/linux-worker.py")
    write_process(proc, 102, state="Z")
    write_process(proc, 103, uid=1001)
    assert checker.matching_slot_workers(expected, proc_root=proc, expected_uid=1000) == []


def test_slot_worker_identity_fails_closed_on_ambiguous_multiple_workers(tmp_path):
    proc = tmp_path / "proc"
    proc.mkdir()
    expected = Path("/workspace/scripts/linux-host/linux-slot-worker.py")
    write_process(proc, 101)
    write_process(proc, 102)
    assert len(checker.matching_slot_workers(expected, proc_root=proc, expected_uid=1000)) == 2
