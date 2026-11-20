from __future__ import annotations

import importlib.util
from pathlib import Path

MODULE = Path(__file__).with_name("display_processes.py")
SPEC = importlib.util.spec_from_file_location("display_processes", MODULE)
display = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(display)


def test_xvfb_adoption_requires_exact_owner_executable_and_arguments():
    args = ["Xvfb", ":99", "-screen", "0", "1280x800x24", "-nolisten", "tcp"]
    assert display.matches_xvfb("/usr/bin/Xvfb", args, 1000, 1000)
    assert not display.matches_xvfb("/usr/bin/Xvfb", args, 1001, 1000)
    assert not display.matches_xvfb("/tmp/Xvfb", args, 1000, 1000)
    assert not display.matches_xvfb("/usr/bin/Xvfb", args[:-1] + ["tcp6"], 1000, 1000)
    assert not display.matches_xvfb("/usr/bin/Xvfb", ["Xvfb", ":98", *args[2:]], 1000, 1000)


def test_only_unauthenticated_legacy_x11vnc_is_selected_for_replacement():
    legacy = [
        "x11vnc", "-display", ":99", "-localhost", "-forever",
        "-shared", "-nopw", "-rfbport", "5900", "-noxdamage",
    ]
    assert display.matches_legacy_x11vnc("/usr/bin/x11vnc", legacy, 1000, 1000)
    secured = legacy.copy()
    secured.remove("-nopw")
    secured.extend(["-passwdfile", "/private/vnc.pass"])
    assert not display.matches_legacy_x11vnc("/usr/bin/x11vnc", secured, 1000, 1000)
    assert not display.matches_legacy_x11vnc("/usr/bin/x11vnc", legacy, 0, 1000)
    assert not display.matches_legacy_x11vnc("/usr/bin/x11vnc", legacy, 1000, 1001)


def test_only_wildcard_legacy_websockify_is_selected_for_replacement():
    legacy = [
        "/usr/bin/python3", "/usr/bin/websockify", "--web=/usr/share/novnc",
        "--heartbeat=30", "--idle-timeout=0", "6080", "127.0.0.1:5900",
    ]
    assert display.matches_legacy_websockify("/usr/bin/python3", legacy, 1000, 1000)
    loopback = legacy.copy()
    loopback[2:2] = ["--listen", "127.0.0.1"]
    assert not display.matches_legacy_websockify("/usr/bin/python3", loopback, 1000, 1000)
    assert not display.matches_legacy_websockify("/usr/bin/python3", legacy, 0, 1000)
    assert not display.matches_legacy_websockify("/usr/bin/python3", legacy[:-1], 1000, 1000)

def test_websockify_must_be_python_runner_for_known_system_service():
    legacy = [
        "/usr/bin/python3", "/usr/bin/websockify", "--web=/usr/share/novnc",
        "--heartbeat=30", "--idle-timeout=0", "6080", "127.0.0.1:5900",
    ]
    assert not display.matches_legacy_websockify("/bin/bash", legacy, 1000, 1000)


if __name__ == "__main__":
    test_xvfb_adoption_requires_exact_owner_executable_and_arguments()
    test_only_unauthenticated_legacy_x11vnc_is_selected_for_replacement()
    test_only_wildcard_legacy_websockify_is_selected_for_replacement()
    test_websockify_must_be_python_runner_for_known_system_service()
    print("PASS: display process identity regressions")



def _verified_x11vnc_snapshot(**changes):
    row = {
        "pid": 4321,
        "uid": 1000,
        "args": ["x11vnc", "-display", ":99", "-localhost", "-rfbport", "5900", "-passwdfile", "/state/vnc.pass"],
        "exe": "/usr/bin/x11vnc",
        "state": "S",
        "start_ticks": 501,
    }
    row.update(changes)
    return row


def test_legacy_cleanup_pidfd_rechecks_full_identity_before_signalling():
    from unittest.mock import patch
    import signal

    expected = _verified_x11vnc_snapshot()
    with patch.object(display, "_snapshot", side_effect=[
        expected,
        _verified_x11vnc_snapshot(start_ticks=502),
    ]), patch.object(display.os, "pidfd_open", return_value=77), \
         patch.object(display.os, "close") as close_fd, \
         patch.object(display.signal, "pidfd_send_signal") as send_signal:
        assert display._terminate_verified(expected, 1000) is False
    close_fd.assert_called_once_with(77)
    send_signal.assert_not_called()


def test_legacy_cleanup_signals_only_pinned_process_with_sigterm():
    from unittest.mock import patch
    import signal

    expected = _verified_x11vnc_snapshot()
    snapshots = [expected, expected, None]
    with patch.object(display, "_snapshot", side_effect=lambda *_args: snapshots.pop(0)), \
         patch.object(display.os, "pidfd_open", return_value=78), \
         patch.object(display.os, "close"), \
         patch.object(display.signal, "pidfd_send_signal") as send_signal:
        assert display._terminate_verified(expected, 1000) is True
    send_signal.assert_called_once_with(78, signal.SIGTERM)
