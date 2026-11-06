"""Read-only Windows MT5 connection doctor.

The doctor never places orders, edits terminal configuration, or prints secrets.
A successful HTTP request is not treated as a broker connection. READY requires
fresh terminal telemetry reported by the EA and a current worker job status.
"""
from __future__ import annotations
import argparse
import getpass
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

try:
    import worker
except ImportError:
    from . import worker  # type: ignore


def safe_request(url: str, token: str | None = None, timeout: int = 8) -> tuple[int, dict[str, Any]]:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    access_id = os.environ.get("CF_ACCESS_CLIENT_ID", "").strip()
    access_secret = os.environ.get("CF_ACCESS_CLIENT_SECRET", "").strip()
    if access_id and access_secret:
        headers["Cf-Access-Client-Id"] = access_id
        headers["Cf-Access-Client-Secret"] = access_secret
    request = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read(256 * 1024)
            value = json.loads(raw.decode("utf-8"))
            return response.status, value if isinstance(value, dict) else {}
    except urllib.error.HTTPError as error:
        try:
            value = json.loads(error.read(256 * 1024).decode("utf-8"))
        except Exception:
            value = {}
        return error.code, value if isinstance(value, dict) else {}
    except Exception:
        return 0, {}


def file_check(path: str, label: str) -> dict[str, Any]:
    p = Path(path).expanduser()
    return {"label": label, "path": str(p), "found": p.is_file(), "detail": "OK" if p.is_file() else "MISSING"}


def bridge_check(base: str, marker: dict[str, Any] | None, token: str) -> dict[str, Any]:
    result: dict[str, Any] = {"bridge_reachability": "BLOCKED", "pairing_state": "UNKNOWN", "worker_status": "UNKNOWN"}
    code, body = safe_request(base.rstrip("/") + "/health/ready")
    result["bridge_http_status"] = code or None
    result["bridge_reachability"] = "REACHABLE" if code else "UNREACHABLE"
    result["bridge_readiness"] = body.get("status") or (body.get("detail") or {}).get("code", "UNKNOWN")
    if marker and marker.get("job_id") and token:
        job = urllib.parse.quote(str(marker["job_id"]), safe="")
        worker_id = urllib.parse.quote(os.environ.get("DESTINEDFX_WORKER_ID", "doctor"), safe="")
        status, body = safe_request(f"{base.rstrip('/')}/v1/worker/jobs/{job}/status?worker_id={worker_id}", token)
        result["worker_status_http"] = status or None
        result["worker_status"] = body.get("status", "UNKNOWN")
        result["worker_error"] = body.get("error_code") or ""
        result["pairing_state"] = "PAIRED" if status == 200 and body.get("status") == "READY" else "PAIRING_OR_AUTHENTICATION_REQUIRED"
    elif marker:
        result["pairing_state"] = "PAIRING_PRESENT_WORKER_TOKEN_REQUIRED"
    else:
        result["pairing_state"] = "NO_LOCAL_TERMINAL_PAIRING"
    return result


def mt5_check(terminal: Path, login: str | None, password: str | None, server: str | None) -> dict[str, Any]:
    result: dict[str, Any] = {"python_mt5_check": "NOT RUN", "reported": {}}
    try:
        import MetaTrader5 as mt5  # type: ignore
    except Exception:
        result["python_mt5_check"] = "BLOCKED: MetaTrader5 Python package is not installed"
        return result
    try:
        kwargs: dict[str, Any] = {"path": str(terminal), "timeout": 8000}
        if login and password and server:
            kwargs.update(login=int(login), password=password, server=server)
        if not mt5.initialize(**kwargs):
            result["python_mt5_check"] = "ERROR: initialize failed"
            return result
        try:
            if login and password and server and not mt5.login(int(login), password=password, server=server, timeout=8000):
                result["python_mt5_check"] = "ERROR: login failed"
                return result
            info = mt5.account_info()
            terminal_info = mt5.terminal_info()
            if info is None or terminal_info is None:
                result["python_mt5_check"] = "ERROR: account or terminal info unavailable"
                return result
            result["python_mt5_check"] = "CONNECTED_TO_TERMINAL"
            result["reported"] = {
                "login": str(getattr(info, "login", "")),
                "server": str(getattr(info, "server", "")),
                "company": str(getattr(terminal_info, "company", "")),
                "balance": float(getattr(info, "balance", 0.0)),
                "equity": float(getattr(info, "equity", 0.0)),
                "free_margin": float(getattr(info, "margin_free", 0.0)),
                "leverage": int(getattr(info, "leverage", 0)),
                "trade_allowed": bool(getattr(terminal_info, "trade_allowed", False) and getattr(info, "trade_mode", 0) != 0),
            }
        finally:
            mt5.shutdown()
    except Exception as error:
        result["python_mt5_check"] = f"ERROR: {type(error).__name__}"
    return result


