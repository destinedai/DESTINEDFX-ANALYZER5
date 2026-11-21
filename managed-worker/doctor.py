#!/usr/bin/env python3
"""Shared MT5 doctor helpers.

The doctor is intentionally read-only: it inspects a paired terminal and never
submits a broker order.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

import worker


def safe_request(url: str, token: str | None = None, timeout: int = 8) -> tuple[int, dict[str, Any]]:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    cf_id = os.getenv("CF_ACCESS_CLIENT_ID", "")
    cf_secret = os.getenv("CF_ACCESS_CLIENT_SECRET", "")
    if cf_id and cf_secret:
        headers["CF-Access-Client-Id"] = cf_id
        headers["CF-Access-Client-Secret"] = cf_secret
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return int(response.status), json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        try:
            return int(error.code), json.loads(error.read().decode("utf-8"))
        except Exception:
            return int(error.code), {}
    except Exception:
        return 0, {}


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

            # MT5 trade_mode identifies the account type, not whether trading is
            # permitted. DEMO is enum value 0 and can still be fully tradable.
            # Permission comes from terminal_info.trade_allowed plus the account's
            # trade_allowed and trade_expert flags, while DEMO/LIVE is reported
            # independently for identity/mode verification.
            trade_mode = int(getattr(info, "trade_mode", -1))
            account_mode = {0: "DEMO", 1: "CONTEST", 2: "LIVE"}.get(trade_mode, "UNKNOWN")
            terminal_connected = bool(getattr(terminal_info, "connected", False))
            trade_allowed = bool(
                account_mode in ("DEMO", "LIVE")
                and terminal_connected
                and getattr(terminal_info, "trade_allowed", False)
                and getattr(info, "trade_allowed", False)
                and getattr(info, "trade_expert", False)
            )

            result["python_mt5_check"] = "CONNECTED_TO_TERMINAL"
            result["reported"] = {
                "login": str(getattr(info, "login", "")),
                "server": str(getattr(info, "server", "")),
                "company": str(getattr(info, "company", "") or getattr(terminal_info, "company", "")),
                "currency": str(getattr(info, "currency", "")),
                "account_mode": account_mode,
                "balance": float(getattr(info, "balance", 0.0)),
                "equity": float(getattr(info, "equity", 0.0)),
                "margin": float(getattr(info, "margin", 0.0)),
                "free_margin": float(getattr(info, "margin_free", 0.0)),
                "margin_level": float(getattr(info, "margin_level", 0.0)),
                "leverage": int(getattr(info, "leverage", 0)),
                "terminal_connected": terminal_connected,
                "trade_allowed": trade_allowed,
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
    marker = markers[0][1] if markers else None
    base = settings["control_plane_url"].rstrip("/")
    status_code, status = safe_request(f"{base}/health/ready")
    job_id = str(marker.get("job_id", "")) if marker else ""
    job_status_code, job_status = safe_request(f"{base}/v1/worker/jobs/{job_id}/status", token=token) if job_id else (0, {})
    output = {
        "broker": brand,
        "doctor_status": "BLOCKED",
        "bridge_reachability": "REACHABLE" if status_code == 200 else "UNREACHABLE",
        "worker_status": job_status.get("status", "UNKNOWN"),
        "pairing_state": "PAIRING_OR_AUTHENTICATION_REQUIRED",
        "terminal_path": {"path": str(terminal), "found": terminal.exists()},
        "metaeditor_path": {"path": str(metaeditor), "found": metaeditor.exists()},
        "reported_identity": {
            "login": marker.get("expected_login", "UNKNOWN") if marker else "UNKNOWN",
            "server": marker.get("expected_server", "UNKNOWN") if marker else "UNKNOWN",
            "company": marker.get("expected_company", "UNKNOWN") if marker else "UNKNOWN",
            "currency": marker.get("expected_currency", "UNKNOWN") if marker else "UNKNOWN",
            "identity_evidence": "UNVERIFIED",
        },
        "python_mt5": {"python_mt5_check": "NOT RUN", "reported": {}},
        "webrequest_allowed": "NO",
    }
    if args.python_check and terminal.exists():
        output["python_mt5"] = mt5_check(terminal, args.login, args.password, args.server)
    ready = (
        status_code == 200
        and job_status_code == 200
        and job_status.get("status") == "READY"
        and terminal.exists()
        and marker is not None
    )
    if ready:
        output["doctor_status"] = "READY"
        output["pairing_state"] = "PAIRED"
        output["reported_identity"]["identity_evidence"] = "SERVER_AUTHENTICATED_FRESH_ACCOUNT_MATCHED_HEARTBEAT"
        output["webrequest_allowed"] = "YES_BY_FRESH_EA_HEARTBEAT"
    return output


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read-only DestinedFX managed worker doctor")
    parser.add_argument("--config", required=True)
    parser.add_argument("--broker", default="ALL")
    parser.add_argument("--worker-token", default=os.getenv("DESTINEDFX_WORKER_TOKEN", ""))
    parser.add_argument("--python-check", action="store_true")
    parser.add_argument("--login")
    parser.add_argument("--password")
    parser.add_argument("--server")
    args = parser.parse_args(argv)
    settings = worker.load_settings(Path(args.config))
    brands = list(settings["broker_terminals"]) if args.broker == "ALL" else [args.broker]
    brokers = [inspect_one(brand, settings, args.worker_token, args) for brand in brands]
    print(json.dumps({"brokers": brokers}, indent=2, sort_keys=True))
    return 0 if brokers and all(item["doctor_status"] == "READY" for item in brokers) else 2


if __name__ == "__main__":
    raise SystemExit(main())
