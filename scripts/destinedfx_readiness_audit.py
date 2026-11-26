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
    analyzer_view_model = read_text(root, "app/src/main/java/com/example/ui/AnalyzerViewModel.kt")
    analyzer_mapping_test = read_text(root, "app/src/test/java/com/example/MT5AnalyzerMathTest.kt")
    bot_ui = read_text(root, "app/src/main/java/com/example/ui/workstations/BotWorkstation.kt")
    bridge_client = read_text(root, "app/src/main/java/com/example/data/trade/MT5RemoteBridgeClient.kt")
    arming = read_text(root, "app/src/main/java/com/example/data/broker/ModeArmingPolicy.kt")
    live_flow = read_text(root, "app/src/main/java/com/example/ui/bot/MetaTraderBrokerFlow.kt")
    live_worker = read_text(root, "managed-worker/worker.py")
    codespaces = read_text(root, "app/src/main/java/com/example/data/codespaces/CodespacesLifecycleClient.kt")
    codespaces_ui = read_text(root, "app/src/main/java/com/example/ui/workstations/CodespacesHostControls.kt")
    bridge = read_text(root, "bridge/app.py")
    broker_directory = read_text(root, "app/src/main/java/com/example/data/broker/BrokerDirectory.kt")
    broker_specs = read_text(root, "app/src/main/java/com/example/data/broker/BrokerLoginSpec.kt")
    linux_slots = read_text(root, "scripts/linux-host/linux-slot-worker.py")
    codespaces_start = read_text(root, "deploy/codespaces/start.sh")
    codespaces_management = read_text(root, "deploy/codespaces/start-management-api.sh")
    codespaces_builder = read_text(root, "scripts/linux-host/build-self-hosted-apk.sh")
    management_api = read_text(root, "backend/app/main.py")
    management_vm = read_text(root, "app/src/main/java/com/example/ui/DestinedFXViewModel.kt")
    management_settings = read_text(root, "app/src/main/java/com/example/ui/SettingsTab.kt")
    android_gradle = read_text(root, "app/build.gradle.kts")
    windows_installer = read_text(root, "managed-worker/windows/install-owner-stack.ps1")
    windows_owner_setup = read_text(root, "managed-worker/windows/OWNER-SETUP.md")
    azure_bootstrap = read_text(root, "deploy/azure-students/cloud-init.yaml")
    broker_matrix = read_text(root, "docs/broker-capability-matrix.md")
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
        vm.count("withTimeoutOrNull(8_000L)") >= 2
        and 'override val selectedApiSource = MutableStateFlow("DERIV_PUBLIC")' in market
        and "verifiedMt5Snapshot" in market
        and "withTimeout(65_000)" in signal
    )
    add_check(
        checks,
        "Analyzer outer refresh and MTF budgets",
        refresh_ok,
        "MT5 Analyzer uses the app-lifetime Bridge snapshot; existing provider and multi-timeframe operations retain bounded timeouts.",
    )

    analyzer_math = read_text(root, "app/src/main/java/com/example/ui/MT5AnalyzerMath.kt")
    mapping_start = analyzer_ui.find("internal fun tradingViewSymbolForMt5")
    mapping_end = analyzer_ui.find("internal fun tradingViewIntervalForMt5", mapping_start)
    analyzer_mapping = analyzer_ui[mapping_start:mapping_end] if mapping_start >= 0 and mapping_end > mapping_start else ""
    chart_branch = (
        analyzer_ui.split("if (chartTicker != null)", 1)[1].split("if (showAnalysis)", 1)[0]
        if "if (chartTicker != null)" in analyzer_ui and "if (showAnalysis)" in analyzer_ui
        else ""
    )
    diagnostics_ok = (
        "managedMt5BridgeSnapshot" in analyzer_ui
        and "startSelectedMt5AnalyzerBridgeMonitor" in analyzer_ui
        and "MT5_TERMINAL" in analyzer_ui
        and "activeChartSymbol" in analyzer_ui
        and "Deriv public WebSocket" not in analyzer_ui
        and "refreshMarketData(" not in analyzer_ui
        and "runSignalScan(" not in analyzer_ui
        and '?: "FX:EURUSD"' not in analyzer_ui
        and '?: "FX:EURUSD"' not in analyzer_view_model
        and "internal fun analyzerTradingViewTicker(mt5Symbol: String): String? =" in analyzer_view_model
        and "tradingViewSymbolForMt5(mt5Symbol)" in analyzer_view_model
        and "return null" in analyzer_mapping
        and 'return "TVC:GOLD"' in analyzer_mapping
        and '"FX:$pair"' in analyzer_mapping
        and "OANDA:" not in analyzer_mapping
        and "val chartTicker = analyzerTradingViewTicker(symbol)" in analyzer_ui
        and "if (chartTicker != null)" in analyzer_ui
        and "Box(Modifier.fillMaxWidth().height(270.dp).background(Color(0xFF171B27)))" in analyzer_ui
        and "val showAnalysis = showPrice && analysisValidated && chartTicker != null" in analyzer_ui
        and "if (showAnalysis)" in analyzer_ui
        and "connected" not in chart_branch
        and "val quote = if (connected)" in analyzer_ui
        and "if (connected) ProfitGreen else Color.Gray" in analyzer_ui
        and "ChartSymbol(0)" in ea
        and "active_chart_symbol" in ea
        and "active_chart_timeframe" in ea
        and "MT5_TERMINAL" in analyzer_math
        and 'tradingViewSymbolForMt5("EURUSD.a")' in analyzer_mapping_test
        and 'tradingViewSymbolForMt5("XAUUSDm")' in analyzer_mapping_test
        and 'tradingViewSymbolForMt5("GOLD")' in analyzer_mapping_test
        and 'analyzerTradingViewTicker("FAKEPAIR")' in analyzer_mapping_test
        and "assertNull(ticker)" in analyzer_mapping_test
    )
    add_check(
        checks,
        "Analyzer provider diagnostics",
        diagnostics_ok,
        "The Analyzer uses the paired MT5 Bridge snapshot, maps only supported TradingView symbols, returns null for unmappable symbols, leaves the chart blank with no ticker, and gates Signal/Levels on a mapped ticker. Price remains MT5-only. No placeholder UI labels are required.",
    )

    persistent_live_policy_ok = (
        "val hostProfile: HostProfile = HostProfile.EPHEMERAL" in arming
        and "request.hostProfile != HostProfile.PERSISTENT" in arming
        and "hostProfile != HostProfile.PERSISTENT" in arming
        and "liveLeases.remove(identity.fingerprint())" in arming
        and "required_slots = 1 if HOST_PROFILE == 'EPHEMERAL' else max(1, minimum_active_slots)" in bridge
        and "if HOST_PROFILE == 'PERSISTENT' and" in bridge
        and 'allow_trade = "1" if access_type == "MASTER" else "0"' in live_worker
        and "AllowLiveTrading={allow_trade}" in live_worker
    )
    add_check(
        checks,
        "fail-closed persistent-host LIVE policy",
        persistent_live_policy_ok,
        "LIVE arming defaults unknown host state to EPHEMERAL, requires explicit PERSISTENT host proof, and revokes an existing local lease if a later host check is EPHEMERAL. Root-attested isolation and MASTER access remain required.",
    )

    bridge_defaults_fail_closed = (
        "os.getenv('BRIDGE_HOST_PROFILE','EPHEMERAL')" in bridge
        and "os.getenv('EXECUTION_STORAGE_DURABLE','false')" in bridge
        and "if not durable_execution_enabled():" in bridge
    )
    add_check(
        checks,
        "fail-closed Bridge host and storage defaults",
        bridge_defaults_fail_closed,
        "An unspecified Bridge is EPHEMERAL and execution is disabled until storage durability is explicitly configured.",
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

    bridge_discovery_ok = (
        "discoverAndSaveBridge" in codespaces
        and "codespacesBridgeOriginCandidates" in codespaces
        and "isCodespacesForwardedBridgeOrigin(candidate)" in codespaces
        and '"/health/live"' in codespaces
        and '"/health/config"' in codespaces
        and "expected_forwarded_host" in codespaces
        and "saveManagedMT5ServiceUrl(candidate)" in codespaces
        and "DISCOVER BRIDGE URL" in codespaces_ui
        and "expected_forwarded_host" in bridge
    )
    add_check(
        checks,
        "automatic Codespaces Bridge discovery",
        bridge_discovery_ok,
        "The phone tries the allow-listed GitHub forwarding domains, verifies Bridge liveness/configuration and exact public host, then saves only the validated HTTPS Codespaces origin.",
    )
    add_check(
        checks,
        "automatic Codespaces Management API discovery and owner bootstrap",
        (
            "discoverAndSaveManagementApi" in codespaces
            and "codespacesManagementApiOriginCandidates" in codespaces
            and "isCodespacesForwardedManagementApiOrigin(candidate)" in codespaces
            and "api/v1/auth/bridge-verification-key" in codespaces
            and "fetchCodespacesOwnerSetupToken" in codespaces
            and "DESTINEDFX_CODESPACES_HOST_DISCOVERY" in management_vm
            and "DISCOVER MANAGEMENT API" in management_settings
            and "8010" in codespaces_management
            and "pg_ctlcluster" in codespaces_management
            and "start-management-api.sh" in codespaces_start
            and "OWNER_SETUP_TOKEN_FILE" in codespaces_management
            and "api.github.com/user" in management_api
            and 'response.headers["Cache-Control"] = "no-store, private"' in management_api
            and "DESTINEDFX_CODESPACES_HOST_DISCOVERY" in android_gradle
            and "https://$CODESPACE_NAME-8010.$CODESPACE_DOMAIN" in codespaces_builder
        ),
        "START HOST starts the real PostgreSQL-backed Management API on loopback port 8010; the phone discovers the forwarded URL, verifies readiness/EdDSA, and retrieves the one-time owner token only after GitHub confirms the Codespace owner.",
    )

    linux_health_ok = (
        "host = linux_host_report()" in bridge
        and "linux_slot_isolation(host)" in bridge
        and "'VERIFIED' if isolation_verified" in bridge
        and "PER_USER_TERMINAL_ISOLATION_REQUIRED" in bridge
    )
    add_check(
        checks,
        "Linux Bridge configuration reflects root slot attestation",
        linux_health_ok,
        "Linux configuration health must use actual root host-agent isolation evidence, not a hard-coded BLOCKED response.",
    )

    required_mt5 = {"FBS", "EXNESS", "HFM", "DERIV"}
    mt5_routes_ok = all(
        token in broker_directory and token in broker_specs and token in arming
        for token in required_mt5
    ) and all(
        (f"{brand} MT5" in broker_matrix or f"{brand} MT5" in broker_matrix)
        for brand in ("FBS", "Exness", "HFM", "Deriv")
    )
    demo_gate_ok = (
        "OWNER_DEMO_ACCEPTANCE_REQUIRED" in arming
        and "every ManagedMt5Broker" not in broker_specs
        and all(token in broker_matrix and "NOT VERIFIED" in broker_matrix for token in (
            "FBS MT5", "Exness MT5", "HFM MT5", "Deriv MT5"
        ))
    )
    add_check(
        checks,
        "four broker-specific MT5 DEMO paths",
        mt5_routes_ok and demo_gate_ok,
        "FBS, Exness, HFM and Deriv MT5 have distinct route identities; none is marked DEMO-accepted without account-bound evidence.",
    )

    slot_memory_ok = (
        "3 * 1024 ** 3" in linux_slots
        and "INSUFFICIENT_MEMORY_FOR_MT5_SLOT" in linux_slots
        and "memory_slots = max(0, (total - 2 * 1024**3) // (3 * 1024**3))" in codespaces_start
        and "if available < 3 * 1024**3:" in codespaces_start
        and "DESTINEDFX_SLOT_MAX_TERMINALS < 1" in codespaces_start
        and "DESTINEDFX_WORKER_TOKEN_FILE" in codespaces_start
    )
    add_check(
        checks,
        "isolated MT5 host memory floor",
        slot_memory_ok,
        "A terminal slot requires at least 3 GiB currently available memory; smaller hosts receive zero capacity and refuse provisioning.",
    )

    windows_background_host_ok = (
        "New-ScheduledTaskTrigger -AtStartup" in windows_installer
        and "New-ScheduledTaskTrigger -AtLogOn -User $owner" in windows_installer
        and "-User $owner -Password $taskPassword" in windows_installer
        and "LogonType Interactive" not in windows_installer
        and "Task Scheduler" in windows_owner_setup and "Windows DPAPI" in windows_owner_setup
    )
    add_check(
        checks,
        "Windows owner host background startup",
        windows_background_host_ok,
        "The Windows Bridge/worker, backup and health jobs run as the DPAPI owner identity using Task Scheduler password logon at boot and sign-in; real logoff MT5 heartbeat acceptance remains external.",
    )

    azure_host_not_overstated = (
        "BLOCKED_OWNER_RUNTIME_SETUP" in azure_bootstrap
        and "does not install or enable an isolated-slot" not in azure_bootstrap
        and "systemd runtime" in azure_bootstrap
    )
    add_check(
        checks,
        "persistent Azure host is not falsely marked ready",
        azure_host_not_overstated,
        "The Azure bootstrap must remain blocked until its isolated-slot systemd runtime, encrypted credentials and MT5 template are installed.",
    )

    phone_market_order_contract = (
        '"PLACE_MARKET_ORDER"' in bridge_client
        and 'OPENING_ACTIONS' in bridge_client
        and 'marketOrderConfig' in bridge_client
        and 'def issue_market_order_command' in bridge
        and 'if x.action in OPENING_ACTIONS: return issue_market_order_command(x,p)' in bridge
        and 'bool ExecutePhoneMarketOrder(' in ea
        and 'action=="PLACE_MARKET_ORDER"' in ea
        and '"MANUAL-"+requestId' in ea
        and "signal_key" in ea
        and 'mt5_phone_market_order_card' in bot_ui
        and 'TIGHTEN_POSITION_SL' in bridge
        and 'issue_position_stop_command' in bridge
        and 'positionStopTightenConfig' in bridge_client
        and 'mt5_position_stop_tighten_card' in bot_ui
        and 'ExecutePhonePositionStopTighten' in ea
        and 'g_sigCommandId=commandId' in ea
        and 'RetryPhonePositionStopAck()' in ea
    )
    add_check(
        checks,
        "phone MT5 orders and position protection route through the Windows EA",
        phone_market_order_contract,
        "The phone only queues an account-bound, quote/news/risk-validated Bridge command. "
        "The Windows MT5 EA computes volume and submits through its broker-side risk engine; it is never a direct Android broker order.",
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
