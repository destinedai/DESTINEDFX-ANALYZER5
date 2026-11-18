#!/usr/bin/env python3
"""Resource-bounded, per-pairing Linux MT5 worker for GitHub Codespaces.

Credentials are not released until a unique non-login Unix user, private Wine
prefix, private Xauthority file, isolated Xvfb display and terminal directory
have been independently verified by the root host agent.
"""
from __future__ import annotations

import argparse
import json
import os
import pwd
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "managed-worker"))
sys.path.insert(0, str(ROOT / "scripts" / "linux-host"))

import worker  # noqa: E402
from linux_isolation import (  # noqa: E402
    IsolationError,
    LinuxSlotManager,
    SlotReservation,
    pairing_key,
)
from host_lifecycle import inspect_shutdown_safety  # noqa: E402

STATE = Path(os.environ.get(
    "DESTINEDFX_CODESPACES_STATE_DIR",
    str(Path.home() / ".local/share/destinedfx-codespaces"),
)).resolve()
RUNTIME = STATE / "runtime"
MANIFEST_DIR = STATE / "isolated-slots"
SLOT_ROOT = Path(os.environ.get("DESTINEDFX_SLOT_ROOT", "/home/destinedfx-slots")).resolve()
SLOT_RUNTIME = RUNTIME / "slot-runtime"
CONTROL_URL = os.environ.get("DESTINEDFX_CONTROL_PLANE_URL", "http://127.0.0.1:8000").rstrip("/")
WORKER_TOKEN = os.environ.get("DESTINEDFX_WORKER_TOKEN", "")
WORKER_ID = os.environ.get("DESTINEDFX_WORKER_ID", "codespaces-host-01")
HOST_AGENT_TOKEN = os.environ.get("HOST_AGENT_TOKEN", "")
HOST_AGENT_URL = os.environ.get("HOST_AGENT_URL", "http://127.0.0.1:8787").rstrip("/")
HOST_GENERATION = os.environ.get("HOST_GENERATION", "")
TERMINAL_EXE = Path(os.environ.get("MT5_TERMINAL_EXE", ""))
TERMINAL_TEMPLATE = Path(os.environ.get("DESTINEDFX_MT5_TERMINAL_TEMPLATE", str(TERMINAL_EXE.parent)))
METAEDITOR_EXE = Path(os.environ.get("MT5_METAEDITOR_EXE", str(TERMINAL_TEMPLATE / "metaeditor64.exe")))
EA_SOURCE = Path(os.environ.get("DESTINEDFX_EA_SOURCE", str(ROOT / "mt5-ea" / "DestinedFXTraderEA.mq5")))
WINE = os.environ.get("WINE_BIN", "wine")
MAX_SLOT_CONFIG = max(1, min(8, int(os.environ.get("DESTINEDFX_SLOT_MAX_TERMINALS", "2"))))
POLL_SECONDS = max(2, min(30, int(os.environ.get("DESTINEDFX_SLOT_POLL_SECONDS", "3"))))


