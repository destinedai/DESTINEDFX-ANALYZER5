package com.example.ui.workstations

import com.example.domain.risk.TradingSafetyLimits
import com.example.service.MT5AccountSessionService
import com.example.service.AccountSessionRegistry

import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import com.example.ui.formatPrice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinedfx.core.bot.BotLifecycleState
import com.example.data.broker.BrokerType
import com.example.data.broker.BrokerConnectionStatus
import com.example.MainActivity
import com.example.ui.DestinedFXViewModel
import com.example.ui.InsightCard
import com.example.ui.InsightLine
import com.example.ui.theme.*
import com.example.ui.components.TerminalPageHeader
import com.example.ui.components.StatChip
import com.example.service.DerivBotServiceStatePolicy
import androidx.compose.ui.platform.LocalContext
import com.example.security.AppSecurityGuard
import com.destinedfx.core.news.HighImpactNewsSentry
import com.destinedfx.core.strategy.StrategyRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.example.data.trade.MT5RemoteBridgeClient
import com.example.util.TradeReportManager
import com.example.service.DerivDemoBotService
import com.example.data.deriv.DerivMultiplierRules
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun BotWorkstationTab(viewModel: DestinedFXViewModel) {
    val bridgeSecrets = viewModel.app.secretsManager
    var selectedAccountMode by remember { mutableStateOf(bridgeSecrets.getManagedMT5SelectedProfile()) }
    var selectedBrokerBrand by remember { mutableStateOf(bridgeSecrets.getSelectedMT5BrokerBrand()) }
    val bridgeProfileKey = "${selectedBrokerBrand}_${selectedAccountMode}"
    val selectedBrokerCompanyName = com.example.data.broker.BrokerDirectory.byBrandId(selectedBrokerBrand)?.name
        ?: "Unsupported MT5 broker"
    val bridgeClient = remember(bridgeSecrets, bridgeProfileKey, selectedAccountMode, selectedBrokerBrand) {
        MT5RemoteBridgeClient(
            bridgeSecrets, bridgeProfileKey,
            { AppSecurityGuard.authorizeSensitiveAction() },
            accountMode = selectedAccountMode,
            brokerBrand = selectedBrokerBrand
        )
    }
    val reportContext = LocalContext.current
    val activeBrokerType by viewModel.activeBrokerType.collectAsStateWithLifecycle()
    var bridgeMode by remember(bridgeProfileKey, activeBrokerType) {
        mutableStateOf(activeBrokerType == BrokerType.MT5 && bridgeSecrets.getMT5BridgeAccountAppToken(bridgeProfileKey).isNotBlank())
    }
    var bridgeCode by remember(bridgeProfileKey) { mutableStateOf("") }
    var bridgeMessage by remember(bridgeProfileKey) { mutableStateOf(bridgeSecrets.getMT5BridgeAccountPendingCommandId(bridgeProfileKey).let { id -> if (id.isBlank()) "SETUP REQUIRED" else "Unresolved saved request $id; checking its acknowledgement. Do not resend." }) }
    var bridgeSnapshot by remember(bridgeProfileKey) { mutableStateOf<MT5RemoteBridgeClient.Snapshot?>(null) }
    var managedBridgeUrlInput by remember {
        mutableStateOf(MT5RemoteBridgeClient.initialServiceUrl(
            bridgeSecrets.getManagedMT5ServiceUrl(), com.example.BuildConfig.MT5_MANAGED_SERVICE_URL
        ))
    }
    var bridgeBusy by remember { mutableStateOf(false) }
    var bridgePendingId by remember(bridgeProfileKey) { mutableStateOf(bridgeSecrets.getMT5BridgeAccountPendingCommandId(bridgeProfileKey)) }
    var bridgePendingAction by remember(bridgeProfileKey) { mutableStateOf(bridgeSecrets.getMT5BridgeAccountPendingCommandAction(bridgeProfileKey)) }
    var bridgeCommandPending by remember(bridgeProfileKey) { mutableStateOf(bridgePendingId.isNotBlank() && bridgePendingAction.isNotBlank()) }
    var bridgePendingServerId by remember(bridgeProfileKey) { mutableStateOf("") }
    var bridgeTradePendingTickets by remember(bridgeProfileKey) { mutableStateOf(emptyList<String>()) }
    var reportStatus by remember(bridgeProfileKey) { mutableStateOf("Trade reports: waiting for authenticated broker ledger") }
    var bridgeExpectedState by remember(bridgeProfileKey) { mutableStateOf(when (bridgePendingAction) { "START", "RESUME" -> "RUNNING"; "PAUSE" -> "PAUSED"; "STOP", "EMERGENCY_STOP" -> "STOPPED"; else -> "" }) }
    var showLiveAuthorizationDialog by remember(bridgeProfileKey) { mutableStateOf(false) }
    var showCloseAllDialog by remember(bridgeProfileKey) { mutableStateOf(false) }
    var showBridgeDetails by remember(bridgeProfileKey) { mutableStateOf(false) }
    val bridgeToken = bridgeSecrets.getMT5BridgeAccountAppToken(bridgeProfileKey)
    // Shared fresh/profile-bound snapshot; Compose LazyColumn items have separate local scopes.
    val verifiedRemoteSnapshotForBot = bridgeSnapshot?.takeIf { snapshot ->
        bridgeMode && isUsableMt5Snapshot(
            snapshot, selectedAccountMode,
            bridgeSecrets.getManagedMT5AccountLogin(bridgeProfileKey),
            bridgeSecrets.getManagedMT5AccountServer(bridgeProfileKey)
        )
    }
    val bridgeIdentityFresh = bridgeSnapshot?.let {
        bridgeMode && it.connected && it.terminalOnline && it.heartbeatFresh && it.terminalConnected && it.accountVerified &&
            it.actualAccountMode == selectedAccountMode &&
            it.actualLogin == bridgeSecrets.getManagedMT5AccountLogin(bridgeProfileKey) &&
            it.actualServer == bridgeSecrets.getManagedMT5AccountServer(bridgeProfileKey)
    } == true
    val bridgeTerminalFresh = bridgeIdentityFresh
    val bridgeTerminalReady = bridgeSnapshot?.let { snapshot ->
        MT5RemoteBridgeClient.canStart(selectedAccountMode, snapshot.managedState, snapshot.accessType, snapshot.actualAccountMode,
            bridgeSecrets.getManagedMT5AccountLogin(bridgeProfileKey), snapshot.actualLogin,
            bridgeSecrets.getManagedMT5AccountServer(bridgeProfileKey), snapshot.actualServer,
            snapshot.state,
            snapshot.heartbeatFresh, snapshot.terminalConnected, snapshot.tradeAllowed, snapshot.accountVerified, snapshot.liveAuthorized,
            snapshot.newsVerdict?.optString("status", "UNKNOWN") == "CLEAR")
    } == true
    val bridgeProfileReady = bridgeSnapshot?.let { snapshot ->
        MT5RemoteBridgeClient.canStart(selectedAccountMode, snapshot.managedState, snapshot.accessType, snapshot.actualAccountMode,
            bridgeSecrets.getManagedMT5AccountLogin(bridgeProfileKey), snapshot.actualLogin,
            bridgeSecrets.getManagedMT5AccountServer(bridgeProfileKey), snapshot.actualServer,
            snapshot.state,
            snapshot.heartbeatFresh, snapshot.terminalConnected, snapshot.tradeAllowed, snapshot.accountVerified, true,
            snapshot.newsVerdict?.optString("status", "UNKNOWN") == "CLEAR")
    } == true
    val bridgeCanManagePositions = bridgeSnapshot?.let { snapshot ->
        MT5RemoteBridgeClient.canManageSnapshot(selectedAccountMode, bridgeSecrets.getManagedMT5AccountLogin(bridgeProfileKey),
            bridgeSecrets.getManagedMT5AccountServer(bridgeProfileKey), snapshot)
    } == true
    fun selectBridgeProfile(profile: String) {
        selectedAccountMode = profile
        bridgeSecrets.saveManagedMT5SelectedProfile(profile)
    }
    val bridgeDisplayState = when {
        !bridgeClient.managedConfigured() -> "CONFIGURATION_REQUIRED"
        bridgeToken.isBlank() -> "SETUP REQUIRED"
        !bridgeMode -> "REMOTE CONTROL DISABLED"
        bridgeCommandPending && bridgePendingAction in setOf("START", "RESUME") -> "STARTING / AWAITING ACK"
        bridgeCommandPending && bridgePendingAction in setOf("STOP", "EMERGENCY_STOP") -> "STOPPING / AWAITING ACK"
        verifiedRemoteSnapshotForBot != null -> bridgeSnapshot?.state ?: "UNKNOWN"
        bridgeIdentityFresh -> bridgeSnapshot?.managedState?.ifBlank { "PROVISIONING" } ?: "PROVISIONING"
        bridgeSnapshot != null -> "STALE / UNVERIFIED"
        else -> "OFFLINE"
    }
    val bridgeStatusColor = when {
        bridgeDisplayState == "RUNNING" -> ProfitGreen
        bridgeDisplayState == "READY" -> ElectricCyan
        bridgeDisplayState == "PAUSED" -> OrangeAccent
        bridgeDisplayState.contains("STALE") || bridgeDisplayState == "ERROR" || bridgeDisplayState == "FAILED" || bridgeDisplayState == "OFFLINE" -> LossRed
        else -> WarningAmber
    }
    val botSummary by viewModel.botStatusSummary.collectAsStateWithLifecycle()
    val botActive by viewModel.botActive.collectAsStateWithLifecycle()
    val derivServiceState by DerivDemoBotService.state.collectAsStateWithLifecycle()
    // Android 13+ hides the foreground-service notification (the bot's visible status and STOP surface) until granted.
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* the bot starts regardless; the in-app status card is the primary indicator */ }
    val derivAccount by viewModel.app.derivBrokerGateway.accountInfo.collectAsStateWithLifecycle()
    val activeBrokerGateway by viewModel.activeBrokerGateway.collectAsStateWithLifecycle()
    val brokerConnStatus by activeBrokerGateway.connectionStatus.collectAsStateWithLifecycle()
    val accountMetrics by activeBrokerGateway.accountMetrics.collectAsStateWithLifecycle()
    val openPositions by activeBrokerGateway.openPositions.collectAsStateWithLifecycle()

    val activeSymbol by viewModel.botSelectedTicker.collectAsStateWithLifecycle()
    val selectedStrategyId by viewModel.botSelectedStrategyId.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.botSelectedTimeframe.collectAsStateWithLifecycle()
    val riskPercent by viewModel.botRiskPercent.collectAsStateWithLifecycle()

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var brokerConnectionMessage by remember { mutableStateOf<String?>(null) }
    var mt5LoginInput by remember(bridgeProfileKey) { mutableStateOf(bridgeSecrets.getManagedMT5AccountLogin(bridgeProfileKey)) }
    var mt5AccessType by remember(bridgeProfileKey) { mutableStateOf(bridgeSecrets.getManagedMT5AccountAccessType(bridgeProfileKey)) }
    var mt5PasswordInput by remember(bridgeProfileKey) {
        mutableStateOf(if (mt5AccessType == "INVESTOR") "" else bridgeSecrets.getManagedMT5AccountPassword(bridgeProfileKey))
    }
    var mt5InvestorPasswordInput by remember(bridgeProfileKey) {
        mutableStateOf(if (mt5AccessType == "INVESTOR") bridgeSecrets.getManagedMT5AccountPassword(bridgeProfileKey) else "")
    }
    var mt5ServerInput by remember(bridgeProfileKey) { mutableStateOf(bridgeSecrets.getManagedMT5AccountServer(bridgeProfileKey)) }
    var managedCredentialConsent by remember(bridgeProfileKey) { mutableStateOf(false) }
    var showMt5Password by remember { mutableStateOf(false) }
    var isConnectingBroker by remember { mutableStateOf(false) }
    val terminalSymbol = viewModel.app.secretsManager.getBrokerSymbolOverrides()[activeSymbol]
        ?.trim()?.takeIf { it.isNotEmpty() } ?: activeSymbol.replace("/", "").replace(" ", "")
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(activeBrokerType, bridgeToken) {
        brokerConnectionMessage = null
        statusMessage = null
        isConnectingBroker = false
        bridgeMode = activeBrokerType == BrokerType.MT5 && bridgeToken.isNotBlank()
    }

    LaunchedEffect(bridgeMode, bridgeToken, bridgeProfileKey, selectedAccountMode, bridgeClient.baseUrl()) {
        if (bridgeMode && bridgeClient.baseUrl().isNotBlank() && bridgeToken.isNotBlank()) {
            runCatching { MT5AccountSessionService.start(reportContext) }
            var lastProcessedSessionSnapshot = ""
            while (true) {
                try {
                    val fresh = AccountSessionRegistry.snapshots.value[bridgeProfileKey]
                    if (fresh == null) {
                        delay(1_000)
                        continue
                    }
                    bridgeSnapshot = fresh
                    val sessionSnapshotKey = fresh.raw.optString("last_heartbeat_at", "") + ":" +
                        (fresh.raw.optJSONObject("last_command")?.toString() ?: "")
                    if (sessionSnapshotKey.isBlank() || sessionSnapshotKey == lastProcessedSessionSnapshot) {
                        delay(1_000)
                        continue
                    }
                    lastProcessedSessionSnapshot = sessionSnapshotKey
                    runCatching {
                        val syncPrefs = reportContext.getSharedPreferences("destinedfx_trade_sync", android.content.Context.MODE_PRIVATE)
                        val syncKey = bridgeProfileKey.lowercase()
                        val records = mutableListOf<JSONObject>(); var cursor = syncPrefs.getInt("${syncKey}_cursor", 0); var updatedAfter = syncPrefs.getString("${syncKey}_watermark", "0")?.toDoubleOrNull() ?: 0.0; var next: Int; var watermark = updatedAfter
                        do { val page = bridgeClient.tradeHistory(cursor, updatedAfter); next = page.cursor; watermark = maxOf(watermark, page.watermark); records += page.trades; if (next <= cursor) break; cursor = next } while (true)
                        val result = TradeReportManager.sync(reportContext, records)
                        syncPrefs.edit().putInt("${syncKey}_cursor", cursor).putString("${syncKey}_watermark", watermark.toString()).apply()
                        reportStatus = "Trade reports: ${result.generated} synchronized/generated, ${result.failed} failed"
                    }.onFailure { reportStatus = "Trade reports: sync unavailable; retrying (${it.javaClass.simpleName})" }
                    val cmd = fresh.raw.optJSONObject("last_command")
                    val observedServerId = cmd?.let { MT5RemoteBridgeClient.serverCommandId(it) }.orEmpty()
                    if (bridgeCommandPending && cmd != null && cmd.optString("request_id") == bridgePendingId &&
                        cmd.optString("action") == bridgePendingAction && (bridgePendingServerId.isBlank() || observedServerId == bridgePendingServerId) && cmd.optString("status") == "ACKNOWLEDGED") {
                        if (bridgePendingAction in MT5RemoteBridgeClient.TRADE_ACTIONS) {
                            val results = cmd.optJSONArray("results")
                            val statuses = linkedMapOf<String, String>()
                            val resultDetails = linkedMapOf<String, String>()
                            for (i in 0 until (results?.length() ?: 0)) {
                                val result = results?.optJSONObject(i) ?: continue
                                MT5RemoteBridgeClient.exactIdentifier(result, "ticket")?.let { ticket ->
                                    statuses[ticket] = result.optString("status", "UNKNOWN").uppercase().ifBlank { "UNKNOWN" }
                                    val remaining = result.opt("remaining_volume")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                                    resultDetails[ticket] = buildString {
                                        append("$ticket: ${statuses[ticket]}")
                                        if (remaining != null) append(" · remaining volume $remaining")
                                        result.optString("message").takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                    }
                                }
                            }
                            val resultTickets = if (bridgeTradePendingTickets.isNotEmpty()) bridgeTradePendingTickets else statuses.keys.toList()
                            if (bridgeTradePendingTickets.isEmpty() && resultTickets.isNotEmpty()) bridgeTradePendingTickets = resultTickets
                            val terminalResults = resultTickets.isNotEmpty() && resultTickets.all { statuses[it] in setOf("APPLIED", "REJECTED") }
                            val applied = resultTickets.filter { statuses[it] == "APPLIED" }
                            val rejected = resultTickets.filter { statuses[it] == "REJECTED" }
                            val appliedGone = applied.isEmpty() || MT5RemoteBridgeClient.effectConfirmed(bridgePendingAction, fresh, applied)
                            val perTicket = (if (resultTickets.isNotEmpty()) resultTickets else bridgeTradePendingTickets).joinToString("; ") { ticket ->
                                resultDetails[ticket] ?: "$ticket: ${statuses[ticket] ?: "PENDING"}"
                            }.ifBlank { "Waiting for server per-ticket results" }
                            if (terminalResults && appliedGone && fresh.heartbeatFresh) {
                                val action = bridgePendingAction
                                bridgeSecrets.clearMT5BridgeAccountPendingCommand(bridgeProfileKey)
                                bridgeSecrets.clearMT5BridgeAccountSafetyStop(bridgeProfileKey)
                                bridgeCommandPending = false; bridgePendingId = ""; bridgePendingAction = ""; bridgePendingServerId = ""; bridgeTradePendingTickets = emptyList()
                                bridgeMessage = "$action reconciled from server command $observedServerId and fresh account telemetry: ${applied.size} applied, ${rejected.size} rejected. $perTicket"
                            } else {
                                val partial = statuses.values.any { it == "PARTIAL" }
                                bridgeMessage = "${bridgePendingAction} command $observedServerId ${if (partial) "partially applied; unresolved volume remains" else "acknowledged; awaiting terminal per-ticket results and fresh telemetry"}. $perTicket. Pending request is retained; no close success is assumed."
                            }
                        } else when (cmd.optString("outcome")) {
                            "REJECTED" -> {
                                val action = bridgePendingAction
                                val original = bridgeSecrets.getMT5BridgeAccountSafetyOriginalId(bridgeProfileKey)
                                val originalAction = bridgeSecrets.getMT5BridgeAccountSafetyOriginalAction(bridgeProfileKey).takeIf { original.isNotBlank() }.orEmpty()
                                bridgeSecrets.clearMT5BridgeAccountPendingCommand(bridgeProfileKey)
                                if (original.isNotBlank()) { bridgeSecrets.saveMT5BridgeAccountPendingCommand(original, originalAction, bridgeProfileKey); bridgeSecrets.clearMT5BridgeAccountSafetyStop(bridgeProfileKey); bridgePendingId = original; bridgePendingAction = originalAction; bridgeExpectedState = when (originalAction) { "START", "RESUME" -> "RUNNING"; "PAUSE" -> "PAUSED"; else -> "STOPPED" }; bridgePendingServerId = ""; bridgeTradePendingTickets = emptyList(); bridgeCommandPending = true; bridgeMessage = "Safety STOP was rejected; original $originalAction request $original remains unresolved. Terminal state is unknown." }
                                else { bridgeCommandPending = false; bridgePendingId = ""; bridgePendingAction = ""; bridgePendingServerId = ""; bridgeTradePendingTickets = emptyList(); bridgeMessage = "$action rejected by EA: ${cmd.optString("message", "No reason supplied")}" }
                            }
                            "APPLIED" -> if (fresh.heartbeatFresh) {
                                val action = bridgePendingAction
                                val expected = bridgeExpectedState
                                val confirmed = fresh.state == expected && cmd.optString("state") == expected
                                if (confirmed) {
                                    bridgeSecrets.clearMT5BridgeAccountPendingCommand(bridgeProfileKey); bridgeSecrets.clearMT5BridgeAccountSafetyStop(bridgeProfileKey); bridgeCommandPending = false; bridgePendingId = ""; bridgePendingAction = ""; bridgePendingServerId = ""; bridgeTradePendingTickets = emptyList()
                                    bridgeMessage = "$action applied; fresh EA heartbeat confirms ${fresh.state}."
                                } else {
                                    bridgeMessage = "$action acknowledged as applied, but fresh EA heartbeat reports actual state ${fresh.state}; target state is not confirmed. Safety reconciliation remains pending."
                                }
                            }
                        }
                    }
                }
                catch (_: Exception) { bridgeSnapshot = null; bridgeMessage = if (bridgeCommandPending) "UNKNOWN request $bridgePendingId: bridge unreachable or authorization required. Do not resend; reconcile or revoke pairing." else "Bridge unreachable or authorization required" }
                delay(2000)
            }
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .testTag("bot_workstation_root"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("bot_connection_type_card")
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DESTINEDFX BOT · CONNECT ACCOUNT", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Choose the connection supported by your account. Credentials are sent only to that broker's existing integration.", color = TextSecondary, fontSize = 10.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                viewModel.selectBroker(BrokerType.MT5)
                                bridgeMode = bridgeToken.isNotBlank()
                            },
                            modifier = Modifier.weight(1f).testTag("select_managed_mt5"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (activeBrokerType == BrokerType.MT5) ElectricCyan else TextSecondary)
                        ) { Text("MT5 · MANAGED") }
                        OutlinedButton(
                            onClick = {
                                viewModel.selectBroker(BrokerType.DERIV)
                                bridgeMode = false
                            },
                            modifier = Modifier.weight(1f).testTag("select_direct_deriv"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (activeBrokerType == BrokerType.DERIV) ElectricCyan else TextSecondary)
                        ) { Text("DIRECT DERIV API") }
                    }
                }
            }
        }
        item {
            if (activeBrokerType == BrokerType.MT5) {
            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), modifier = Modifier.fillMaxWidth().testTag("mt5_remote_bridge_card")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("REMOTE MT5 EA BRIDGE", modifier = Modifier.weight(1f), color = ElectricCyan, fontWeight = FontWeight.Bold)
                        Switch(checked = bridgeMode, enabled = !bridgeBusy && !bridgeCommandPending && (bridgeToken.isBlank() || !bridgeMode), onCheckedChange = { bridgeMode = it })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("DEMO", "LIVE").forEach { mode ->
                            OutlinedButton(onClick = { selectBridgeProfile(mode) }, modifier = Modifier.weight(1f), enabled = !bridgeBusy) {
                                Text(if (mode == selectedAccountMode) "$mode PROFILE · SELECTED" else "$mode PROFILE", fontSize = 9.sp, color = if (mode == selectedAccountMode) ElectricCyan else TextSecondary)
                            }
                        }
                    }
                    Text("Bridge: ${if (bridgeSnapshot?.connected == true) "CONNECTED" else "DISCONNECTED"} · Terminal: ${when { !bridgeIdentityFresh && bridgeSnapshot != null -> "STALE / UNVERIFIED"; bridgeIdentityFresh -> "ONLINE · ACCOUNT VERIFIED"; else -> "UNVERIFIED" }} · EA: ${if (bridgeIdentityFresh) bridgeSnapshot?.state ?: "UNKNOWN" else "STALE / UNKNOWN"}", color = TextPrimary, fontSize = 11.sp)
                    OutlinedButton(onClick = { showBridgeDetails = !showBridgeDetails }, modifier = Modifier.fillMaxWidth().testTag("bridge_setup_details_toggle")) {
                        Text(if (showBridgeDetails) "HIDE CONNECTION DETAILS" else "CONNECTION SETUP & POSITION DETAILS", fontSize = 10.sp)
                    }
                    if (showBridgeDetails) {
                    val news = bridgeSnapshot?.newsVerdict.takeIf { verifiedRemoteSnapshotForBot != null }
                    val newsStatus = news?.optString("status", "UNKNOWN")?.uppercase() ?: "UNKNOWN"
                    Text(if (newsStatus == "CLEAR") "NEWS CLEAR · ${news?.optString("source", "provider")}" else if (newsStatus == "HIGH_IMPACT_WINDOW") "HIGH-IMPACT NEWS — TRADING BLOCKED" else "NEWS UNKNOWN — TRADING BLOCKED", color = if (newsStatus == "CLEAR") ProfitGreen else LossRed, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("News: ${news?.optString("detail", "Authenticated news verdict unavailable") ?: "Authenticated news verdict unavailable"} · retrieved ${news?.optString("retrieved_at_ms", "unavailable") ?: "unavailable"} · event ${news?.optString("event_time_ms", "unavailable") ?: "unavailable"}", color = TextSecondary, fontSize = 10.sp)
                    Text(reportStatus, color = TextSecondary, fontSize = 10.sp)
                    Text("Selected profile: $selectedAccountMode · Verified terminal: ${if (bridgeIdentityFresh) bridgeSnapshot?.account?.ifBlank { "not verified" } ?: "not verified" else "STALE / UNVERIFIED"} · Actual mode: ${if (bridgeIdentityFresh) bridgeSnapshot?.actualAccountMode?.ifBlank { "UNVERIFIED" } ?: "UNVERIFIED" else "UNVERIFIED"}", color = TextSecondary, fontSize = 10.sp)
                    Text("Managed service: ${if (bridgeClient.managedConfigured()) "configured" else "CONFIGURATION_REQUIRED"}", color = TextSecondary, fontSize = 10.sp)
                    OutlinedTextField(
                        value = managedBridgeUrlInput,
                        onValueChange = { managedBridgeUrlInput = it },
                        modifier = Modifier.fillMaxWidth().testTag("managed_bridge_url"),
                        enabled = !bridgeBusy,
                        label = { Text("Managed bridge HTTPS URL") },
                        placeholder = { Text("https://your-service.onrender.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        supportingText = { Text("Enter the public service origin only. Never enter a token or password here.") }
                    )
                    if (managedBridgeUrlInput.contains(".trycloudflare.com", ignoreCase = true)) {
                        Text(
                            "DEMO / DEVELOPMENT ONLY — Cloudflare Quick Tunnel URLs are temporary and are not a production endpoint.",
                            color = WarningAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(enabled = !bridgeBusy, onClick = {
                            val origin = MT5RemoteBridgeClient.validServiceOrigin(managedBridgeUrlInput)
                            when {
                                origin == null -> bridgeMessage = "Invalid bridge URL. Use a real public HTTPS origin without a path."
                                !bridgeSecrets.isSecureStorageAvailable -> bridgeMessage = "Secure storage is unavailable; URL was not saved."
                                !bridgeSecrets.saveManagedMT5ServiceUrl(origin) -> bridgeMessage = "Bridge URL could not be saved."
                                else -> {
                                    managedBridgeUrlInput = origin
                                    bridgeSnapshot = null
                                    bridgeMode = false
                                    bridgeMessage = "Bridge URL saved. Tap CHECK CONNECTION."
                                }
                            }
                        }) { Text("SAVE BRIDGE URL") }
                        OutlinedButton(enabled = !bridgeBusy && bridgeClient.managedConfigured(), onClick = {
                            coroutineScope.launch {
                                bridgeBusy = true
                                try {
                                    val snapshot = bridgeClient.state()
                                    bridgeSnapshot = snapshot
                                    bridgeMessage = "Bridge responded. Worker: ${snapshot.managedState.ifBlank { "UNKNOWN" }}; EA: ${snapshot.state}."
                                } catch (e: Exception) {
                                    bridgeMessage = e.message ?: "Bridge connection failed; status is unverified."
                                } finally { bridgeBusy = false }
                            }
                        }) { Text("CHECK CONNECTION") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(enabled = !bridgeBusy && bridgeSecrets.getMT5BridgeAccountAppToken(bridgeProfileKey).isNotBlank(), onClick = { coroutineScope.launch { bridgeBusy = true; try { bridgeClient.revoke(); bridgeSecrets.clearMT5BridgeAccountPendingCommand(bridgeProfileKey); bridgeCommandPending = false; bridgePendingId = ""; bridgePendingAction = ""; bridgeExpectedState = ""; bridgeSnapshot = null; bridgeMode = false; bridgeCode = ""; bridgeMessage = "$selectedAccountMode pairing revoked" } catch (_: Exception) { bridgeMessage = "Revoke not confirmed" }; bridgeBusy = false } }) { Text("Revoke $selectedAccountMode") }
                    }
                    if (bridgeMessage.isNotBlank()) Text(bridgeMessage, color = WarningAmber, fontSize = 10.sp)
                    val provisioningError = bridgeSnapshot?.raw?.optJSONObject("managed_execution")?.optString("error_message").orEmpty()
                    if (provisioningError.isNotBlank()) Text("Provisioning error: $provisioningError", color = LossRed, fontSize = 10.sp)
                    val bridgeReady = bridgeTerminalReady
                    val supportedStrategy = selectedStrategyId in StrategyRegistry.managedStrategyIds
                    val validBridgeRisk = MT5RemoteBridgeClient.isValidRiskPercent(riskPercent)
                    if (selectedAccountMode == "LIVE") {
                        Text("LIVE authorization: ${if (bridgeSnapshot?.liveAuthorized == true) "ACTIVE (server-verified, short-lived)" else "NOT AUTHORIZED"}", color = if (bridgeSnapshot?.liveAuthorized == true) WarningAmber else LossRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Button(enabled = !bridgeBusy && bridgeProfileReady && bridgeSnapshot?.liveAuthorized != true, onClick = { showLiveAuthorizationDialog = true }) {
                            Text("AUTHORIZE LIVE SESSION (15 MIN)")
                        }
                        if (bridgeSnapshot?.liveAuthorized == true) {
                            OutlinedButton(enabled = !bridgeBusy, onClick = { coroutineScope.launch { bridgeBusy = true; try { bridgeClient.clearLiveAuthorization(); bridgeSnapshot = bridgeClient.state(); bridgeMessage = "LIVE authorization revoked; new START/RESUME is blocked." } catch (_: Exception) { bridgeMessage = "LIVE authorization revocation not confirmed." }; bridgeBusy = false } }) { Text("REVOKE LIVE AUTHORIZATION") }
                        }
                        if (showLiveAuthorizationDialog) AlertDialog(
                            onDismissRequest = { showLiveAuthorizationDialog = false },
                            title = { Text("Authorize LIVE trading?") },
                            text = { Text("This confirms LIVE profile ${bridgeSecrets.getManagedMT5AccountLogin("${selectedBrokerBrand}_LIVE")} at ${bridgeSecrets.getManagedMT5AccountServer("${selectedBrokerBrand}_LIVE")} for up to 15 minutes. The connected MT5 terminal must independently report this exact account as LIVE with MASTER access and fresh readiness. This permits real-money entries after all risk checks; it does not liquidate positions or guarantee outcomes.") },
                            confirmButton = { TextButton(onClick = { showLiveAuthorizationDialog = false; coroutineScope.launch { bridgeBusy = true; try { val expiry = bridgeClient.authorizeLiveSession(); bridgeSnapshot = bridgeClient.state(); bridgeMessage = "LIVE authorization confirmed until $expiry." } catch (e: Exception) { bridgeMessage = e.message ?: "LIVE authorization failed closed." }; bridgeBusy = false } }) { Text("AUTHORIZE LIVE") } },
                            dismissButton = { TextButton(onClick = { showLiveAuthorizationDialog = false }) { Text("CANCEL") } }
                        )
                    }
                    Text("Managed EA strategy support is registry-validated; support does not imply profitability or real-broker testing.", color = WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    BridgeTelemetry(
                        if (bridgeMode) bridgeSnapshot else null, selectedAccountMode,
                        bridgeSecrets.getManagedMT5AccountLogin(bridgeProfileKey),
                        bridgeSecrets.getManagedMT5AccountServer(bridgeProfileKey)
                    )
                    val actionSnapshot = if (bridgeMode) bridgeSnapshot else null
                    val verifiedActionSnapshot = actionSnapshot?.takeIf { snapshot ->
                        isUsableMt5Snapshot(
                            snapshot, selectedAccountMode,
                            bridgeSecrets.getManagedMT5AccountLogin(bridgeProfileKey),
                            bridgeSecrets.getManagedMT5AccountServer(bridgeProfileKey)
                        )
                    }
                    val actionSnapshotVerified = verifiedActionSnapshot != null
                    val actionsReady = bridgeMode && bridgeCanManagePositions && actionSnapshotVerified && !bridgeBusy && !bridgeCommandPending
                    Text("SNAPSHOT-BOUND MANAGEMENT · $selectedAccountMode", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("mt5_snapshot_management_header"))
                    if (verifiedActionSnapshot != null) {
                        val verifiedOrders = verifiedActionSnapshot.activeOrders.orEmpty()
                        Text("PENDING ORDERS (${verifiedOrders.size})", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("mt5_pending_orders_section"))
                        if (verifiedOrders.isEmpty()) Text("No pending orders confirmed for this account.", color = TextSecondary, fontSize = 10.sp)
                        verifiedOrders.forEach { order ->
                            val orderTicket = MT5RemoteBridgeClient.exactIdentifier(order, "ticket")
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${order.optString("direction", "TYPE UNKNOWN")} ${order.optString("symbol", "?")} · ticket ${orderTicket ?: "unavailable"} · ${order.optString("state", "state unavailable")}", color = TextPrimary, fontSize = 10.sp)
                                    Text("Remaining ${order.optString("remaining_volume", "unavailable")} · SL ${order.optString("stop_loss", "unavailable")} · TP ${order.optString("take_profit", "unavailable")} · EA ownership ${if (order.opt("managed_by_ea") is Boolean) if (order.optBoolean("managed_by_ea")) "MANAGED" else "NOT MANAGED" else "UNVERIFIED"}", color = TextSecondary, fontSize = 9.sp)
                                }
                                OutlinedButton(
                                    enabled = actionsReady && orderTicket != null,
                                    modifier = Modifier.testTag("mt5_cancel_order_${orderTicket ?: "unavailable"}"),
                                    onClick = {
                                    if (bridgeBusy || bridgeCommandPending || orderTicket == null) return@OutlinedButton
                                    bridgeBusy = true
                                    coroutineScope.launch {
                                        try {
                                            val fresh = bridgeClient.state(); bridgeSnapshot = fresh
                                            val config = MT5RemoteBridgeClient.actionConfig(fresh, "CANCEL_PENDING_SELECTED", listOf(orderTicket))
                                            sendBridgeTradeCommand(bridgeClient, "CANCEL_PENDING_SELECTED", config, listOf(orderTicket), bridgeSecrets, bridgeProfileKey,
                                                { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgePendingServerId = it }, { bridgeTradePendingTickets = it }, { bridgeMessage = it })
                                        } catch (e: Exception) { bridgeMessage = e.message ?: "Order cancellation was not sent; snapshot is unverified."; bridgeBusy = false }
                                    }
                                }) { Text("CANCEL", fontSize = 9.sp) }
                            }
                        }
                        val managedTicketsAvailable = MT5RemoteBridgeClient.managedOrderTicketList(verifiedActionSnapshot)
                        val orderOwnershipKnown = verifiedActionSnapshot.activeOrders != null && verifiedActionSnapshot.activeOrders.orEmpty().all {
                            it.has("managed_by_ea") && it.opt("managed_by_ea") is Boolean
                        }
                        OutlinedButton(
                            enabled = actionsReady && managedTicketsAvailable != null,
                            modifier = Modifier.testTag("mt5_cancel_ea_pending_orders"),
                            onClick = {
                                if (bridgeBusy || bridgeCommandPending || managedTicketsAvailable == null) return@OutlinedButton
                                bridgeBusy = true
                                coroutineScope.launch {
                                    try {
                                        val fresh = bridgeClient.state(); bridgeSnapshot = fresh
                                        val tickets = MT5RemoteBridgeClient.managedOrderTicketList(fresh)
                                            ?: error("Fresh EA-owned pending-order identifiers are unavailable.")
                                        val config = MT5RemoteBridgeClient.actionConfig(fresh, "CANCEL_EA_PENDING")
                                        sendBridgeTradeCommand(bridgeClient, "CANCEL_EA_PENDING", config, tickets, bridgeSecrets, bridgeProfileKey,
                                            { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgePendingServerId = it }, { bridgeTradePendingTickets = it }, { bridgeMessage = it })
                                    } catch (e: Exception) { bridgeMessage = e.message ?: "EA pending-order cancellation was not sent."; bridgeBusy = false }
                                }
                            }
                        ) {
                            Text(
                                text = when {
                                    !orderOwnershipKnown -> "CANCEL EA PENDING ORDERS — OWNERSHIP UNKNOWN"
                                    managedTicketsAvailable == null -> "CANCEL EA PENDING ORDERS — NONE CONFIRMED"
                                    else -> "CANCEL EA PENDING ORDERS (${managedTicketsAvailable.size})"
                                },
                                fontSize = 9.sp
                            )
                        }
                        val verifiedPositions = verifiedActionSnapshot.positions.orEmpty()
                        Text("OPEN POSITIONS (${verifiedPositions.size})", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("mt5_open_positions_section"))
                        if (verifiedPositions.isEmpty()) Text("No open positions confirmed for this account.", color = TextSecondary, fontSize = 10.sp)
                        verifiedPositions.forEach { position ->
                            val positionTicket = MT5RemoteBridgeClient.exactIdentifier(position, "ticket")
                            val positionId = MT5RemoteBridgeClient.exactIdentifier(position, "position_id")
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${position.optString("direction", "TYPE UNKNOWN")} ${position.optString("symbol", "?")} · ticket ${positionTicket ?: "unavailable"}", color = TextPrimary, fontSize = 10.sp)
                                    Text("Volume ${position.optString("volume", "unavailable")} · P/L ${position.optString("floating_profit", "unavailable")} · SL ${position.optString("stop_loss", "unavailable")} · TP ${position.optString("take_profit", "unavailable")} · EA ownership ${if (position.opt("managed_by_ea") is Boolean) if (position.optBoolean("managed_by_ea")) "MANAGED" else "NOT MANAGED" else "UNVERIFIED"}", color = TextSecondary, fontSize = 9.sp)
                                }
                                OutlinedButton(
                                    enabled = actionsReady && positionId != null,
                                    modifier = Modifier.testTag("mt5_close_position_${positionId ?: "unavailable"}"),
                                    onClick = {
                                    if (bridgeBusy || bridgeCommandPending || positionId == null) return@OutlinedButton
                                    bridgeBusy = true
                                    coroutineScope.launch {
                                        try {
                                            val fresh = bridgeClient.state(); bridgeSnapshot = fresh
                                            val config = MT5RemoteBridgeClient.actionConfig(fresh, "CLOSE_POSITION_SELECTED", listOf(positionId))
                                            sendBridgeTradeCommand(bridgeClient, "CLOSE_POSITION_SELECTED", config, listOf(positionId), bridgeSecrets, bridgeProfileKey,
                                                { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgePendingServerId = it }, { bridgeTradePendingTickets = it }, { bridgeMessage = it })
                                        } catch (e: Exception) { bridgeMessage = e.message ?: "Position close was not sent; snapshot is unverified."; bridgeBusy = false }
                                    }
                                }) { Text("CLOSE", fontSize = 9.sp) }
                            }
                        }
                        val currentPositionIds = MT5RemoteBridgeClient.positionIdList(verifiedActionSnapshot)
                        Button(
                            enabled = actionsReady && currentPositionIds?.isNotEmpty() == true,
                            onClick = { if (actionsReady && currentPositionIds?.isNotEmpty() == true) showCloseAllDialog = true },
                            modifier = Modifier.fillMaxWidth().testTag("mt5_close_all_positions")
                        ) {
                            Text(
                                text = when {
                                    currentPositionIds == null -> "CLOSE ALL POSITIONS — UNAVAILABLE"
                                    currentPositionIds.isEmpty() -> "CLOSE ALL POSITIONS — NONE CONFIRMED"
                                    else -> "CLOSE ALL POSITIONS (${currentPositionIds.size})…"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text("PENDING ORDERS · UNAVAILABLE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("mt5_pending_orders_section"))
                        Text("Order actions require a fresh, verified terminal snapshot.", color = WarningAmber, fontSize = 10.sp)
                        OutlinedButton(enabled = false, onClick = {}, modifier = Modifier.testTag("mt5_cancel_ea_pending_orders")) { Text("CANCEL EA PENDING ORDERS") }
                        Text("OPEN POSITIONS · UNAVAILABLE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("mt5_open_positions_section"))
                        Text("Position actions require a fresh, verified terminal snapshot.", color = WarningAmber, fontSize = 10.sp)
                        Button(enabled = false, onClick = {}, modifier = Modifier.fillMaxWidth().testTag("mt5_close_all_positions")) { Text("CLOSE ALL POSITIONS — VERIFY CONNECTION", fontSize = 10.sp) }
                    }
                    if (showCloseAllDialog) AlertDialog(
                        onDismissRequest = { if (!bridgeBusy) showCloseAllDialog = false },
                        title = { Text("Close every current position?") },
                        text = { Text("Account ${actionSnapshot?.actualLogin?.takeIf { it.isNotBlank() } ?: "unverified"} @ ${actionSnapshot?.actualServer?.takeIf { it.isNotBlank() } ?: "unverified"} · profile $selectedAccountMode · positions ${actionSnapshot?.positions?.size ?: "unavailable"}. This closes every position in the fresh verified snapshot; pending orders are not included. It is irreversible and is not STOP/EMERGENCY STOP. A one-use server token is required, and no success is shown until every result and fresh telemetry reconcile.") },
                        confirmButton = { TextButton(enabled = !bridgeBusy && !bridgeCommandPending, onClick = {
                            showCloseAllDialog = false
                            if (bridgeBusy || bridgeCommandPending) return@TextButton
                            bridgeBusy = true
                            coroutineScope.launch {
                                try {
                                    val confirmation = bridgeClient.requestCloseAllConfirmation()
                                    val fresh = bridgeClient.state(); bridgeSnapshot = fresh
                                    val config = MT5RemoteBridgeClient.actionConfig(fresh, "CLOSE_ALL", confirmation.tickets, confirmation.token)
                                    sendBridgeTradeCommand(bridgeClient, "CLOSE_ALL", config, confirmation.tickets, bridgeSecrets, bridgeProfileKey,
                                        { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgePendingServerId = it }, { bridgeTradePendingTickets = it }, { bridgeMessage = it })
                                } catch (e: Exception) { bridgeMessage = e.message ?: "Close-all confirmation or command failed closed."; bridgeBusy = false }
                            }
                        }) { Text("CONFIRM CLOSE ALL") } },
                        dismissButton = { TextButton(onClick = { showCloseAllDialog = false }) { Text("CANCEL") } }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("PAUSE", "RESUME", "STOP", "EMERGENCY_STOP").forEach { action ->
                            val needsTradingReady = action == "PAUSE" || action == "RESUME"
                            val safetyOverride = bridgeCommandPending && action == "STOP"
                            OutlinedButton(enabled = bridgeMode && (if (needsTradingReady) bridgeReady else bridgeSnapshot?.connected == true) && !bridgeBusy && (!bridgeCommandPending || safetyOverride), onClick = {
                                coroutineScope.launch { sendBridgeCommand(bridgeClient, action, JSONObject(), bridgeSecrets, bridgeProfileKey, { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgeExpectedState = it }, { bridgeMessage = it }) }
                            }) { Text(action.replace('_', ' '), fontSize = 9.sp) }
                        }
                    }
                    Text("POSITION SAFETY: STOP, PAUSE and EMERGENCY STOP block new entries only. They do not close existing positions or move broker SL/TP; manage open positions directly on the MT5 terminal.", color = WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (!viewModel.app.secretsManager.isSecureStorageAvailable) Text("Secure storage unavailable; pairing and app-token persistence are disabled.", color = LossRed, fontSize = 10.sp)
                    if (bridgeMode && !bridgeReady) Text("SETUP REQUIRED — START needs a verified account, connected/trading-ready terminal and fresh heartbeat.", color = LossRed, fontSize = 10.sp)
                    if (bridgeMode && !supportedStrategy) Text("START BLOCKED — selected strategy is not in the managed EA registry.", color = LossRed, fontSize = 10.sp)
                    if (bridgeMode && !validBridgeRisk) Text("START BLOCKED — risk must be finite and greater than 0 and no more than 1%; no clamping is applied.", color = LossRed, fontSize = 10.sp)
                    if (bridgeCommandPending && bridgeMessage.contains("No confirmed response")) Text("UNKNOWN request $bridgePendingId — do not resend. Refresh/reconcile this ID with bridge last_command or recover manually; no duplicate was sent.", color = LossRed, fontSize = 10.sp)
                    Text(if (bridgeMode) "Bridge control operates on the remote terminal; the terminal must stay online independently." else "Bridge mode off; current broker mode unchanged.", color = TextSecondary, fontSize = 10.sp)
                    }
                }
            }
            }
        }
        item {
            if (activeBrokerType in setOf(BrokerType.MT5, BrokerType.DERIV)) {
            val isConnected = if (bridgeMode) bridgeTerminalFresh else brokerConnStatus == BrokerConnectionStatus.CONNECTED
            val statusColor = if (bridgeMode) bridgeStatusColor else when (botSummary.state) {
                BotLifecycleState.ACTIVE_SCANNING -> ProfitGreen
                BotLifecycleState.EXECUTING -> ElectricCyan
                BotLifecycleState.CONNECTING_BROKER -> OrangeAccent
                BotLifecycleState.ERROR -> LossRed
                else -> if (isConnected) ProfitGreen else WarningAmber
            }
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.42f))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(statusColor.copy(alpha = 0.13f)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = statusColor, modifier = Modifier.size(19.dp))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("BOT TERMINAL", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                            Text("${if (bridgeMode) "MT5 EA" else activeBrokerType.name} · ${if (bridgeMode) selectedAccountMode else "LOCAL"}", fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                            Text("$activeSymbol · $selectedTimeframe · $selectedStrategyId",
                                fontSize = 9.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                        }
                        Box(Modifier.clip(RoundedCornerShape(7.dp)).background(statusColor.copy(alpha = 0.14f))
                            .padding(horizontal = 7.dp, vertical = 4.dp)) {
                            Text(if (isConnected) "CONNECTED" else "OFFLINE", fontSize = 8.sp, color = statusColor,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                    }
                    HorizontalDivider(color = CardBorder)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("BOT STATE", fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text(if (bridgeMode) bridgeDisplayState else botSummary.state.name.replace('_', ' '), fontSize = 12.sp, color = statusColor,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("BROKER", fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text(if (bridgeMode) "MT5 EA" else activeBrokerType.name, fontSize = 12.sp, color = TextPrimary,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("RISK LIMIT", fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text("${riskPercent}% (max 1%)", fontSize = 12.sp, color = WarningAmber,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text(if (bridgeMode) "Account: ${bridgeSnapshot?.actualLogin?.takeIf { bridgeIdentityFresh } ?: "—"} · ${bridgeSnapshot?.actualServer?.takeIf { bridgeIdentityFresh } ?: "server unverified"}"
                        else "Connection: ${brokerConnStatus.name.replace('_', ' ')} · metrics appear when available",
                        fontSize = 9.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                    val verifiedLocalMetrics = accountMetrics.takeIf { !bridgeMode && isConnected }
                    val verifiedBridgeMetrics = verifiedRemoteSnapshotForBot.takeIf { bridgeMode }
                    val currency = verifiedBridgeMetrics?.currency ?: verifiedLocalMetrics?.currency.orEmpty()
                    val balance = verifiedBridgeMetrics?.balance ?: verifiedLocalMetrics?.balance
                    val equity = verifiedBridgeMetrics?.equity ?: verifiedLocalMetrics?.equity
                    val freeMargin = verifiedBridgeMetrics?.freeMargin ?: verifiedLocalMetrics?.freeMargin
                    val positionCount = if (bridgeMode) verifiedBridgeMetrics?.positions?.size else openPositions.size.takeIf { isConnected }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        StatChip("BALANCE", balance?.let { "$currency ${String.format(java.util.Locale.US, "%.2f", it)}" } ?: "—",
                            Modifier.weight(1f), TextPrimary)
                        StatChip("EQUITY", equity?.let { "$currency ${String.format(java.util.Locale.US, "%.2f", it)}" } ?: "—",
                            Modifier.weight(1f), TextPrimary)
                        StatChip("FREE MARGIN", freeMargin?.let { "$currency ${String.format(java.util.Locale.US, "%.2f", it)}" } ?: "—",
                            Modifier.weight(1f), TextPrimary)
                        StatChip("POSITIONS", positionCount?.toString() ?: "—",
                            Modifier.weight(0.8f), ElectricCyan)
                    }
                }
            }
            }
        }
        item {
            if (activeBrokerType == BrokerType.DERIV) {
            DerivDemoAccountCard(viewModel)
            }
        }
        // Master Bot Control Card
        item {
            val derivServiceActive = DerivBotServiceStatePolicy.isSwitchChecked(derivServiceState)
            val isRunning = if (bridgeMode) bridgeDisplayState in setOf("RUNNING", "PAUSED") else if (activeBrokerType == BrokerType.DERIV) derivServiceActive else botSummary.isRunning || botActive
            val bridgeFresh = bridgeTerminalFresh
            val bridgeReady = bridgeTerminalReady
            val bridgeCanStart = bridgeReady && (bridgeSnapshot?.state == "READY" || bridgeSnapshot?.state == "STOPPED")
            val supportedStrategy = selectedStrategyId in StrategyRegistry.managedStrategyIds
            val validBridgeRisk = MT5RemoteBridgeClient.isValidRiskPercent(riskPercent)
            val derivExecutionMode = activeBrokerType == BrokerType.DERIV
            val derivSymbolSupported = DerivMultiplierRules.isSupportedUnderlying(activeSymbol)
            val statusColor = if (bridgeMode) bridgeStatusColor else if (activeBrokerType == BrokerType.DERIV) when (derivServiceState) {
                "RUNNING" -> ProfitGreen
                "CONNECTING", "RECONCILING", "PAUSED" -> OrangeAccent
                "ERROR" -> LossRed
                else -> TextSecondary
            } else when (botSummary.state) {
                BotLifecycleState.ACTIVE_SCANNING -> ProfitGreen
                BotLifecycleState.EXECUTING -> CyanPrimary
                BotLifecycleState.CONNECTING_BROKER -> OrangeAccent
                BotLifecycleState.ERROR -> LossRed
                else -> if (isRunning) ProfitGreen else TextSecondary
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, if (isRunning) statusColor.copy(alpha = 0.6f) else TabBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("bot_master_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = "BOT STATUS: ${if (bridgeMode) bridgeDisplayState else if (activeBrokerType == BrokerType.DERIV) derivServiceState else botSummary.state.name}",
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = statusColor
                            )
                        }

                        Switch(
                            checked = isRunning,
                            enabled = if (bridgeMode) (!bridgeBusy && !bridgeCommandPending && (if (isRunning) bridgeFresh else bridgeCanStart && supportedStrategy && validBridgeRisk)) else if (derivExecutionMode) {
                                if (isRunning) DerivBotServiceStatePolicy.canStop(derivServiceState)
                                else DerivBotServiceStatePolicy.canStart(derivServiceState)
                            } else true,
                            onCheckedChange = { active ->
                                if (active && !AppSecurityGuard.authorizeSensitiveAction()) {
                                    statusMessage = "REAUTHENTICATION REQUIRED — authenticate before starting or resuming trading."
                                    (reportContext as? com.example.MainActivity)?.showBiometricPrompt()
                                } else if (bridgeMode) coroutineScope.launch {
                                    bridgeBusy = true
                                    val action = if (active) "START" else "STOP"
                                    val config = JSONObject()
                                        .put("strategy", selectedStrategyId)
                                        .put("symbols", JSONArray().put(terminalSymbol))
                                        .put("timeframes", JSONArray().put(selectedTimeframe))
                                        .put("risk_percent", riskPercent)
                                        .put("max_daily_loss_percent", TradingSafetyLimits.MAX_DAILY_LOSS_PERCENT)
                                        .put("max_concurrent_positions", TradingSafetyLimits.MAX_CONCURRENT_POSITIONS)
                                    if (active && !supportedStrategy) { bridgeMessage = "Blocked: selected strategy is not in the managed EA registry."; bridgeBusy = false; return@launch }
                                    if (active && (!MT5RemoteBridgeClient.isValidRiskPercent(riskPercent))) { bridgeMessage = "Blocked: risk must be finite and in (0, 1]%; no clamping applied."; bridgeBusy = false; return@launch }
                                    if (active && !bridgeCanStart) { bridgeMessage = "Blocked: verified account must be READY or STOPPED with a fresh, trade-authorized heartbeat."; bridgeBusy = false; return@launch }
                                    sendBridgeCommand(bridgeClient, action, config, bridgeSecrets, bridgeProfileKey, { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgeExpectedState = it }, { bridgeMessage = it })
                                } else if (derivExecutionMode) {
                                    if (active) {
                                        when {
                                            derivAccount == null || !DerivMultiplierRules.isVerifiedAccount(derivAccount) -> statusMessage = "START BLOCKED — verify the selected active Deriv account first."
                                            derivAccount?.mode == "real" && !bridgeSecrets.isDerivRealExecutionEnabled() -> statusMessage = "START BLOCKED — ARM LIVE TRADING for ${derivAccount?.loginId} first."
                                            !derivSymbolSupported -> statusMessage = "START BLOCKED — $activeSymbol is a Forex/CFD symbol, not a supported Deriv Multiplier underlying. Select an available synthetic index."
                                            else -> {
                                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                                    androidx.core.content.ContextCompat.checkSelfPermission(reportContext, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                                ) {
                                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                                }
                                                DerivDemoBotService.start(reportContext, activeSymbol, selectedStrategyId, selectedTimeframe)
                                                statusMessage = "START REQUESTED — waiting for the foreground service to verify the selected ${derivAccount?.mode?.uppercase()} account and broker state."
                                            }
                                        }
                                    } else {
                                        DerivDemoBotService.stop(reportContext)
                                        statusMessage = "STOP REQUESTED — no new entry requests will be generated; known contracts remain broker-tracked."
                                    }
                                } else viewModel.setBotStatus(active) { success, err ->
                                    statusMessage = if (success) {
                                        if (active) "Start accepted; verify the engine status and account heartbeat before relying on it."
                                        else "Bot stopped; existing positions are not automatically closed."
                                    } else err ?: "Failed to change engine state"
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DarkBackground,
                                checkedTrackColor = ProfitGreen,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = DarkSurfaceVariant
                            ),
                            modifier = Modifier.testTag("bot_toggle_switch")
                        )
                    }

                    HorizontalDivider(color = TabBorder)
                    Text(
                        text = when {
                            bridgeMode -> "Automatic broker-wide symbol discovery and strategy ranking are not implemented. MT5 currently scans the configured symbols with one selected strategy."
                            derivExecutionMode -> "Automatic multi-symbol ranking is not implemented. Direct Deriv currently scans the selected supported underlying and strategy only."
                            else -> "Automatic broker-wide symbol discovery and strategy ranking are not implemented for the current local execution route."
                        },
                        color = WarningAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("bot_auto_selection_gap")
                    )
                    if (derivExecutionMode) {
                        Text(
                            "DERIV MULTIPLIER: $derivServiceState · supports only MULTUP/MULTDOWN on a verified DEMO or explicitly armed REAL synthetic account. Forex/CFD plans and MT5 execution remain blocked.",
                            color = WarningAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }

                    // Last Action & Logs
                    Text(
                        text = "LAST ACTION: ${botSummary.lastAction}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )

                    if (botSummary.lastError != null) {
                        Text(
                            text = "ERROR: ${botSummary.lastError}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = LossRed
                        )
                    }

                    val msg = statusMessage
                    if (msg != null) {
                        Text(
                            text = msg,
                            fontSize = 10.sp,
                            color = CyanPrimary
                        )
                    }
                }
            }
        }

        // Target Pair Selection & Autonomous Balance & Free Margin Audit
        item {
            val dynamicAnalysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
            val ticker = viewModel.tickers.collectAsStateWithLifecycle().value[activeSymbol]
            val cmp = ticker?.price?.takeIf { it.isFinite() && it > 0.0 && viewModel.isDataFresh(activeSymbol) } ?: 0.0
            val isBullish = (dynamicAnalysis?.direction ?: "BUY").contains("BUY", true) || (dynamicAnalysis?.direction ?: "").contains("LONG", true)
            val effectiveEntry = if (dynamicAnalysis != null && dynamicAnalysis!!.entry > 0.0) dynamicAnalysis!!.entry else cmp
            val effectiveSl = if (dynamicAnalysis != null && dynamicAnalysis!!.stopLoss > 0.0) dynamicAnalysis!!.stopLoss else 0.0
            val effectiveTp1 = if (dynamicAnalysis != null && dynamicAnalysis!!.tp1 > 0.0) dynamicAnalysis!!.tp1 else 0.0
            val effectiveTp2 = dynamicAnalysis?.tp2 ?: 0.0
            val effectiveTp3 = dynamicAnalysis?.tp3 ?: 0.0

            var reqMarginState by remember(activeSymbol, cmp, brokerConnStatus, bridgeMode) { mutableStateOf(0.0) }
            var reqMarginVerified by remember(activeSymbol, cmp, brokerConnStatus, bridgeMode) { mutableStateOf(false) }
            LaunchedEffect(activeSymbol, cmp, brokerConnStatus, bridgeMode) {
                reqMarginState = 0.0
                reqMarginVerified = false
                if (cmp <= 0.0 || !cmp.isFinite()) return@LaunchedEffect
                // A local margin result is verified only when returned by a connected broker.
                // Remote MT5 performs its own final margin check in the EA before each order.
                if (!bridgeMode && brokerConnStatus == BrokerConnectionStatus.CONNECTED) {
                    val result = runCatching { activeBrokerGateway.calculateRequiredMargin(activeSymbol, 0.01) }.getOrNull()
                    val margin = result?.getOrNull()
                    if (result?.isSuccess == true && margin != null && margin.isFinite() && margin > 0.0) {
                        reqMarginState = margin
                        reqMarginVerified = true
                    }
                }
            }

            val availablePairs = listOf("EUR/USD", "GBP/USD", "XAU/USD", "BTC/USD", "NAS100", "SPX500", "USD/JPY", "USD/CAD", "USD/CHF")

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("bot_pair_margin_audit_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "BOT INSTRUMENT SELECTION & MARGIN CHECK",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = ElectricCyan
                            )
                            Text(
                                text = "Select a pair • broker-confirmed margin is required for a pass state",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background((when {
                                    reqMarginVerified -> ProfitGreen
                                    bridgeMode && verifiedRemoteSnapshotForBot != null -> ElectricCyan
                                    else -> WarningAmber
                                }).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = when {
                                    reqMarginVerified -> "BROKER VERIFIED"
                                    bridgeMode && verifiedRemoteSnapshotForBot != null -> "MT5 EA CHECKS MARGIN"
                                    else -> "UNVERIFIED"
                                },
                                fontSize = 8.sp,
                                color = when {
                                    reqMarginVerified -> ProfitGreen
                                    bridgeMode && verifiedRemoteSnapshotForBot != null -> ElectricCyan
                                    else -> WarningAmber
                                },
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    HorizontalDivider(color = CardBorder)

                    Text(
                        text = "1. TARGET TRADING INSTRUMENT",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(availablePairs) { pair ->
                            val isSelected = pair == activeSymbol
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.selectBotTicker(pair) },
                                label = {
                                    Text(
                                        text = pair,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan,
                                    selectedLabelColor = DarkBackground,
                                    containerColor = DarkSurfaceVariant,
                                    labelColor = TextPrimary
                                ),
                                modifier = Modifier.testTag("bot_pair_chip_$pair")
                            )
                        }
                    }

                    Text("2. BOT STRATEGY • changes apply on the next explicit start", fontSize = 9.sp,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = TextSecondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(StrategyRegistry.allStrategies.filter { it.botExecutionEligible }) { strategy ->
                            FilterChip(
                                selected = selectedStrategyId == strategy.id,
                                onClick = { viewModel.selectBotStrategy(strategy.id) },
                                label = { Text(strategy.name, fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.testTag("bot_strategy_chip_${strategy.id}")
                            )
                        }
                    }

                    Text("3. BOT PRIMARY TIMEFRAME • running Bot keeps its captured timeframe", fontSize = 9.sp,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = TextSecondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("M15", "M30", "H1", "H4", "D1", "W1")) { timeframe ->
                            FilterChip(
                                selected = selectedTimeframe == timeframe,
                                onClick = { viewModel.selectBotTimeframe(timeframe) },
                                label = { Text(timeframe, fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.testTag("bot_timeframe_chip_$timeframe")
                            )
                        }
                    }

                    Text("4. BOT RISK PER TRADE • hard maximum 1%", fontSize = 9.sp,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.25, 0.5, 1.0).forEach { risk ->
                            FilterChip(
                                selected = riskPercent == risk,
                                onClick = { viewModel.setBotRiskPercent(risk) },
                                label = { Text("$risk%", fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.testTag("bot_risk_chip_$risk")
                            )
                        }
                    }

                    HorizontalDivider(color = CardBorder)

                    Text(
                        text = "5. ACCOUNT BALANCE, EQUITY & AUTO LOT AUDIT",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )

                    val remoteMetrics = bridgeSnapshot.takeIf { snapshot ->
                        bridgeMode && isUsableMt5Snapshot(
                            snapshot, selectedAccountMode,
                            bridgeSecrets.getManagedMT5AccountLogin(bridgeProfileKey),
                            bridgeSecrets.getManagedMT5AccountServer(bridgeProfileKey)
                        )
                    }
                    val localMetricsVerified = !bridgeMode && brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null
                    val localFreeMargin = if (localMetricsVerified) accountMetrics!!.freeMargin.takeIf { it.isFinite() && it >= 0.0 } else null
                    val remoteFreeMargin = remoteMetrics?.freeMargin?.takeIf { it.isFinite() && it >= 0.0 }
                    val verifiedFreeMargin = remoteFreeMargin ?: localFreeMargin
                    val freeMarginVal = verifiedFreeMargin ?: 0.0
                    val marginAccountVerified = verifiedFreeMargin != null
                    val verifiedBalance = remoteMetrics?.balance?.takeIf { it.isFinite() && it >= 0.0 }
                        ?: if (localMetricsVerified) accountMetrics!!.balance.takeIf { it.isFinite() && it >= 0.0 } else null
                    val verifiedEquity = remoteMetrics?.equity?.takeIf { it.isFinite() && it >= 0.0 }
                        ?: if (localMetricsVerified) accountMetrics!!.equity.takeIf { it.isFinite() && it >= 0.0 } else null
                    val verifiedLeverage = remoteMetrics?.leverage ?: if (localMetricsVerified) accountMetrics!!.leverage.takeIf { it > 0 } else null
                    val sizingInputsVerified = verifiedFreeMargin != null && cmp > 0.0 &&
                        effectiveEntry.isFinite() && effectiveEntry > 0.0 && effectiveSl.isFinite() && effectiveSl > 0.0
                    val sizedResult = remember(activeSymbol, verifiedFreeMargin, riskPercent, effectiveEntry, effectiveSl, sizingInputsVerified) {
                        if (sizingInputsVerified) viewModel.sizeFor(activeSymbol, verifiedFreeMargin!!, riskPercent, effectiveEntry, effectiveSl) else null
                    }
                    val lotDisplay = when {
                        verifiedFreeMargin == null -> "UNVERIFIED"
                        !sizingInputsVerified -> "PLAN / PRICE REQUIRED"
                        sizedResult is com.destinedfx.core.risk.SizingResult.Sized -> "%.2f Lots".format(sizedResult.lots)
                        else -> "BLOCKED"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StatChip(
                            label = "BALANCE",
                            value = verifiedBalance?.let { String.format(java.util.Locale.US, "%,.2f", it) } ?: "UNVERIFIED",
                            modifier = Modifier.weight(1f),
                            color = if (verifiedBalance != null) TextPrimary else WarningAmber
                        )
                        StatChip(
                            label = "EQUITY",
                            value = verifiedEquity?.let { String.format(java.util.Locale.US, "%,.2f", it) } ?: "UNVERIFIED",
                            modifier = Modifier.weight(1f),
                            color = if (verifiedEquity != null) TextPrimary else WarningAmber
                        )
                        StatChip(
                            label = "LEVERAGE",
                            value = verifiedLeverage?.let { "1:${it}" } ?: "UNVERIFIED",
                            modifier = Modifier.weight(1f),
                            color = if (verifiedLeverage != null) CyanPrimary else WarningAmber
                        )
                        StatChip(
                            label = "AUTO LOT",
                            value = lotDisplay,
                            modifier = Modifier.weight(1f),
                            color = ElectricCyan
                        )
                    }

                    val isMarginSafe = marginAccountVerified && reqMarginVerified && verifiedFreeMargin != null &&
                        verifiedFreeMargin >= reqMarginState && reqMarginState > 0.0
                    val marginStatusColor = when {
                        isMarginSafe -> ProfitGreen
                        marginAccountVerified && reqMarginVerified -> LossRed
                        bridgeMode && verifiedRemoteSnapshotForBot != null -> ElectricCyan
                        else -> WarningAmber
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(marginStatusColor.copy(alpha = 0.12f))
                            .border(1.dp, marginStatusColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = when {
                                    !marginAccountVerified -> "MARGIN UNVERIFIED • FRESH ACCOUNT SNAPSHOT REQUIRED"
                                    !reqMarginVerified && bridgeMode && verifiedRemoteSnapshotForBot != null -> "MT5 EA VALIDATES MARGIN BEFORE EVERY ORDER"
                                    !reqMarginVerified -> "REQUIRED MARGIN UNVERIFIED • BROKER CHECK REQUIRED"
                                    isMarginSafe -> "BROKER MARGIN CHECK PASSED"
                                    else -> "INSUFFICIENT FREE MARGIN"
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = marginStatusColor
                            )
                            Text(
                                text = when {
                                    !marginAccountVerified -> "No fresh account-bound free-margin snapshot is available. Saved capital is never treated as live free margin."
                                    !reqMarginVerified && bridgeMode && verifiedRemoteSnapshotForBot != null -> "Free margin is from a fresh verified MT5 snapshot. The remote EA must confirm required margin at the actual order boundary; this panel does not declare an entry safe."
                                    !reqMarginVerified -> "Required margin could not be verified by the connected broker. No margin pass is declared."
                                    isMarginSafe -> "Verified free margin (${String.format(java.util.Locale.US, "%.2f", freeMarginVal)}) covers broker-reported required margin (${String.format(java.util.Locale.US, "%.2f", reqMarginState)}). The terminal still performs final risk checks."
                                    else -> "Verified free margin (${String.format(java.util.Locale.US, "%.2f", freeMarginVal)}) is below broker-reported required margin (${String.format(java.util.Locale.US, "%.2f", reqMarginState)}). New entries must remain blocked."
                                },
                                fontSize = 10.sp,
                                color = TextPrimary
                            )
                        }
                    }

                    HorizontalDivider(color = CardBorder)

                    Text(
                        text = "3. BOT ENTRY, STOP LOSS & TARGET PLAN",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )

                    if (effectiveEntry > 0.0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("ENTRY / CMP", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(formatPrice(effectiveEntry, activeSymbol), fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            }
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("STOP LOSS", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(if (effectiveSl > 0.0) formatPrice(effectiveSl, activeSymbol) else "AWAITING SMC", fontSize = 11.sp, color = LossRed, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            }
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("TP1", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(if (effectiveTp1 > 0.0) formatPrice(effectiveTp1, activeSymbol) else "AWAITING SMC", fontSize = 11.sp, color = ProfitGreen, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            }
                        }

                        if (effectiveTp2 > 0.0 || effectiveTp3 > 0.0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (effectiveTp2 > 0.0) {
                                    Column(
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text("TP2 (EXTENDED)", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                        Text(formatPrice(effectiveTp2, activeSymbol), fontSize = 11.sp, color = ProfitGreen, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                    }
                                }
                                if (effectiveTp3 > 0.0) {
                                    Column(
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text("TP3 (RUNWAY)", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                        Text(formatPrice(effectiveTp3, activeSymbol), fontSize = 11.sp, color = ProfitGreen, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Break-Even Guidance: Shift Stop Loss to Entry (${formatPrice(effectiveEntry, activeSymbol)}) once price reaches TP1.",
                            fontSize = 9.5.sp,
                            color = ElectricCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = "Awaiting market quote and analysis setup for $activeSymbol...",
                            fontSize = 10.sp,
                            color = WarningAmber
                        )
                    }
                }
            }
        }

        // Engine Safeguards & Quantitative Controls
        item {
            val capital by viewModel.accountCapital.collectAsStateWithLifecycle()
            val strat by viewModel.activeStrategy.collectAsStateWithLifecycle()
            val nfpArmed by viewModel.nfpProtectionArmed.collectAsStateWithLifecycle()
            val isHalted by viewModel.showHaltNotification.collectAsStateWithLifecycle()
            val isMultiScanning by viewModel.isMultiTimeframeAnalysisScanning.collectAsStateWithLifecycle()
            val consensusStatus by viewModel.multiTimeframeAnalysisStatus.collectAsStateWithLifecycle()

            InsightCard("ENGINE SAFEGUARDS & QUANT CONTROLS") {
                InsightLine("Active Strategy", strat)
                val verifiedDisplayBalance = verifiedRemoteSnapshotForBot?.balance?.takeIf { it.isFinite() && it >= 0.0 }
                InsightLine(
                    if (verifiedDisplayBalance != null) "Verified MT5 Balance" else "Configured Capital (not live balance)",
                    if (verifiedDisplayBalance != null) "${verifiedRemoteSnapshotForBot?.currency?.takeIf { it.isNotBlank() } ?: "Currency"} ${java.lang.String.format(java.util.Locale.US, "%.2f", verifiedDisplayBalance)}" else java.lang.String.format(java.util.Locale.US, "%.2f", capital)
                )
                InsightLine("NFP Safeguard", if (nfpArmed) "ARMED (High-Impact Guard Active)" else "DISARMED")
                InsightLine("News / Halt Guard", if (isHalted) "HALTED (News Filter Lock)" else "No engine halt; broker/risk checks still apply")
                InsightLine("Deterministic MTF Scan", if (isMultiScanning) "SCANNING (H1 / M15 / H4)" else consensusStatus)
                if (isHalted) {
                    Text(
                        text = "Trading halted due to high-impact economic event window.",
                        color = LossRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // HARDENED APP SHIELD & HIGH-IMPACT NEWS PROTECTION
        item {
            val context = LocalContext.current
            val isSecureStorageAvailable by viewModel.secureStorageAvailable.collectAsStateWithLifecycle()
            val secReport = remember(isSecureStorageAvailable) {
                AppSecurityGuard.auditAppSecurity(context, isSecureStorageAvailable)
            }
            val newsAlert by HighImpactNewsSentry.sentryAlert.collectAsStateWithLifecycle()
            var autoExitNewsEnabled by remember { mutableStateOf(true) }

            // Periodically check high-impact news for selected asset
            LaunchedEffect(activeSymbol, brokerConnStatus) {
                while (true) {
                    try {
                        HighImpactNewsSentry.auditAndProtectPositions(
                            symbol = activeSymbol,
                            gateway = activeBrokerGateway,
                            calendarResult = null, // Will use cached/real calendar if available
                            autoExitEnabled = autoExitNewsEnabled
                        )
                    } catch (_: Exception) {}
                    kotlinx.coroutines.delay(10_000L)
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, ProfitGreen.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("app_security_news_shield_card")
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = ProfitGreen, modifier = Modifier.size(20.dp))
                            Text(
                                text = "HARDENED APP SHIELD & NEWS SENTRY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = ProfitGreen
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ProfitGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "SHIELD ACTIVE",
                                fontSize = 8.sp,
                                color = ProfitGreen,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Text(
                        text = secReport.statusMessage,
                        fontSize = 10.sp,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace
                    )

                    HorizontalDivider(color = CardBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("HIGH-IMPACT NEWS SENTRY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ElectricCyan)
                            Text("Auto-exit positions on high impact releases (NFP, CPI, FOMC)", fontSize = 9.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = autoExitNewsEnabled,
                            onCheckedChange = { autoExitNewsEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = DarkBackground, checkedTrackColor = ElectricCyan)
                        )
                    }

                    newsAlert?.let { alert ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(LossRed.copy(alpha = 0.15f))
                                .border(1.dp, LossRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = alert.detailMessage,
                                fontSize = 10.sp,
                                color = LossRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // MetaTrader 5 Native Architecture: Account Management, Real Balance & Direct Broker Gateway
        item {
            if (activeBrokerType == BrokerType.MT5) {
            var showBrokerSearchDialog by remember { mutableStateOf(false) }
            var brokerSearchQuery by remember { mutableStateOf("") }
            var savePasswordChecked by remember(bridgeProfileKey) { mutableStateOf(bridgeSecrets.isManagedMT5AccountPasswordSaveEnabled(bridgeProfileKey)) }
            var showOpenDemoDialog by remember { mutableStateOf(false) }
            var showOpenRealDialog by remember { mutableStateOf(false) }

            val currentSessionStatus = viewModel.app.secretsManager.getMT5SessionStatus()
            val hasSavedLogin = bridgeSecrets.getManagedMT5AccountLogin(bridgeProfileKey).isNotBlank() && bridgeSecrets.getManagedMT5AccountServer(bridgeProfileKey).isNotBlank()
            val isSessionExpired = currentSessionStatus == "SESSION_EXPIRED"
            var isEditingCredentials by remember(bridgeProfileKey) {
                mutableStateOf(!hasSavedLogin || bridgeSecrets.getMT5BridgeAccountAppToken(bridgeProfileKey).isBlank())
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, Color(0xFF2196F3).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("mt5_native_login_card")
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AccountBalance, contentDescription = null, tint = Color(0xFF2196F3), modifier = Modifier.size(20.dp))
                            Text(
                                text = "FBS / EXNESS / DERIV / HFM • MT5 ACCOUNT ARCHITECTURE",
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF2196F3)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2196F3).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "DESTINEDFX MANAGED BRIDGE",
                                fontSize = 8.sp,
                                color = Color(0xFF2196F3),
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    if (hasSavedLogin && !isEditingCredentials) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val savedLogin = bridgeSecrets.getManagedMT5AccountLogin(bridgeProfileKey)
                            Column(Modifier.weight(1f)) {
                                Text("$selectedAccountMode · ••••${savedLogin.takeLast(4)}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("${bridgeSecrets.getManagedMT5AccountServer(bridgeProfileKey)} · $bridgeDisplayState", color = if (bridgeIdentityFresh) ElectricCyan else WarningAmber, fontSize = 9.sp)
                            }
                            TextButton(onClick = { isEditingCredentials = true }, modifier = Modifier.testTag("edit_mt5_account")) {
                                Text("EDIT", color = ElectricCyan)
                            }
                        }
                    }

                    // Session Expiry Alert Banner
                    if (isSessionExpired) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = LossRed.copy(alpha = 0.15f)),
                            border = BorderStroke(1.dp, LossRed.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().testTag("mt5_session_expired_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = LossRed, modifier = Modifier.size(18.dp))
                                    Column {
                                        Text("MANAGED SESSION EXPIRED", fontSize = 11.sp, fontWeight = FontWeight.Black, color = LossRed)
                                        Text("Managed execution authorization expired for account ${viewModel.getMT5Login()}. Re-enter credentials to provision it again.", fontSize = 9.sp, color = TextPrimary)
                                    }
                                }
                                Button(
                                    onClick = {
                                        brokerConnectionMessage = "Re-enter broker credentials and consent to reconnect through the DestinedFX managed bridge."
                                        isEditingCredentials = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = LossRed, contentColor = Color.White),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("RE-ENTER", fontSize = 10.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }

                    // Active MT5 Account Overview (Official MT5 Account Card)
                    if (!bridgeMode && brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null && !isEditingCredentials) {
                        val activeLogin = viewModel.getMT5Login()
                        val activeServer = viewModel.getMT5BrokerServer()
                        val verifiedMetrics = accountMetrics!!
                        val bal = verifiedMetrics.balance
                        val eq = verifiedMetrics.equity
                        val margin = verifiedMetrics.margin
                        val freeMargin = verifiedMetrics.freeMargin
                        val marginLevel = verifiedMetrics.marginLevel
                        val ccy = verifiedMetrics.currency

                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth().testTag("active_mt5_account_card")
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = activeServer,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 13.sp,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "Account: $activeLogin",
                                            fontSize = 11.sp,
                                            color = TextSecondary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedAccountMode == "LIVE") ProfitGreen.copy(alpha = 0.2f) else ElectricCyan.copy(alpha = 0.2f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "MODE NOT VERIFIED",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ElectricCyan
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(DarkBackground)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = if (verifiedMetrics.leverage > 0) "1:${verifiedMetrics.leverage}" else "Leverage unavailable",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(color = CardBorder)

                                // Account Financial Metrics (Real Balance & Live Floating Equity)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("Balance:", fontSize = 9.sp, color = TextSecondary)
                                        Text(
                                            text = "$${String.format(java.util.Locale.US, "%,.2f", bal)} $ccy",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 13.sp,
                                            color = TextPrimary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Equity (Floating):", fontSize = 9.sp, color = TextSecondary)
                                        Text(
                                            text = "$${String.format(java.util.Locale.US, "%,.2f", eq)} $ccy",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 13.sp,
                                            color = if (eq >= bal) ProfitGreen else LossRed,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("Free Margin:", fontSize = 9.sp, color = TextSecondary)
                                        Text(
                                            text = if (freeMargin.isFinite()) "$${String.format(java.util.Locale.US, "%,.2f", freeMargin)}" else "Unavailable",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = ElectricCyan,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Margin Used:", fontSize = 9.sp, color = TextSecondary)
                                        Text(
                                            text = if (margin.isFinite()) "$${String.format(java.util.Locale.US, "%,.2f", margin)}" else "Unavailable",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = TextPrimary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Margin Level:", fontSize = 9.sp, color = TextSecondary)
                                        Text(
                                            text = if (margin.isFinite() && marginLevel.isFinite() && margin > 0.0) "${String.format(java.util.Locale.US, "%.1f", marginLevel)}%" else "—",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = if (marginLevel.isFinite() && marginLevel >= 100.0) ProfitGreen else TextSecondary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                HorizontalDivider(color = CardBorder)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(
                                            modifier = Modifier.size(8.dp).clip(CircleShape).background(if (brokerConnStatus == BrokerConnectionStatus.CONNECTED) ProfitGreen else LossRed)
                                        )
                                        Text(
                                            text = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED) "Connected (Real Quotes)" else "Offline",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED) ProfitGreen else TextSecondary
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { isEditingCredentials = true },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricCyan),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Change Settings", fontSize = 10.sp)
                                        }

                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    activeBrokerGateway.disconnect()
                                                    isEditingCredentials = true
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurface, contentColor = LossRed),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.testTag("bot_disconnect_button")
                                        ) {
                                            Text("Disconnect", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        TextButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    activeBrokerGateway.disconnect()
                                                    viewModel.app.secretsManager.clearMT5Credentials()
                                                    mt5LoginInput = ""
                                                    mt5PasswordInput = ""
                                                    mt5ServerInput = ""
                                                    isEditingCredentials = true
                                                }
                                            },
                                            modifier = Modifier.testTag("bot_remove_mt5_account")
                                        ) {
                                            Text("Remove", fontSize = 10.sp, color = LossRed)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Managed credentials are collected only when connecting or explicitly editing the account.
                    if (isEditingCredentials) {
                        // Top Broker Company Search Header
                        Row(
                            modifier = Modifier.fillMaxWidth().background(DarkSurfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Business, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                                Column {
                                    Text(selectedBrokerCompanyName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("Select your broker; enter the exact server issued for this account", fontSize = 9.sp, color = TextSecondary)
                                }
                            }
                            Button(
                                onClick = { showBrokerSearchDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.testTag("search_broker_button")
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Find Broker", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Open Demo / Real Helper Row
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Card(
                                onClick = { showOpenDemoDialog = true },
                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).testTag("open_demo_account_card")
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.School, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                                    Column {
                                        Text("Open Demo Account", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Text("Open via your broker", fontSize = 8.5.sp, color = TextSecondary)
                                    }
                                }
                            }

                            Card(
                                onClick = { showOpenRealDialog = true },
                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).testTag("open_real_account_card")
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.TrendingUp, contentDescription = null, tint = ProfitGreen, modifier = Modifier.size(18.dp))
                                    Column {
                                        Text("Open Real Account", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Text("Live trading verification", fontSize = 8.5.sp, color = TextSecondary)
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = CardBorder)

                        Text(
                            text = "LOGIN TO YOUR MT5 ACCOUNT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2196F3),
                            fontFamily = FontFamily.Monospace
                        )

                        // Mode Selector: DEMO vs REAL
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { selectBridgeProfile("DEMO") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selectedAccountMode == "DEMO") ElectricCyan else DarkSurfaceVariant,
                                    contentColor = if (selectedAccountMode == "DEMO") DarkBackground else TextSecondary
                                ),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f).height(38.dp)
                            ) {
                                Text("DEMO ACCOUNT", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { selectBridgeProfile("LIVE") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selectedAccountMode == "LIVE") ProfitGreen else DarkSurfaceVariant,
                                    contentColor = if (selectedAccountMode == "LIVE") DarkBackground else TextSecondary
                                ),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f).height(38.dp)
                            ) {
                                Text("REAL ACCOUNT", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Account / Client ID Input
                        OutlinedTextField(
                            value = mt5LoginInput, onValueChange = { mt5LoginInput = it.filter(Char::isDigit) },
                            label = { Text("Trade account / Client ID") },
                            placeholder = { Text("e.g. 50123456") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("bot_mt5_login")
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("MASTER" to "MASTER · TRADING", "INVESTOR" to "INVESTOR · READ ONLY").forEach { (access, label) ->
                                OutlinedButton(onClick = { mt5AccessType = access }, modifier = Modifier.weight(1f)) {
                                    Text(label, fontSize = 9.sp, color = if (mt5AccessType == access) ElectricCyan else TextSecondary)
                                }
                            }
                        }
                        Text(if (mt5AccessType == "INVESTOR") "Investor access can view account data but cannot trade. START will remain blocked." else "Master access permits trading; START remains blocked until a live terminal is verified.", fontSize = 9.sp, color = WarningAmber)
                        OutlinedTextField(
                            value = mt5PasswordInput,
                            onValueChange = { mt5PasswordInput = it },
                            label = { Text("MT5 trading password") },
                            placeholder = { Text("Enter the master trading password") },
                            visualTransformation = if (showMt5Password) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("bot_mt5_password"),
                            trailingIcon = {
                                IconButton(onClick = { showMt5Password = !showMt5Password }) {
                                    Icon(if (showMt5Password) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (showMt5Password) "Hide password" else "Show password")
                                }
                            }
                        )
                        OutlinedTextField(
                            value = mt5InvestorPasswordInput,
                            onValueChange = { mt5InvestorPasswordInput = it },
                            label = { Text("Investor password (optional)") },
                            placeholder = { Text("Used for read-only INVESTOR access when supplied") },
                            visualTransformation = if (showMt5Password) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("bot_mt5_investor_password")
                        )
                        Text(
                            "For INVESTOR access, the optional investor password is preferred when provided. The bridge independently blocks all trading commands for INVESTOR profiles.",
                            fontSize = 9.sp, color = TextSecondary
                        )
                        Text("The selected account mode is checked against the authenticated MT5 terminal before trading is enabled.", fontSize = 9.sp, color = TextSecondary)
                        OutlinedTextField(
                            value = mt5ServerInput,
                            onValueChange = { mt5ServerInput = it },
                            label = { Text("Exact MT5 server") },
                            placeholder = { Text("Enter the server issued by your broker") },
                            modifier = Modifier.fillMaxWidth().testTag("bot_mt5_server"),
                            singleLine = true
                        )

                        Text("Server names are not guessed or prefilled. Listed broker names are discovery aids only; the managed terminal must authenticate the exact server you enter.", fontSize = 9.sp, color = WarningAmber)
                        Text("Commission and spread checks use observed completed broker history. New accounts remain blocked until representative cost evidence is available; pricing is never guessed.", fontSize = 9.sp, color = WarningAmber)
                        Text("Balance, equity, margin, currency and leverage appear only after a verified broker account snapshot.", fontSize = 9.sp, color = TextSecondary)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked = managedCredentialConsent, onCheckedChange = { managedCredentialConsent = it })
                            Text("I consent to send these broker credentials over HTTPS to DestinedFX managed execution for encrypted storage and MT5 terminal login.", fontSize = 10.sp, color = TextPrimary)
                        }

                        // Local password persistence remains opt-in and separate from hosted credential storage consent.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = savePasswordChecked,
                                onCheckedChange = { savePasswordChecked = it },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF2196F3))
                            )
                            Text("Also save password on this device", fontSize = 11.sp, color = TextPrimary)
                        }

                        // Prominent MT5 Blue Login Button (#2196F3)
                        Button(
                            onClick = {
                                if (mt5LoginInput.isBlank()) {
                                    brokerConnectionMessage = "Please enter your Trade Account / Login ID."
                                    return@Button
                                }
                                val selectedTerminalPassword = if (mt5AccessType == "INVESTOR") {
                                    mt5InvestorPasswordInput.trim().ifBlank { mt5PasswordInput }
                                } else {
                                    mt5PasswordInput
                                }
                                if (selectedTerminalPassword.isBlank()) {
                                    brokerConnectionMessage = "Enter a password for the selected MT5 access profile."
                                    return@Button
                                }
                                if (mt5ServerInput.isBlank()) {
                                    brokerConnectionMessage = "Please enter or select a broker server."
                                    return@Button
                                }
                                if (!managedCredentialConsent) {
                                    brokerConnectionMessage = "Consent is required before sending credentials to managed execution."
                                    return@Button
                                }
                                // Use observed broker history; never guess a zero-commission model or accept unverified cost inputs.
                                val selectedCostModel = "OBSERVED_ROUND_TURN"
                                val selectedCommission = 0.0
                                val selectedCostEvidence = ""
                                val activity = reportContext as? MainActivity
                                if (activity == null) {
                                    brokerConnectionMessage = "Authentication is unavailable; sensitive action denied."
                                    return@Button
                                }
                                activity.requestSensitiveAuthentication(
                                    onSuccess = {
                                        isConnectingBroker = true
                                        brokerConnectionMessage = null
                                        coroutineScope.launch {
                                    val secret = selectedTerminalPassword
                                    try {
                                        if (!bridgeClient.managedConfigured()) {
                                            brokerConnectionMessage = "CONFIGURATION_REQUIRED"
                                        } else {
                                            viewModel.selectBroker(BrokerType.MT5)
                                            bridgeMode = true
                                            val snapshot = bridgeClient.registerManagedAccount(
                                                mt5LoginInput, secret, mt5ServerInput, mt5AccessType, managedCredentialConsent,
                                                savePasswordChecked, selectedCostModel, selectedCommission, selectedCostEvidence
                                            )
                                            bridgeMode = true
                                            runCatching { MT5AccountSessionService.start(reportContext) }
                                            bridgeSnapshot = snapshot
                                            bridgeMessage = snapshot.managedState.ifBlank { "PROVISIONING" }
                                            brokerConnectionMessage = "${snapshot.managedState.ifBlank { "PROVISIONING" }} — broker connection is not confirmed until a fresh authenticated EA heartbeat."
                                            isEditingCredentials = false
                                        }
                                    } catch (e: Exception) {
                                        brokerConnectionMessage = e.message?.takeIf {
                                            it == "CONFIGURATION_REQUIRED" || it.contains("Secure storage") ||
                                                it.contains("encrypted storage")
                                        } ?: "Managed registration failed; check service availability and credentials."
                                    } finally {
                                        mt5PasswordInput = ""
                                        mt5InvestorPasswordInput = ""
                                        managedCredentialConsent = false
                                        isConnectingBroker = false
                                    }
                                        }
                                    },
                                    onFailure = { message ->
                                        brokerConnectionMessage = message
                                        isConnectingBroker = false
                                    }
                                )
                            },
                            enabled = !isConnectingBroker,
                            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("bot_connect_mt5"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2196F3),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isConnectingBroker) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Text("CONNECT", fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.sp)
                            }
                        }
                    }

                    // Search listed broker/server suggestions Dialog
                    if (showBrokerSearchDialog) {
                        AlertDialog(
                            onDismissRequest = { showBrokerSearchDialog = false },
                            title = { Text("Listed broker names", fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Names are not verification. No server, demo login, or execution capability is assumed until the managed MT5 terminal returns a fresh authenticated heartbeat.", fontSize = 10.sp, color = WarningAmber)
                                    OutlinedTextField(
                                        value = brokerSearchQuery,
                                        onValueChange = { brokerSearchQuery = it },
                                        label = { Text("Choose FBS, Exness, Deriv MT5 or HFM") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    val results = remember(brokerSearchQuery) {
                                        com.example.data.broker.BrokerDirectory.searchBrokers(brokerSearchQuery)
                                    }
                                    LazyColumn(modifier = Modifier.height(260.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(results) { broker ->
                                            Card(
                                                onClick = {
                                                    selectedBrokerBrand = broker.brandId
                                                    bridgeSecrets.saveSelectedMT5BrokerBrand(broker.brandId)
                                                    showBrokerSearchDialog = false
                                                },
                                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Text(broker.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ElectricCyan)
                                                    Text("Broker listed · enter the exact server issued for this account", fontSize = 9.sp, color = TextSecondary)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showBrokerSearchDialog = false }) {
                                    Text("Close")
                                }
                            }
                        )
                    }

                    // Open Demo / Real Account Dialogs
                    if (showOpenDemoDialog) {
                        AlertDialog(
                            onDismissRequest = { showOpenDemoDialog = false },
                            title = { Text("Open Demo Account") },
                            text = { Text("Demo accounts must be created through your broker. This app cannot create or simulate an account.") },
                            confirmButton = { TextButton(onClick = { showOpenDemoDialog = false }) { Text("OK") } },
                            dismissButton = {
                                TextButton(onClick = { showOpenDemoDialog = false }) { Text("Cancel") }
                            }
                        )
                    }

                    if (showOpenRealDialog) {
                        AlertDialog(
                            onDismissRequest = { showOpenRealDialog = false },
                            title = { Text("Open Real Account") },
                            text = { Text("Real trading accounts with $selectedBrokerCompanyName require verification and live deposits through your broker portal. Enter your verified account credentials and exact starting balance once issued.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showOpenRealDialog = false
                                    selectBridgeProfile("LIVE")
                                }) { Text("OK") }
                            }
                        )
                    }

                    brokerConnectionMessage?.let { message ->
                        Text(
                            text = message, fontSize = 10.sp,
                            color = if (message.contains("failed", true) || message.contains("error", true)) LossRed else ElectricCyan,
                            lineHeight = 15.sp, modifier = Modifier.testTag("bot_broker_connection_status")
                        )
                    }
                }
            }
            }
        }
        if (activeBrokerType == BrokerType.DERIV) {
        // Open Positions Section
        item {
            Text(
                text = "OPEN POSITIONS (${openPositions.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary
            )
        }

        if (openPositions.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No open positions currently active on ${activeBrokerGateway.displayName}.",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(openPositions) { pos ->
                val ticker = viewModel.tickers.collectAsStateWithLifecycle().value[pos.symbol]
                val freshCmp = ticker?.price?.takeIf { it.isFinite() && it > 0.0 && viewModel.isDataFresh(pos.symbol) }
                val isLong = pos.direction == com.example.domain.model.SignalDirection.LONG
                val liveFloatingPnl = if (activeBrokerType != BrokerType.DERIV && freshCmp != null && pos.entryPrice > 0.0) {
                    val mult = if (isLong) 1.0 else -1.0
                    val spec = com.destinedfx.core.instrument.SpecRegistry.get(pos.symbol)
                    val contractSize = spec?.contractSize
                    if (contractSize != null && contractSize.isFinite() && contractSize > 0.0) {
                        ((freshCmp - pos.entryPrice) * mult * contractSize * pos.lotSize).takeIf { it.isFinite() }
                    } else pos.currentProfit.takeIf { it.isFinite() }
                } else pos.currentProfit.takeIf { it.isFinite() }
                val isProfitable = liveFloatingPnl?.let { it >= 0.0 }
                val pnlCurrency = accountMetrics?.currency?.takeIf { it.isNotBlank() } ?: "CCY UNKNOWN"
                val positionLabel = if (activeBrokerType == BrokerType.DERIV) {
                    "${if (isLong) "MULTUP" else "MULTDOWN"} ${pos.symbol}"
                } else {
                    "${if (isLong) "BUY" else "SELL"} ${pos.lotSize} ${pos.symbol}"
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, when (isProfitable) {
                        true -> ProfitGreen.copy(alpha = 0.5f)
                        false -> LossRed.copy(alpha = 0.5f)
                        null -> WarningAmber.copy(alpha = 0.5f)
                    }),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("position_card_${pos.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = positionLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isLong) ProfitGreen else LossRed
                                )
                                Text(
                                    text = liveFloatingPnl?.let {
                                        "${if (it >= 0.0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", it)} $pnlCurrency"
                                    } ?: "P/L UNAVAILABLE",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    color = when (isProfitable) {
                                        true -> ProfitGreen
                                        false -> LossRed
                                        null -> WarningAmber
                                    },
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                text = "Entry: ${formatPrice(pos.entryPrice, pos.symbol)} → CMP: ${freshCmp?.let { formatPrice(it, pos.symbol) } ?: "STALE / UNAVAILABLE"}",
                                fontSize = 10.sp,
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                            if (pos.stopLoss > 0.0 || pos.takeProfit > 0.0) {
                                Text(
                                    text = "SL: ${if (pos.stopLoss > 0.0) formatPrice(pos.stopLoss, pos.symbol) else "—"} | TP: ${if (pos.takeProfit > 0.0) formatPrice(pos.takeProfit, pos.symbol) else "—"}",
                                    fontSize = 9.sp,
                                    color = TextSecondary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        if (activeBrokerType == BrokerType.MT5) {
                            Column(horizontalAlignment = Alignment.End) {
                                Button(enabled = false, onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = LossRed),
                                    shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("close_position_${pos.id}")) {
                                    Text("Remote bridge only", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                Text("Use matching verified snapshot action above; local MT5 close is disabled.", color = WarningAmber, fontSize = 8.sp)
                            }
                        } else Button(
                            onClick = {
                                coroutineScope.launch {
                                    activeBrokerGateway.closePosition(pos.id)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LossRed),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("close_position_${pos.id}")
                        ) {
                            Text("Close", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        }
    }


}


private fun isUsableMt5Snapshot(
    snapshot: MT5RemoteBridgeClient.Snapshot?,
    profile: String,
    expectedLogin: String,
    expectedServer: String
): Boolean = snapshot?.let {
    it.connected && it.terminalOnline && it.heartbeatFresh && it.terminalConnected &&
        it.accountVerified && it.snapshotsValid && it.managedState == "READY" &&
        profile in setOf("DEMO", "LIVE") && it.profileMode == profile &&
        it.actualAccountMode == profile && expectedLogin.isNotBlank() && it.actualLogin == expectedLogin &&
        expectedServer.isNotBlank() && it.actualServer == expectedServer
} == true

@Composable
private fun BridgeTelemetry(
    snapshot: MT5RemoteBridgeClient.Snapshot?,
    selectedProfile: String,
    expectedLogin: String,
    expectedServer: String
) {
    val verified = isUsableMt5Snapshot(snapshot, selectedProfile, expectedLogin, expectedServer)
    val current = snapshot.takeIf { verified }
    val unavailable = if (snapshot == null) "Unavailable" else "STALE / UNVERIFIED"
    val currency = current?.currency?.ifBlank { unavailable } ?: unavailable
    Text("ACTUAL TERMINAL ACCOUNT · $currency", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    Text(
        "SNAPSHOT: ${if (verified) "FRESH · ACCOUNT VERIFIED" else if (snapshot == null) "UNAVAILABLE" else "STALE / PROFILE OR IDENTITY MISMATCH"}",
        color = if (verified) ProfitGreen else WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold
    )
    Text(
        "Balance: ${current?.balance?.toString() ?: unavailable} ${current?.currency.orEmpty()} · Equity: ${current?.equity?.toString() ?: unavailable} ${current?.currency.orEmpty()}",
        color = TextPrimary, fontSize = 11.sp
    )
    Text(
        "Margin: ${current?.margin?.toString() ?: unavailable} · Free margin: ${current?.freeMargin?.toString() ?: unavailable} · Margin level: ${current?.marginLevel?.toString() ?: unavailable} · Leverage: ${current?.leverage?.let { "1:$it" } ?: unavailable}",
        color = TextPrimary, fontSize = 10.sp
    )
    val positions = current?.positions
    val floatingValues = positions.orEmpty().mapNotNull { p ->
        if (p.has("floating_profit") && !p.isNull("floating_profit")) p.optDouble("floating_profit").takeIf { it.isFinite() } else null
    }
    val floatingTotal = when {
        current == null || positions == null -> unavailable
        positions.isEmpty() -> "0.0"
        floatingValues.size == positions.size -> floatingValues.sum().toString()
        else -> unavailable
    }
    Text("Floating P/L: $floatingTotal ${current?.currency.orEmpty()}", color = TextPrimary, fontSize = 11.sp)
    Text("OPEN POSITIONS (${positions?.size ?: unavailable})", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    if (positions == null || positions.isEmpty()) {
        Text(if (current != null && positions?.isEmpty() == true) "No open positions confirmed" else unavailable, color = TextSecondary, fontSize = 10.sp)
    }
    positions.orEmpty().forEach { p ->
        Text(
            "#${p.optString("ticket", unavailable)} / ${p.optString("position_id", unavailable)} · ${p.optString("symbol", unavailable)} ${p.optString("direction", unavailable)} · volume ${p.optString("volume", unavailable)} · entry ${p.optString("open_price", unavailable)} · current broker P/L ${p.optString("floating_profit", unavailable)} · SL ${p.optString("stop_loss", unavailable)} · TP ${p.optString("take_profit", unavailable)} · swap ${p.optString("swap", unavailable)}",
            color = TextPrimary, fontSize = 10.sp
        )
    }
    val activeOrders = current?.activeOrders
    Text("PENDING BROKER ORDERS (${activeOrders?.size ?: unavailable})", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    if (activeOrders == null || activeOrders.isEmpty()) {
        Text(if (current != null && activeOrders?.isEmpty() == true) "No pending orders confirmed" else unavailable, color = TextSecondary, fontSize = 10.sp)
    }
    activeOrders.orEmpty().forEach { order ->
        Text(
            "#${order.optString("ticket", unavailable)} · ${order.optString("symbol", unavailable)} ${order.optString("direction", unavailable)} · remaining ${order.optString("remaining_volume", unavailable)} · price ${order.optString("open_price", unavailable)} · SL ${order.optString("stop_loss", unavailable)} · TP ${order.optString("take_profit", unavailable)} · state ${order.optString("state", unavailable)}",
            color = TextPrimary, fontSize = 10.sp
        )
    }
    val deals = current?.closedDeals
    val netValue = if (deals == null) null
        else MT5RemoteBridgeClient.recentClosedDealNetPnl(deals, System.currentTimeMillis())
    Text(
        "REALIZED DEAL NET (last 7 days, including fees): ${netValue?.toString() ?: unavailable} ${current?.currency.orEmpty()}",
        color = TextPrimary, fontSize = 10.sp
    )
    deals.orEmpty().take(10).forEach { deal ->
        val feeValue = if (deal.has("fees") && !deal.isNull("fees")) deal.opt("fees") else null
        val fees = feeValue?.toString() ?: unavailable
        Text(
            "Closed deal #${deal.optString("ticket", unavailable)} / position ${deal.optString("position_id", unavailable)} · ${deal.optString("symbol", unavailable)} · closed ${deal.optString("closed_at", unavailable)} · realized ${deal.optString("profit", unavailable)} · commission ${deal.optString("commission", unavailable)} · swap ${deal.optString("swap", unavailable)} · fees $fees",
            color = TextSecondary, fontSize = 10.sp
        )
    }
    val lastSignal = current?.lastSignal
    val signalState = lastSignal?.optString("execution_state")?.takeIf { it.isNotBlank() }
        ?: lastSignal?.optString("state")?.takeIf { it.isNotBlank() }
        ?: unavailable
    Text(
        "Last signal: $signalState · requested ${lastSignal?.optString("requested_volume", unavailable) ?: unavailable} · executed ${lastSignal?.optString("executed_volume", unavailable) ?: unavailable} · order ${lastSignal?.optString("order", unavailable) ?: unavailable} · deal ${lastSignal?.optString("deal", unavailable) ?: unavailable}",
        color = TextSecondary, fontSize = 10.sp
    )
    current?.raw?.optString("last_error")?.takeIf { it.isNotBlank() }?.let { Text("EA ERROR: $it", color = LossRed, fontSize = 10.sp) }
}

private suspend fun sendBridgeCommand(
    client: MT5RemoteBridgeClient, action: String, config: JSONObject, secrets: com.example.security.SecretsManager,
    profile: String,
    busy: (Boolean) -> Unit, pending: (Boolean) -> Unit, requestId: (String) -> Unit,
    pendingAction: (String) -> Unit, expected: (String) -> Unit, message: (String) -> Unit
) {
    val unresolvedId = secrets.getMT5BridgeAccountPendingCommandId(profile)
    if (unresolvedId.isNotBlank() && action != "STOP") {
        pending(true); requestId(unresolvedId); pendingAction(secrets.getMT5BridgeAccountPendingCommandAction(profile))
        message("A bridge command is already unresolved. Reconcile its saved request ID or use STOP as the one safety override; no other command was sent.")
        busy(false)
        return
    }
    val id = java.util.UUID.randomUUID().toString()
    if (unresolvedId.isNotBlank() && action == "STOP" && !secrets.saveMT5BridgeAccountSafetyStop(unresolvedId, secrets.getMT5BridgeAccountPendingCommandAction(profile), id, profile)) {
        message("Secure safety-stop storage failed; no STOP command was sent.")
        busy(false)
        return
    }
    if (!secrets.saveMT5BridgeAccountPendingCommand(id, action, profile)) {
        message("Secure pending-command storage failed; no command was sent.")
        busy(false)
        return
    }
    requestId(id); pendingAction(action)
    expected(when (action) { "START", "RESUME" -> "RUNNING"; "PAUSE" -> "PAUSED"; else -> "STOPPED" })
    pending(true)
    busy(true)

    fun handleDefinitiveRejection(detail: String) {
        val original = secrets.getMT5BridgeAccountSafetyOriginalId(profile)
        val originalAction = secrets.getMT5BridgeAccountSafetyOriginalAction(profile)
        if (action == "STOP" && original.isNotBlank() && originalAction.isNotBlank()) {
            secrets.saveMT5BridgeAccountPendingCommand(original, originalAction, profile)
            secrets.clearMT5BridgeAccountSafetyStop(profile)
            requestId(original)
            pendingAction(originalAction)
            expected(when (originalAction) { "START", "RESUME" -> "RUNNING"; "PAUSE" -> "PAUSED"; else -> "STOPPED" })
            pending(true)
        } else {
            secrets.clearMT5BridgeAccountPendingCommand(profile)
            pending(false); requestId(""); pendingAction("")
        }
        message("Bridge rejected $action: $detail. No broker execution is claimed.")
    }

    try {
        val result = client.command(id, action, config)
        if (result.rejected) {
            handleDefinitiveRejection(result.detail)
        } else {
            message(if (result.queued) "$action queued (request ${result.requestId}); awaiting matching acknowledgement and fresh heartbeat; not confirmed."
                else "UNKNOWN request ${result.requestId}: ${result.detail}. Do not resend; reconcile this ID or recover manually.")
        }
    } catch (e: Exception) {
        val bridgeError = e as? MT5RemoteBridgeClient.BridgeHttpException
        if (bridgeError != null && bridgeError.status in 400..499) {
            handleDefinitiveRejection(bridgeError.safeDescription)
        } else {
            // A timeout/5xx can follow server acceptance. Keep this exact ID and never resend automatically.
            pending(true); requestId(id); pendingAction(action)
            message("UNKNOWN request $id: no confirmed response. It may have been accepted; do not resend. Reconcile this same request ID.")
        }
    } finally {
        busy(false)
    }
}

private suspend fun sendBridgeTradeCommand(
    client: MT5RemoteBridgeClient, action: String, config: JSONObject, targetTickets: List<String>,
    secrets: com.example.security.SecretsManager, profile: String,
    busy: (Boolean) -> Unit, pending: (Boolean) -> Unit, requestId: (String) -> Unit,
    pendingAction: (String) -> Unit, serverId: (String) -> Unit, pendingTickets: (List<String>) -> Unit,
    message: (String) -> Unit
) {
    val unresolvedId = secrets.getMT5BridgeAccountPendingCommandId(profile)
    if (unresolvedId.isNotBlank()) {
        pending(true); requestId(unresolvedId); pendingAction(secrets.getMT5BridgeAccountPendingCommandAction(profile))
        message("A bridge command is unresolved. Reconcile its request ID before sending any position/order action.")
        busy(false)
        return
    }
    val id = java.util.UUID.randomUUID().toString()
    if (!secrets.saveMT5BridgeAccountPendingCommand(id, action, profile)) {
        message("Secure pending-command storage failed; no position/order command was sent.")
        busy(false)
        return
    }
    requestId(id); pendingAction(action); serverId(""); pendingTickets(targetTickets); pending(true); busy(true)

    try {
        val result = client.command(id, action, config)
        // Use the server's canonical target list. CANCEL_EA_PENDING may expand
        // between the phone snapshot and server validation; never reconcile only a stale subset.
        pendingTickets(result.targetTickets)
        when {
            result.rejected -> {
                secrets.clearMT5BridgeAccountPendingCommand(profile)
                pending(false); requestId(""); pendingAction(""); serverId(""); pendingTickets(emptyList())
                message("Blocked: ${result.detail}")
            }
            result.queued -> {
                serverId(result.serverCommandId)
                message("$action queued as server command ${result.serverCommandId} (request ${result.requestId}); awaiting matching per-ticket results and fresh telemetry. Not confirmed.")
            }
            else -> {
                serverId(result.serverCommandId)
                message("UNKNOWN request ${result.requestId}${result.serverCommandId.takeIf { it.isNotBlank() }?.let { " / server command $it" }.orEmpty()}: ${result.detail}. Do not resend; reconcile this same ID.")
            }
        }
    } catch (e: Exception) {
        val bridgeError = e as? MT5RemoteBridgeClient.BridgeHttpException
        if (bridgeError != null && bridgeError.status in 400..499) {
            secrets.clearMT5BridgeAccountPendingCommand(profile)
            pending(false); requestId(""); pendingAction(""); serverId(""); pendingTickets(emptyList())
            message("Bridge rejected $action (${bridgeError.status}); no broker result is claimed.")
        } else {
            // Keep the generated request ID because the server may have accepted the command before the timeout.
            pending(true); requestId(id); pendingAction(action); serverId(""); pendingTickets(targetTickets)
            message("UNKNOWN $action request $id: no confirmed response. It may have been accepted; do not resend. Reconcile this same request ID.")
        }
    } finally {
        busy(false)
    }
}
