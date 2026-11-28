#!/usr/bin/env python3
"""DestinedFX REAL MT5 session preflight and explicit trade confirmation.

This owner tool never submits an order. LIVE authorization is bound to the
currently authenticated REAL MT5 MASTER login and the exact phrase
CONFIRM REAL TRADE. It does not require a PERSISTENT host, a DEMO pairing,
database backup checks, Management password step-up, or two MT5 terminals.
Per-user process isolation and durable order storage remain platform security
requirements; order submission still uses the current broker quote and risk data.
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable

BROKERS = ("FBS", "EXNESS", "HFM", "DERIV")
MAX_BYTES = 65536
CONFIRMATION = "CONFIRM REAL TRADE"


class PreflightError(RuntimeError):
    """Sanitized error code; never includes response bodies or secrets."""


def base_url(value: str, label: str = "BRIDGE") -> str:
    try:
        parsed = urllib.parse.urlsplit((value or "").strip().rstrip("/"))
        port = parsed.port
    except ValueError:
        raise PreflightError(f"{label}_URL_INVALID") from None
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
        or parsed.path not in ("", "/")
    ):
        raise PreflightError(f"{label}_URL_MUST_BE_HTTPS_ORIGIN")
    return f"https://{parsed.hostname.lower()}" + (f":{port}" if port else "")


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Do not forward an account bearer token to another origin."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _read_json(url: str, method: str = "GET", payload: dict[str, Any] | None = None,
               bearer: str | None = None, timeout: float = 8.0) -> tuple[int, dict[str, Any]]:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise PreflightError("AUTH_ENDPOINT_MUST_BE_HTTPS")
    headers = {"Accept": "application/json", "User-Agent": "DestinedFX-REAL-MT5/1.0"}
    if bearer is not None:
        if not bearer.strip() or any(char in bearer for char in "\r\n"):
            raise PreflightError("APP_PAIRING_TOKEN_INVALID")
        headers["Authorization"] = "Bearer " + bearer.strip()
    body = None
    if payload is not None:
        body = json.dumps(payload, separators=(",", ":"), allow_nan=False).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    opener = urllib.request.build_opener(NoRedirectHandler)
    try:
        with opener.open(request, timeout=timeout) as response:
            status, raw = int(response.status), response.read(MAX_BYTES + 1)
    except urllib.error.HTTPError as error:
        status = int(error.code)
        try:
            raw = error.read(MAX_BYTES + 1)
        except OSError:
            raw = b""
    except (OSError, TimeoutError, urllib.error.URLError):
        raise PreflightError("ENDPOINT_UNREACHABLE") from None
    if len(raw) > MAX_BYTES:
        raise PreflightError("ENDPOINT_RESPONSE_TOO_LARGE")
    try:
        value = json.loads(raw.decode()) if raw else {}
    except (UnicodeError, json.JSONDecodeError):
        raise PreflightError("ENDPOINT_RESPONSE_NOT_JSON") from None
    if not isinstance(value, dict):
        raise PreflightError("ENDPOINT_SCHEMA_INVALID")
    return status, value


def get_json(url: str) -> tuple[int, dict[str, Any]]:
    return _read_json(url)


def authenticated_json(url: str, method: str, payload: dict[str, Any] | None = None,
                       bearer: str | None = None, timeout: float = 8.0) -> tuple[int, dict[str, Any]]:
    return _read_json(url, method, payload, bearer, timeout)


def error_code(payload: dict[str, Any]) -> str:
    detail = payload.get("detail")
    code = detail.get("code") if isinstance(detail, dict) else None
    return code if isinstance(code, str) and re.fullmatch(r"[A-Z0-9_]{1,80}", code) else "HTTP_REQUEST_BLOCKED"


def run(management_url: str = "", bridge_url: str = "", *, brokers: tuple[str, ...] = BROKERS) -> dict[str, Any]:
    """Read-only infrastructure check. No host lifetime or backup is a LIVE gate."""
    bridge = base_url(bridge_url, "BRIDGE")
    checks: list[dict[str, Any]] = []
    config: dict[str, Any] = {}
    try:
        status, config = get_json(bridge + "/health/config")
        configured = status == 200 and config.get("status") == "configured"
        checks.append({
            "name": "bridge_config",
            "status": "PASS" if configured else "BLOCKED",
            "code": "BRIDGE_CONFIGURED" if configured else error_code(config) if status >= 400 else "BRIDGE_CONFIG_NOT_READY",
        })
        durable = config.get("database") == "ok" and config.get("managed_execution") == "configured"
        checks.append({
            "name": "durable_order_storage",
            "status": "PASS" if durable else "BLOCKED",
            "code": "ORDER_STORAGE_READY" if durable else "ORDER_STORAGE_NOT_READY",
        })
        isolation = config.get("multi_user_terminal_isolation") == "VERIFIED"
        checks.append({
            "name": "per_user_terminal_isolation",
            "status": "PASS" if isolation else "BLOCKED",
            "code": "PER_USER_ISOLATION_VERIFIED" if isolation else "PER_USER_TERMINAL_ISOLATION_REQUIRED",
        })
        max_slots = config.get("max_isolated_slots")
        capacity = isinstance(max_slots, int) and not isinstance(max_slots, bool) and max_slots >= 1
        checks.append({
            "name": "one_live_terminal_slot",
            "status": "PASS" if capacity else "BLOCKED",
            "code": "ONE_ISOLATED_TERMINAL_SLOT_AVAILABLE" if capacity else "LIVE_TERMINAL_SLOT_NOT_READY",
            "max_slots": max_slots if capacity else 0,
        })
        profile = config.get("host_profile", "UNKNOWN")
        checks.append({
            "name": "host_profile",
            "status": "PASS",
            "code": "PERSISTENT_OR_EPHEMERAL_ACCEPTED",
            "value": profile,
        })
    except PreflightError as error:
        checks.extend([
            {"name": "bridge_config", "status": "BLOCKED", "code": str(error)},
            {"name": "durable_order_storage", "status": "BLOCKED", "code": "BRIDGE_CONFIG_UNAVAILABLE"},
            {"name": "per_user_terminal_isolation", "status": "BLOCKED", "code": "BRIDGE_CONFIG_UNAVAILABLE"},
            {"name": "one_live_terminal_slot", "status": "BLOCKED", "code": "BRIDGE_CONFIG_UNAVAILABLE"},
        ])

    terminal_ready = False
    try:
        status, ready = get_json(bridge + "/health/ready")
        if status == 200 and ready.get("status") == "ready" and ready.get("verified_terminal_pairing") == "healthy":
            terminal_ready = True
            terminal_status, terminal_code = "PASS", "REAL_TERMINAL_READY"
        elif status == 503 and error_code(ready) == "NO_VERIFIED_TERMINAL_PAIRING":
            terminal_status, terminal_code = "PENDING", "LOGIN_TO_REAL_MT5_ACCOUNT"
        else:
            terminal_status, terminal_code = "BLOCKED", error_code(ready) if status >= 400 else "REAL_TERMINAL_NOT_READY"
    except PreflightError:
        terminal_status, terminal_code = "PENDING", "BRIDGE_READINESS_NOT_REACHABLE"
    checks.append({"name": "real_mt5_terminal", "status": terminal_status, "code": terminal_code})

    chosen = tuple(dict.fromkeys(value.upper() for value in brokers))
    valid_brokers = bool(chosen) and all(value in BROKERS for value in chosen)
    checks.append({
        "name": "broker_code_paths",
        "status": "PASS" if valid_brokers else "BLOCKED",
        "code": "SUPPORTED_BROKER_PATHS" if valid_brokers else "INVALID_BROKER_LIST",
        "brokers": list(chosen),
    })

    blockers = [row for row in checks if row["status"] == "BLOCKED"]
    if blockers:
        overall = "BLOCKED"
    elif terminal_ready:
        overall = "READY_FOR_REAL_CONFIRMATION"
    else:
        overall = "READY_FOR_REAL_LOGIN"
    return {
        "schema_version": 2,
        "overall_status": overall,
        "checks": checks,
        "non_actions": [
            "No LIVE state changed",
            "No broker credentials read",
            "No account evidence fabricated",
            "No order or trading command submitted",
        ],
        "required_for_REAL": [
            "The selected account logs in to the exact REAL MT5 broker server with MASTER/TRADE access",
            "The active terminal heartbeat reports that same REAL login and server",
            "The owner presses CONFIRM REAL TRADE in the Bot START flow",
        ],
        "order_safety": [
            "Each order still needs a fresh broker quote, supported symbol, valid size and protective stop/target",
            "Current EA risk controls and economic-calendar checks remain active at order execution",
        ],
        "host_profile": config.get("host_profile", "UNKNOWN"),
    }


def arm_live(bridge_url: str, *, credential_provider: Callable[[], Any],
             confirm_typed_phrase: Callable[[str], bool]) -> dict[str, Any]:
    """Bind one explicit confirmation to the active REAL MT5 account; never sends an order."""
    bridge = base_url(bridge_url, "BRIDGE")
    try:
        credentials = credential_provider()
    except Exception:
        raise PreflightError("LIVE_AUTH_INPUT_UNAVAILABLE") from None
    token = credentials[0] if isinstance(credentials, (tuple, list)) and credentials else credentials
    if not isinstance(token, str) or not token.strip():
        raise PreflightError("APP_PAIRING_TOKEN_REQUIRED")
    status, challenge = authenticated_json(bridge + "/v1/app/live-challenge", "POST", {}, bearer=token)
    if status != 200:
        raise PreflightError(error_code(challenge))
    challenge_id = str(challenge.get("challenge_id") or "")
    broker = str(challenge.get("broker_brand") or "").strip().upper()
    login = str(challenge.get("login") or "")
    server = str(challenge.get("server") or "").strip()
    mode = str(challenge.get("account_mode") or "").strip().upper()
    phrase = str(challenge.get("typed_confirmation") or "")
    if (
        not challenge_id or broker not in BROKERS or
        not re.fullmatch(r"[0-9]{1,20}", login) or not server or
        mode != "LIVE" or phrase != CONFIRMATION
    ):
        raise PreflightError("LIVE_CHALLENGE_IDENTITY_INVALID")
    try:
        confirmed = confirm_typed_phrase(CONFIRMATION) is True
    except Exception:
        confirmed = False
    if not confirmed:
        raise PreflightError("LIVE_TYPED_CONFIRMATION_MISMATCH")
    status, result = authenticated_json(bridge + "/v1/app/live-authorization", "POST", {
        "challenge_id": challenge_id,
        "typed_confirmation": CONFIRMATION,
    }, bearer=token)
    if status != 200:
        raise PreflightError(error_code(result))
    if result.get("authorized") is not True:
        raise PreflightError("LIVE_AUTHORIZATION_NOT_CONFIRMED")
    return {
        "authorized": True,
        "expires_at": result.get("expires_at"),
        "duration_seconds": result.get("duration_seconds"),
        "broker_brand": broker,
        "login": login,
        "server": server,
        "account_mode": "LIVE",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bridge-url", required=True, help="DestinedFX Bridge HTTPS origin")
    parser.add_argument("--management-url", default="", help="Optional Management API health origin; not used for LIVE step-up")
    parser.add_argument("--broker", action="append", choices=BROKERS, help="Repeat for selected brands; default is all four")
    parser.add_argument("--arm-live", action="store_true", help="Confirm the exact active REAL MT5 account. Does not submit an order.")
    args = parser.parse_args(argv)
    try:
        report = run(args.management_url, args.bridge_url, brokers=tuple(args.broker or BROKERS))
    except PreflightError as error:
        print(json.dumps({"overall_status": "BLOCKED", "fatal_code": str(error)}, indent=2))
        return 2
    print(json.dumps(report, indent=2, sort_keys=True))
    if report["overall_status"] == "BLOCKED":
        return 2
    if not args.arm_live:
        return 0

    print("This confirms REAL trading for the currently authenticated REAL MT5 account. It does not place an order.")
    def credentials() -> tuple[str]:
        token = os.environ.get("DESTINEDFX_APP_BEARER_TOKEN", "").strip()
        if not token:
            token = getpass.getpass("LIVE MT5 app-pairing bearer token (hidden): ").strip()
        return (token,)
    def confirm(phrase: str) -> bool:
        print("Type this exact account-specific phrase:")
        print(phrase)
        return input("> ").strip() == phrase
    try:
        result = arm_live(args.bridge_url, credential_provider=credentials, confirm_typed_phrase=confirm)
    except PreflightError as error:
        print(json.dumps({"live_authorization": "BLOCKED", "code": str(error)}, indent=2))
        return 2
    print(json.dumps({"live_authorization": result}, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
