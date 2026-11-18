#!/usr/bin/env python3
"""Read-only DestinedFX source and optional live-Bridge readiness audit.

This tool never logs in to a broker, submits a trade, changes host configuration,
or writes credentials. For a live Bridge probe, pass its HTTPS origin with
--bridge-url. For an authenticated app-state read, set
DESTINEDFX_AUDIT_APP_TOKEN in the local environment and pass
--authenticated-state. The token is never printed or persisted.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def read_text(root: Path, relative: str) -> str:
    try:
        return (root / relative).read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        return ""


def add_check(checks: list[dict[str, Any]], name: str, ok: bool, detail: str) -> None:
    checks.append({
        "name": name,
        "status": "PASS" if ok else "FAIL",
        "ok": bool(ok),
        "detail": detail,
    })


def integer_constant(source: str, name: str) -> int | None:
    match = re.search(
        rf"^\s*(?:internal\s+)?const\s+val\s+{re.escape(name)}\s*=\s*(\d[\d_]*)L?\s*$",
        source,
        flags=re.MULTILINE,
    )
    return int(match.group(1).replace("_", "")) if match else None


def audit_repository(root: Path = ROOT) -> dict[str, Any]:
    """Check source contracts; this is not a runtime or broker-connection test."""
    checks: list[dict[str, Any]] = []
    market = read_text(root, "app/src/main/java/com/example/data/market/MarketDataRepositoryImpl.kt")
    vm = read_text(root, "app/src/main/java/com/example/ui/DestinedFXViewModel.kt")
    signal = read_text(root, "app/src/main/java/com/example/domain/usecase/impl/SignalGenerationUseCaseImpl.kt")
    analyzer_ui = read_text(root, "app/src/main/java/com/example/ui/SimplifiedAnalyzerTab.kt")
    bot_ui = read_text(root, "app/src/main/java/com/example/ui/workstations/BotWorkstation.kt")
    bridge_client = read_text(root, "app/src/main/java/com/example/data/trade/MT5RemoteBridgeClient.kt")
    arming = read_text(root, "app/src/main/java/com/example/data/broker/ModeArmingPolicy.kt")
    ea = read_text(root, "mt5-ea/DestinedFXTraderEA.mq5")
    readme = read_text(root, "README.md")

    required = {
        "MarketDataRepositoryImpl.kt": market,
        "DestinedFXViewModel.kt": vm,
        "SignalGenerationUseCaseImpl.kt": signal,
        "SimplifiedAnalyzerTab.kt": analyzer_ui,
        "BotWorkstation.kt": bot_ui,
        "MT5RemoteBridgeClient.kt": bridge_client,
        "ModeArmingPolicy.kt": arming,
        "DestinedFXTraderEA.mq5": ea,
        "README.md": readme,
    }
    missing = [name for name, body in required.items() if not body]
    add_check(
        checks,
        "required project files",
        not missing,
        "All audited source and documentation files are present." if not missing
        else "Missing or unreadable: " + ", ".join(missing),
    )

    active = integer_constant(market, "DERIV_ACTIVE_SYMBOLS_TIMEOUT_MS")
    resolve = integer_constant(market, "DERIV_SYMBOL_RESOLUTION_TIMEOUT_MS")
    candles = integer_constant(market, "DERIV_CANDLE_HISTORY_TIMEOUT_MS")
    timeout_ok = (
        active is not None and active > 16_000 + 12_000
        and resolve is not None and resolve > active
        and candles is not None and candles > 16_000 + 10_000
        and "withTimeoutOrNull(DERIV_SYMBOL_RESOLUTION_TIMEOUT_MS)" in market
        and "withTimeoutOrNull(DERIV_CANDLE_HISTORY_TIMEOUT_MS)" in market
    )
    add_check(
        checks,
        "Deriv nested provider timeout budgets",
        timeout_ok,
        f"active-symbol={active}, symbol-resolution={resolve}, candle-history={candles} ms; "
        "must exceed the gateway's 16s connect + 12s/10s response windows.",
    )

    refresh_ok = (
        "withTimeoutOrNull(36_000L)" in vm
        and "withTimeoutOrNull(65_000L)" in vm
        and "withTimeout(65_000)" in signal
    )
    add_check(
        checks,
        "Analyzer outer refresh and MTF budgets",
        refresh_ok,
        "UI quote/OHLC refresh and multi-timeframe loads must not cancel the longer provider path.",
    )

    diagnostics_ok = (
        "candleFetchErrors" in analyzer_ui
        and "candleDiagnostic" in analyzer_ui
        and "OHLC PROVIDER:" in analyzer_ui
        and "REFRESH VERIFIED QUOTE & OHLC DATA" in analyzer_ui
        and "Provider detail:" in market
    )
    add_check(
        checks,
        "Analyzer provider diagnostics",
        diagnostics_ok,
        "The Analyzer exposes the stored OHLC failure reason and an explicit refresh action.",
    )

    live_gate_ok = (
        "HostProfile.PERSISTENT" in arming
        and "EPHEMERAL_HOST_LIVE_BLOCKED" in arming
        and "hostProfile != HostProfile.PERSISTENT" in arming
        and "LIVE IS BLOCKED" in bot_ui
        and "PERSISTENT HOST NOT VERIFIED" in bot_ui
    )
    add_check(
        checks,
        "fail-closed persistent-host Live policy",
        live_gate_ok,
        "LIVE remains blocked for ephemeral or unknown hosts; the Bot displays the host gate.",
    )

    pairing_ok = (
        "managementIdentityAssertionProvider" in bridge_client
        and '"/v1/owner/bootstrap-code"' in bridge_client
        and "ownerBootstrapCode" in bridge_client
        and "Optional owner bootstrap code (manual recovery)" in bot_ui
    )
    add_check(
        checks,
        "signed-management pairing bootstrap",
        pairing_ok,
        "Public Bridge pairing can request a one-use bootstrap using the signed management identity; "
        "the text field is only a manual recovery fallback.",
    )

    actions_ok = (
        '"/v1/app/commands"' in bridge_client
        and "TRADE_ACTIONS = setOf" in bridge_client
        and "CANCEL_PENDING_SELECTED" in bridge_client
        and "CANCEL_EA_PENDING" in bridge_client
        and "CLOSE_POSITION_SELECTED" in bridge_client
        and "CLOSE_ALL" in bridge_client
        and "trade.Buy(" in ea and "trade.Sell(" in ea
        and "AccountIdentityMatches()" in ea
        and "HEARTBEAT_STALE_SECONDS" in ea
        and "RiskDayOK()" in ea
        and "PersistOrderState()" in ea
    )
    add_check(
        checks,
        "MT5 EA order and management path",
        actions_ok,
        "The app queues START/STOP/Pause/Resume and position-management commands; "
        "the EA owns new Buy/Sell submissions and checks identity, heartbeat, risk and its journal.",
    )

    manual_order_api = any(token in bridge_client for token in (
        '"OPEN_MARKET_ORDER"', '"PLACE_MARKET_ORDER"', '"OPEN_PENDING_ORDER"', '"SUBMIT_ORDER"'
    ))
    add_check(
        checks,
        "no misleading phone-side MT5 market-order API",
        not manual_order_api,
        "The current MT5 command contract has no phone-side arbitrary market-order action. "
        "New managed entries are generated and submitted by the EA after START.",
    )

    documentation_ok = all(term.lower() in readme.lower() for term in (
        "Analyzer", "Bot", "MT5", "persistent", "Codespaces", "DATA_UNAVAILABLE"
    ))
    add_check(
        checks,
        "operational README coverage",
        documentation_ok,
        "README should describe Analyzer, Bot, MT5, persistent-host limits, Codespaces and data-unavailable behavior.",
    )

    failed = [item["name"] for item in checks if not item["ok"]]
    return {
        "audit": "DestinedFX source readiness",
        "mode": "static_read_only",
        "repository_root": str(root),
        "status": "PASS" if not failed else "FAIL",
        "checks": checks,
        "failed_checks": failed,
        "runtime_boundary": (
            "Static source checks do not prove that Android, a public Bridge, a Codespace, "
            "Wine/MT5, an EA, or a broker account is running."
        ),
    }


def validate_bridge_origin(value: str) -> str:
    raw = value.strip().rstrip("/")
    parsed = urlsplit(raw)
    if (
        parsed.scheme.lower() != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in ("", "/")
    ):
        raise ValueError("Bridge URL must be a bare HTTPS origin (no credentials, path, query or fragment).")
    host = parsed.hostname.lower()
    if host in {"localhost", "127.0.0.1", "0.0.0.0", "::1", "example.com"}:
        raise ValueError("A public HTTPS Bridge origin is required.")
    return raw


def get_json(url: str, timeout: float, token: str | None = None,
             opener=urlopen) -> tuple[int, dict[str, Any]]:
    headers = {
        "Accept": "application/json",
        "X-Tunnel-Skip-AntiPhishing-Page": "true",
        "User-Agent": "DestinedFX-Readiness-Audit/1.0",
    }
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(url, headers=headers, method="GET")
    try:
        response = opener(request, timeout=timeout)
        with response:
            status = getattr(response, "status", response.getcode())
            raw = response.read(128 * 1024)
    except HTTPError as error:
        status = error.code
        raw = error.read(128 * 1024)
    try:
        body = json.loads(raw.decode("utf-8")) if raw else {}
    except (UnicodeError, json.JSONDecodeError):
        body = {}
    return int(status), body if isinstance(body, dict) else {}


def probe_bridge(origin: str, timeout: float = 8.0,
                 app_token: str | None = None, opener=urlopen) -> dict[str, Any]:
    """Read Bridge health/readiness; optionally read sanitized app state."""
    base = validate_bridge_origin(origin)
    results: dict[str, Any] = {"origin": base}
    for label, route in (
        ("health", "/health"),
        ("configuration", "/health/config"),
        ("readiness", "/health/ready"),
    ):
        try:
            status, body = get_json(base + route, timeout, opener=opener)
            item: dict[str, Any] = {"http_status": status}
            for key in (
                "status", "host_profile", "host_lifecycle_status", "public_exposure",
                "database", "managed_execution", "per_user_terminal_isolation",
            ):
                if key in body and isinstance(body[key], (str, int, float, bool)):
                    item[key] = body[key]
            detail = body.get("detail")
            if isinstance(detail, dict):
                code = detail.get("code")
                if isinstance(code, str):
                    item["error_code"] = code[:80]
            results[label] = item
        except (OSError, URLError, TimeoutError, ValueError) as error:
            results[label] = {"error": type(error).__name__}

    ready = results.get("readiness", {})
    health = results.get("health", {})
    results["ready"] = (
        isinstance(ready, dict) and ready.get("http_status") == 200
        and ready.get("status") in {"ready", "ok"}
        and isinstance(health, dict) and health.get("http_status") == 200
    )

    if app_token:
        try:
            status, body = get_json(base + "/v1/app/state", timeout, token=app_token, opener=opener)
            managed = body.get("managed_execution")
            account = body.get("account")
            managed = managed if isinstance(managed, dict) else {}
            account = account if isinstance(account, dict) else {}
            results["authenticated_state"] = {
                "http_status": status,
                "managed_state": managed.get("state", body.get("managed_state", "UNKNOWN")),
                "worker_status": managed.get("worker_status", body.get("worker_status", "UNKNOWN")),
                "terminal_online": bool(body.get("terminal_online", False)),
                "heartbeat_fresh": bool(body.get("heartbeat_fresh", False)),
                "account_mode": account.get("account_mode", "UNKNOWN"),
                "broker_identity_verified": bool(body.get("broker_identity_verified", False)),
            }
        except (OSError, URLError, TimeoutError, ValueError) as error:
            results["authenticated_state"] = {"error": type(error).__name__}
    else:
        results["authenticated_state"] = "not_requested"

    results["runtime_boundary"] = (
        "HTTP readiness does not prove a real MT5 terminal, EA heartbeat, broker login, "
        "or accepted/finalized broker order."
    )
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root (default: detected)")
    parser.add_argument("--bridge-url", help="optional live Bridge HTTPS origin; read-only probes only")
    parser.add_argument(
        "--authenticated-state",
        action="store_true",
        help="also GET /v1/app/state using DESTINEDFX_AUDIT_APP_TOKEN from the environment",
    )
    parser.add_argument("--timeout", type=float, default=8.0)
    args = parser.parse_args(argv)

    report: dict[str, Any] = audit_repository(args.root.resolve())
    if args.bridge_url:
        token = os.environ.get("DESTINEDFX_AUDIT_APP_TOKEN", "").strip() if args.authenticated_state else ""
        if args.authenticated_state and not token:
            report["live_bridge"] = {"error": "DESTINEDFX_AUDIT_APP_TOKEN is not set in the environment"}
        else:
            try:
                live = probe_bridge(args.bridge_url, timeout=max(1.0, args.timeout), app_token=token or None)
                report["live_bridge"] = live
            except ValueError as error:
                report["live_bridge"] = {"error": str(error)}
    else:
        report["live_bridge"] = "not_checked; pass --bridge-url to probe a live HTTPS Bridge"

    print(json.dumps(report, indent=2, sort_keys=True))
    if report["status"] != "PASS":
        return 2
    if args.bridge_url and not report.get("live_bridge", {}).get("ready", False):
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
