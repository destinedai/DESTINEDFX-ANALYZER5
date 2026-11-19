from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "scripts" / "linux-host" / "accept_two_user_runtime.py"
SPEC = importlib.util.spec_from_file_location("accept_two_user_runtime", MODULE)
acceptance = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(acceptance)


def _row(uid: int, pairing: str) -> dict:
    return {
        "pairing_id": pairing,
        "job_id": f"job-{pairing}",
        "worker_id": "codespaces-host",
        "linux_user": f"dfxslot_{pairing:0<16}"[:24],
        "linux_uid": uid,
        "slot_root": f"/slots/{pairing}",
        "wine_prefix": f"/slots/{pairing}/wine",
        "terminal_home": f"/slots/{pairing}/terminal",
        "terminal_path": f"/slots/{pairing}/terminal/terminal64.exe",
        "xauthority": f"/slots/{pairing}/.Xauthority",
        "display": f":{100 + uid}",
        "xvfb_pid": 10000 + uid,
        "process_pid": 20000 + uid,
    }


def test_distinct_slot_rows_accept_two_independent_identities():
    acceptance.require_distinct(
        [_row(2401, "pair-a"), _row(2402, "pair-b")],
        ("pairing_id", "job_id", "linux_user", "linux_uid", "slot_root",
         "wine_prefix", "terminal_home", "terminal_path", "xauthority",
         "display", "xvfb_pid", "process_pid"),
    )


def test_duplicate_uid_is_rejected():
    try:
        acceptance.require_distinct(
            [_row(2401, "pair-a"), _row(2401, "pair-b")],
            ("pairing_id", "linux_uid"),
        )
    except acceptance.AcceptanceError as exc:
        assert exc.args[0] == "SLOT_IDENTITIES_NOT_UNIQUE"
    else:
        raise AssertionError("Two pairings must never share one operating-system UID")


def test_duplicate_wine_prefix_is_rejected():
    first = _row(2401, "pair-a")
    second = _row(2402, "pair-b")
    second["wine_prefix"] = first["wine_prefix"]
    try:
        acceptance.require_distinct([first, second], ("wine_prefix",))
    except acceptance.AcceptanceError as exc:
        assert exc.args[0] == "SLOT_IDENTITIES_NOT_UNIQUE"
    else:
        raise AssertionError("Two pairings must never share one Wine prefix")


def test_duplicate_display_or_terminal_process_is_rejected():
    first = _row(2401, "pair-a")
    second = _row(2402, "pair-b")
    second["display"] = first["display"]
    try:
        acceptance.require_distinct([first, second], ("display",))
    except acceptance.AcceptanceError as exc:
        assert exc.args[0] == "SLOT_IDENTITIES_NOT_UNIQUE"
    else:
        raise AssertionError("Two pairings must never share one X display")
