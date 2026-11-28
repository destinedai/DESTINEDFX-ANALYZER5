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
BRIDGE_API = (ROOT / "bridge/app.py").read_text(encoding="utf-8")
ANALYZER_CLIENT = (ROOT / "app/src/main/java/com/example/data/trade/MT5AnalyzerMarketFeedClient.kt").read_text(encoding="utf-8")
ANALYZER_UI = (ROOT / "app/src/main/java/com/example/ui/SimplifiedAnalyzerTab.kt").read_text(encoding="utf-8")
ANALYZER_EA = (ROOT / "mt5-ea/AnalyzerMarketFeedEA.mq5").read_text(encoding="utf-8")
LOGIN_MODEL = (ROOT / "app/src/main/java/com/example/ui/bot/BrokerLoginModel.kt").read_text(encoding="utf-8")
BROKER_FLOW = (ROOT / "app/src/main/java/com/example/ui/bot/MetaTraderBrokerFlow.kt").read_text(encoding="utf-8")
SERVER_CATALOG = (ROOT / "bridge/mt5_server_catalog.py").read_text(encoding="utf-8")
LINUX_SLOT_WORKER = (ROOT / "scripts/linux-host/linux-slot-worker.py").read_text(encoding="utf-8")


def test_mt5_login_is_trade_only_and_server_must_come_from_real_terminal_catalog():
    assert "INVESTOR" not in LOGIN_MODEL
    assert "Investor" not in BROKER_FLOW
    assert "AccessType.TRADE" in LOGIN_MODEL
    assert "mt5_type_trade_only" in BROKER_FLOW
    assert "serverSelectionVerified" in BROKER_FLOW
    assert "server_results" in BROKER_FLOW or "serverResults" in BROKER_FLOW
    assert "onSearchServers" in BROKER_FLOW
    assert "Type exact server name" not in BROKER_FLOW
    assert "Server not found" in BROKER_FLOW
    assert "/v1/mt5/server-catalog" in BRIDGE_CLIENT
    assert "Config" in SERVER_CATALOG and "servers.dat" in SERVER_CATALOG
    assert 'name.lower() != "servers.dat"' in LINUX_SLOT_WORKER


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
    assert "revocationPending = { client.isRevocationPending() }" in revoke_dialog
    assert revoke_dialog.index("stopAndRevokeManagedMt5ForSwitch(") < revoke_dialog.index("client.revoke()")
    switch_helper = WORKSTATION.split("internal suspend fun stopAndRevokeManagedMt5ForSwitch(", 1)[1].split("@Composable", 1)[0]
    assert "if (!revocationPending())" in switch_helper
    assert switch_helper.index("stopAndConfirm()") < switch_helper.index("revoke()")
    revoke_client = BRIDGE_CLIENT.split("suspend fun revoke()", 1)[1].split("private fun gateFailure", 1)[0]
    assert '"/v1/app/pairing/revocation-status"' in revoke_client
    assert '"/v1/app/pairing"' in revoke_client and '"REVOKED"' in revoke_client
    assert "delay(1_000L)" in revoke_client
    assert revoke_client.index('"REVOKED"') < revoke_client.index("secrets.clearMT5BridgeAccountAppToken(profile)")
    assert "def pairing_revocation_status" in BRIDGE_API
    assert "terminal_stopped':status=='REVOKED'" in BRIDGE_API
    assert "allow_revoked=True" in BRIDGE_API
    assert "row['revocation_pending']" in BRIDGE_API and "revocation_pending=1" in BRIDGE_API


def test_mt5_stop_closes_bot_owned_positions_orders_and_retries_for_both_modes():
    assert 'action=="STOP"||action=="EMERGENCY_STOP"' in EA
    assert "StopAndCloseBotTrades(outcome,msg)" in EA
    assert "AttemptBotStopCloseout(outcome,message)" in EA
    assert "RetryPendingStopCloseout();" in EA
    assert "PersistStopCloseoutIntent(true)" in EA and "GlobalVariablesFlush()" in EA
    assert "CountBotExposure(remainingPositions,remainingOrders)" in EA
    assert "AccountIdentityMatches()" in EA and "BrokerAccountMode()" in EA
    assert "botPositions.isEmpty() && botOrders.isEmpty()" in WORKSTATION


def test_analyzer_public_feed_and_tradingview_are_independent_of_bot_login():
    assert "publicGateway.latestQuotes" in ANALYZER_UI
    assert "publicGateway.subscribeTicks" in ANALYZER_UI
    assert "fetchActiveSymbols()" in ANALYZER_UI
    assert "embed-widget-advanced-chart.js" in ANALYZER_UI
    assert "Deriv public live market data" in ANALYZER_UI
    assert "analysis only, not a broker execution quote" in ANALYZER_UI
    assert "state(" in ANALYZER_CLIENT
    assert "account_identity_sha256" in ANALYZER_CLIENT
    assert "getMT5BridgeAccountAppToken" not in ANALYZER_CLIENT


def test_analyzer_feed_is_token_free_and_matches_the_selected_app_account():
    assert "expectedAccountLogin: String = \"\"" in ANALYZER_CLIENT
    assert "expectedAccountServer: String = \"\"" in ANALYZER_CLIENT
    assert "expectedAccountMode: String = \"\"" in ANALYZER_CLIENT
    assert "account_identity_sha256" in ANALYZER_CLIENT
    assert "MessageDigest.getInstance(\"SHA-256\")" in ANALYZER_CLIENT
    assert "collectAsStateWithLifecycle()" in ANALYZER_UI
    assert "getManagedMT5AccountLogin(selectedMt5Profile)" in ANALYZER_UI
    assert "getManagedMT5AccountServer(selectedMt5Profile)" in ANALYZER_UI
    assert "expectedAccountLogin, expectedAccountServer, selectedMt5Mode" in ANALYZER_UI
    assert "ACCOUNT_LOGIN" in ANALYZER_EA and "ACCOUNT_SERVER" in ANALYZER_EA
    assert "ACCOUNT_TRADE_MODE_DEMO" in ANALYZER_EA and "ACCOUNT_TRADE_MODE_REAL" in ANALYZER_EA
    assert "account_identity_sha256" in BRIDGE_API
    assert "getMT5BridgeAccountAppToken" not in ANALYZER_CLIENT
    assert "pairing_code" not in ANALYZER_EA and "authorization:" not in ANALYZER_EA
