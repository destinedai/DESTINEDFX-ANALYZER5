#!/usr/bin/env python3
"""One-shot Linux MT5 connect-job runner for the managed-worker protocol.

The process handles at most one connect job and exits, limiting the lifetime of
claimed credentials in Python memory. Passwords never enter argv or logs.
"""
from __future__ import annotations

import argparse
import gc
import json
import logging
import os
import re
import stat
import subprocess
import sys
import time
from pathlib import Path
from typing import Callable

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "managed-worker"))
import worker  # noqa: E402

LOG = logging.getLogger("destinedfx.linux-worker")
_SAFE_UNIT = re.compile(r"^[A-Za-z0-9_.@-]{1,128}\.service$")


class LinuxRunnerError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def write_private_file(path: Path, data: bytes | bytearray | str, *, exclusive: bool = True) -> None:
    """Create a service-user-owned 0600 file and durably write its contents."""
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        os.chmod(path.parent, 0o700)
    except OSError:
        raise LinuxRunnerError("PRIVATE_DIRECTORY_FAILED") from None
    flags = os.O_WRONLY | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
    flags |= os.O_EXCL if exclusive else os.O_TRUNC
    fd = None
    try:
        fd = os.open(path, flags, 0o600)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as stream:
            if isinstance(data, str):
                stream.write(data.encode("utf-8"))
            else:
                stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        info = path.stat(follow_symlinks=False)
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.geteuid():
            raise LinuxRunnerError("PRIVATE_FILE_OWNERSHIP_FAILED")
    except LinuxRunnerError:
        if fd is not None:
            path.unlink(missing_ok=True)
        raise
    except OSError:
        if fd is not None:
            path.unlink(missing_ok=True)
        raise LinuxRunnerError("PRIVATE_FILE_WRITE_FAILED") from None


def securely_remove_file(path: Path) -> None:
    """Best-effort overwrite, fsync, and unlink of a regular private file."""
    path = Path(path)
    try:
        info = path.lstat()
    except FileNotFoundError:
        return
    except OSError:
        raise LinuxRunnerError("PRIVATE_FILE_CLEANUP_FAILED") from None
    if not stat.S_ISREG(info.st_mode):
        raise LinuxRunnerError("PRIVATE_FILE_CLEANUP_FAILED")
    try:
        with path.open("r+b", buffering=0) as stream:
            remaining = info.st_size
            zeroes = b"\x00" * 4096
            while remaining:
                block = zeroes[: min(remaining, len(zeroes))]
                stream.write(block)
                remaining -= len(block)
            os.fsync(stream.fileno())
        path.unlink()
        dir_fd = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(dir_fd)
        finally:
            os.close(dir_fd)
    except OSError:
        raise LinuxRunnerError("PRIVATE_FILE_CLEANUP_FAILED") from None


def _clear_job_secrets(job: dict) -> None:
    """Drop Python references promptly; the one-shot service exits after a job."""
    account = job.get("account") if isinstance(job, dict) else None
    if isinstance(account, dict):
        for key in ("password", "investor_password"):
            if key in account:
                account[key] = ""
    if isinstance(job, dict):
        job["pairing_code"] = ""


def _write_active_job(path: Path, job_id: str, pairing_id: str, started_at: float, state: str) -> None:
    body = json.dumps({
        "job_id": job_id,
        "pairing_id": pairing_id,
        "started_at": started_at,
        "status": state,
    }, separators=(",", ":"))
    temp = path.with_name(path.name + ".tmp")
    securely_remove_file(temp)
    write_private_file(temp, body)
    os.replace(temp, path)
    os.chmod(path, 0o600)


def load_active_job(path: Path) -> dict | None:
    try:
        info = path.stat(follow_symlinks=False)
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) & 0o077:
            raise LinuxRunnerError("ACTIVE_JOB_FILE_NOT_PRIVATE")
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", str(value.get("job_id", ""))):
            raise LinuxRunnerError("ACTIVE_JOB_FILE_INVALID")
        return value
    except FileNotFoundError:
        return None
    except LinuxRunnerError:
        raise
    except (OSError, ValueError, TypeError):
        raise LinuxRunnerError("ACTIVE_JOB_FILE_INVALID") from None


