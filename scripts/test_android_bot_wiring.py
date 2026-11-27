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


VIEWMODEL = (ROOT / "app/src/main/java/com/example/ui/DestinedFXViewModel.kt").read_text(encoding="utf-8")
HOME = (ROOT / "app/src/main/java/com/example/ui/HomeDashboardTab.kt").read_text(encoding="utf-8")
BRIDGE_CLIENT = (ROOT / "app/src/main/java/com/example/data/trade/MT5RemoteBridgeClient.kt").read_text(encoding="utf-8")
WORKER = (ROOT / "managed-worker/worker.py").read_text(encoding="utf-8")
EA = (ROOT / "mt5-ea/DestinedFXTraderEA.mq5").read_text(encoding="utf-8")


def test_mt5_login_credentials_and_mode_are_passed_through_to_the_same_terminal():
    login = WORKSTATION.split("LoginConnector.MT5_TERMINAL -> {", 1)[1].split(
        "onRefreshAccount =", 1
    )[0]
    assert 'val mode = form.mode.name' in login
    assert 'mt5Client("${brand}_${mode}", mode, brand)' in login
    assert "accountMode = form.mode.name" in BACKEND
    assert "login = form.login.trim()" in BACKEND
    assert "password = form.password" in BACKEND
    assert "server = form.server.trim()" in BACKEND
    assert "require(login.brokerBrand == brokerBrand && login.accountMode == accountMode)" in BRIDGE_CLIENT
    assert "Login={account['login']}" in WORKER
    assert "Server={account['server']}" in WORKER
    assert "Password={login_password(account)}" in WORKER
    assert "InpExpectedLogin={account['login']}" in WORKER
    assert "InpExpectedServer={account['server']}" in WORKER
    assert "InpExpectedAccountMode={expected_mode}" in WORKER
    assert "AccountIdentityMatches()" in EA and "BrokerAccountMode()" in EA


def test_mt5_profile_switch_is_blocked_without_logout_and_home_has_no_bypass():
    profile_selection = VIEWMODEL.split("fun selectMt5AccountProfile", 1)[1].split(
        "fun selectMt5Mode", 1
    )[0]
    mode_selection = VIEWMODEL.split("fun selectMt5Mode", 1)[1].split(
        "fun selectScreen", 1
    )[0]
    assert "getMT5BridgeAccountAppToken(currentProfile).isNotBlank()" in profile_selection
    assert "getMT5BridgeAccountAppToken(currentProfile).isNotBlank()" in mode_selection
    assert "selectedMt5ProfilePaired" in HOME
    assert "enabled = !selectedMt5ProfilePaired" in HOME
    revoke_dialog = WORKSTATION.split("if (showPairingRevokeDialog) AlertDialog", 1)[1].split(
        "if (showLiveAuthorizationDialog)", 1
    )[0]
    assert "stopAndRevokeManagedMt5ForSwitch(" in revoke_dialog
    assert revoke_dialog.index("stopAndRevokeManagedMt5ForSwitch(") < revoke_dialog.index("client.revoke()")


def test_mt5_stop_closes_bot_owned_positions_orders_and_retries_for_both_modes():
    assert 'action=="STOP"||action=="EMERGENCY_STOP"' in EA
    assert "StopAndCloseBotTrades(outcome,msg)" in EA
    assert "AttemptBotStopCloseout(outcome,message)" in EA
    assert "RetryPendingStopCloseout();" in EA
    assert "CountBotExposure(remainingPositions,remainingOrders)" in EA
    assert "AccountIdentityMatches()" in EA and "BrokerAccountMode()" in EA
    assert "botPositions.isEmpty() && botOrders.isEmpty()" in WORKSTATION
