#!/usr/bin/env python3
"""Read-only two-user MT5 runtime acceptance for a real DestinedFX Codespaces host.

Run only on the Codespaces host after two separate DEMO pairings have reached READY:
    sudo -E python3 scripts/linux-host/accept_two_user_runtime.py --expected-users 2

This script never provisions, revokes, launches, or modifies a terminal. It emits
sanitized evidence only; it never prints pairing IDs, logins, server names or tokens.
"""
from __future__ import annotations

import argparse
import json
import os
import pwd
import re
import stat
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "linux-host"))

from linux_isolation import (  # noqa: E402
    IsolationError,
    find_slot_process,
    pairing_key,
    username_for_pairing,
    verify_slot_reservation,
)

ACTIVE = {"RESERVED", "LAUNCH_PENDING", "AUTHENTICATING", "READY", "OPERATOR_REVIEW"}
REQUIRED = {
    "pairing_id", "job_id", "worker_id", "linux_user", "linux_uid",
    "slot_root", "job_root", "wine_prefix", "terminal_home", "terminal_path",
    "xauthority", "display", "xvfb_pid", "xvfb_start_ticks", "status",
    "process_pid", "process_start_ticks",
}
SAFE_ID = re.compile(r"^[A-Za-z0-9_-]{1,128}$")


class AcceptanceError(RuntimeError):
    """A sanitized, user-actionable acceptance failure."""


def _private_file(path: Path, *, owner_uid: int, mode: int) -> dict[str, Any]:
    try:
        info = path.lstat()
    except OSError:
        raise AcceptanceError("PRIVATE_FILE_MISSING") from None
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise AcceptanceError("PRIVATE_FILE_TYPE_INVALID")
    if info.st_uid != owner_uid or stat.S_IMODE(info.st_mode) != mode:
        raise AcceptanceError("PRIVATE_FILE_OWNERSHIP_OR_MODE_INVALID")
    return json.loads(path.read_text(encoding="utf-8"))


def _private_directory(path: Path, *, owner_uid: int, mode: int) -> None:
    try:
        info = path.lstat()
    except OSError:
        raise AcceptanceError("PRIVATE_DIRECTORY_MISSING") from None
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != owner_uid:
        raise AcceptanceError("PRIVATE_DIRECTORY_OWNERSHIP_INVALID")
    if stat.S_IMODE(info.st_mode) != mode:
        raise AcceptanceError("PRIVATE_DIRECTORY_MODE_INVALID")


def _read_active_manifests(manifest_dir: Path, owner_uid: int) -> list[tuple[Path, dict[str, Any]]]:
    _private_directory(manifest_dir, owner_uid=owner_uid, mode=0o700)
    rows: list[tuple[Path, dict[str, Any]]] = []
    for path in sorted(manifest_dir.glob("*.json")):
        try:
            row = _private_file(path, owner_uid=owner_uid, mode=0o600)
        except (AcceptanceError, OSError, ValueError, TypeError):
            raise AcceptanceError("SLOT_MANIFEST_UNREADABLE_OR_UNSAFE") from None
        if not isinstance(row, dict):
            raise AcceptanceError("SLOT_MANIFEST_INVALID")
        if row.get("status") in ACTIVE:
            rows.append((path, row))
    return rows


def require_distinct(rows: list[dict[str, Any]], fields: tuple[str, ...]) -> None:
    for field in fields:
        values = [str(row.get(field, "")) for row in rows]
        if any(not value for value in values) or len(set(values)) != len(values):
            raise AcceptanceError("SLOT_IDENTITIES_NOT_UNIQUE")


def _owner_uid(args: argparse.Namespace) -> int:
    value = args.owner_uid
    if value is None:
        value = os.environ.get("HOST_OWNER_UID") or os.environ.get("SUDO_UID")
    if value is None:
        raise AcceptanceError("HOST_OWNER_UID_REQUIRED_USE_SUDO_E")
    try:
        uid = int(value)
        account = pwd.getpwuid(uid)
    except (ValueError, KeyError):
        raise AcceptanceError("HOST_OWNER_UID_INVALID") from None
    if uid <= 0 or account.pw_uid == os.geteuid():
        raise AcceptanceError("HOST_OWNER_UID_MUST_BE_NON_ROOT_OWNER")
    return uid