class SlotWorkerError(RuntimeError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _log(message: str) -> None:
    # Only fixed messages and bounded operational codes are permitted here.
    print(f"destinedfx-slot-worker: {message}", flush=True)


def _require_stop_config() -> None:
    if not Path(os.environ.get("BRIDGE_DB_PATH", str(STATE / "bridge.sqlite3"))).is_file():
        raise SlotWorkerError("BRIDGE_DATABASE_UNAVAILABLE")
    try:
        subprocess.run(
            ["sudo", "-n", "true"], stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=True,
        )
    except (OSError, subprocess.SubprocessError):
        raise SlotWorkerError("SLOT_ADMIN_PRIVILEGE_UNAVAILABLE") from None


def _require_config() -> None:
    if CONTROL_URL != "http://127.0.0.1:8000":
        raise SlotWorkerError("CONTROL_PLANE_MUST_BE_LOOPBACK")
    if not WORKER_TOKEN or len(WORKER_TOKEN) > 512 or any(ord(x) < 32 for x in WORKER_TOKEN):
        raise SlotWorkerError("WORKER_TOKEN_UNAVAILABLE")
    if not HOST_AGENT_TOKEN or len(HOST_AGENT_TOKEN) < 32:
        raise SlotWorkerError("HOST_AGENT_TOKEN_UNAVAILABLE")
    if not HOST_GENERATION or len(HOST_GENERATION) > 128:
        raise SlotWorkerError("HOST_GENERATION_UNAVAILABLE")
    if not WORKER_ID or len(WORKER_ID) > 128:
        raise SlotWorkerError("WORKER_ID_INVALID")
    if not TERMINAL_EXE.is_file() or not TERMINAL_TEMPLATE.is_dir():
        raise SlotWorkerError("MT5_TERMINAL_TEMPLATE_MISSING")
    if not EA_SOURCE.is_file():
        raise SlotWorkerError("EA_SOURCE_MISSING")


def _memory_available_bytes() -> int:
    try:
        for line in Path("/proc/meminfo").read_text(encoding="ascii").splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    return 0


def capacity() -> int:
    """Conservative session capacity for the actual Codespaces machine resources."""
    cpus = os.cpu_count() or 1
    available_gib = _memory_available_bytes() // (1024 ** 3)
    memory_slots = available_gib // 3
    if available_gib <= 0:
        return 1
    return max(1, min(MAX_SLOT_CONFIG, cpus, int(memory_slots)))


def _api(method: str, path: str, payload: dict | None = None, timeout: int = 20) -> dict:
    return worker.request_json(f"{CONTROL_URL}{path}", WORKER_TOKEN, method, payload, timeout=timeout)


def _agent(path: str, payload: dict, timeout: int = 5) -> dict:
    url = f"{HOST_AGENT_URL}{path}"
    raw = json.dumps(payload, separators=(",", ":"), allow_nan=False).encode("utf-8")
    request = urllib.request.Request(
        url, data=raw,
        headers={
            "Authorization": "Bearer " + HOST_AGENT_TOKEN,
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            result = json.loads(response.read(32768).decode("utf-8"))
    except urllib.error.HTTPError:
        raise SlotWorkerError("HOST_AGENT_REJECTED_SLOT") from None
    except Exception:
        raise SlotWorkerError("HOST_AGENT_UNAVAILABLE") from None
    if not isinstance(result, dict) or result.get("verified") is not True:
        raise SlotWorkerError("HOST_AGENT_SLOT_NOT_VERIFIED")
    return result


def _manifest_path(job_id: str) -> Path:
    if not isinstance(job_id, str) or not worker.SAFE_ID.fullmatch(job_id):
        raise SlotWorkerError("INVALID_JOB_ID")
    return MANIFEST_DIR / f"{job_id}.json"


def _write_manifest(value: dict[str, Any]) -> None:
    MANIFEST_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(MANIFEST_DIR, 0o700)
    path = _manifest_path(str(value.get("job_id", "")))
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, sort_keys=True, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        temporary.unlink(missing_ok=True)


def _read_manifest(path: Path) -> dict[str, Any] | None:
    try:
        mode = path.stat().st_mode & 0o777
        if mode & 0o077:
            return None
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return None
    return value if isinstance(value, dict) else None


def _active_manifests() -> list[dict[str, Any]]:
    MANIFEST_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    result = []
    for path in MANIFEST_DIR.glob("*.json"):
        row = _read_manifest(path)
        if row and row.get("status") in {
            "RESERVED", "LAUNCH_PENDING", "AUTHENTICATING", "READY", "OPERATOR_REVIEW"
        }:
            result.append(row)
    return result


def _reservation_payload(reservation: SlotReservation) -> dict[str, Any]:
    return reservation.attestation_fields()


def _attestation(job: dict, reservation: SlotReservation) -> dict[str, Any]:
    account = job.get("account_identity") or {}
    now = time.time()
    evidence = {
        "pairing_id": str(job["pairing_id"]),
        "worker_id": WORKER_ID,
        "job_id": str(job["job_id"]),
        "slot_id": f"{WORKER_ID}-{job['job_id']}",
        "linux_user": reservation.linux_user,
        "linux_uid": reservation.linux_uid,
        "slot_root": str(reservation.slot_root),
        "terminal_home": str(reservation.terminal_home),
        "wine_prefix": str(reservation.wine_prefix),
        "terminal_path": str(reservation.terminal_path),
        "xauthority": str(reservation.xauthority),
        "display": reservation.display,
        "xvfb_pid": reservation.xvfb_pid,
        "xvfb_start_ticks": reservation.xvfb_start_ticks,
        "process_identity": (
            f"xvfb-pid={reservation.xvfb_pid};start_ticks={reservation.xvfb_start_ticks}"
        ),
        "ea_identity": "DestinedFXTraderEA",
        "broker_brand": str(account.get("broker_brand", "")).upper(),
        "login": str(account.get("login", "")),
        "server": str(account.get("server", "")),
        "account_mode": str(account.get("account_mode", "")),
        "host_generation": HOST_GENERATION,
        "issued_at": now,
        "expires_at": now + 300,
        "nonce": uuid.uuid4().hex,
    }
    evidence["signature"] = worker._attestation_signature(WORKER_TOKEN, evidence)
    return evidence


def _write_slot_file(reservation: SlotReservation, path: Path, text: str) -> None:
    # File content travels over stdin, never argv or process environment.
    code = (
        "import os,sys; p=sys.argv[1]; "
        "fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600); "
        "f=os.fdopen(fd,'w',encoding='utf-8',newline='\\n'); "
        "f.write(sys.stdin.read()); f.flush(); os.fsync(f.fileno()); f.close()"
    )
    manager = _MANAGER
    manager.run_as_slot(reservation, ["python3", "-c", code, str(path)],
                        input_text=text, timeout=20)


def _terminal_copy_ignore(source: str, names: list[str]) -> set[str]:
    root = Path(TERMINAL_TEMPLATE)
    current = Path(source)
    try:
        relative = current.relative_to(root)
    except ValueError:
        return set()
    parts = relative.parts
    excluded = {
        "bases", "config", "logs", "profiles", "history", "tester",
        "MQL5/Files", "MQL5/Logs", "MQL5/Profiles", "MQL5/Presets",
        "MQL5/Experts",
    }
    ignored = set()
    for name in names:
        candidate = "/".join(parts + (name,))
        if candidate in excluded or name.lower() in {"accounts.dat", "origin.txt"}:
            ignored.add(name)
    return ignored


def _copy_clean_terminal(reservation: SlotReservation) -> None:
    """Copy program binaries only; never clone global account cache, logs or profiles."""
    staging_root = STATE / "terminal-staging"
    staging_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    staging = staging_root / reservation.job_id
    shutil.rmtree(staging, ignore_errors=True)
    try:
        shutil.copytree(
            TERMINAL_TEMPLATE, staging, symlinks=False, dirs_exist_ok=False,
            ignore=_terminal_copy_ignore,
        )
        subprocess.run(
            ["sudo", "-n", "cp", "-a", f"{staging}/.", str(reservation.terminal_home)],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=180, check=True,
        )
        account = pwd.getpwnam(reservation.linux_user)
        subprocess.run(
            ["sudo", "-n", "chown", "-R", f"{reservation.linux_user}:{account.pw_gid}",
             str(reservation.terminal_home)],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=120, check=True,
        )
    except (OSError, subprocess.SubprocessError):
        raise SlotWorkerError("TERMINAL_STAGE_FAILED") from None
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    if not (reservation.terminal_home / "terminal64.exe").is_file():
        raise SlotWorkerError("TERMINAL_STAGE_MISSING_EXE")
    if not (reservation.terminal_home / "MQL5" / "Include" / "Trade" / "Trade.mqh").is_file():
        raise SlotWorkerError("MQL5_STANDARD_LIBRARY_MISSING")


def _copy_ea_and_compile(reservation: SlotReservation) -> Path:
    experts = reservation.terminal_home / "MQL5" / "Experts"
    presets = reservation.terminal_home / "MQL5" / "Presets"
    try:
        subprocess.run(
            ["sudo", "-n", "install", "-d", "-o", reservation.linux_user,
             "-g", pwd.getpwnam(reservation.linux_user).pw_gid, "-m", "0700", str(experts)],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=15, check=True,
        )
        subprocess.run(
            ["sudo", "-n", "install", "-m", "0644", str(EA_SOURCE),
             str(experts / "DestinedFXTraderEA.mq5")],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=15, check=True,
        )
        subprocess.run(
            ["sudo", "-n", "chown", "-R",
             f"{reservation.linux_user}:{pwd.getpwnam(reservation.linux_user).pw_gid}",
             str(experts)],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=20, check=True,
        )
        subprocess.run(
            ["sudo", "-n", "install", "-d", "-o", reservation.linux_user,
             "-g", pwd.getpwnam(reservation.linux_user).pw_gid, "-m", "0700", str(presets)],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=15, check=True,
        )
    except (OSError, subprocess.SubprocessError):
        raise SlotWorkerError("EA_SOURCE_STAGE_FAILED") from None
    metaeditor = reservation.terminal_home / METAEDITOR_EXE.name
    if not metaeditor.is_file():
        raise SlotWorkerError("EA_COMPILER_MISSING")
    # Match the existing Windows/MetaEditor worker: the repository root is the
    # include root for the EA's relative includes.
    include_root = EA_SOURCE.parent.parent
    src_win = _MANAGER.run_as_slot(
        reservation, ["winepath", "-w", str(experts / "DestinedFXTraderEA.mq5")], timeout=30
    ).stdout.strip()
    include_win = _MANAGER.run_as_slot(
        reservation, ["winepath", "-w", str(include_root)], timeout=30
    ).stdout.strip()
    log_path = reservation.job_root / "metaeditor-compile.log"
    log_win = _MANAGER.run_as_slot(
        reservation, ["winepath", "-w", str(log_path)], timeout=30
    ).stdout.strip()
    _MANAGER.run_as_slot(
        reservation,
        [WINE, str(metaeditor), f"/compile:{src_win}", f"/include:{include_win}", f"/log:{log_win}"],
        timeout=180,
    )
    compiled = experts / "DestinedFXTraderEA.ex5"
    check = _MANAGER.run_as_slot(
        reservation, ["test", "-s", str(compiled)], timeout=10
    )
    if check.returncode != 0:
        raise SlotWorkerError("EA_COMPILE_OUTPUT_MISSING")
    return compiled


def _manifest_from_reservation(reservation: SlotReservation, status: str) -> dict[str, Any]:
    return {
        "job_id": reservation.job_id,
        "pairing_id": reservation.pairing_id,
        "worker_id": reservation.worker_id,
        "linux_user": reservation.linux_user,
        "linux_uid": reservation.linux_uid,
        "slot_root": str(reservation.slot_root),
        "job_root": str(reservation.job_root),
        "wine_prefix": str(reservation.wine_prefix),
        "terminal_home": str(reservation.terminal_home),
        "terminal_path": str(reservation.terminal_path),
        "xauthority": str(reservation.xauthority),
        "display": reservation.display,
        "xvfb_pid": reservation.xvfb_pid,
        "xvfb_start_ticks": reservation.xvfb_start_ticks,
        "status": status,
        "updated_at": time.time(),
    }


def _update_terminal_marker(reservation: SlotReservation, status: str, **extra: Any) -> None:
    marker = reservation.terminal_home / ".destinedfx-worker.json"
    value = {
        "job_id": reservation.job_id,
        "pairing_id": reservation.pairing_id,
        "linux_user": reservation.linux_user,
        "linux_uid": reservation.linux_uid,
        "slot_root": str(reservation.slot_root),
        "wine_prefix": str(reservation.wine_prefix),
        "terminal_home": str(reservation.terminal_home),
        "executable": str(reservation.terminal_path),
        "xauthority": str(reservation.xauthority),
        "display": reservation.display,
        "xvfb_pid": reservation.xvfb_pid,
        "xvfb_start_ticks": reservation.xvfb_start_ticks,
        "status": status,
        "updated_at": time.time(),
        **extra,
    }
    _write_slot_file(reservation, marker, json.dumps(value, sort_keys=True, separators=(",", ":")))


def _wait_for_terminal_process(reservation: SlotReservation, timeout: int = 60) -> dict[str, int]:
    payload = _reservation_payload(reservation)
    deadline = time.monotonic() + timeout
    last_code = "TERMINAL_PROCESS_NOT_FOUND"
    while time.monotonic() < deadline:
        try:
            result = _agent("/internal/slot/discover", payload, timeout=4)
            return {"pid": int(result["pid"]), "start_ticks": int(result["start_ticks"])}
        except (SlotWorkerError, KeyError, ValueError, TypeError) as exc:
            last_code = getattr(exc, "code", last_code)
        time.sleep(1)
    raise SlotWorkerError(last_code)


def _finalize_attestation(job: dict, reservation: SlotReservation, identity: dict) -> None:
    evidence_row = job.get("isolation_evidence")
    attestation_id = str(evidence_row.get("id", "")) if isinstance(evidence_row, dict) else ""
    if not attestation_id:
        raise SlotWorkerError("WORKER_ATTESTATION_ID_MISSING")
    process = {
        "attestation_id": attestation_id,
        "worker_id": WORKER_ID,
        "terminal_path": str(reservation.terminal_path),
        "process_identity": (
            f"pid={identity['pid']};start_ticks={identity['start_ticks']};"
            f"uid={reservation.linux_uid};display={reservation.display}"
        ),
        "ea_identity": "DestinedFXTraderEA",
        "pid": identity["pid"],
        "process_start_ticks": identity["start_ticks"],
    }
    process["signature"] = worker._attestation_signature(WORKER_TOKEN, process)
    _api("POST", "/v1/worker/attestations/finalize", process, timeout=15)


def _remove_startup_credentials(reservation: SlotReservation) -> None:
    ini = reservation.job_root / "startup.ini"
    preset = reservation.terminal_home / "MQL5" / "Presets" / "DestinedFXTraderEA.set"
    _MANAGER.run_as_slot(reservation, ["rm", "-f", str(ini), str(preset)], timeout=15)


def _prepare_and_launch(job: dict, reservation: SlotReservation) -> dict[str, int]:
    validated = worker.validate_job(job)
    account = validated["account"]
    if account.get("account_mode") != "DEMO":
        # Isolation supports per-user sessions, but new Linux provisioning remains DEMO-only.
        raise SlotWorkerError("LINUX_LIVE_PROVISIONING_DISABLED")
    _copy_clean_terminal(reservation)
    _MANAGER.run_as_slot(reservation, ["wineboot", "-u"], timeout=180)
    _copy_ea_and_compile(reservation)
    set_text = worker.make_set_file(validated["bridge_url"], validated["pairing_code"], account)
    ini_text = worker.ini_text(validated, account["access_type"], os.environ.get("DESTINEDFX_SYMBOL", "EURUSD"))
    preset = reservation.terminal_home / "MQL5" / "Presets" / "DestinedFXTraderEA.set"
    ini = reservation.job_root / "startup.ini"
    _write_slot_file(reservation, preset, set_text)
    _write_slot_file(reservation, ini, ini_text)
    ini_win = _MANAGER.run_as_slot(reservation, ["winepath", "-w", str(ini)], timeout=30).stdout.strip()
    _update_terminal_marker(reservation, "LAUNCH_PENDING")
    _write_manifest(_manifest_from_reservation(reservation, "LAUNCH_PENDING"))
    log_path = (RUNTIME / f"terminal-{pairing_key(reservation.pairing_id)}.log")
    log_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(log_path.parent, 0o700)
    fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(fd, "ab") as sink:
        _MANAGER.popen_as_slot(
            reservation,
            [WINE, str(reservation.terminal_path), "/portable", f"/config:{ini_win}"],
            cwd=reservation.terminal_home,
            stdout=sink,
            stderr=subprocess.STDOUT,
        )
    identity = _wait_for_terminal_process(reservation, timeout=90)
    _update_terminal_marker(
        reservation, "AUTHENTICATING", pid=identity["pid"], start_ticks=identity["start_ticks"]
    )
    row = _manifest_from_reservation(reservation, "AUTHENTICATING")
    row.update(process_pid=identity["pid"], process_start_ticks=identity["start_ticks"])
    _write_manifest(row)
    _finalize_attestation(validated, reservation, identity)
    return identity


def _reservation_from_manifest(row: dict[str, Any]) -> SlotReservation:
    from pathlib import Path
    required = ("pairing_id", "job_id", "worker_id", "linux_user", "linux_uid",
                "slot_root", "job_root", "wine_prefix", "terminal_home", "terminal_path",
                "xauthority", "display", "xvfb_pid", "xvfb_start_ticks")
    if any(key not in row for key in required):
        raise SlotWorkerError("SLOT_MANIFEST_INCOMPLETE")
    return SlotReservation(
        pairing_id=row["pairing_id"], job_id=row["job_id"], worker_id=row["worker_id"],
        linux_user=row["linux_user"], linux_uid=int(row["linux_uid"]),
        slot_root=Path(row["slot_root"]), job_root=Path(row["job_root"]),
        wine_prefix=Path(row["wine_prefix"]), terminal_home=Path(row["terminal_home"]),
        terminal_path=Path(row["terminal_path"]), xauthority=Path(row["xauthority"]),
        display=row["display"], xvfb_pid=int(row["xvfb_pid"]),
        xvfb_start_ticks=int(row["xvfb_start_ticks"]),
    )


def _stop_pairing(job: dict) -> str:
    pairing_id = str(job.get("pairing_id", ""))
    candidates = [row for row in _active_manifests() if row.get("pairing_id") == pairing_id]
    if not candidates:
        return "REVOKE_MARKER_NOT_FOUND"
    if len(candidates) != 1:
        return "REVOKE_SLOT_AMBIGUOUS"
    row = candidates[0]
    safety = inspect_shutdown_safety(os.environ.get("BRIDGE_DB_PATH", str(STATE / "bridge.sqlite3")))
    if not safety.get("safe"):
        row["status"] = "OPERATOR_REVIEW"
        row["revoke_blocked"] = True
        _write_manifest(row)
        return "REVOKE_BLOCKED_UNSAFE_BROKER_STATE"
    reservation = _reservation_from_manifest(row)
    _MANAGER.stop(
        pairing_id, reservation.job_id, reservation.linux_user, reservation.linux_uid,
        reservation.display, reservation.xvfb_pid, reservation.xvfb_start_ticks,
    )
    try:
        _manifest_path(reservation.job_id).unlink()
    except FileNotFoundError:
        pass
    return "REVOKED"


def run_once() -> str:
    _require_config()
    active_before = _active_manifests()
    max_slots = capacity()
    payload = {
        "worker_id": WORKER_ID,
        "active_terminals": min(len(active_before), max_slots),
        "max_terminals": max_slots,
    }
    job = _api("POST", "/v1/worker/jobs/claim", payload, timeout=20)
    if job.get("kind") == "REVOKE":
        result = _stop_pairing(job)
        if result == "REVOKED":
            _api("PATCH", f"/v1/worker/jobs/{job['job_id']}",
                 {"status": "REVOKED", "worker_id": WORKER_ID}, timeout=15)
            return "REVOKED"
        _api("PATCH", f"/v1/worker/jobs/{job['job_id']}",
             {"status": "FAILED", "worker_id": WORKER_ID,
              "error_code": result, "error_message": result.replace("_", " ").lower()}, timeout=15)
        return "OPERATOR_REVIEW"
    if job.get("status") == "ATTESTATION_REQUIRED":
        reservation = None
        try:
            existing = [row for row in _active_manifests() if row.get("job_id") == job["job_id"]]
            if existing:
                if len(existing) != 1 or existing[0].get("status") != "RESERVED":
                    raise SlotWorkerError("SLOT_RESERVATION_REQUIRES_OPERATOR_REVIEW")
                reservation = _reservation_from_manifest(existing[0])
            else:
                reservation = _MANAGER.prepare(job["pairing_id"], job["job_id"], WORKER_ID)
                _write_manifest(_manifest_from_reservation(reservation, "RESERVED"))
            reservation_payload = _reservation_payload(reservation)
            _agent("/internal/slot/reservation", reservation_payload)
            evidence = _attestation(job, reservation)
            # Use the same pre-reservation capacity observation on retry. The new
            # reservation is the job we are claiming, not a second occupied slot.
            claim = dict(payload, attestation=evidence)
            job = _api("POST", "/v1/worker/jobs/claim", claim, timeout=20)
        except Exception:
            if reservation is not None:
                try:
                    _MANAGER.stop(
                        reservation.pairing_id, reservation.job_id, reservation.linux_user,
                        reservation.linux_uid, reservation.display,
                        reservation.xvfb_pid, reservation.xvfb_start_ticks, strict=True,
                    )
                    _manifest_path(reservation.job_id).unlink(missing_ok=True)
                except Exception:
                    _write_manifest(_manifest_from_reservation(reservation, "OPERATOR_REVIEW"))
            raise
    if job.get("kind") != "PROVISION" or not job.get("job_id"):
        return "NO_JOB"
    reservation_rows = [r for r in _active_manifests() if r.get("job_id") == job["job_id"]]
    if len(reservation_rows) != 1:
        raise SlotWorkerError("SLOT_RESERVATION_NOT_FOUND")
    reservation = _reservation_from_manifest(reservation_rows[0])
    try:
        job = worker.validate_job(job)
        if job["pairing_id"] != reservation.pairing_id:
            raise SlotWorkerError("SLOT_PAIRING_MISMATCH")
        worker.post_state(CONTROL_URL, WORKER_TOKEN, job["job_id"], WORKER_ID, "PROVISIONING")
        worker.post_state(CONTROL_URL, WORKER_TOKEN, job["job_id"], WORKER_ID, "AUTHENTICATING")
        identity = _prepare_and_launch(job, reservation)
        status = worker.wait_for_terminal(
            CONTROL_URL, WORKER_TOKEN, job["job_id"], WORKER_ID,
            int(os.environ.get("DESTINEDFX_AUTH_TIMEOUT_SECONDS", "270")),
        )
        if status == "READY":
            _remove_startup_credentials(reservation)
            _update_terminal_marker(
                reservation, "READY", pid=identity["pid"], start_ticks=identity["start_ticks"]
            )
            row = _manifest_from_reservation(reservation, "READY")
            row.update(process_pid=identity["pid"], process_start_ticks=identity["start_ticks"])
            _write_manifest(row)
            return "READY"
        _update_terminal_marker(
            reservation, "OPERATOR_REVIEW", pid=identity["pid"], start_ticks=identity["start_ticks"]
        )
        row = _manifest_from_reservation(reservation, "OPERATOR_REVIEW")
        row.update(process_pid=identity["pid"], process_start_ticks=identity["start_ticks"])
        _write_manifest(row)
        if status == "FAILED":
            return "FAILED_OPERATOR_REVIEW"
        return "AUTH_TIMEOUT_OPERATOR_REVIEW"
    except Exception as exc:
        code = getattr(exc, "code", "LINUX_SLOT_PROVISION_FAILED")
        try:
            worker.post_state(CONTROL_URL, WORKER_TOKEN, job["job_id"], WORKER_ID,
                              "FAILED", str(code)[:64], str(code).replace("_", " ").lower()[:120])
        except Exception:
            pass
        # Never kill or erase a terminal when its broker heartbeat/state is unknown.
        row = _manifest_from_reservation(reservation, "OPERATOR_REVIEW")
        row["error_code"] = str(code)[:64]
        _write_manifest(row)
        _log(f"job failed safely: {str(code)[:64]}")
        return "OPERATOR_REVIEW"


def stop_all() -> int:
    _require_stop_config()
    database = Path(os.environ.get("BRIDGE_DB_PATH", str(STATE / "bridge.sqlite3")))
    safety = inspect_shutdown_safety(database)
    if not safety.get("safe"):
        _log("host stop blocked by unresolved broker state")
        return 2
    rows = _active_manifests()
    for row in rows:
        reservation = _reservation_from_manifest(row)
        _MANAGER.stop(
            reservation.pairing_id, reservation.job_id, reservation.linux_user,
            reservation.linux_uid, reservation.display, reservation.xvfb_pid,
            reservation.xvfb_start_ticks,
        )
        _manifest_path(reservation.job_id).unlink(missing_ok=True)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--loop", action="store_true")
    parser.add_argument("--stop-all", action="store_true")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--interval", type=float, default=float(POLL_SECONDS))
    args = parser.parse_args()
    global _MANAGER
    _MANAGER = LinuxSlotManager(SLOT_ROOT, SLOT_RUNTIME)
    try:
        if args.stop_all:
            _require_stop_config()
            return stop_all()
        _require_config()
        if args.loop:
            while True:
                try:
                    result = run_once()
                    if result not in {"NO_JOB", "READY", "REVOKED"}:
                        _log(result)
                except Exception as exc:
                    _log(f"worker cycle failed: {getattr(exc, 'code', type(exc).__name__)}")
                time.sleep(max(2.0, min(30.0, args.interval)))
        run_once()
        return 0
    except (SlotWorkerError, IsolationError, worker.ProvisionError) as exc:
        _log(f"blocked: {getattr(exc, 'code', type(exc).__name__)}")
        return 2


_MANAGER: LinuxSlotManager


if __name__ == "__main__":
    raise SystemExit(main())
