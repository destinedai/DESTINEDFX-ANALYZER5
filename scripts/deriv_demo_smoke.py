#!/usr/bin/env python3
"""Read-only Deriv Options DEMO smoke harness.

Credentials are entered locally through hidden prompts or environment variables.
The script never prints tokens, passwords, authorization headers, OTPs, or raw
broker responses. It lists active accounts, requests a fresh account OTP,
reads balance/portfolio, and requests one public tick. It never calls any order
endpoint.

Requires Python 3.10+ and the optional websocket-client package for the
account-scoped balance/portfolio and public tick checks:
    python -m pip install websocket-client
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "https://api.derivws.com"
WS_BASE = "wss://api.derivws.com/trading/v1/options/ws/"
PUBLIC_WS = "wss://api.derivws.com/trading/v1/options/ws/public"


def redacted(value: str, keep: int = 4) -> str:
    value = str(value or "")
    return value[:keep] + "..." if len(value) > keep else "[redacted]"


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
        suffix = f", {code}" if code else ""
        raise RuntimeError(f"Deriv REST rejected the request (HTTP {error.code}{suffix}).") from None
    except (urllib.error.URLError, TimeoutError) as error:
        raise RuntimeError(f"Deriv REST network request failed ({type(error).__name__}).") from error
    except (ValueError, UnicodeError):
        raise RuntimeError("Deriv returned a response that was not valid JSON.") from None


def open_authenticated_socket(url: str):
    try:
        import websocket  # type: ignore
    except ImportError as error:
        raise RuntimeError("Install websocket-client locally to complete the WebSocket checks; no broker request was made after the OTP.") from error
    return websocket.create_connection(url, timeout=15, enable_multithread=False)


def new_request_id() -> int:
    """Return the integer req_id required by the Deriv JSON schema."""
    return int(uuid.uuid4().int % 2_000_000_000)


def validate_authenticated_ws_url(url: str, mode: str = "demo") -> bool:
    parsed = urllib.parse.urlparse(str(url or "").strip())
    expected_path = f"/trading/v1/options/ws/{mode}"
    query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
    return (
        parsed.scheme == "wss"
        and parsed.netloc == "api.derivws.com"
        and parsed.path == expected_path
        and bool(query.get("otp", [""])[0])
    )


def recv_matching(socket, req_id: int, expected_msg_type: str, timeout: float = 15.0) -> dict:
    """Ignore unrelated frames and return only the expected correlated response."""
    deadline = time.monotonic() + timeout
    socket.settimeout(timeout)
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError(f"Deriv WebSocket timed out waiting for {expected_msg_type}.")
        socket.settimeout(remaining)
        try:
            value = json.loads(socket.recv())
        except Exception as error:
            raise RuntimeError(f"Deriv WebSocket returned no usable {expected_msg_type} response ({type(error).__name__}).") from error
        if not isinstance(value, dict):
            continue
        if value.get("req_id") != req_id:
            continue
        if value.get("error") or value.get("errors"):
            raise RuntimeError(f"Deriv WebSocket rejected the {expected_msg_type} request.")
        if value.get("msg_type") != expected_msg_type:
            raise RuntimeError(f"Deriv WebSocket returned an unexpected response for {expected_msg_type}.")
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
    if not validate_authenticated_ws_url(url, "demo"):
        raise SystemExit("Deriv returned an invalid DEMO authenticated WebSocket endpoint; no connection was attempted.")

    socket = open_authenticated_socket(url)
    try:
        balance_id = new_request_id()
        socket.send(json.dumps({"balance": 1, "subscribe": 0, "req_id": balance_id}))
        balance = recv_matching(socket, balance_id, "balance")
        portfolio_id = new_request_id()
        socket.send(json.dumps({"portfolio": 1, "req_id": portfolio_id}))
        portfolio = recv_matching(socket, portfolio_id, "portfolio")
    finally:
        socket.close()

    public = open_authenticated_socket(PUBLIC_WS)
    try:
        tick_id = new_request_id()
        public.send(json.dumps({"ticks": args.symbol, "subscribe": 0, "req_id": tick_id}))
        tick = recv_matching(public, tick_id, "tick")
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
