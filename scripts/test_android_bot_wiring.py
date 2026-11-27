from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKSTATION = (ROOT / "app/src/main/java/com/example/ui/SimplifiedBotWorkstation.kt").read_text(encoding="utf-8")
BACKEND = (ROOT / "app/src/main/java/com/example/ui/bot/MT5BridgeConnectBackend.kt").read_text(encoding="utf-8")


def test_mt5_login_requires_the_phone_codespaces_wake_callback_before_pairing():
    assert "private val wakeHost: (suspend ((ConnectStage) -> Unit) -> Unit)" in BACKEND
    assert "wakeHost.invoke(onStage)" in BACKEND
    assert "wakeHost?.invoke" not in BACKEND
    login = WORKSTATION.split("LoginConnector.MT5_TERMINAL -> {", 1)[1].split("onRefreshAccount =", 1)[0]
    assert "wakeHost = { reportStage -> wakeCodespacesHostForMt5(client, reportStage) }" in login
    wake = WORKSTATION.split("suspend fun wakeCodespacesHostForMt5(", 1)[1].split(
        'if (showModernBrokerFlow && !connected)', 1
    )[0]
    for required in (
        "githubCodespacesClient.listTargetCodespaces()",
        "githubCodespacesClient.startCodespace(current)",
        "codespacesLifecycleClient.discoverAndSaveBridge()",
        "codespacesLifecycleClient.discoverAndSaveManagementApi()",
        "client.checkServiceReadiness()",
        'readiness.perUserTerminalIsolation.equals("VERIFIED", ignoreCase = true)',
    ):
        assert required in wake, required


def test_mt5_dashboard_start_sends_auto_market_watch_scan_not_a_phone_selected_symbol():
    block = WORKSTATION.split("onStart = {", 1)[1].split("onStop = {", 1)[0]
    assert 'put("symbols", JSONArray().put("AUTO"))' in block
    assert 'put("symbols", JSONArray().put(botConfig.symbol))' not in block
    assert 'put("max_concurrent_positions", 1)' in block
    assert 'val result = client.command(UUID.randomUUID().toString(), "START", config)' in block
    assert 'fresh.state == "RUNNING"' in block
