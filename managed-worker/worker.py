"""Windows-only, fail-closed MT5 provisioning worker for managed protocol v1.

The worker never logs claim responses, credentials, bearer tokens, or subprocess
arguments. Importing this module is safe on non-Windows hosts for unit testing.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import tomllib
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any

LOG = logging.getLogger("destinedfx.worker")
SAFE_ID = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
SAFE_CODE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
LOGIN = re.compile(r"^[0-9]{1,20}$")
SAFE_SERVER = re.compile(r"^[A-Za-z0-9 ._:-]{1,128}$")
SAFE_SYMBOL = re.compile(r"^[A-Za-z0-9._-]{1,32}$")
VALID_ACCESS = {"MASTER", "INVESTOR"}

def validate_origin(raw: str) -> str | None:
    value = (raw or "").strip().rstrip("/")
    try:
        parsed = urllib.parse.urlparse(value)
        host = (parsed.hostname or "").lower().rstrip(".")
        port = parsed.port
    except (ValueError, UnicodeError):
        return None
    placeholder = (host in {"example.com", "example.org", "example.net", "testserver", "bridge.example", "localhost"}
                   or host.endswith(".example") or host.startswith("your-")
                   or host.startswith("placeholder") or host.endswith((".localhost", ".test", ".invalid")) or "example" in host)
    if (not host or parsed.scheme != "https" or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path not in ("", "/") or placeholder
            or port is not None and not (1 <= port <= 65535)):
        return None
    try:
        address = ipaddress.ip_address(host.strip("[]"))
        if address.is_private or address.is_loopback or address.is_link_local or address.is_multicast or address.is_reserved or address.is_unspecified:
            return None
    except ValueError:
        pass
    return f"https://{host}" + (f":{port}" if port else "")


class ProvisionError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def safe_child(root: Path, name: str) -> Path:
    if not SAFE_ID.fullmatch(name):
        raise ProvisionError("INVALID_IDENTIFIER")
    root = root.resolve()
    child = (root / name).resolve()
    if child.parent != root:
        raise ProvisionError("INVALID_PATH")
    return child


def terminal_records(root: Path) -> list[tuple[Path, dict[str, Any]]]:
    """Read only worker-owned terminal markers; malformed markers are ignored."""
    records = []
    if not root.exists():
        return records
    for marker in root.glob("*/terminal/.destinedfx-worker.json"):
        try:
            data = json.loads(marker.read_text(encoding="utf-8"))
            if isinstance(data, dict) and SAFE_ID.fullmatch(str(data.get("job_id", ""))) and SAFE_ID.fullmatch(str(data.get("pairing_id", ""))):
                records.append((marker, data))
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            continue
    return records


def process_is_owned(pid: int, executable: Path) -> bool:
    """Return true only when the recorded PID is alive and belongs to this executable."""
    if pid <= 0:
        return False
    if os.name != "nt":
        try:
            os.kill(pid, 0)
            return executable.is_file()
        except OSError:
            return False
    escaped = str(executable.resolve()).replace("'", "''")
    script = (f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId = {pid}'; "
              f"if(-not $p -or -not $p.ExecutablePath) {{exit 1}}; "
              f"if(([IO.Path]::GetFullPath($p.ExecutablePath) -ine '{escaped}')) {{exit 2}}; exit 0")
    result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=False)
    return result.returncode == 0


def supervise_terminals(settings: dict, base: str | None = None, token: str | None = None,
                        worker_id: str | None = None) -> list[dict[str, Any]]:
    """Reconcile owned terminals and retry crashed portable terminals with bounded backoff.

    Restarting a process never restores READY. The bridge must observe a fresh,
    authenticated, account-matched EA heartbeat before the owning job becomes READY.
    """
    root = Path(settings["data_root"]).resolve()
    max_retries = max(0, min(10, int(settings.get("max_restart_retries", 3))))
    results = []
    for marker, record in terminal_records(root):
        terminal_home = marker.parent
        executable = (terminal_home / Path(settings["terminal_exe"]).name).resolve()
        pid = int(record.get("pid", 0) or 0)
        backend_status = None
        if base and token and worker_id:
            try:
                backend_status = request_json(
                    f"{base.rstrip('/')}/v1/worker/jobs/{urllib.parse.quote(record['job_id'], safe='')}/status?worker_id={urllib.parse.quote(worker_id, safe='')}",
                    token, "GET").get("status")
            except ProvisionError:
                backend_status = None
        if process_is_owned(pid, executable):
            record["status"] = backend_status or "RUNNING"
            marker.write_text(json.dumps(record, separators=(",", ":")), encoding="utf-8")
            results.append({"job_id": record["job_id"], "status": record["status"]})
            continue
        retries = int(record.get("restart_count", 0) or 0)
        backoff = max(0, int(settings.get("restart_backoff_seconds", 30)))
        last_restart = float(record.get("last_restart", 0) or 0)
        wait_seconds = backoff * (2 ** max(0, retries - 1))
        if last_restart and time.time() - last_restart < wait_seconds:
            results.append({"job_id": record["job_id"], "status": "RESTART_BACKOFF", "retry_after": round(wait_seconds - (time.time() - last_restart), 1)})
            continue
        if retries >= max_retries:
            record["status"] = "RESTART_LIMIT"
            marker.write_text(json.dumps(record, separators=(",", ":")), encoding="utf-8")
            if base and token and worker_id:
                try:
                    post_state(base, token, record["job_id"], worker_id, "FAILED", "TERMINAL_RESTART_LIMIT", "Terminal restart limit reached; operator review is required")
                except ProvisionError:
                    pass
            results.append({"job_id": record["job_id"], "status": "RESTART_LIMIT"})
            continue
        try:
            proc = subprocess.Popen([str(executable), "/portable"], cwd=str(terminal_home),
                                    shell=False, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, close_fds=True)
        except OSError:
            record["status"] = "RESTART_FAILED"
            marker.write_text(json.dumps(record, separators=(",", ":")), encoding="utf-8")
            results.append({"job_id": record["job_id"], "status": "RESTART_FAILED"})
            continue
        record.update(pid=proc.pid, restart_count=retries + 1, status="AUTHENTICATING", last_restart=time.time())
        marker.write_text(json.dumps(record, separators=(",", ":")), encoding="utf-8")
        if base and token and worker_id:
            try:
                post_state(base, token, record["job_id"], worker_id, "AUTHENTICATING")
            except ProvisionError:
                pass
        results.append({"job_id": record["job_id"], "status": "RESTARTED", "restart_count": retries + 1})
    return results


def quarantine_terminal(settings: dict, job_id: str) -> str:
    """Move only a dead, worker-owned tree to quarantine; never kill an unverified process."""
    root = Path(settings["data_root"]).resolve()
    for marker, record in terminal_records(root):
        if record.get("job_id") != job_id:
            continue
        terminal_home = marker.parent
        executable = (terminal_home / Path(settings["terminal_exe"]).name).resolve()
        if process_is_owned(int(record.get("pid", 0) or 0), executable):
            record["status"] = "OPERATOR_REVIEW"
            marker.write_text(json.dumps(record, separators=(",", ":")), encoding="utf-8")
            return "OPERATOR_REVIEW"
        account_dir = terminal_home.parent
        quarantine = root / ".quarantine"
        quarantine.mkdir(mode=0o700, exist_ok=True)
        destination = quarantine / f"{job_id}-{int(time.time())}"
        account_dir.rename(destination)
        return str(destination)
    return "NOT_FOUND"


def validate_job(job: Any) -> dict[str, Any]:
    if not isinstance(job, dict):
        raise ProvisionError("INVALID_JOB")
    for key in ("job_id", "pairing_id"):
        if not isinstance(job.get(key), str) or not SAFE_ID.fullmatch(job[key]):
            raise ProvisionError("INVALID_IDENTIFIER")
    if not isinstance(job.get("pairing_code"), str) or not SAFE_CODE.fullmatch(job["pairing_code"]):
        raise ProvisionError("INVALID_PAIRING_CODE")
    bridge = job.get("bridge_url")
    normalized = validate_origin(bridge if isinstance(bridge, str) else "")
    if normalized is None:
        raise ProvisionError("INVALID_BRIDGE_URL")
    account = job.get("account")
    if not isinstance(account, dict):
        raise ProvisionError("INVALID_ACCOUNT")
    login, server, password, access, mode = (account.get(k) for k in ("login", "server", "password", "access_type", "account_mode"))
    if not isinstance(login, str) or not LOGIN.fullmatch(login):
        raise ProvisionError("INVALID_ACCOUNT")
    if not isinstance(server, str) or not SAFE_SERVER.fullmatch(server):
        raise ProvisionError("INVALID_ACCOUNT")
    if not isinstance(password, str) or not password or "\r" in password or "\n" in password:
        raise ProvisionError("INVALID_ACCOUNT")
    if access not in VALID_ACCESS:
        raise ProvisionError("INVALID_ACCESS_TYPE")
    if mode not in {"DEMO", "LIVE"}:
        raise ProvisionError("INVALID_ACCOUNT_MODE")
    result = dict(job)
    result["bridge_url"] = normalized
    return result


def request_json(url: str, token: str, method: str, payload: dict | None = None, timeout: int = 20) -> dict:
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            result = json.loads(response.read())
            return result if isinstance(result, dict) else {}
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError):
        raise ProvisionError("CONTROL_PLANE_UNAVAILABLE") from None


def post_state(base: str, token: str, job_id: str, worker_id: str, state: str, code: str = "", message: str = "") -> None:
    if state not in {"PROVISIONING", "AUTHENTICATING", "READY", "FAILED"}:
        raise ProvisionError("INVALID_STATE")
    payload = {"status": state, "worker_id": worker_id}
    if code:
        payload.update(error_code=code[:64], error_message=message[:160])
    request_json(f"{base.rstrip('/')}/v1/worker/jobs/{urllib.parse.quote(job_id, safe='')}", token, "PATCH", payload)


def wait_for_terminal(base: str, token: str, job_id: str, worker_id: str, timeout: int = 300) -> str:
    url = f"{base.rstrip('/')}/v1/worker/jobs/{urllib.parse.quote(job_id, safe='')}/status?worker_id={urllib.parse.quote(worker_id, safe='')}"
    deadline = time.monotonic() + max(1, timeout)
    while time.monotonic() < deadline:
        status = request_json(url, token, "GET").get("status", "")
        if status in {"READY", "FAILED", "REVOKED"}:
            return status
        time.sleep(5)
    return "TIMEOUT"


def revoke_pairing(settings: dict, pairing_id: str) -> None:
    """Stop only the terminal process recorded by this worker, then erase its local account tree."""
    if not SAFE_ID.fullmatch(pairing_id):
        raise ProvisionError("INVALID_IDENTIFIER")
    root = Path(settings["data_root"]).resolve()
    found = False
    for marker in root.glob("*/terminal/.destinedfx-worker.json"):
        try:
            data = json.loads(marker.read_text(encoding="utf-8"))
            account_dir = marker.parent.parent.resolve()
            if account_dir.parent != root or data.get("pairing_id") != pairing_id:
                continue
            found = True
            pid = int(data.get("pid", 0))
            if os.name == "nt" and pid <= 0:
                raise ProvisionError("REVOKE_PROCESS_ID_MISSING")
            exe = str((marker.parent / Path(settings["terminal_exe"]).name).resolve())
            if os.name == "nt" and pid > 0:
                escaped = exe.replace("'", "''")
                script = (f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId = {pid}'; "
                          f"if(-not $p) {{exit 0}}; "
                          f"if(-not $p.ExecutablePath -or ([IO.Path]::GetFullPath($p.ExecutablePath) -ine '{escaped}')) {{exit 2}}; "
                          f"Stop-Process -Id {pid} -Force -ErrorAction Stop; Wait-Process -Id {pid} -Timeout 10 -ErrorAction SilentlyContinue; exit 0")
                result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                                        shell=False, timeout=15, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
                if result.returncode != 0:
                    raise ProvisionError("REVOKE_PROCESS_IDENTITY_MISMATCH")
            shutil.rmtree(account_dir)
            if account_dir.exists():
                raise ProvisionError("REVOKE_CLEANUP_FAILED")
        except ProvisionError:
            raise
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            raise ProvisionError("REVOKE_CLEANUP_FAILED") from None
    if not found:
        # Empty worker state is safe; a non-empty but unidentifiable tree is not.
        if root.exists() and any(root.iterdir()):
            raise ProvisionError("REVOKE_MARKER_NOT_FOUND")


def write_private(path: Path, text: str) -> None:
    # On Windows, protect the whole worker/data root with a dedicated service
    # account ACL (see README); chmod is additionally useful on POSIX tests.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
    except Exception:
        path.unlink(missing_ok=True)
        raise


def make_set_file(bridge_url: str, pairing_code: str, account: dict) -> str:
    # MT5 .set values follow EA input order; all entries use standard text form.
    return (f"InpBridgeBaseURL={bridge_url}\nInpPairingCode={pairing_code}\n"
            f"InpExpectedLogin={account['login']}\nInpExpectedServer={account['server']}\n"
            f"InpExpectedAccountMode={account['account_mode']}\n"
            "InpSymbols=EURUSD\nInpTimeframes=M5\nInpRiskPercent=0.25\n"
            "InpMaxDailyLossPercent=2.0\nInpMaxConcurrentPositions=2\n"
            "InpPollSeconds=5\nInpScanSeconds=10\nInpHeartbeatSeconds=5\n"
            "InpMagic=5271026\nInpDeviationPoints=20\n")


def ini_text(job: dict, access_type: str, symbol: str) -> str:
    account = job["account"]
    allow_trade = "1" if access_type == "MASTER" else "0"
    return ("[Common]\n"
            f"Login={account['login']}\nServer={account['server']}\nPassword={account['password']}\n"
            "KeepPrivate=1\nNewsEnable=0\nProxyEnable=0\n"
            "\n[Experts]\n"
            f"AllowLiveTrading={allow_trade}\nAllowDllImport=0\nEnabled=1\nAccount=1\nProfile=1\n"
            "\n[StartUp]\nExpert=DestinedFXTraderEA\n"
            f"Symbol={symbol}\nPeriod=M5\nExpertParameters=DestinedFXTraderEA.set\n")


def compile_ea(metaeditor: Path, source: Path, output: Path, cwd: Path) -> None:
    if not metaeditor.is_file():
        raise ProvisionError("EA_COMPILER_MISSING")
    # MetaEditor receives paths only; capture output to a pipe and discard it,
    # since compiler diagnostics may echo source lines or local paths.
    try:
        p = subprocess.run([str(metaeditor), f"/compile:{source}", f"/log:{cwd / 'compile.log'}"],
                           cwd=str(cwd), shell=False, timeout=180, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ProvisionError("EA_COMPILE_FAILED") from None
    if p.returncode != 0 or not output.is_file() or output.stat().st_size == 0:
        raise ProvisionError("EA_COMPILE_FAILED")
    (cwd / "compile.log").unlink(missing_ok=True)


def _provision(job: dict, settings: dict, *, launch: bool = True) -> Path:
    validate_job(job)
    root = Path(settings["data_root"]).resolve()
    install = Path(settings["mt5_install"]).resolve()
    terminal = Path(settings["terminal_exe"]).resolve()
    if not terminal.is_file():
        raise ProvisionError("TERMINAL_MISSING")
    for _, record in terminal_records(root):
        if record.get("pairing_id") == job["pairing_id"] and record.get("status") not in {"RESTART_LIMIT", "FAILED", "REVOKED"}:
            raise ProvisionError("DUPLICATE_TERMINAL")
    account_dir = safe_child(root, job["job_id"])
    account_dir.mkdir(parents=True, exist_ok=False)
    # MT5 /portable keeps its data beside the executable; clone the installation
    # so neither terminal files nor account data are shared across accounts.
    terminal_home = account_dir / "terminal"
    try:
        shutil.copytree(install, terminal_home)
    except OSError:
        raise ProvisionError("TERMINAL_STAGE_FAILED") from None
    account_terminal = terminal_home / terminal.name
    if not account_terminal.is_file():
        raise ProvisionError("TERMINAL_MISSING")
    experts = terminal_home / "MQL5" / "Experts"
    experts.mkdir(parents=True, exist_ok=True)
    source = Path(settings["ea_source"]).resolve()
    if not source.is_file():
        raise ProvisionError("EA_SOURCE_MISSING")
    staged_source = experts / "DestinedFXTraderEA.mq5"
    shutil.copy2(source, staged_source)
    compiled = experts / "DestinedFXTraderEA.ex5"
    metaeditor = terminal_home / Path(settings["metaeditor_exe"]).name
    # Existing trusted build is accepted; otherwise compilation is mandatory.
    supplied = settings.get("compiled_ea")
    if supplied and Path(supplied).is_file():
        shutil.copy2(Path(supplied), compiled)
    else:
        compile_ea(metaeditor, staged_source, compiled, account_dir)
    presets = terminal_home / "MQL5" / "Presets"
    presets.mkdir(parents=True, exist_ok=True)
    write_private(presets / "DestinedFXTraderEA.set", make_set_file(job["bridge_url"], job["pairing_code"], job["account"]))
    # Credentials exist only in this short-lived INI until process creation.
    ini = account_dir / f"startup-{uuid.uuid4().hex}.ini"
    write_private(ini, ini_text(job, job["account"]["access_type"], settings.get("symbol", "EURUSD")))
    if not launch:
        ini.unlink(missing_ok=True)
        return account_dir
    args = [str(account_terminal), f"/config:{ini}", "/portable"]
    try:
        proc = subprocess.Popen(args, cwd=str(terminal_home), shell=False, stdin=subprocess.DEVNULL,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True)
        write_private(terminal_home / ".destinedfx-worker.json",
                      json.dumps({"job_id": job["job_id"], "pairing_id": job["pairing_id"], "account_dir": str(account_dir),
                                  "pid": proc.pid, "executable": str(account_terminal), "restart_count": 0,
                                  "status": "AUTHENTICATING", "created": time.time()}, separators=(",", ":")))
    except OSError:
        ini.unlink(missing_ok=True)
        raise ProvisionError("TERMINAL_LAUNCH_FAILED") from None
    finally:
        # Terminal reads /config synchronously during startup; remove as soon as
        # CreateProcess returns. persisted broker login material is terminal-owned.
        ini.unlink(missing_ok=True)
    return account_dir


def provision(job: dict, settings: dict, *, launch: bool = True) -> Path:
    """Provision with cleanup of unlaunched partial trees; preserve owned live trees for review."""
    root = Path(settings["data_root"]).resolve()
    account_dir = safe_child(root, job["job_id"])
    try:
        return _provision(job, settings, launch=launch)
    except Exception:
        marker = account_dir / "terminal" / ".destinedfx-worker.json"
        if not marker.exists() and account_dir.exists():
            shutil.rmtree(account_dir, ignore_errors=False)
        raise


def run_once(settings: dict, operator_token: str, worker_id: str) -> None:
    base = settings["control_plane_url"].rstrip("/")
    job = request_json(f"{base}/v1/worker/jobs/claim", operator_token, "POST", {"worker_id": worker_id})
    if job.get("job") is None and not job.get("job_id"):
        return
    job_id = job.get("job_id") if isinstance(job, dict) else None
    if not isinstance(job_id, str) or not SAFE_ID.fullmatch(job_id):
        raise ProvisionError("INVALID_JOB")
    if job.get("kind") == "REVOKE":
        try:
            revoke_pairing(settings, job.get("pairing_id", ""))
            post_state(base, operator_token, job_id, worker_id, "REVOKED")
            LOG.info("Revocation completed for pairing %s", job.get("pairing_id", "unknown"))
        except ProvisionError as exc:
            try:
                post_state(base, operator_token, job_id, worker_id, "FAILED", exc.code, "Revocation failed safely; operator review is required")
            except ProvisionError:
                pass
            LOG.error("Revocation failed for job %s (%s)", job_id, exc.code)
        return
    try:
        job = validate_job(job)
        post_state(base, operator_token, job_id, worker_id, "PROVISIONING")
        provision(job, settings)
        # A process launch/authentication attempt is not evidence of a fresh EA
        # heartbeat, so READY is only for a server-observed authenticated state.
        post_state(base, operator_token, job_id, worker_id, "AUTHENTICATING")
        terminal_status = wait_for_terminal(base, operator_token, job_id, worker_id,
                                            int(settings.get("auth_timeout_seconds", 300)))
        if terminal_status == "READY":
            LOG.info("Terminal authenticated and fresh for job %s", job_id)
        elif terminal_status == "FAILED":
            quarantine_terminal(settings, job_id)
            LOG.error("Terminal authentication failed for job %s", job_id)
        elif terminal_status == "TIMEOUT":
            post_state(base, operator_token, job_id, worker_id, "FAILED", "TERMINAL_AUTH_TIMEOUT", "Terminal did not authenticate in time")
            quarantine_terminal(settings, job_id)
            LOG.error("Terminal authentication timed out for job %s", job_id)
    except ProvisionError as exc:
        try:
            post_state(base, operator_token, job_id, worker_id, "FAILED", exc.code, "Provisioning failed safely")
        except ProvisionError:
            pass
        LOG.error("Provisioning failed for job %s (%s)", job_id, exc.code)
    except Exception:
        try:
            post_state(base, operator_token, job_id, worker_id, "FAILED", "WORKER_INTERNAL_ERROR", "Provisioning failed safely")
        except ProvisionError:
            pass
        LOG.error("Provisioning failed for job %s (WORKER_INTERNAL_ERROR)", job_id)


def load_settings(path: Path) -> dict:
    try:
        with path.open("rb") as f:
            cfg = tomllib.load(f)
        required = ("control_plane_url", "data_root", "mt5_install", "terminal_exe", "metaeditor_exe", "ea_source")
        if any(not isinstance(cfg.get(k), str) or not cfg[k] for k in required):
            raise ValueError
        normalized = validate_origin(cfg["control_plane_url"])
        if normalized is None:
            raise ValueError
        cfg["control_plane_url"] = normalized
        return cfg
    except (OSError, ValueError, tomllib.TOMLDecodeError, KeyError):
        raise ProvisionError("INVALID_WORKER_CONFIG") from None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path("worker.toml"))
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    if os.name != "nt":
        LOG.error("Windows worker required")
        return 2
    try:
        config = load_settings(args.config)
        token = os.environ.get("DESTINEDFX_WORKER_TOKEN", "")
        if not token:
            raise ProvisionError("WORKER_TOKEN_MISSING")
        worker_id = os.environ.get("DESTINEDFX_WORKER_ID", "") or str(uuid.uuid4())
        if not SAFE_ID.fullmatch(worker_id):
            raise ProvisionError("INVALID_WORKER_ID")
        while True:
            try:
                supervise_terminals(config, config["control_plane_url"], token, worker_id)
                run_once(config, token, worker_id)
            except ProvisionError as exc:
                LOG.error("Worker cycle failed (%s)", exc.code)
            if args.once:
                return 0
            time.sleep(max(2, int(config.get("poll_seconds", 5))))
    except ProvisionError as exc:
        LOG.error("Worker startup failed (%s)", exc.code)
        return 2


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    raise SystemExit(main())
