#!/usr/bin/env python3
"""Read-only Deriv Options DEMO smoke harness.

Credentials are entered locally through hidden prompts or environment variables.
The script never prints tokens, passwords, authorization headers, OTPs, or raw
broker responses. It lists active accounts, requests a fresh account OTP,
reads balance/portfolio, and requests one public tick. It never calls buy,
sell, proposal, or any order endpoint.

Requires Python 3.10+ and the optional websocket-client package for the
account-scoped balance/portfolio and public tick checks:
    python -m pip install websocket-client
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "https://api.derivws.com"
WS_BASE = "wss://api.derivws.com/trading/v1/options/ws/"


def redacted(value: str, keep: int = 4) -> str:
    value = str(value or "")
    return value[:keep] + "…" if len(value) > keep else "[redacted]"


def request_json(path: str, app_id: str, token: str, method: str = "GET") -> dict:
    request = urllib.request.Request(
        BASE + path,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Deriv-App-ID": app_id,
            "Accept": "application/json",
            "Content-Type": "application/json",
        },
        data=b"" if method != "GET" else None,
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        try:
            body = json.loads(error.read().decode("utf-8"))
            errors = body.get("errors") or []
            code = errors[0].get("code", "") if errors else ""
        except Exception:
            code = ""
        raise RuntimeError(f"Deriv REST rejected the request (HTTP {error.code}{', ' + code if code else ''}).") from None
    except (urllib.error.URLError, TimeoutError) as error:
        raise RuntimeError(f"Deriv REST network request failed: {type(error).__name__}.") from error
    except (ValueError, UnicodeError):
        raise RuntimeError("Deriv returned a response that was not valid JSON.") from None


def open_authenticated_socket(url: str):
    try:
        import websocket  # type: ignore
    except ImportError as error:
        raise RuntimeError("Install websocket-client locally to complete the WebSocket checks; no broker request was made after the OTP.") from error
    return websocket.create_connection(url, timeout=15, enable_multithread=False)


def recv_json(socket, timeout: float = 15.0) -> dict:
    socket.settimeout(timeout)
    try:
        value = json.loads(socket.recv())
    except Exception as error:
        raise RuntimeError(f"Deriv WebSocket returned no usable response ({type(error).__name__}).") from error
    if not isinstance(value, dict):
        raise RuntimeError("Deriv WebSocket returned an unexpected response shape.")
    if value.get("error") or value.get("errors"):
        raise RuntimeError("Deriv WebSocket rejected a read-only request.")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only Deriv DEMO smoke check")
    parser.add_argument("--app-id", default=os.getenv("DERIV_APP_ID"))
    parser.add_argument("--token", default=os.getenv("DERIV_PAT"))
    parser.add_argument("--account-id", default=os.getenv("DERIV_DEMO_ACCOUNT_ID"))
    parser.add_argument("--symbol", default="R_100", help="Public tick symbol; default R_100")
    args = parser.parse_args()

    app_id = (args.app_id or input("Deriv App ID: ")).strip()
    token = (args.token or getpass.getpass("Deriv DEMO PAT (hidden): ")).strip()
    if not app_id.isdigit() or app_id == "1089":
        raise SystemExit("Refusing to run: enter the current numeric App ID registered for this app; legacy 1089 is rejected.")
    if not token:
        raise SystemExit("Refusing to run: a PAT is required locally.")

    accounts_body = request_json("/trading/v1/options/accounts", app_id, token)
    rows = accounts_body.get("data") if isinstance(accounts_body, dict) else None
    active_demo = [
        row for row in (rows or [])
        if isinstance(row, dict)
        and str(row.get("status", "")).lower() == "active"
        and str(row.get("account_type", "")).lower() == "demo"
    ]
    if not active_demo:
        raise SystemExit("No active DEMO Options account was returned. Check PAT account-read/trade scope and DEMO availability.")
    requested = (args.account_id or "").strip()
    account = next((row for row in active_demo if str(row.get("account_id", "")) == requested), None) if requested else None
    if account is None:
        if len(active_demo) != 1:
            raise SystemExit("More than one DEMO account was returned; rerun locally with --account-id for the exact broker account.")
        account = active_demo[0]
    account_id = str(account.get("account_id", "")).strip()
    if not account_id:
        raise SystemExit("Deriv returned a DEMO row without an account ID.")

    otp = request_json(
        "/trading/v1/options/accounts/" + urllib.parse.quote(account_id, safe="") + "/otp",
        app_id,
        token,
        method="POST",
    )
    url = str((otp.get("data") or {}).get("url", "")).strip()
    parsed = urllib.parse.urlparse(url)
    if not (url.startswith(WS_BASE) and parsed.scheme == "wss" and parsed.netloc == "api.derivws.com" and parsed.query):
        raise SystemExit("Deriv returned an invalid authenticated WebSocket endpoint; no connection was attempted.")

    socket = open_authenticated_socket(url)
    try:
        balance_id = str(uuid.uuid4().int % 2_000_000_000)
        socket.send(json.dumps({"balance": 1, "subscribe": 0, "req_id": balance_id}))
        balance = recv_json(socket)
        portfolio_id = str(uuid.uuid4().int % 2_000_000_000)
        socket.send(json.dumps({"portfolio": 1, "req_id": portfolio_id}))
        portfolio = recv_json(socket)
    finally:
        socket.close()

    public = open_authenticated_socket("wss://api.derivws.com/trading/v1/options/ws/public")
    try:
        tick_id = str(uuid.uuid4().int % 2_000_000_000)
        public.send(json.dumps({"ticks": args.symbol, "subscribe": 0, "req_id": tick_id}))
        tick = recv_json(public)
    finally:
        public.close()

    balance_obj = balance.get("balance") or {}
    portfolio_obj = portfolio.get("portfolio") or {}
    tick_obj = tick.get("tick") or {}
    print(json.dumps({
        "result": "PASS_READ_ONLY_DEMO",
        "account": redacted(account_id),
        "account_type": str(account.get("account_type", "")).upper(),
        "currency": str(account.get("currency", "")),
        "balance_received": bool(balance_obj),
        "portfolio_received": bool(portfolio_obj),
        "tick_received": bool(tick_obj),
        "tick_symbol": tick_obj.get("symbol", args.symbol),
        "checked_at_epoch": int(time.time()),
        "orders_sent": False,
        "secrets_printed": False,
    }, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit("Stopped locally; no order was sent.")
    except RuntimeError as error:
        raise SystemExit(str(error))