def _check_loopback_url(value: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    if (
        parsed.scheme != "http"
        or parsed.hostname not in {"127.0.0.1", "::1", "localhost"}
        or parsed.username or parsed.password or parsed.query or parsed.fragment
        or parsed.path not in {"", "/", "/health/ready"}
    ):
        raise AcceptanceError("HOST_AGENT_URL_MUST_BE_LOOPBACK")
    return f"http://{parsed.netloc}/health/ready"


def _host_agent_report(url: str) -> dict[str, Any]:
    target = _check_loopback_url(url)
    request = urllib.request.Request(target, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            if response.status != 200:
                raise AcceptanceError("HOST_AGENT_NOT_READY")
            value = json.loads(response.read(65536).decode("utf-8"))
    except (OSError, urllib.error.URLError, TimeoutError, ValueError):
        raise AcceptanceError("HOST_AGENT_HEALTH_PROBE_FAILED") from None
    if not isinstance(value, dict):
        raise AcceptanceError("HOST_AGENT_HEALTH_RESPONSE_INVALID")
    isolation = value.get("slot_isolation")
    if not isinstance(isolation, dict):
        raise AcceptanceError("HOST_AGENT_SLOT_REPORT_MISSING")
    if value.get("healthy") is not True or isolation.get("enabled") is not True:
        raise AcceptanceError("HOST_AGENT_NOT_HEALTHY_OR_ISOLATION_DISABLED")
    if isolation.get("ready") is not True:
        raise AcceptanceError("HOST_AGENT_ISOLATION_NOT_READY")
    return isolation


def _check_one_slot(
    row: dict[str, Any],
    manifest_path: Path,
    *,
    slots_root: Path,
    owner_uid: int,
) -> tuple[int, str, Path]:
    if not REQUIRED.issubset(row):
        raise AcceptanceError("SLOT_MANIFEST_FIELDS_MISSING")
    if row.get("status") != "READY":
        raise AcceptanceError("ACTIVE_SLOT_NOT_READY")
    pairing_id = str(row["pairing_id"])
    job_id = str(row["job_id"])
    worker_id = str(row["worker_id"])
    if not SAFE_ID.fullmatch(pairing_id) or not SAFE_ID.fullmatch(job_id) or not SAFE_ID.fullmatch(worker_id):
        raise AcceptanceError("SLOT_MANIFEST_ID_INVALID")
    if manifest_path.stem != job_id:
        raise AcceptanceError("SLOT_MANIFEST_FILENAME_MISMATCH")

    uid = int(row["linux_uid"])
    username = str(row["linux_user"])
    slot_root = Path(str(row["slot_root"]))
    job_root = Path(str(row["job_root"]))
    wine_prefix = Path(str(row["wine_prefix"]))
    terminal_home = Path(str(row["terminal_home"]))
    terminal_path = Path(str(row["terminal_path"]))
    xauthority = Path(str(row["xauthority"]))

    expected_root = (slots_root / pairing_key(pairing_id)).resolve()
    expected_job = expected_root / job_id
    if username != username_for_pairing(pairing_id):
        raise AcceptanceError("SLOT_USERNAME_DOES_NOT_MATCH_PAIRING")
    if slot_root.resolve() != expected_root or job_root.resolve() != expected_job:
        raise AcceptanceError("SLOT_PATH_DOES_NOT_MATCH_PAIRING")
    if wine_prefix.resolve() != expected_job / "wine":
        raise AcceptanceError("WINE_PREFIX_IS_NOT_PRIVATE")
    if terminal_home.resolve() != expected_job / "terminal":
        raise AcceptanceError("TERMINAL_HOME_IS_NOT_PRIVATE")
    if terminal_path.resolve() != terminal_home.resolve() / "terminal64.exe":
        raise AcceptanceError("TERMINAL_PATH_IS_NOT_PRIVATE")
    if xauthority.resolve() != expected_job / ".Xauthority":
        raise AcceptanceError("XAUTHORITY_IS_NOT_PRIVATE")

    reservation = {
        key: row[key]
        for key in (
            "pairing_id", "job_id", "linux_user", "linux_uid", "slot_root",
            "wine_prefix", "terminal_home", "xauthority", "display",
            "xvfb_pid", "xvfb_start_ticks",
        )
    }
    if not verify_slot_reservation(reservation, slots_root, owner_uid):
        raise AcceptanceError("ROOT_HOST_AGENT_RESERVATION_CHECK_FAILED")

    marker_path = terminal_home / ".destinedfx-worker.json"
    try:
        marker_info = marker_path.lstat()
        if (
            not stat.S_ISREG(marker_info.st_mode)
            or marker_info.st_uid != uid
            or stat.S_IMODE(marker_info.st_mode) != 0o600
            or marker_info.st_nlink != 1
        ):
            raise AcceptanceError("TERMINAL_MARKER_PERMISSIONS_INVALID")
        marker = json.loads(marker_path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        raise AcceptanceError("TERMINAL_MARKER_UNREADABLE") from None

    if not isinstance(marker, dict) or marker.get("status") != "READY":
        raise AcceptanceError("TERMINAL_MARKER_NOT_READY")
    marker_expected = {
        "pairing_id": pairing_id,
        "job_id": job_id,
        "linux_user": username,
        "linux_uid": uid,
        "slot_root": str(slot_root),
        "wine_prefix": str(wine_prefix),
        "terminal_home": str(terminal_home),
        "executable": str(terminal_path),
        "xauthority": str(xauthority),
        "display": str(row["display"]),
        "xvfb_pid": int(row["xvfb_pid"]),
        "xvfb_start_ticks": int(row["xvfb_start_ticks"]),
        "pid": int(row["process_pid"]),
        "start_ticks": int(row["process_start_ticks"]),
    }
    if any(marker.get(key) != value for key, value in marker_expected.items()):
        raise AcceptanceError("TERMINAL_MARKER_AND_MANIFEST_MISMATCH")

    process = find_slot_process(
        linux_uid=uid,
        wine_prefix=str(wine_prefix),
        terminal_path=str(terminal_path),
        display=str(row["display"]),
    )
    if not process:
        raise AcceptanceError("REAL_MT5_PROCESS_NOT_FOUND")
    if (
        process["pid"] != int(row["process_pid"])
        or process["start_ticks"] != int(row["process_start_ticks"])
    ):
        raise AcceptanceError("REAL_MT5_PROCESS_IDENTITY_MISMATCH")
    if not terminal_path.is_file():
        raise AcceptanceError("MT5_TERMINAL_EXECUTABLE_MISSING")

    startup_ini = job_root / "startup.ini"
    startup_set = terminal_home / "MQL5" / "Presets" / "DestinedFXTraderEA.set"
    if startup_ini.exists() or startup_set.exists():
        raise AcceptanceError("STARTUP_CREDENTIAL_ARTIFACT_STILL_PRESENT")

    return uid, username, xauthority


def _deny_cross_slot_access(slots: list[tuple[int, str, Path]]) -> int:
    probes = 0
    for source_uid, source_user, _ in slots:
        for target_uid, _target_user, target_auth in slots:
            if source_uid == target_uid:
                continue
            for flag in ("-r", "-w"):
                result = subprocess.run(
                    ["sudo", "-n", "-u", source_user, "--", "test", flag, str(target_auth)],
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=5,
                    check=False,
                )
                probes += 1
                if result.returncode == 0:
                    raise AcceptanceError("CROSS_USER_XAUTHORITY_ACCESS_NOT_BLOCKED")
                if result.returncode not in {1, 2}:
                    raise AcceptanceError("CROSS_USER_PERMISSION_PROBE_FAILED")
    return probes


def run_acceptance(args: argparse.Namespace) -> dict[str, Any]:
    if os.geteuid() != 0:
        raise AcceptanceError("RUN_AS_ROOT_WITH_SUDO_E")
    owner_uid = _owner_uid(args)
    owner_home = Path(pwd.getpwuid(owner_uid).pw_dir)
    state_dir = Path(args.state_dir) if args.state_dir else owner_home / ".local/share/destinedfx-codespaces"
    manifest_dir = state_dir / "isolated-slots"
    slots_root = Path(args.slots_root or os.environ.get("DESTINEDFX_SLOT_ROOT", "/home/destinedfx-slots")).resolve()

    _private_directory(state_dir, owner_uid=owner_uid, mode=0o700)
    active = _read_active_manifests(manifest_dir, owner_uid)
    if len(active) < args.expected_users:
        raise AcceptanceError("NOT_ENOUGH_ACTIVE_USER_SLOTS")
    rows = [row for _path, row in active]
    if any(row.get("status") != "READY" for row in rows):
        raise AcceptanceError("ALL_ACTIVE_SLOTS_MUST_BE_READY")
    require_distinct(
        rows,
        (
            "pairing_id", "job_id", "linux_user", "linux_uid", "slot_root",
            "wine_prefix", "terminal_home", "terminal_path", "xauthority",
            "display", "xvfb_pid", "process_pid",
        ),
    )

    # Runtime source must still enforce the Linux DEMO-only provisioning gate.
    worker_source = (ROOT / "scripts" / "linux-host" / "linux-slot-worker.py").read_text(encoding="utf-8")
    if 'account.get("account_mode") != "DEMO"' not in worker_source:
        raise AcceptanceError("LINUX_DEMO_ONLY_GUARD_NOT_FOUND")

    checked: list[tuple[int, str, Path]] = []
    for manifest_path, row in active:
        checked.append(_check_one_slot(row, manifest_path, slots_root=slots_root, owner_uid=owner_uid))

    cross_user_probes = _deny_cross_slot_access(checked)
    agent = _host_agent_report(args.host_agent_url or os.environ.get("HOST_AGENT_URL", "http://127.0.0.1:8787/health/ready"))
    if int(agent.get("active_slots", 0)) < args.expected_users:
        raise AcceptanceError("HOST_AGENT_ACTIVE_SLOT_COUNT_TOO_LOW")
    if int(agent.get("max_slots", 0)) < args.expected_users:
        raise AcceptanceError("HOST_AGENT_CAPACITY_BELOW_EXPECTED")

    return {
        "accepted": True,
        "scope": "read-only live Codespaces host validation",
        "active_ready_slots": len(active),
        "expected_minimum_users": args.expected_users,
        "unique_os_uids": len({item[0] for item in checked}),
        "unique_slot_users": len({item[1] for item in checked}),
        "distinct_wine_terminal_xauthority_and_display": True,
        "real_mt5_pid_and_start_time_verified": True,
        "root_reservation_attestation_verified": True,
        "cross_user_xauthority_read_write_denied": True,
        "cross_user_permission_probes": cross_user_probes,
        "startup_credentials_removed": True,
        "linux_demo_only_guard_present": True,
        "host_agent_ready": True,
        "credentials_or_account_identifiers_emitted": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-users", type=int, default=2)
    parser.add_argument("--owner-uid", type=int)
    parser.add_argument("--state-dir")
    parser.add_argument("--slots-root")
    parser.add_argument("--host-agent-url")
    args = parser.parse_args()
    if args.expected_users < 2 or args.expected_users > 8:
        print(json.dumps({"accepted": False, "error": "EXPECTED_USERS_OUT_OF_RANGE"}))
        return 2
    try:
        result = run_acceptance(args)
    except (AcceptanceError, IsolationError, OSError, ValueError, TypeError, KeyError) as exc:
        code = exc.args[0] if isinstance(exc, AcceptanceError) and exc.args else "ACCEPTANCE_CHECK_FAILED"
        if isinstance(exc, IsolationError):
            code = exc.code
        print(json.dumps({"accepted": False, "error": str(code)}, sort_keys=True))
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
