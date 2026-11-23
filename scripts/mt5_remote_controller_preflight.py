#!/usr/bin/env python3
"""Read-only source and runtime preflight for the DestinedFX phone-to-MT5 remote controller.

This tool performs source-contract inspection and optional HTTPS GET-only checks.
It never accepts broker credentials, reads Android secrets, mutates LIVE state,
submits an order, or retries a command.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


def read(root: Path, relative: str) -> str:
    try:
        return (root / relative).read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        return ""


def validate_origin(raw: str, label: str) -> str:
    value = (raw or "").strip().rstrip("/")
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError as exc:
        raise ValueError(f"{label}_URL_INVALID") from exc
    host = (parsed.hostname or "").lower().rstrip(".")
    placeholder = (
        host in {"localhost", "example.com", "example.net", "0.0.0.0",
                 "127.0.0.1", "::1", "testserver"}
        or "example" in host
        or host.endswith((".example", ".invalid", ".test", ".localhost"))
        or host.startswith(("your-", "placeholder"))
    )
    if (
        parsed.scheme.lower() != "https" or not host or parsed.username or parsed.password
        or parsed.path not in ("", "/") or parsed.query or parsed.fragment
        or placeholder or (port is not None and not 1 <= port <= 65535)
    ):
        raise ValueError(f"{label}_URL_INVALID")
    return f"https://{parsed.netloc}"


def source_checks(root: Path) -> list[dict[str, str]]:
    bridge = read(root, "bridge/app.py")
    android = read(root, "app/src/main/java/com/example/data/trade/MT5RemoteBridgeClient.kt")
    workstation = read(root, "app/src/main/java/com/example/ui/workstations/BotWorkstation.kt")
    ea = read(root, "mt5-ea/DestinedFXTraderEA.mq5")
    gradle = read(root, "app/build.gradle.kts")

    checks = [
        (
            "bridge_market_order_contract",
            all(token in bridge for token in (
                "OPENING_ACTIONS={'PLACE_MARKET_ORDER'}",
                "def issue_market_order_command(x,p):",
                "if x.action in OPENING_ACTIONS: return issue_market_order_command(x,p)",
                "live_authorization_valid(p,managed,beat)",
                "fresh_terminal_broker_quotes(beat)",
                "heartbeat_news_clear(beat,[symbol])",
                "expires_at_epoch",
            )),
            "Bridge validates and queues a short-lived account/quote/news/risk-bound action.",
        ),
        (
            "android_remote_market_order_client",
            all(token in android for token in (
                'internal val OPENING_ACTIONS = setOf("PLACE_MARKET_ORDER")',
                "marketOrderConfig(",
                "commandPayload(requestId, action, config)",
                "sensitiveActionAuthorized()",
            )),
            "Android serializes the current verified terminal quote and profile into the Bridge command.",
        ),
        (
            "phone_remote_order_panel_and_reconciliation",
            all(token in workstation for token in (
                "mt5_phone_market_order_card",
                "mt5_phone_market_order_review",
                'bridgeClient, "PLACE_MARKET_ORDER", config, emptyList()',
                "signal_key",
                "MANUAL-$bridgePendingId",
            )),
            "Phone reviews BUY/SELL, SL/TP and risk, stores the request ID securely and reconciles the EA heartbeat.",
        ),
        (
            "mt5_ea_is_only_broker_executor",
            all(token in ea for token in (
                "bool ExecutePhoneMarketOrder(",
                'action=="PLACE_MARKET_ORDER"',
                'Place(symbol,PERIOD_M5,side=="BUY"',
                '"MANUAL-"+requestId',
                "g_liveAuthorizedUntil<=0",
                "QuoteFresh(symbol,q)",
                "NewsClear(symbol)",
                "OrderCalcProfit",
                "OrderCalcMargin",
                "signal_key",
            )),
            "The MT5 EA checks the current account, quote, news, risk, margin and broker result; on the Linux production host the Windows terminal binary runs under Wine.",
        ),
        (
            "phone_position_stop_tightening_path",
            (
                'POSITION_MANAGEMENT_ACTIONS' in android
                and 'TIGHTEN_POSITION_SL' in android
                and 'positionStopTightenConfig(' in android
                and 'def issue_position_stop_command(x,p):' in bridge
                and 'if x.action in POSITION_MANAGEMENT_ACTIONS: return issue_position_stop_command(x,p)' in bridge
                and 'POSITION_STOP_ALREADY_PENDING' in bridge
                and 'POSITION_STOP_MUST_TIGHTEN' in bridge
                and 'bool ExecutePhonePositionStopTighten(' in ea
                and 'trade.PositionModify(ticket,newStop,currentTake)' in ea
                and 'ReconcilePhonePositionStopState()' in ea
                and 'g_sigCommandId=commandId' in ea
                and 'RetryPhonePositionStopAck()' in ea
                and 'bool Ack(string id,string outcome,string msg,string resultJson' in ea
                and 'mt5_position_stop_tighten_card' in workstation
                and 'TIGHTEN_POSITION_SL' in workstation
            ),
            "A position edit may only tighten SL, preserves TP, and is reconciled from the exact broker ticket by the MT5 EA runtime.",
        ),
        (
            "apk_service_origins_are_configurable",
            all(token in gradle for token in (
                'System.getenv("MT5_MANAGED_SERVICE_URL")',
                'System.getenv("MANAGEMENT_API_ORIGIN")',
                'buildConfigField("String", "MT5_MANAGED_SERVICE_URL"',
                'buildConfigField("String", "MANAGEMENT_API_ORIGIN"',
            )) and "MT5_MANAGED_SERVICE_URL" in android,
            "APK supports explicit management and managed-Bridge origins; placeholder values must be replaced in build settings or app settings.",
        ),
    ]
    return [
        {"name": name, "status": "PASS" if ok else "BLOCKED", "detail": detail}
        for name, ok, detail in checks
    ]


def get_json(url: str, timeout: float = 8.0) -> tuple[int, dict[str, Any]]:
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/json", "User-Agent": "DestinedFX-Remote-Controller-Preflight/1.0"},
        method="GET",
    )
    try:
        response = urllib.request.urlopen(request, timeout=timeout)
    except urllib.error.HTTPError as exc:
        response = exc
    with response:
        body = response.read(512 * 1024 + 1)
        if len(body) > 512 * 1024:
            raise ValueError("REMOTE_RESPONSE_TOO_LARGE")
        try:
            value = json.loads(body.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as exc:
            raise ValueError("REMOTE_RESPONSE_NOT_JSON") from exc
        if not isinstance(value, dict):
            raise ValueError("REMOTE_RESPONSE_NOT_OBJECT")
        status = getattr(response, "status", None) or response.getcode()
        return int(status), value


def public_key_fingerprint(pem: str) -> str:
    try:
        from cryptography.hazmat.primitives import serialization
        key = serialization.load_pem_public_key(pem.encode("ascii"))
        der = key.public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    except Exception as exc:
        raise ValueError("STEP_UP_PUBLIC_KEY_INVALID") from exc
    return hashlib.sha256(der).hexdigest()


def remote_checks(management_raw: str, bridge_raw: str, timeout: float = 8.0) -> list[dict[str, str]]:
    checks: list[dict[str, str]] = []
    try:
        management = validate_origin(management_raw, "MANAGEMENT")
        bridge = validate_origin(bridge_raw, "BRIDGE")
    except ValueError as exc:
        return [{"name": "service_origins", "status": "BLOCKED", "detail": str(exc)}]

    management_ready = False
    remote_fingerprint = ""
    issuer = ""
    audience = ""
    try:
        status, data = get_json(management + "/ready", timeout)
        management_ready = status == 200 and data.get("status") == "ready"
        checks.append({
            "name": "management_backend",
            "status": "PASS" if management_ready else "BLOCKED",
            "detail": "Management API reports ready." if management_ready else "Management API is not ready.",
        })
    except Exception as exc:
        checks.append({"name": "management_backend", "status": "BLOCKED", "detail": type(exc).__name__})

    try:
        status, key = get_json(management + "/api/v1/auth/bridge-verification-key", timeout)
        pem = key.get("public_key_pem", "")
        issuer = str(key.get("issuer", ""))
        audience = str(key.get("audience", ""))
        if status != 200 or key.get("algorithm") != "EdDSA":
            raise ValueError("STEP_UP_PUBLIC_KEY_UNAVAILABLE")
        remote_fingerprint = public_key_fingerprint(pem)
        checks.append({
            "name": "management_public_verification_key",
            "status": "PASS" if issuer == "destinedfx-management" and audience == "destinedfx-bridge" else "BLOCKED",
            "detail": "Ed25519 public verification key is available; private key is not requested.",
        })
    except Exception as exc:
        checks.append({"name": "management_public_verification_key", "status": "BLOCKED", "detail": str(exc) or type(exc).__name__})

    config: dict[str, Any] = {}
    try:
        status, config = get_json(bridge + "/health/config", timeout)
        configured = status == 200 and config.get("status") == "configured"
        checks.append({
            "name": "bridge_configuration",
            "status": "PASS" if configured else "BLOCKED",
            "detail": "Bridge configuration endpoint responded." if configured else "Bridge configuration is not ready.",
        })
    except Exception as exc:
        checks.append({"name": "bridge_configuration", "status": "BLOCKED", "detail": type(exc).__name__})

    config_ready = bool(config and config.get("status") == "configured")
    if config_ready:
        persistent = config.get("host_profile") == "PERSISTENT"
        linux_host = config.get("linux_host_mode") is True
        isolation = (
            linux_host
            and config.get("per_user_terminal_isolation") == "VERIFIED"
            and config.get("multi_user_terminal_isolation") == "VERIFIED"
        )
        linux_production_host = persistent and linux_host and isolation
        checks.append({
            "name": "persistent_linux_execution_host",
            "status": "PASS" if linux_production_host else "BLOCKED",
            "detail": (
                "Persistent Linux host and root-host-agent per-pairing isolation are reported."
                if linux_production_host else
                "LIVE requires the persistent Linux production runtime; ephemeral Codespaces and the Windows compatibility worker are not accepted."
            ),
        })
        checks.append({
            "name": "per_user_terminal_isolation",
            "status": "PASS" if linux_production_host else "BLOCKED",
            "detail": (
                "Root host-agent reports per-pairing Unix identity and terminal isolation."
                if linux_production_host else
                "Linux root attestation is absent or unverified; Windows portable folders do not prove separate OS identities."
            ),
        })
        active_slots = config.get("isolated_slots")
        max_slots = config.get("max_isolated_slots")
        valid_active = isinstance(active_slots, int) and not isinstance(active_slots, bool)
        valid_maximum = isinstance(max_slots, int) and not isinstance(max_slots, bool)
        capacity = linux_production_host and valid_active and active_slots >= 2 and valid_maximum and max_slots >= 2
        checks.append({
            "name": "live_terminal_slot_capacity",
            "status": "PASS" if capacity else "BLOCKED",
            "detail": (
                "At least two simultaneously active root-attested Linux slots are reported for linked DEMO and LIVE profiles."
                if capacity else
                "LIVE requires two active, separately isolated MT5 slots on the persistent Linux host."
            ),
        })
        news = config.get("news_provider_configured") is True
        checks.append({
            "name": "news_calendar",
            "status": "PASS" if news else "BLOCKED",
            "detail": "Bridge reports a configured economic-calendar provider; runtime freshness is still checked on every order.",
        })
        bridge_fingerprint = str(config.get("management_step_up_key_fingerprint_sha256", ""))
        trust = bool(remote_fingerprint and bridge_fingerprint == remote_fingerprint)
        checks.append({
            "name": "management_bridge_key_match",
            "status": "PASS" if trust else "BLOCKED",
            "detail": "Bridge verification-key fingerprint must exactly match the management API public key.",
        })
        issuer_ok = issuer == config.get("management_step_up_issuer") and audience == config.get("management_step_up_audience")
        checks.append({
            "name": "management_bridge_issuer_audience",
            "status": "PASS" if issuer_ok else "BLOCKED",
            "detail": "Management issuer/audience must match the Bridge verifier.",
        })

    try:
        status, readiness = get_json(bridge + "/health/ready", timeout)
        detail_value = readiness.get("detail", {})
        runtime_error = detail_value.get("code", "") if isinstance(detail_value, dict) else ""
        runtime_ready = (
            status == 200 and readiness.get("status") == "ready"
        ) or (status == 503 and runtime_error == "NO_VERIFIED_TERMINAL_PAIRING")
        checks.append({
            "name": "bridge_host_runtime",
            "status": "PASS" if runtime_ready else "BLOCKED",
            "detail": (
                "Bridge host runtime and storage report ready; this does not prove a broker account is paired."
                if runtime_ready else "Bridge host runtime is not ready."
            ),
        })
        terminal_verified = (
            status == 200 and readiness.get("status") == "ready"
            and readiness.get("verified_terminal_pairing") == "healthy"
        )
        checks.append({
            "name": "verified_mt5_terminal",
            "status": "PASS" if terminal_verified else ("PENDING" if runtime_ready else "BLOCKED"),
            "detail": (
                "Bridge explicitly reports a verified terminal pairing."
                if terminal_verified else
                "Host readiness is not broker acceptance; a fresh authenticated MT5 account heartbeat is still required."
                if runtime_ready else
                "A broker-specific MT5 terminal/account heartbeat is not verified because host readiness is blocked."
            ),
        })
    except Exception as exc:
        checks.append({"name": "bridge_host_runtime", "status": "BLOCKED", "detail": type(exc).__name__})
        checks.append({"name": "verified_mt5_terminal", "status": "PENDING", "detail": type(exc).__name__})

    return checks


def build_report(root: Path, management_url: str = "", bridge_url: str = "", timeout: float = 8.0) -> dict[str, Any]:
    checks = source_checks(root)
    runtime_requested = bool(management_url.strip() or bridge_url.strip())
    if runtime_requested:
        if not management_url.strip() or not bridge_url.strip():
            remote = [{"name": "service_origins", "status": "BLOCKED", "detail": "Supply both management and Bridge HTTPS origins."}]
        else:
            remote = remote_checks(management_url, bridge_url, timeout)
        checks.extend(remote)

    blocked = any(item["status"] == "BLOCKED" for item in checks)
    pending = any(item["status"] == "PENDING" for item in checks)
    source_blocked = any(item["status"] == "BLOCKED" for item in checks if item["name"] in {
        "bridge_market_order_contract", "android_remote_market_order_client",
        "phone_remote_order_panel_and_reconciliation", "mt5_ea_is_only_broker_executor",
        "apk_service_origins_are_configurable",
    })
    status = (
        "SOURCE_CONTRACT_BLOCKED" if source_blocked else
        "BLOCKED" if blocked else
        "SOURCE_READY_RUNTIME_NOT_CHECKED" if not runtime_requested else
        "ACCOUNT_PAIRING_OR_RUNTIME_VERIFICATION_PENDING" if pending else
        "INFRASTRUCTURE_READY_PER_ACCOUNT_AUTHORIZATION_REQUIRED"
    )
    return {
        "schema_version": 1,
        "overall_status": status,
        "checks": checks,
        "non_actions": [
            "No broker passwords, investor passwords, API tokens or account credentials were read.",
            "No LIVE authorization was created or extended.",
            "No broker order, position modification or cancellation was submitted.",
            "Only HTTPS GET requests were used for optional runtime probes.",
        ],
        "still_required": [
            "Configure the exact deployed Management API and private managed-Bridge HTTPS origins in the APK build or app settings.",
            "Install the Management API's public Ed25519 verification key on the persistent Linux production Bridge; never copy its private key.",
            "Verify root-attested per-pairing Unix identities, Wine prefixes, terminal trees, displays and Xauthority state on the real Linux host.",
            "Treat the Windows owner-worker as a limited compatibility path; its shared Windows identity does not qualify for multi-user LIVE.",
            "Pair the exact user DEMO and LIVE broker accounts and verify MASTER permission and account-issued server.",
            "Complete the broker-specific DEMO acceptance and fresh risk/news checks.",
            "Perform LIVE password reauthentication and exact account-bound confirmation for each user's LIVE account.",
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--management-url", default="", help="Optional bare HTTPS Management API origin (GET-only)")
    parser.add_argument("--bridge-url", default="", help="Optional bare HTTPS managed Bridge origin (GET-only)")
    parser.add_argument("--timeout", type=float, default=8.0)
    args = parser.parse_args(argv)
    report = build_report(args.repo_root, args.management_url, args.bridge_url, args.timeout)
    print(json.dumps(report, indent=2, sort_keys=True))
    if report["overall_status"] == "SOURCE_CONTRACT_BLOCKED":
        return 1
    if report["overall_status"] == "BLOCKED":
        return 2
    if report["overall_status"] == "SOURCE_READY_RUNTIME_NOT_CHECKED":
        return 0
    return 0 if report["overall_status"] == "INFRASTRUCTURE_READY_PER_ACCOUNT_AUTHORIZATION_REQUIRED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