def _run_systemctl(action: str, unit: str) -> None:
    if action not in {"start", "stop", "restart"} or not _SAFE_UNIT.fullmatch(unit):
        raise LinuxRunnerError("SYSTEMD_ACTION_INVALID")
    try:
        result = subprocess.run(
            ["sudo", "/usr/bin/systemctl", action, unit],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=60,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        raise LinuxRunnerError("SYSTEMD_CONTROL_FAILED") from None
    if result.returncode != 0:
        raise LinuxRunnerError("SYSTEMD_CONTROL_FAILED")


def _post_state(settings: dict, job_id: str, state: str, code: str = "") -> None:
    worker.post_state(
        settings["control_plane_url"],
        settings["worker_token"],
        job_id,
        settings["worker_id"],
        state,
        code,
        "Terminal login was not confirmed; check the bounded failure code and owner runbook" if code else "",
    )


def _stop_then_cleanup(settings: dict, startup: Path, preset: Path, active: Path,
                       start_marker: Path, systemctl: Callable[[str, str], None], unit: str) -> bool:
    try:
        systemctl("stop", unit)
    except LinuxRunnerError:
        return False
    securely_remove_file(startup)
    securely_remove_file(preset)
    securely_remove_file(start_marker)
    securely_remove_file(active)
    return True


def execute_connect_job(job: dict, settings: dict, *,
                        systemctl: Callable[[str, str], None] = _run_systemctl,
                        wait_status: Callable[[str, str, int], str] | None = None,
                        clock: Callable[[], float] = time.time) -> str:
    """Write a private MT5 login config, restart, then trust only backend READY."""
    validated = worker.validate_job(job)
    if validated.get("kind", "PROVISION") != "PROVISION":
        raise LinuxRunnerError("UNSUPPORTED_JOB_KIND")
    job_id = validated["job_id"]
    pairing_id = validated["pairing_id"]
    base = settings["control_plane_url"]
    token = settings["worker_token"]
    worker_id = settings["worker_id"]
    startup = Path(settings["startup_config"])
    preset = Path(settings["ea_set_path"])
    active = Path(settings["active_job_path"])
    start_marker = Path(settings["connect_start_file"])
    unit = settings["mt5_unit"]
    attempt_started_at = clock()
    secrets_cleared = False
    stopped = False

    try:
        _post_state(settings, job_id, "PROVISIONING")
        ini_data = bytearray(worker.ini_text(validated, validated["account"]["access_type"], settings.get("symbol", "EURUSD")).encode("utf-8"))
        set_data = bytearray(worker.make_set_file(validated["bridge_url"], validated["pairing_code"], validated["account"]).encode("utf-8"))
        write_private_file(startup, ini_data)
        write_private_file(preset, set_data)
        # Zero mutable serialized buffers and drop immutable references as soon
        # as both private files are durable. CPython cannot promise physical
        # memory zeroization; this job process exits immediately after handling.
        for data in (ini_data, set_data):
            data[:] = b"\x00" * len(data)
        del ini_data, set_data
        _write_active_job(active, job_id, pairing_id, attempt_started_at, "AUTHENTICATING")
        write_private_file(start_marker, f"{attempt_started_at:.6f}\n")
        _clear_job_secrets(validated)
        _clear_job_secrets(job)
        secrets_cleared = True
        _post_state(settings, job_id, "AUTHENTICATING")
        systemctl("restart", unit)
        if wait_status is None:
            terminal_status = worker.wait_for_terminal(
                base, token, job_id, worker_id,
                int(settings.get("auth_timeout_seconds", 270)),
            )
        else:
            terminal_status = wait_status(job_id, worker_id, int(settings.get("auth_timeout_seconds", 270)))

        if terminal_status == "READY":
            # The bridge returns READY only after a fresh EA heartbeat whose
            # account identity and mode match the pairing/job identity.
            securely_remove_file(startup)
            securely_remove_file(preset)
            securely_remove_file(start_marker)
            _write_active_job(active, job_id, pairing_id, attempt_started_at, "READY")
            return "READY"

        # Stop the service before removing a config that the terminal may still
        # be consuming. Preserve private files for operator review if stop fails.
        try:
            systemctl("stop", unit)
            stopped = True
        except LinuxRunnerError:
            _write_active_job(active, job_id, pairing_id, attempt_started_at, "OPERATOR_REVIEW")
            if terminal_status != "FAILED":
                try:
                    _post_state(settings, job_id, "FAILED", "TERMINAL_OWNERSHIP_UNVERIFIED")
                except worker.ProvisionError:
                    pass
            return "OPERATOR_REVIEW"

        code = worker.journal_failure_code({"journal_path": settings.get("journal_path", "")}, attempt_started_at)
        try:
            _post_state(settings, job_id, "FAILED", code)
        except worker.ProvisionError:
            LOG.warning("Could not post bounded failure state for job %s", job_id)
        securely_remove_file(startup)
        securely_remove_file(preset)
        securely_remove_file(start_marker)
        securely_remove_file(active)
        return code
    except worker.ProvisionError as exc:
        if not secrets_cleared:
            _clear_job_secrets(validated)
            _clear_job_secrets(job)
        try:
            systemctl("stop", unit)
            stopped = True
        except LinuxRunnerError:
            _write_active_job(active, job_id, pairing_id, attempt_started_at, "OPERATOR_REVIEW")
            return "OPERATOR_REVIEW"
        securely_remove_file(startup)
        securely_remove_file(preset)
        securely_remove_file(start_marker)
        securely_remove_file(active)
        try:
            _post_state(settings, job_id, "FAILED", exc.code)
        except worker.ProvisionError:
            pass
        return exc.code
    except (OSError, LinuxRunnerError, ValueError, TypeError):
        if not secrets_cleared:
            _clear_job_secrets(validated)
            _clear_job_secrets(job)
        try:
            systemctl("stop", unit)
            stopped = True
        except LinuxRunnerError:
            _write_active_job(active, job_id, pairing_id, attempt_started_at, "OPERATOR_REVIEW")
            return "OPERATOR_REVIEW"
        securely_remove_file(startup)
        securely_remove_file(preset)
        securely_remove_file(start_marker)
        securely_remove_file(active)
        try:
            _post_state(settings, job_id, "FAILED", "TERMINAL_LAUNCH_FAILED")
        except worker.ProvisionError:
            pass
        LOG.error("Linux terminal connect job %s failed safely", job_id)
        return "TERMINAL_LAUNCH_FAILED"
    finally:
        # References to claimed credentials are removed from the job dictionaries
        # and the short-lived service process exits after this call.
        if not secrets_cleared:
            _clear_job_secrets(validated)
            _clear_job_secrets(job)
        gc.collect()


def _status(settings: dict, job_id: str) -> dict:
    query = worker.urllib.parse.urlencode({"worker_id": settings["worker_id"]})
    return worker.request_json(
        f"{settings['control_plane_url']}/v1/worker/jobs/{worker.urllib.parse.quote(job_id, safe='')}/status?{query}",
        settings["worker_token"],
        "GET",
    )


def _refresh_active(settings: dict, active_job: dict,
                    systemctl: Callable[[str, str], None]) -> bool:
    status = _status(settings, active_job["job_id"]).get("status", "")
    if status in {"LEASED", "PROVISIONING", "AUTHENTICATING"}:
        return True
    if status == "READY":
        active_job["status"] = "READY"
        _write_active_job(Path(settings["active_job_path"]), active_job["job_id"],
                          active_job.get("pairing_id", "unknown"),
                          float(active_job.get("started_at", 0)), "READY")
        return True
    # Backend FAILED/REVOKED means the account must not stay on the terminal.
    startup = Path(settings["startup_config"])
    preset = Path(settings["ea_set_path"])
    start_marker = Path(settings["connect_start_file"])
    active_path = Path(settings["active_job_path"])
    return _stop_then_cleanup(settings, startup, preset, active_path, start_marker, systemctl, settings["mt5_unit"])


def execute_revoke_job(job: dict, settings: dict, *,
                       systemctl: Callable[[str, str], None] = _run_systemctl) -> str:
    job_id = str(job.get("job_id", ""))
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", job_id):
        raise LinuxRunnerError("INVALID_JOB")
    try:
        systemctl("stop", settings["mt5_unit"])
    except LinuxRunnerError:
        try:
            _post_state(settings, job_id, "FAILED", "REVOKE_PROCESS_NOT_TERMINATED")
        except worker.ProvisionError:
            pass
        return "OPERATOR_REVIEW"
    for key in ("startup_config", "ea_set_path", "connect_start_file", "active_job_path"):
        securely_remove_file(Path(settings[key]))
    try:
        worker.post_state(settings["control_plane_url"], settings["worker_token"], job_id,
                          settings["worker_id"], "REVOKED")
    except worker.ProvisionError:
        return "REVOKE_ACK_FAILED"
    return "REVOKED"


def run_once(settings: dict, *,
             systemctl: Callable[[str, str], None] = _run_systemctl,
             wait_status: Callable[[str, str, int], str] | None = None) -> str:
    active_path = Path(settings["active_job_path"])
    active = load_active_job(active_path)
    if active and not _refresh_active(settings, active, systemctl):
        LOG.error("Active account cleanup requires operator review")
        return "OPERATOR_REVIEW"
    active = load_active_job(active_path)
    payload = {
        "worker_id": settings["worker_id"],
        "active_terminals": 1 if active else 0,
        "max_terminals": 1,
    }
    job = worker.request_json(
        f"{settings['control_plane_url']}/v1/worker/jobs/claim",
        settings["worker_token"],
        "POST",
        payload,
    )
    if not job or job.get("job") is None and not job.get("job_id"):
        return "NO_JOB"
    if job.get("kind") == "REVOKE":
        return execute_revoke_job(job, settings, systemctl=systemctl)
    if job.get("kind") != "PROVISION":
        try:
            worker.post_state(settings["control_plane_url"], settings["worker_token"],
                              str(job.get("job_id", "")), settings["worker_id"],
                              "FAILED", "INVALID_JOB")
        except (worker.ProvisionError, ValueError):
            pass
        return "INVALID_JOB"
    if active:
        try:
            worker.post_state(settings["control_plane_url"], settings["worker_token"],
                              job["job_id"], settings["worker_id"], "FAILED", "TERMINAL_BUSY")
        except worker.ProvisionError:
            pass
        return "TERMINAL_BUSY"
    return execute_connect_job(job, settings, systemctl=systemctl, wait_status=wait_status)


def load_settings(env: dict[str, str] | None = None) -> dict:
    env = os.environ if env is None else env
    base = worker.validate_origin(env.get("DESTINEDFX_CONTROL_PLANE_URL", ""))
    token = env.get("DESTINEDFX_WORKER_TOKEN", "")
    if not token:
        token_file = env.get("DESTINEDFX_WORKER_TOKEN_FILE", "")
        credential_dir = env.get("CREDENTIALS_DIRECTORY", "")
        if not token_file and credential_dir:
            token_file = str(Path(credential_dir) / "worker-token")
        try:
            if token_file:
                token = Path(token_file).read_text(encoding="ascii").strip()
        except (OSError, UnicodeError):
            raise LinuxRunnerError("WORKER_CREDENTIAL_UNAVAILABLE") from None
    worker_id = env.get("DESTINEDFX_WORKER_ID", "")
    unit = env.get("DESTINEDFX_MT5_SERVICE", "destinedfx-mt5.service")
    if (not base or not token or len(token) > 512 or any(ord(char) < 32 or ord(char) == 127 for char in token)
            or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", worker_id) or not _SAFE_UNIT.fullmatch(unit)):
        raise LinuxRunnerError("INVALID_WORKER_CONFIG")
    try:
        timeout = int(env.get("DESTINEDFX_AUTH_TIMEOUT_SECONDS", "270"))
    except ValueError:
        raise LinuxRunnerError("INVALID_WORKER_CONFIG") from None
    if not 30 <= timeout <= 270:
        raise LinuxRunnerError("INVALID_WORKER_CONFIG")
    return {
        "control_plane_url": base,
        "worker_token": token,
        "worker_id": worker_id,
        "startup_config": env.get("DESTINEDFX_TERMINAL_STARTUP_CONFIG", "/var/lib/destinedfx/terminal/startup.ini"),
        "ea_set_path": env.get("DESTINEDFX_EA_SET_PATH", "/var/lib/destinedfx/terminal/MQL5/Presets/DestinedFXTraderEA.set"),
        "active_job_path": env.get("DESTINEDFX_ACTIVE_JOB_PATH", "/var/lib/destinedfx/active-job.json"),
        "connect_start_file": env.get("CONNECT_JOB_START_FILE", "/var/lib/destinedfx/connect-job-start.epoch"),
        "journal_path": env.get("MT5_JOURNAL_PATH", env.get("MT5_JOURNAL_DIR", "/var/lib/destinedfx/terminal/MQL5/Logs")),
        "mt5_unit": unit,
        "auth_timeout_seconds": timeout,
        "symbol": env.get("DESTINEDFX_SYMBOL", "EURUSD"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true", help="claim at most one job and exit")
    args = parser.parse_args()
    if os.name != "posix":
        LOG.error("Linux host runner requires a POSIX host")
        return 2
    try:
        settings = load_settings()
        result = run_once(settings)
        LOG.info("Linux worker cycle finished (%s)", result)
        return 0
    except (LinuxRunnerError, worker.ProvisionError) as exc:
        code = exc.code if isinstance(exc, LinuxRunnerError) else exc.code
        LOG.error("Linux worker cycle failed safely (%s)", code)
        return 2


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    raise SystemExit(main())