def inspect_one(brand: str, settings: dict[str, Any], token: str, args: argparse.Namespace) -> dict[str, Any]:
    resolved = worker.settings_for_broker(settings, brand)
    terminal = Path(resolved["terminal_exe"]).expanduser()
    metaeditor = Path(resolved["metaeditor_exe"]).expanduser()
    data_root = Path(settings["data_root"]).expanduser().resolve()
    markers = [(path, record) for path, record in worker.terminal_records(data_root) if str(record.get("broker_brand", "")).upper() == brand]
    marker_path, marker = markers[-1] if markers else (None, None)
    tailscale_path = shutil.which("tailscale.exe") or shutil.which("tailscale")
    tailscale_status = "NOT INSTALLED"
    if tailscale_path:
        try:
            proc = subprocess.run([tailscale_path, "status"], capture_output=True, text=True, timeout=8)
            tailscale_status = "CONNECTED" if proc.returncode == 0 else "DISCONNECTED"
        except Exception:
            tailscale_status = "ERROR"
    output: dict[str, Any] = {
        "broker": brand,
        "python": {"found": bool(shutil.which("py.exe") or shutil.which("python3") or shutil.which("python")), "required": "3.11+"},
        "tailscale": tailscale_status,
        "configured_capacity": settings.get("max_terminals", "UNKNOWN"),
        "capacity_note": "Measure safe simultaneous terminal capacity on this host; configured capacity is not proof.",
        "terminal_path": file_check(str(terminal), "terminal64.exe"),
        "metaeditor_path": file_check(str(metaeditor), "metaeditor64.exe"),
        "ea_source": file_check(str(Path(settings["ea_source"])), "DestinedFXTraderEA.mq5"),
        "ea_attached": bool(marker and marker.get("status") not in {"FAILED", "REVOKED", "OPERATOR_REVIEW"}),
        "webrequest_allowed": "UNKNOWN_UNTIL_FRESH_EA_HEARTBEAT",
        "marker_status": marker.get("status", "NO_MARKER") if marker else "NO_MARKER",
        "marker_path": str(marker_path) if marker_path else "",
    }
    output.update(bridge_check(str(settings["control_plane_url"]), marker, token))
    if output["pairing_state"] == "PAIRED" and output["worker_status"] == "READY":
        output["webrequest_allowed"] = "YES_BY_FRESH_EA_HEARTBEAT"
    if args.python_check:
        output.update(mt5_check(terminal, args.login, args.password, args.server))
    output["reported_identity"] = {
        "login": marker.get("expected_login", "UNKNOWN") if marker else "UNKNOWN",
        "server": marker.get("expected_server", "UNKNOWN") if marker else "UNKNOWN",
        "mode": marker.get("account_mode", "UNKNOWN") if marker else "UNKNOWN",
        "company": marker.get("expected_company", "UNKNOWN") if marker else "UNKNOWN",
        "currency": marker.get("expected_currency", "UNKNOWN") if marker else "UNKNOWN",
        "identity_evidence": "SERVER_AUTHENTICATED_FRESH_ACCOUNT_MATCHED_HEARTBEAT" if output["worker_status"] == "READY" and output["pairing_state"] == "PAIRED" else "UNVERIFIED",
        "trade_allowed": "UNKNOWN until fresh EA heartbeat",
        "last_heartbeat_age": "UNKNOWN until bridge state is queried with app credentials",
    }
    ready = (
        output["terminal_path"]["found"] and output["metaeditor_path"]["found"] and
        output["ea_source"]["found"] and output["ea_attached"] and
        output["webrequest_allowed"] == "YES_BY_FRESH_EA_HEARTBEAT" and output["pairing_state"] == "PAIRED" and
        output["worker_status"] == "READY" and output["tailscale"] == "CONNECTED"
    )
    output["doctor_status"] = "READY" if ready else "BLOCKED"
    if not ready:
        output["reason"] = "Require valid Python, terminal and MetaEditor files, connected Tailscale, EA attachment, allowlisted WebRequest evidenced by a fresh authenticated account-matched EA heartbeat, and worker READY; HTTP reachability alone is insufficient."
    return output


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read-only DestinedFX MT5 connection doctor")
    parser.add_argument("--config", type=Path, default=Path("worker.toml"))
    parser.add_argument("--broker", choices=sorted(worker.SUPPORTED_BROKER_BRANDS), action="append")
    parser.add_argument("--python-check", action="store_true", help="also query the installed MetaTrader5 Python package read-only")
    parser.add_argument("--login", help="optional login for the read-only Python check; never printed")
    parser.add_argument("--server", help="optional server for the read-only Python check; never printed")
    parser.add_argument("--password-stdin", action="store_true", help="read the optional Python-check password from the console without printing it")
    args = parser.parse_args(argv)
    try:
        settings = worker.load_settings(args.config)
        token = os.environ.get("DESTINEDFX_WORKER_TOKEN", "")
        if args.python_check and args.login and args.server and args.password_stdin:
            args.password = getpass.getpass("MT5 read-only password (not stored or printed): ")
        else:
            args.password = None
        brands = args.broker or sorted((settings.get("broker_terminals") or {settings.get("single_broker_brand"): {}}).keys())
        results = []
        for brand in brands:
            try:
                results.append(inspect_one(str(brand).upper(), settings, token, args))
            except worker.ProvisionError as error:
                results.append({"broker": str(brand).upper(), "doctor_status": "BLOCKED", "reason": error.code})
        print(json.dumps({"checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "brokers": results}, indent=2, sort_keys=True))
        return 0 if results and all(item.get("doctor_status") == "READY" for item in results) else 2
    except worker.ProvisionError as error:
        print(json.dumps({"doctor_status": "BLOCKED", "reason": error.code}, sort_keys=True))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
