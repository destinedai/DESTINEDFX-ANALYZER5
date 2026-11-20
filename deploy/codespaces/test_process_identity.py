from __future__ import annotations

import importlib.util
import json
import signal
from pathlib import Path
from unittest.mock import patch

MODULE = Path(__file__).with_name("process_identity.py")
SPEC = importlib.util.spec_from_file_location("process_identity", MODULE)
identity = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(identity)


def process(**changes):
    row = {
        "pid": 4242,
        "start_ticks": 100,
        "uid": 1000,
        "exe": "/usr/bin/python3",
        "args": ["/usr/bin/python3", "/workspace/worker.py", "--loop", "--interval", "3"],
        "state": "S",
    }
    row.update(changes)
    return row


def record(tmp_path: Path):
    path = tmp_path / "runtime" / "worker.identity.json"
    identity.write_record(path, process())
    return path


def test_capture_records_complete_linux_process_identity(tmp_path):
    path = tmp_path / "runtime" / "worker.identity.json"
    with patch.object(identity, "snapshot", return_value=process()):
        captured = identity.capture_identity(
            4242, expected_uid=1000,
            expected_fragment="worker.py --loop",
            expected_exe="/usr/bin/python3",
            timeout_seconds=0,
        )
    identity.write_record(path, captured)
    result = identity.read_record(path)
    assert result == {key: process()[key] for key in identity.IDENTITY_FIELDS}
    assert path.stat().st_mode & 0o777 == 0o600
    assert path.parent.stat().st_mode & 0o777 == 0o700


def test_capture_rejects_wrong_uid_executable_or_expected_argv():
    with patch.object(identity, "snapshot", return_value=process(uid=2000)):
        try:
            identity.capture_identity(4242, expected_uid=1000, timeout_seconds=0)
        except identity.IdentityError:
            pass
        else:
            raise AssertionError("wrong UID must be rejected")
    with patch.object(identity, "snapshot", return_value=process(exe="/tmp/python3")):
        try:
            identity.capture_identity(4242, expected_uid=1000, expected_exe="/usr/bin/python3", timeout_seconds=0)
        except identity.IdentityError:
            pass
        else:
            raise AssertionError("wrong executable must be rejected")
    with patch.object(identity, "snapshot", return_value=process(args=["/usr/bin/python3", "other.py"])):
        try:
            identity.capture_identity(4242, expected_uid=1000, expected_fragment="worker.py", timeout_seconds=0)
        except identity.IdentityError:
            pass
        else:
            raise AssertionError("wrong command line must be rejected")


def test_pid_reuse_with_new_start_ticks_is_never_signalled(tmp_path):
    path = record(tmp_path)
    sent = []
    with patch.object(identity, "snapshot", return_value=process(start_ticks=101)):
        try:
            identity.stop_record(path, kill_process=lambda pid, sig: sent.append((pid, sig)))
        except identity.IdentityError as exc:
            assert "left untouched" in str(exc)
        else:
            raise AssertionError("PID reuse must fail closed")
    assert sent == []
    assert path.exists()


def test_uid_executable_and_exact_argv_changes_are_never_signalled(tmp_path):
    mutations = [
        {"uid": 2000},
        {"exe": "/tmp/other"},
        {"args": ["/usr/bin/python3", "unrelated.py", "--loop"]},
    ]
    for mutation in mutations:
        path = record(tmp_path)
        sent = []
        with patch.object(identity, "snapshot", return_value=process(**mutation)):
            try:
                identity.stop_record(path, kill_process=lambda pid, sig: sent.append((pid, sig)))
            except identity.IdentityError:
                pass
            else:
                raise AssertionError(f"identity mutation was accepted: {mutation}")
        assert sent == []


def test_verified_process_receives_only_sigterm_and_record_clears_after_exit(tmp_path):
    path = record(tmp_path)
    states = [process(), None]
    sent = []

    def take_snapshot(_pid, _root=Path("/proc")):
        return states.pop(0)

    with patch.object(identity, "snapshot", side_effect=take_snapshot):
        stopped = identity.stop_record(
            path,
            kill_process=lambda pid, sig: sent.append((pid, sig)),
            timeout_seconds=1,
            sleep=lambda _seconds: None,
            clock=lambda: 0,
        )
    assert stopped is True
    assert sent == [(4242, signal.SIGTERM)]
    assert not path.exists()


def test_unresponsive_verified_process_is_not_killed_or_escalated(tmp_path):
    path = record(tmp_path)
    sent = []
    with patch.object(identity, "snapshot", return_value=process()):
        try:
            identity.stop_record(
                path,
                kill_process=lambda pid, sig: sent.append((pid, sig)),
                timeout_seconds=0,
                sleep=lambda _seconds: None,
                clock=lambda: 0,
            )
        except identity.IdentityError as exc:
            assert "refusing SIGKILL" in str(exc)
        else:
            raise AssertionError("unresponsive process should be reported")
    assert sent == [(4242, signal.SIGTERM)]
    assert path.exists()


def test_malformed_and_world_accessible_identity_records_fail_closed(tmp_path):
    path = record(tmp_path)
    path.chmod(0o644)
    try:
        identity.read_record(path)
    except identity.IdentityError:
        pass
    else:
        raise AssertionError("world-readable identity record must be rejected")
    path.write_text(json.dumps({"version": 999}), encoding="utf-8")
    path.chmod(0o600)
    try:
        identity.read_record(path)
    except identity.IdentityError:
        pass
    else:
        raise AssertionError("unknown identity record version must be rejected")
