package com.example.ui.workstations

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.example.ui.DestinedFXViewModel
import com.example.ui.InsightCard
import com.example.ui.InsightLine
import com.example.ui.theme.*
import com.example.ui.components.TerminalPageHeader
import com.example.ui.components.StatChip
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
    val bridgeClient = remember(bridgeSecrets, selectedAccountMode) { MT5RemoteBridgeClient(bridgeSecrets, selectedAccountMode) }
    val reportContext = LocalContext.current
    var bridgeMode by remember(selectedAccountMode) { mutableStateOf(bridgeSecrets.getMT5BridgeAppToken(selectedAccountMode).isNotBlank()) }
    var bridgeCode by remember { mutableStateOf("") }
    var bridgeMessage by remember(selectedAccountMode) { mutableStateOf(bridgeSecrets.getMT5BridgePendingCommandId(selectedAccountMode).let { id -> if (id.isBlank()) "SETUP REQUIRED" else "Unresolved saved request $id; checking its acknowledgement. Do not resend." }) }
    var bridgeSnapshot by remember(selectedAccountMode) { mutableStateOf<MT5RemoteBridgeClient.Snapshot?>(null) }
    var managedBridgeUrlInput by remember {
        mutableStateOf(bridgeSecrets.getManagedMT5ServiceUrl().ifBlank {
            com.example.BuildConfig.MT5_MANAGED_SERVICE_URL.takeUnless { it == "__CONFIGURATION_REQUIRED__" }.orEmpty()
        })
    }
    var bridgeBusy by remember { mutableStateOf(false) }
    var bridgePendingId by remember(selectedAccountMode) { mutableStateOf(bridgeSecrets.getMT5BridgePendingCommandId(selectedAccountMode)) }
    var bridgePendingAction by remember(selectedAccountMode) { mutableStateOf(bridgeSecrets.getMT5BridgePendingCommandAction(selectedAccountMode)) }
    var bridgeCommandPending by remember(selectedAccountMode) { mutableStateOf(bridgePendingId.isNotBlank() && bridgePendingAction.isNotBlank()) }
    var bridgePendingServerId by remember(selectedAccountMode) { mutableStateOf("") }
    var bridgeTradePendingTickets by remember(selectedAccountMode) { mutableStateOf(emptyList<String>()) }
    var reportStatus by remember(selectedAccountMode) { mutableStateOf("Trade reports: waiting for authenticated broker ledger") }
    var bridgeExpectedState by remember(selectedAccountMode) { mutableStateOf(when (bridgePendingAction) { "START", "RESUME" -> "RUNNING"; "PAUSE" -> "PAUSED"; "STOP", "EMERGENCY_STOP" -> "STOPPED"; else -> "" }) }
    var showLiveAuthorizationDialog by remember { mutableStateOf(false) }
    var showCloseAllDialog by remember { mutableStateOf(false) }
    val bridgeToken = bridgeSecrets.getMT5BridgeAppToken(selectedAccountMode)
    val bridgeTerminalFresh = bridgeSnapshot?.let { it.connected && it.terminalOnline && it.heartbeatFresh && it.terminalConnected } == true
    val bridgeTerminalReady = bridgeSnapshot?.let { snapshot ->
        MT5RemoteBridgeClient.canStart(selectedAccountMode, snapshot.managedState, snapshot.accessType, snapshot.actualAccountMode,
            bridgeSecrets.getManagedMT5Login(selectedAccountMode), snapshot.actualLogin,
            bridgeSecrets.getManagedMT5Server(selectedAccountMode), snapshot.actualServer,
            snapshot.state,
            snapshot.heartbeatFresh, snapshot.terminalConnected, snapshot.tradeAllowed, snapshot.accountVerified, snapshot.liveAuthorized,
            snapshot.newsVerdict?.optString("status", "UNKNOWN") == "CLEAR")
    } == true
    val bridgeProfileReady = bridgeSnapshot?.let { snapshot ->
        MT5RemoteBridgeClient.canStart(selectedAccountMode, snapshot.managedState, snapshot.accessType, snapshot.actualAccountMode,
            bridgeSecrets.getManagedMT5Login(selectedAccountMode), snapshot.actualLogin,
            bridgeSecrets.getManagedMT5Server(selectedAccountMode), snapshot.actualServer,
            snapshot.state,
            snapshot.heartbeatFresh, snapshot.terminalConnected, snapshot.tradeAllowed, snapshot.accountVerified, true,
            snapshot.newsVerdict?.optString("status", "UNKNOWN") == "CLEAR")
    } == true
    val bridgeCanManagePositions = bridgeSnapshot?.let { snapshot ->
        MT5RemoteBridgeClient.canManageSnapshot(selectedAccountMode, bridgeSecrets.getManagedMT5Login(selectedAccountMode),
            bridgeSecrets.getManagedMT5Server(selectedAccountMode), snapshot)
    } == true
    fun selectBridgeProfile(profile: String) {
        selectedAccountMode = profile
        bridgeSecrets.saveManagedMT5SelectedProfile(profile)
    }
    val bridgeDisplayState = bridgeSnapshot?.let { if (it.managedState == "READY") it.state else it.managedState.ifBlank { it.state } } ?: if (!bridgeClient.managedConfigured()) "CONFIGURATION_REQUIRED" else if (bridgeToken.isBlank()) "SETUP REQUIRED" else "OFFLINE"
    val bridgeStatusColor = when (bridgeDisplayState) {
        "RUNNING" -> ProfitGreen
        "READY" -> ElectricCyan
        "PAUSED" -> OrangeAccent
        "ERROR", "FAILED", "OFFLINE", "STALE" -> LossRed
        else -> WarningAmber
    }
    val botSummary by viewModel.botStatusSummary.collectAsStateWithLifecycle()
    val botActive by viewModel.botActive.collectAsStateWithLifecycle()
    val derivServiceState by DerivDemoBotService.state.collectAsStateWithLifecycle()
    val derivAccount by viewModel.app.derivBrokerGateway.accountInfo.collectAsStateWithLifecycle()
    val activeBrokerType by viewModel.activeBrokerType.collectAsStateWithLifecycle()
    val activeBrokerGateway by viewModel.activeBrokerGateway.collectAsStateWithLifecycle()
    val brokerConnStatus by activeBrokerGateway.connectionStatus.collectAsStateWithLifecycle()
    val accountMetrics by activeBrokerGateway.accountMetrics.collectAsStateWithLifecycle()
    val openPositions by activeBrokerGateway.openPositions.collectAsStateWithLifecycle()

    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val selectedStrategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val riskPercent by viewModel.riskPercent.collectAsStateWithLifecycle()

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var brokerConnectionMessage by remember { mutableStateOf<String?>(null) }
    var mt5LoginInput by remember(selectedAccountMode) { mutableStateOf(bridgeSecrets.getManagedMT5Login(selectedAccountMode)) }
    var mt5PasswordInput by remember(selectedAccountMode) { mutableStateOf(bridgeSecrets.getManagedMT5Password(selectedAccountMode)) }
    var mt5ServerInput by remember(selectedAccountMode) { mutableStateOf(bridgeSecrets.getManagedMT5Server(selectedAccountMode)) }
    var mt5AccessType by remember(selectedAccountMode) { mutableStateOf(bridgeSecrets.getManagedMT5AccessType(selectedAccountMode)) }
    var mt5CostModel by remember(selectedAccountMode) { mutableStateOf("OBSERVED_ROUND_TURN") }
    var mt5CommissionPerLot by remember(selectedAccountMode) { mutableStateOf("") }
    var mt5CostEvidence by remember(selectedAccountMode) { mutableStateOf("") }
    var managedCredentialConsent by remember(selectedAccountMode) { mutableStateOf(false) }
    var showMt5Password by remember { mutableStateOf(false) }
    var isConnectingBroker by remember { mutableStateOf(false) }
    var showPostLoginPairSelectorModal by remember { mutableStateOf(false) }
    val tickers by viewModel.tickers.collectAsStateWithLifecycle()
    var instantLotSize by remember { mutableStateOf(0.01) }
    var instantSlPips by remember { mutableStateOf("20") }
    var instantTpPips by remember { mutableStateOf("50") }
    var instantExecutionStatus by remember { mutableStateOf<String?>(null) }
    val terminalSymbol = viewModel.app.secretsManager.getBrokerSymbolOverrides()[activeSymbol]
        ?.trim()?.takeIf { it.isNotEmpty() } ?: activeSymbol.replace("/", "").replace(" ", "")
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(activeBrokerType) {
        brokerConnectionMessage = null
        statusMessage = null
        isConnectingBroker = false
    }

    LaunchedEffect(bridgeMode, bridgeToken, selectedAccountMode, bridgeClient.baseUrl()) {
        if (bridgeMode && bridgeClient.baseUrl().isNotBlank() && bridgeToken.isNotBlank()) {
            while (true) {
                try {
                    val fresh = bridgeClient.state()
                    bridgeSnapshot = fresh
                    runCatching {
                        val syncPrefs = reportContext.getSharedPreferences("destinedfx_trade_sync", android.content.Context.MODE_PRIVATE)
                        val syncKey = selectedAccountMode.uppercase()
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
                                bridgeSecrets.clearMT5BridgePendingCommand(selectedAccountMode)
                                bridgeSecrets.clearMT5BridgeSafetyStop(selectedAccountMode)
                                bridgeCommandPending = false; bridgePendingId = ""; bridgePendingAction = ""; bridgePendingServerId = ""; bridgeTradePendingTickets = emptyList()
                                bridgeMessage = "$action reconciled from server command $observedServerId and fresh account telemetry: ${applied.size} applied, ${rejected.size} rejected. $perTicket"
                            } else {
                                val partial = statuses.values.any { it == "PARTIAL" }
                                bridgeMessage = "${bridgePendingAction} command $observedServerId ${if (partial) "partially applied; unresolved volume remains" else "acknowledged; awaiting terminal per-ticket results and fresh telemetry"}. $perTicket. Pending request is retained; no close success is assumed."
                            }
                        } else when (cmd.optString("outcome")) {
                            "REJECTED" -> {
                                val action = bridgePendingAction
                                val original = bridgeSecrets.getMT5BridgeSafetyOriginalId(selectedAccountMode)
                                val originalAction = bridgeSecrets.getMT5BridgeSafetyOriginalAction(selectedAccountMode).takeIf { original.isNotBlank() }.orEmpty()
                                bridgeSecrets.clearMT5BridgePendingCommand(selectedAccountMode)
                                if (original.isNotBlank()) { bridgeSecrets.saveMT5BridgePendingCommand(original, originalAction, selectedAccountMode); bridgeSecrets.clearMT5BridgeSafetyStop(selectedAccountMode); bridgePendingId = original; bridgePendingAction = originalAction; bridgeExpectedState = when (originalAction) { "START", "RESUME" -> "RUNNING"; "PAUSE" -> "PAUSED"; else -> "STOPPED" }; bridgePendingServerId = ""; bridgeTradePendingTickets = emptyList(); bridgeCommandPending = true; bridgeMessage = "Safety STOP was rejected; original $originalAction request $original remains unresolved. Terminal state is unknown." }
                                else { bridgeCommandPending = false; bridgePendingId = ""; bridgePendingAction = ""; bridgePendingServerId = ""; bridgeTradePendingTickets = emptyList(); bridgeMessage = "$action rejected by EA: ${cmd.optString("message", "No reason supplied")}" }
                            }
                            "APPLIED" -> if (fresh.heartbeatFresh) {
                                val action = bridgePendingAction
                                val expected = bridgeExpectedState
                                val confirmed = fresh.state == expected && cmd.optString("state") == expected
                                if (confirmed) {
                                    bridgeSecrets.clearMT5BridgePendingCommand(selectedAccountMode); bridgeSecrets.clearMT5BridgeSafetyStop(selectedAccountMode); bridgeCommandPending = false; bridgePendingId = ""; bridgePendingAction = ""; bridgePendingServerId = ""; bridgeTradePendingTickets = emptyList()
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
                    Text("Bridge: ${if (bridgeSnapshot?.connected == true) "CONNECTED" else "DISCONNECTED"} · Terminal: ${bridgeSnapshot?.let { if (!it.terminalOnline) "OFFLINE" else if (!it.heartbeatFresh) "STALE HEARTBEAT" else "ONLINE" } ?: "UNVERIFIED"} · EA: ${bridgeSnapshot?.state ?: "UNKNOWN"}", color = TextPrimary, fontSize = 11.sp)
                    val news = bridgeSnapshot?.newsVerdict
                    val newsStatus = news?.optString("status", "UNKNOWN")?.uppercase() ?: "UNKNOWN"
                    Text(if (newsStatus == "CLEAR") "NEWS CLEAR · ${news?.optString("source", "provider")}" else if (newsStatus == "HIGH_IMPACT_WINDOW") "HIGH-IMPACT NEWS — TRADING BLOCKED" else "NEWS UNKNOWN — TRADING BLOCKED", color = if (newsStatus == "CLEAR") ProfitGreen else LossRed, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("News: ${news?.optString("detail", "Authenticated news verdict unavailable") ?: "Authenticated news verdict unavailable"} · retrieved ${news?.optString("retrieved_at_ms", "unavailable") ?: "unavailable"} · event ${news?.optString("event_time_ms", "unavailable") ?: "unavailable"}", color = TextSecondary, fontSize = 10.sp)
                    Text(reportStatus, color = TextSecondary, fontSize = 10.sp)
                    Text("Selected profile: $selectedAccountMode · Verified terminal: ${bridgeSnapshot?.account?.ifBlank { "not verified" } ?: "not verified"} · Actual mode: ${bridgeSnapshot?.actualAccountMode?.ifBlank { "UNVERIFIED" } ?: "UNVERIFIED"}", color = TextSecondary, fontSize = 10.sp)
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
                        Button(enabled = !bridgeBusy && bridgeSecrets.getMT5BridgeAppToken(selectedAccountMode).isNotBlank(), onClick = { coroutineScope.launch { bridgeBusy = true; try { bridgeClient.revoke(); bridgeSecrets.clearMT5BridgePendingCommand(selectedAccountMode); bridgeCommandPending = false; bridgePendingId = ""; bridgePendingAction = ""; bridgeExpectedState = ""; bridgeSnapshot = null; bridgeMode = false; bridgeCode = ""; bridgeMessage = "$selectedAccountMode pairing revoked" } catch (_: Exception) { bridgeMessage = "Revoke not confirmed" }; bridgeBusy = false } }) { Text("Revoke $selectedAccountMode") }
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
                            text = { Text("This confirms LIVE profile ${bridgeSecrets.getManagedMT5Login("LIVE")} at ${bridgeSecrets.getManagedMT5Server("LIVE")} for up to 15 minutes. The connected MT5 terminal must independently report this exact account as LIVE with MASTER access and fresh readiness. This permits real-money entries after all risk checks; it does not liquidate positions or guarantee outcomes.") },
                            confirmButton = { TextButton(onClick = { showLiveAuthorizationDialog = false; coroutineScope.launch { bridgeBusy = true; try { val expiry = bridgeClient.authorizeLiveSession(); bridgeSnapshot = bridgeClient.state(); bridgeMessage = "LIVE authorization confirmed until $expiry." } catch (e: Exception) { bridgeMessage = e.message ?: "LIVE authorization failed closed." }; bridgeBusy = false } }) { Text("AUTHORIZE LIVE") } },
                            dismissButton = { TextButton(onClick = { showLiveAuthorizationDialog = false }) { Text("CANCEL") } }
                        )
                    }
                    Text("Managed EA strategy support is registry-validated; support does not imply profitability or real-broker testing.", color = WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    BridgeTelemetry(bridgeSnapshot)
                    val actionSnapshot = bridgeSnapshot
                    val actionsReady = bridgeMode && bridgeCanManagePositions && actionSnapshot != null && !bridgeBusy && !bridgeCommandPending
                    Text("SNAPSHOT-BOUND MANAGEMENT · $selectedAccountMode", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("mt5_snapshot_management_header"))
                    if (actionSnapshot != null && actionSnapshot.snapshotsValid) {
                        val verifiedOrders = actionSnapshot.activeOrders.orEmpty()
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
                                            sendBridgeTradeCommand(bridgeClient, "CANCEL_PENDING_SELECTED", config, listOf(orderTicket), bridgeSecrets, selectedAccountMode,
                                                { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgePendingServerId = it }, { bridgeTradePendingTickets = it }, { bridgeMessage = it })
                                        } catch (e: Exception) { bridgeMessage = e.message ?: "Order cancellation was not sent; snapshot is unverified."; bridgeBusy = false }
                                    }
                                }) { Text("CANCEL", fontSize = 9.sp) }
                            }
                        }
                        val managedTicketsAvailable = MT5RemoteBridgeClient.managedOrderTicketList(actionSnapshot)
                        val orderOwnershipKnown = actionSnapshot.activeOrders != null && actionSnapshot.activeOrders.orEmpty().all {
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
                                        sendBridgeTradeCommand(bridgeClient, "CANCEL_EA_PENDING", config, tickets, bridgeSecrets, selectedAccountMode,
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
                        val verifiedPositions = actionSnapshot.positions.orEmpty()
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
                                            sendBridgeTradeCommand(bridgeClient, "CLOSE_POSITION_SELECTED", config, listOf(positionId), bridgeSecrets, selectedAccountMode,
                                                { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgePendingServerId = it }, { bridgeTradePendingTickets = it }, { bridgeMessage = it })
                                        } catch (e: Exception) { bridgeMessage = e.message ?: "Position close was not sent; snapshot is unverified."; bridgeBusy = false }
                                    }
                                }) { Text("CLOSE", fontSize = 9.sp) }
                            }
                        }
                        val currentPositionIds = MT5RemoteBridgeClient.positionIdList(actionSnapshot)
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
                                    sendBridgeTradeCommand(bridgeClient, "CLOSE_ALL", config, confirmation.tickets, bridgeSecrets, selectedAccountMode,
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
                                coroutineScope.launch { sendBridgeCommand(bridgeClient, action, JSONObject(), bridgeSecrets, selectedAccountMode, { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgeExpectedState = it }, { bridgeMessage = it }) }
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
        item {
            val isConnected = if (bridgeMode) bridgeTerminalFresh else brokerConnStatus == BrokerConnectionStatus.CONNECTED
            val statusColor = if (bridgeMode) bridgeStatusColor else when (botSummary.state) {
                BotLifecycleState.ACTIVE_SCANNING -> ProfitGreen
                BotLifecycleState.EXECUTING -> ElectricCyan
                BotLifecycleState.CONNECTING_BROKER -> OrangeAccent
                BotLifecycleState.ERROR -> LossRed
                else -> if (isConnected) ProfitGreen else WarningAmber
            }
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.42f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(statusColor.copy(alpha = 0.13f)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = statusColor, modifier = Modifier.size(23.dp))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("AUTOMATION / EXECUTION", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                            Text("Bot command desk", fontSize = 22.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                            Text("$activeSymbol · $selectedTimeframe · $selectedStrategyId",
                                fontSize = 9.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                        }
                        Box(Modifier.clip(RoundedCornerShape(7.dp)).background(statusColor.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 6.dp)) {
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
                    Text(if (bridgeMode) "Remote terminal state comes from EA heartbeat; phone analysis does not execute trades."
                        else if (isConnected) "Execution gateway connected; verify all orders before use."
                        else "Execution is disconnected. Analysis and chart display are separate from broker execution.",
                        fontSize = 10.sp, color = TextSecondary, lineHeight = 15.sp)
                }
            }
        }
        item {
            DerivDemoAccountCard(viewModel)
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChip(
                    label = "BOT STATE",
                    value = if (bridgeMode) bridgeDisplayState else botSummary.state.name.replace('_', ' '),
                    modifier = Modifier.weight(1f),
                    color = if (bridgeMode) bridgeStatusColor else if (botSummary.isRunning || botActive) ProfitGreen else WarningAmber
                )
                StatChip(
                    label = "RISK CAP",
                    value = "${riskPercent}% (max 1%)",
                    modifier = Modifier.weight(1f),
                    color = WarningAmber
                )
            }
        }

        // Master Bot Control Card
        item {
            val isRunning = if (bridgeMode) bridgeDisplayState in setOf("RUNNING", "PAUSED") else if (activeBrokerType == BrokerType.DERIV) derivServiceState == "RUNNING" else botSummary.isRunning || botActive
            val bridgeFresh = bridgeTerminalFresh
            val bridgeReady = bridgeTerminalReady
            val bridgeCanStart = bridgeReady && (bridgeSnapshot?.state == "READY" || bridgeSnapshot?.state == "STOPPED")
            val supportedStrategy = selectedStrategyId in StrategyRegistry.managedStrategyIds
            val validBridgeRisk = MT5RemoteBridgeClient.isValidRiskPercent(riskPercent)
            val derivExecutionMode = activeBrokerType == BrokerType.DERIV
            val derivSymbolSupported = DerivMultiplierRules.isSupportedUnderlying(activeSymbol)
            val statusColor = if (bridgeMode) bridgeStatusColor else when (botSummary.state) {
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
                                text = "BOT STATUS: ${if (bridgeMode) bridgeDisplayState else botSummary.state.name}",
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = statusColor
                            )
                        }

                        Switch(
                            checked = isRunning,
                            enabled = if (bridgeMode) (!bridgeBusy && !bridgeCommandPending && (if (isRunning) bridgeFresh else bridgeCanStart && supportedStrategy && validBridgeRisk)) else if (derivExecutionMode) derivServiceState !in setOf("STARTING", "STOPPING") else true,
                            onCheckedChange = { active ->
                                if (bridgeMode) coroutineScope.launch {
                                    bridgeBusy = true
                                    val action = if (active) "START" else "STOP"
                                    val config = JSONObject()
                                        .put("strategy", selectedStrategyId)
                                        .put("symbols", JSONArray().put(terminalSymbol))
                                        .put("timeframes", JSONArray().put(selectedTimeframe))
                                        .put("risk_percent", riskPercent)
                                        .put("max_daily_loss_percent", 2.0)
                                        .put("max_concurrent_positions", 2)
                                    if (active && !supportedStrategy) { bridgeMessage = "Blocked: selected strategy is not in the managed EA registry."; bridgeBusy = false; return@launch }
                                    if (active && (!MT5RemoteBridgeClient.isValidRiskPercent(riskPercent))) { bridgeMessage = "Blocked: risk must be finite and in (0, 1]%; no clamping applied."; bridgeBusy = false; return@launch }
                                    if (active && !bridgeCanStart) { bridgeMessage = "Blocked: verified account must be READY or STOPPED with a fresh, trade-authorized heartbeat."; bridgeBusy = false; return@launch }
                                    sendBridgeCommand(bridgeClient, action, config, bridgeSecrets, selectedAccountMode, { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgeExpectedState = it }, { bridgeMessage = it })
                                } else if (derivExecutionMode) {
                                    if (active) {
                                        when {
                                            derivAccount?.demoVerified != true -> statusMessage = "START BLOCKED — verify an active DEMO account first."
                                            !derivSymbolSupported -> statusMessage = "START BLOCKED — $activeSymbol is a Forex/CFD symbol, not a supported Deriv Multiplier underlying. Select an available synthetic index."
                                            else -> {
                                                DerivDemoBotService.start(reportContext, activeSymbol, selectedStrategyId, selectedTimeframe)
                                                statusMessage = "START REQUESTED — waiting for the foreground service to verify the Demo account and broker state."
                                            }
                                        }
                                    } else {
                                        DerivDemoBotService.stop(reportContext)
                                        statusMessage = "STOP REQUESTED — no new entry requests will be generated; known contracts remain broker-tracked."
                                    }
                                } else viewModel.setBotStatus(active) { success, err ->
                                    statusMessage = if (success) { if (active) "24/7 Surveillance Engine activated" else "Surveillance stopped" } else err ?: "Failed to change engine state"
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
                    if (derivExecutionMode) {
                        Text(
                            "DERIV DEMO MULTIPLIER: $derivServiceState · supports only MULTUP/MULTDOWN on verified synthetic underlyings. Forex/CFD plans and MT5 execution remain blocked.",
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
                                .background((if (reqMarginVerified) ProfitGreen else WarningAmber).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (reqMarginVerified) "BROKER VERIFIED" else "UNVERIFIED",
                                fontSize = 8.sp,
                                color = if (reqMarginVerified) ProfitGreen else WarningAmber,
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
                                onClick = { viewModel.selectTicker(pair) },
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

                    HorizontalDivider(color = CardBorder)

                    Text(
                        text = "2. ACCOUNT BALANCE, EQUITY & AUTO LOT AUDIT",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )

                    val remoteMetrics = bridgeSnapshot?.takeIf { snapshot ->
                        bridgeMode && snapshot.connected && snapshot.terminalOnline && snapshot.heartbeatFresh &&
                            snapshot.terminalConnected && snapshot.accountVerified && snapshot.snapshotsValid && snapshot.managedState == "READY" &&
                            snapshot.actualAccountMode == selectedAccountMode &&
                            snapshot.actualLogin == bridgeSecrets.getManagedMT5Login(selectedAccountMode) &&
                            snapshot.actualServer == bridgeSecrets.getManagedMT5Server(selectedAccountMode)
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
                    val sizedResult = remember(activeSymbol, verifiedFreeMargin, riskPercent, effectiveEntry, effectiveSl) {
                        if (verifiedFreeMargin != null) viewModel.sizeFor(activeSymbol, verifiedFreeMargin, riskPercent, effectiveEntry, effectiveSl) else null
                    }
                    val lotDisplay = when {
                        verifiedFreeMargin == null -> "UNVERIFIED"
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
                                    !reqMarginVerified -> "Required margin could not be verified by the connected broker. Remote MT5 still performs its own final margin check before any order."
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
            val isMultiScanning by viewModel.isMultiAIScanning.collectAsStateWithLifecycle()
            val consensusStatus by viewModel.multiAIConsensusStatus.collectAsStateWithLifecycle()

            InsightCard("ENGINE SAFEGUARDS & QUANT CONTROLS") {
                InsightLine("Active Strategy", strat)
                InsightLine("Account Capital", "$${"%.2f".format(capital)}")
                InsightLine("NFP Safeguard", if (nfpArmed) "ARMED (High-Impact Guard Active)" else "DISARMED")
                InsightLine("Execution Status", if (isHalted) "HALTED (News Filter Lock)" else "NORMAL")
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
            var selectedBrokerCompanyName by remember { mutableStateOf("FBS Markets Inc.") }
            var showBrokerSearchDialog by remember { mutableStateOf(false) }
            var brokerSearchQuery by remember { mutableStateOf("") }
            var savePasswordChecked by remember(selectedAccountMode) { mutableStateOf(bridgeSecrets.isManagedMT5PasswordSaveEnabled(selectedAccountMode)) }
            var showOpenDemoDialog by remember { mutableStateOf(false) }
            var showOpenRealDialog by remember { mutableStateOf(false) }

            val storedBal = remember {
                viewModel.app.secretsManager.getMT5AccountBalance().takeIf { it > 0.0 } ?: 0.0
            }
            var mt5BalanceInput by remember { mutableStateOf(String.format(java.util.Locale.US, "%.2f", storedBal)) }
            var selectedLeverage by remember { mutableStateOf(viewModel.app.secretsManager.getMT5Leverage().takeIf { it > 0 } ?: 0) }
            var expandedLeverage by remember { mutableStateOf(false) }
            val currentSessionStatus = viewModel.app.secretsManager.getMT5SessionStatus()
            val hasSavedLogin = bridgeSecrets.getManagedMT5Login(selectedAccountMode).isNotBlank() && bridgeSecrets.getManagedMT5Server(selectedAccountMode).isNotBlank()
            val isSessionExpired = currentSessionStatus == "SESSION_EXPIRED"
            var isEditingCredentials by remember(selectedAccountMode) { mutableStateOf(!hasSavedLogin) }

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
                                text = "METATRADER 5 • ACCOUNT ARCHITECTURE",
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

                    // Broker Login & Configuration Inputs (Only shown when editing or disconnected)
                    if (isEditingCredentials || brokerConnStatus != BrokerConnectionStatus.CONNECTED) {
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
                                    Text("Search listed suggestions or enter the exact server manually", fontSize = 9.sp, color = TextSecondary)
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
                        // The selected account access type uses one password field to prevent accidental credential-mode confusion.
                        OutlinedTextField(
                            value = mt5PasswordInput,
                            onValueChange = { mt5PasswordInput = it },
                            label = { Text(if (mt5AccessType == "INVESTOR") "Investor password" else "Master trading password") },
                            placeholder = { Text(if (mt5AccessType == "INVESTOR") "Read-only investor password" else "Enter trading password") },
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
                        Text("The account mode you select is checked against the authenticated MT5 terminal before trading is enabled.", fontSize = 9.sp, color = TextSecondary)
                        // Server Selector with Dynamic Dropdown
                        var expandedServer by remember { mutableStateOf(false) }
                        val matchedBrokers = remember(mt5ServerInput, selectedBrokerCompanyName) {
                            com.example.data.broker.BrokerDirectory.searchBrokers(
                                if (mt5ServerInput.isNotBlank()) mt5ServerInput else selectedBrokerCompanyName
                            )
                        }
                        val availableServerList = remember(matchedBrokers) {
                            matchedBrokers.flatMap { it.servers }.distinct().take(12)
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = mt5ServerInput,
                                onValueChange = { mt5ServerInput = it },
                                label = { Text("Server") },
                                placeholder = { Text("e.g. Exness-MT5Real, ICMarketsSC-Live01, FBS-Real") },
                                trailingIcon = {
                                    IconButton(onClick = { expandedServer = !expandedServer }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("bot_mt5_server"),
                                singleLine = true
                            )
                            DropdownMenu(
                                expanded = expandedServer && availableServerList.isNotEmpty(),
                                onDismissRequest = { expandedServer = false },
                                modifier = Modifier.fillMaxWidth(0.85f)
                            ) {
                                availableServerList.forEach { srv ->
                                    DropdownMenuItem(
                                        text = { Text(srv, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            mt5ServerInput = srv
                                            expandedServer = false
                                        }
                                    )
                                }
                            }
                        }

                        Text("TRANSACTION COST MODEL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Choose the model that matches your broker's published pricing. Incorrect cost inputs can make risk calculations unsafe.", fontSize = 9.sp, color = WarningAmber)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                listOf("OBSERVED_ROUND_TURN" to "Observed history", "CONFIGURED_ROUND_TURN" to "Round-turn commission"),
                                listOf("CONFIGURED_PER_SIDE" to "Per-side commission", "ZERO_COMMISSION_SPREAD_ONLY" to "Zero commission + spread")
                            ).forEach { group ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    group.forEach { (model, label) ->
                                        OutlinedButton(
                                            onClick = {
                                                mt5CostModel = model
                                                if (model == "OBSERVED_ROUND_TURN") {
                                                    mt5CommissionPerLot = ""
                                                    mt5CostEvidence = ""
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (mt5CostModel == model) ElectricCyan else TextSecondary)
                                        ) {
                                            Text(if (mt5CostModel == model) "✓ $label" else label, fontSize = 8.sp, maxLines = 2)
                                        }
                                    }
                                }
                            }
                        }
                        if (mt5CostModel == "CONFIGURED_ROUND_TURN" || mt5CostModel == "CONFIGURED_PER_SIDE") {
                            OutlinedTextField(
                                value = mt5CommissionPerLot,
                                onValueChange = { mt5CommissionPerLot = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                label = { Text(if (mt5CostModel == "CONFIGURED_PER_SIDE") "Commission per side / 1.00 lot" else "Round-turn commission / 1.00 lot") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("bot_cost_commission")
                            )
                        }
                        if (mt5CostModel != "OBSERVED_ROUND_TURN") {
                            OutlinedTextField(
                                value = mt5CostEvidence,
                                onValueChange = { mt5CostEvidence = it.filter { ch -> ch.code >= 32 && ch.code != 127 }.take(512) },
                                label = { Text("Broker pricing evidence reference") },
                                placeholder = { Text("Official schedule / contract-spec section") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("bot_cost_evidence")
                            )
                            Text("Use an actual broker fee schedule or contract specification you have checked. This reference text is not independently verified by the app. Amounts are in account currency per standard lot.", fontSize = 9.sp, color = TextSecondary)
                        } else {
                            Text("Observed-history mode requires representative completed EA trades on this symbol; a fresh account may be blocked until valid history exists.", fontSize = 9.sp, color = TextSecondary)
                        }
                        Text("Balance, equity, margin, currency and leverage appear only after a verified broker account snapshot.", fontSize = 9.sp, color = TextSecondary)
                        // Leverage Selector
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = "1:$selectedLeverage",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Account Leverage") },
                                trailingIcon = {
                                    IconButton(onClick = { expandedLeverage = !expandedLeverage }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("bot_mt5_leverage"),
                                singleLine = true
                            )
                            DropdownMenu(
                                expanded = expandedLeverage,
                                onDismissRequest = { expandedLeverage = false },
                                modifier = Modifier.fillMaxWidth(0.85f)
                            ) {
                                listOf(100, 200, 500, 1000).forEach { lev ->
                                    DropdownMenuItem(
                                        text = { Text("1:$lev", fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            selectedLeverage = lev
                                            expandedLeverage = false
                                        }
                                    )
                                }
                            }
                        }

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
                                if (mt5PasswordInput.isBlank()) {
                                    brokerConnectionMessage = "Please enter your password."
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
                                val selectedCostModel = mt5CostModel
                                val selectedCommission = when (selectedCostModel) {
                                    "OBSERVED_ROUND_TURN", "ZERO_COMMISSION_SPREAD_ONLY" -> 0.0
                                    else -> mt5CommissionPerLot.trim().toDoubleOrNull()
                                }
                                val selectedCostEvidence = if (selectedCostModel == "OBSERVED_ROUND_TURN") "" else mt5CostEvidence.trim()
                                if (selectedCommission == null || !selectedCommission.isFinite() || selectedCommission < 0.0) {
                                    brokerConnectionMessage = "Enter a valid finite commission value from the broker's pricing schedule."
                                    return@Button
                                }
                                if (selectedCostModel in setOf("CONFIGURED_ROUND_TURN", "CONFIGURED_PER_SIDE") && selectedCommission <= 0.0) {
                                    brokerConnectionMessage = "Configured commission must be greater than zero; choose zero-commission mode only when broker pricing confirms it."
                                    return@Button
                                }
                                if (selectedCostModel == "ZERO_COMMISSION_SPREAD_ONLY" && selectedCommission != 0.0) {
                                    brokerConnectionMessage = "Zero-commission mode must use a commission value of zero."
                                    return@Button
                                }
                                if (selectedCostModel != "OBSERVED_ROUND_TURN" && (selectedCostEvidence.length < 8 || selectedCostEvidence.any { ch -> ch.code < 32 || ch.code == 127 })) {
                                    brokerConnectionMessage = "Enter a reference to the actual broker pricing or contract specification (at least 8 characters)."
                                    return@Button
                                }
                                isConnectingBroker = true
                                brokerConnectionMessage = null
                                coroutineScope.launch {
                                    val secret = mt5PasswordInput
                                    try {
                                        if (!bridgeClient.managedConfigured()) {
                                            brokerConnectionMessage = "CONFIGURATION_REQUIRED"
                                        } else {
                                            bridgeMode = true
                                            val snapshot = bridgeClient.registerManagedAccount(
                                                mt5LoginInput, secret, mt5ServerInput, mt5AccessType, managedCredentialConsent,
                                                savePasswordChecked, selectedCostModel, selectedCommission, selectedCostEvidence
                                            )
                                            bridgeMode = true
                                            bridgeSnapshot = snapshot
                                            bridgeMessage = snapshot.managedState.ifBlank { "PROVISIONING" }
                                            brokerConnectionMessage = "${snapshot.managedState.ifBlank { "PROVISIONING" }} — broker connection is not confirmed until a fresh authenticated EA heartbeat."
                                            isEditingCredentials = false
                                        }
                                    } catch (e: Exception) {
                                        brokerConnectionMessage = e.message?.takeIf { it == "CONFIGURATION_REQUIRED" } ?: "Managed registration failed; check service availability and credentials."
                                    } finally {
                                        mt5PasswordInput = ""
                                        managedCredentialConsent = false
                                        isConnectingBroker = false
                                    }
                                }
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
                            title = { Text("Search listed brokers", fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = brokerSearchQuery,
                                        onValueChange = { brokerSearchQuery = it },
                                        label = { Text("Search Broker or Server (e.g. Exness, IC Markets, XM)") },
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
                                                    selectedBrokerCompanyName = broker.name
                                                    if (mt5ServerInput.isBlank()) {
                                                        mt5ServerInput = broker.servers.firstOrNull().orEmpty()
                                                    }
                                                    showBrokerSearchDialog = false
                                                },
                                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Text(broker.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ElectricCyan)
                                                    Text("Servers: ${broker.servers.take(3).joinToString(", ")}", fontSize = 9.sp, color = TextSecondary)
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

        // Target Configuration Parameters Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, TabBorder),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "AUTONOMOUS PARAMETERS & SAFETY CONSTRAINTS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyanPrimary
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Instrument:", fontSize = 11.sp, color = TextSecondary)
                        Text(activeSymbol, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Strategy:", fontSize = 11.sp, color = TextSecondary)
                        Text(selectedStrategyId, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Timeframe:", fontSize = 11.sp, color = TextSecondary)
                        Text(selectedTimeframe, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Max Risk per Trade:", fontSize = 11.sp, color = TextSecondary)
                        Text("${riskPercent}% (Capped strictly <= 1.0%)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ProfitGreen)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Minimum Executable RR:", fontSize = 11.sp, color = TextSecondary)
                        Text("1:2.0 (Absolute minimum)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ProfitGreen)
                    }
                }
            }
        }

        // MetaTrader 5 Instant 1-Tap Market Execution (One-Click Trading)
        item {
            val currentTicker = tickers[activeSymbol]
            val currentPrice = currentTicker?.price ?: 0.0
            val bidPrice = currentTicker?.bid ?: currentPrice
            val askPrice = currentTicker?.ask ?: currentPrice
            val spreadVal = if (currentTicker?.bid != null && currentTicker?.ask != null && currentTicker.hasRealSpread) {
                (currentTicker.ask - currentTicker.bid)
            } else currentTicker?.spread

            val spec = com.destinedfx.core.instrument.SpecRegistry.get(activeSymbol)
            val pipUnit = spec?.pipSize ?: if (activeSymbol.contains("JPY", true)) 0.01 else if (activeSymbol.contains("XAU", true)) 0.1 else 0.0001
            val slPipsNum = (instantSlPips.toDoubleOrNull() ?: 20.0).coerceAtLeast(5.0)
            val tpPipsNum = (instantTpPips.toDoubleOrNull() ?: 50.0).coerceAtLeast(10.0)
            val rrRatio = if (slPipsNum > 0.0) tpPipsNum / slPipsNum else 2.5

            val buySl = if (askPrice > 0.0) askPrice - (slPipsNum * pipUnit) else 0.0
            val buyTp = if (askPrice > 0.0) askPrice + (tpPipsNum * pipUnit) else 0.0
            val sellSl = if (bidPrice > 0.0) bidPrice + (slPipsNum * pipUnit) else 0.0
            val sellTp = if (bidPrice > 0.0) bidPrice - (tpPipsNum * pipUnit) else 0.0

            val isConnected = brokerConnStatus == BrokerConnectionStatus.CONNECTED

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("instant_execution_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(ElectricCyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                            }
                            Column {
                                Text(
                                    text = "INSTANT MARKET EXECUTION",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = ElectricCyan
                                )
                                Text(
                                    text = "MetaTrader 5 One-Click Deal Protocol",
                                    fontSize = 9.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (isConnected) ProfitGreen.copy(alpha = 0.15f) else LossRed.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isConnected) "MT5 LIVE GATEWAY" else "OFFLINE",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (isConnected) ProfitGreen else LossRed
                            )
                        }
                    }

                    HorizontalDivider(color = CardBorder)

                    // Symbol & Real-Time Price Overview
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(activeSymbol, fontSize = 18.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                            Text("Active Order Symbol", fontSize = 10.sp, color = TextSecondary)
                        }

                        if (spreadVal != null && spreadVal > 0.0) {
                            val spreadPips = spreadVal / pipUnit
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(DarkSurfaceVariant)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Spread: ${String.format(java.util.Locale.US, "%.1f", spreadPips)} pips",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Lot Size Selector & Stepper
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Order Volume (Lots):", fontSize = 11.sp, color = TextSecondary)
                            Text(
                                text = "${String.format(java.util.Locale.US, "%.2f", instantLotSize)} Lot",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    instantLotSize = (instantLotSize - 0.01).coerceAtLeast(0.01)
                                },
                                modifier = Modifier.size(36.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("-", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            }

                            val lotPresets = listOf(0.01, 0.02, 0.05, 0.10, 0.50, 1.00)
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(lotPresets) { lot ->
                                    val isSelected = Math.abs(instantLotSize - lot) < 0.001
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) ElectricCyan else DarkSurfaceVariant)
                                            .border(1.dp, if (isSelected) ElectricCyan else CardBorder, RoundedCornerShape(6.dp))
                                            .clickable { instantLotSize = lot }
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = String.format(java.util.Locale.US, "%.2f", lot),
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                                            color = if (isSelected) DarkBackground else TextPrimary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    instantLotSize = (instantLotSize + 0.01).coerceAtMost(10.0)
                                },
                                modifier = Modifier.size(36.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            }
                        }
                    }

                    // SL / TP pip settings
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = instantSlPips,
                            onValueChange = { instantSlPips = it },
                            label = { Text("SL (Pips)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = instantTpPips,
                            onValueChange = { instantTpPips = it },
                            label = { Text("TP (Pips)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    // RR calculation info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Projected RR: 1:${String.format(java.util.Locale.US, "%.1f", rrRatio)}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (rrRatio >= 2.0) ProfitGreen else WarningAmber,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (rrRatio >= 2.0) "Passes SMC Filter (>= 1:2.0)" else "SMC Warning: RR < 1:2.0",
                            fontSize = 10.sp,
                            color = if (rrRatio >= 2.0) ProfitGreen else WarningAmber
                        )
                    }

                    if (activeBrokerType == com.example.data.broker.BrokerType.MT5) {
                        Text("Direct MT5 orders are disabled. Use the managed EA controls above; only broker-confirmed terminal telemetry can confirm an execution.", color = WarningAmber, fontSize = 10.sp)
                    }
                    if (activeBrokerType == BrokerType.DERIV) {
                        Text("Deriv Multiplier entries are blocked here: this control uses Forex-style lots and price stops, not a validated Multiplier stake/contract workflow.", color = WarningAmber, fontSize = 10.sp)
                    }
                    // Direct MT5 order routing is disabled; MT5 execution is managed-terminal only.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // SELL BUTTON
                        Button(
                            enabled = activeBrokerType != BrokerType.MT5 && activeBrokerType != BrokerType.DERIV,
                            onClick = {
                                if (!isConnected) {
                                    instantExecutionStatus = "⚠️ MT5 Terminal is offline. Connect your broker above before executing orders."
                                } else if (bidPrice <= 0.0) {
                                    instantExecutionStatus = "⚠️ Waiting for live bid quote on $activeSymbol..."
                                } else {
                                    viewModel.executeTrade(
                                        symbol = activeSymbol,
                                        positionType = "SELL",
                                        entryPrice = bidPrice,
                                        stopLoss = sellSl,
                                        takeProfit = sellTp,
                                        rationale = "MT5 1-Tap Execution (${String.format(java.util.Locale.US, "%.2f", instantLotSize)} lots)",
                                        lotSize = instantLotSize
                                    )
                                    instantExecutionStatus = "Order request submitted to the local trade workflow; fill is not confirmed. Check broker order/deal history."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LossRed),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(62.dp).testTag("instant_sell_button")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("SELL BY MARKET", fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color.White)
                                Text(
                                    text = if (bidPrice > 0.0) formatPrice(bidPrice, activeSymbol) else "—",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White
                                )
                                Text("BID", fontSize = 8.sp, color = Color.White.copy(alpha = 0.7f))
                            }
                        }

                        // BUY BUTTON
                        Button(
                            enabled = activeBrokerType != BrokerType.MT5 && activeBrokerType != BrokerType.DERIV,
                            onClick = {
                                if (!isConnected) {
                                    instantExecutionStatus = "⚠️ MT5 Terminal is offline. Connect your broker above before executing orders."
                                } else if (askPrice <= 0.0) {
                                    instantExecutionStatus = "⚠️ Waiting for live ask quote on $activeSymbol..."
                                } else {
                                    viewModel.executeTrade(
                                        symbol = activeSymbol,
                                        positionType = "BUY",
                                        entryPrice = askPrice,
                                        stopLoss = buySl,
                                        takeProfit = buyTp,
                                        rationale = "MT5 1-Tap Execution (${String.format(java.util.Locale.US, "%.2f", instantLotSize)} lots)",
                                        lotSize = instantLotSize
                                    )
                                    instantExecutionStatus = "Order request submitted to the local trade workflow; fill is not confirmed. Check broker order/deal history."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(62.dp).testTag("instant_buy_button")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("BUY BY MARKET", fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color.White)
                                Text(
                                    text = if (askPrice > 0.0) formatPrice(askPrice, activeSymbol) else "—",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White
                                )
                                Text("ASK", fontSize = 8.sp, color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // No deterministic strategy-plan-to-broker execution flow exists for this shortcut.
                    Button(
                        enabled = false,
                        onClick = { instantExecutionStatus = "Unavailable: no broker-validated strategy plan is available." },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("full_auto_execute_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = DarkBackground)
                            Text(
                                text = "FULL-AUTO ORDERING UNAVAILABLE — VERIFIED PLAN REQUIRED",
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp,
                                color = DarkBackground,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Execution status / alert message
                    instantExecutionStatus?.let { msg ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkSurfaceVariant)
                                .border(1.dp, if (msg.startsWith("✅")) ProfitGreen.copy(alpha = 0.5f) else WarningAmber.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = msg,
                                fontSize = 10.sp,
                                color = if (msg.startsWith("✅")) ProfitGreen else WarningAmber,
                                lineHeight = 14.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        if (!bridgeMode) {
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
                val cmp = freshCmp ?: 0.0
                val isLong = pos.direction == com.example.domain.model.SignalDirection.LONG
                val mult = if (isLong) 1.0 else -1.0
                val spec = com.destinedfx.core.instrument.SpecRegistry.get(pos.symbol)
                val contractSize = spec?.contractSize
                val liveFloatingPnl = if (freshCmp != null && pos.entryPrice > 0.0 && contractSize != null && contractSize.isFinite() && contractSize > 0.0) {
                    (freshCmp - pos.entryPrice) * mult * contractSize * pos.lotSize
                } else pos.currentProfit
                val isProfitable = liveFloatingPnl >= 0.0

                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, if (isProfitable) ProfitGreen.copy(alpha = 0.5f) else LossRed.copy(alpha = 0.5f)),
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
                                    text = "${if (isLong) "BUY" else "SELL"} ${pos.lotSize} ${pos.symbol}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isLong) ProfitGreen else LossRed
                                )
                                Text(
                                    text = "${if (isProfitable) "+" else ""}${String.format(java.util.Locale.US, "%.2f", liveFloatingPnl)} USD",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    color = if (isProfitable) ProfitGreen else LossRed,
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

    if (showPostLoginPairSelectorModal) {
        AlertDialog(
            onDismissRequest = { showPostLoginPairSelectorModal = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = ProfitGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "BROKER CONNECTED SUCCESSFULLY",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = ProfitGreen
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Select a pair for bot analysis. Account figures shown here come only from the authenticated broker snapshot; execution still requires risk and market-data checks.",
                        fontSize = 11.sp,
                        color = TextPrimary,
                        lineHeight = 16.sp
                    )

                    HorizontalDivider(color = CardBorder)

                    Text(
                        text = "CHOOSE PAIR TO AUTOMATE:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = ElectricCyan
                    )

                    val selectPairs = listOf(
                        "EUR/USD" to "Forex Major",
                        "GBP/USD" to "Forex Major",
                        "XAU/USD" to "Gold Commodity",
                        "BTC/USD" to "Crypto Asset",
                        "NAS100" to "US Tech Index",
                        "SPX500" to "US SP500 Index",
                        "USD/JPY" to "Forex Major",
                        "USD/CAD" to "Forex Major",
                        "USD/CHF" to "Forex Major"
                    )

                    LazyColumn(
                        modifier = Modifier.height(240.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(selectPairs) { (pair, category) ->
                            Card(
                                onClick = {
                                    viewModel.selectTicker(pair)
                                    showPostLoginPairSelectorModal = false
                                    statusMessage = "Bot configured for $pair. Verified account metrics are used when available; live execution requires all safety checks."
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (pair == activeSymbol) ElectricCyan.copy(alpha = 0.15f) else DarkSurfaceVariant
                                ),
                                border = BorderStroke(1.dp, if (pair == activeSymbol) ElectricCyan else CardBorder),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().testTag("modal_pair_$pair")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(pair, fontWeight = FontWeight.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                                        Text(category, fontSize = 9.sp, color = TextSecondary)
                                    }
                                    if (pair == activeSymbol) {
                                        Text("ACTIVE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = ElectricCyan, fontFamily = FontFamily.Monospace)
                                    } else {
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPostLoginPairSelectorModal = false },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)
                ) {
                    Text("Confirm $activeSymbol", fontWeight = FontWeight.Black, fontSize = 11.sp)
                }
            }
        )
    }
}


@Composable
private fun BridgeTelemetry(snapshot: MT5RemoteBridgeClient.Snapshot?) {
    val unavailable = "Unavailable"
    Text("ACTUAL TERMINAL ACCOUNT · ${snapshot?.currency?.ifBlank { unavailable } ?: unavailable}", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    Text("Balance: ${snapshot?.balance?.toString() ?: unavailable} ${snapshot?.currency.orEmpty()} · Equity: ${snapshot?.equity?.toString() ?: unavailable} ${snapshot?.currency.orEmpty()}", color = TextPrimary, fontSize = 11.sp)
    Text("Margin: ${snapshot?.margin?.toString() ?: unavailable} · Free margin: ${snapshot?.freeMargin?.toString() ?: unavailable} · Margin level: ${snapshot?.marginLevel?.toString() ?: unavailable} · Leverage: ${snapshot?.leverage?.let { "1:$it" } ?: unavailable}", color = TextPrimary, fontSize = 10.sp)
    val positions = snapshot?.positions
    val floatingValues = positions.orEmpty().mapNotNull { p -> if (p.has("floating_profit") && !p.isNull("floating_profit")) p.optDouble("floating_profit").takeIf { it.isFinite() } else null }
    val floatingTotal = when { snapshot == null || !snapshot.snapshotsValid || positions == null -> unavailable; positions.isEmpty() -> "0.0"; floatingValues.size == positions.size -> floatingValues.sum().toString(); else -> unavailable }
    Text("Floating P/L: $floatingTotal ${snapshot?.currency.orEmpty()}", color = TextPrimary, fontSize = 11.sp)
    Text("LIVE POSITIONS (${positions?.size ?: unavailable})", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    if (positions == null || !snapshot!!.snapshotsValid || positions.isEmpty()) Text(if (positions?.isEmpty() == true && snapshot.snapshotsValid) "No open positions confirmed" else unavailable, color = TextSecondary, fontSize = 10.sp)
    positions.orEmpty().forEach { p -> Text("#${p.optString("ticket", unavailable)} / ${p.optString("position_id", unavailable)} · ${p.optString("symbol", unavailable)} ${p.optString("direction", unavailable)} · volume ${p.optString("volume", unavailable)} · entry ${p.optString("open_price", unavailable)} · current broker P/L ${p.optString("floating_profit", unavailable)} · SL ${p.optString("stop_loss", unavailable)} · TP ${p.optString("take_profit", unavailable)} · swap ${p.optString("swap", unavailable)}", color = TextPrimary, fontSize = 10.sp) }
    val activeOrders = snapshot?.activeOrders
    Text("PENDING BROKER ORDERS (${activeOrders?.size ?: unavailable})", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    if (activeOrders == null || !snapshot!!.snapshotsValid || activeOrders.isEmpty()) Text(if (activeOrders?.isEmpty() == true && snapshot.snapshotsValid) "No pending orders confirmed" else unavailable, color = TextSecondary, fontSize = 10.sp)
    activeOrders.orEmpty().forEach { order -> Text("#${order.optString("ticket", unavailable)} · ${order.optString("symbol", unavailable)} ${order.optString("direction", unavailable)} · remaining ${order.optString("remaining_volume", unavailable)} · price ${order.optString("open_price", unavailable)} · SL ${order.optString("stop_loss", unavailable)} · TP ${order.optString("take_profit", unavailable)} · state ${order.optString("state", unavailable)}", color = TextPrimary, fontSize = 10.sp) }
    val deals = snapshot?.closedDeals
    val netValue = if (deals == null || snapshot?.snapshotsValid != true) null
        else MT5RemoteBridgeClient.recentClosedDealNetPnl(deals, System.currentTimeMillis())
    Text(
        "REALIZED DEAL NET (last 7 days, including fees): ${netValue?.toString() ?: unavailable} ${snapshot?.currency.orEmpty()}",
        color = TextPrimary,
        fontSize = 10.sp
    )
    deals.orEmpty().take(10).forEach { deal ->
        val fees = if (deal.has("fees") && !deal.isNull("fees")) deal.opt("fees").toString() else unavailable
        Text("Closed deal #${deal.optString("ticket", unavailable)} / position ${deal.optString("position_id", unavailable)} · ${deal.optString("symbol", unavailable)} · closed ${deal.optString("closed_at", unavailable)} · realized ${deal.optString("profit", unavailable)} · commission ${deal.optString("commission", unavailable)} · swap ${deal.optString("swap", unavailable)} · fees $fees", color = TextSecondary, fontSize = 10.sp)
    }
    val lastSignal = snapshot?.lastSignal
    Text("Last signal: ${lastSignal?.optString("execution_state", lastSignal?.optString("state", unavailable)) ?: unavailable} · requested ${lastSignal?.optString("requested_volume", unavailable) ?: unavailable} · executed ${lastSignal?.optString("executed_volume", unavailable) ?: unavailable} · order ${lastSignal?.optString("order", unavailable) ?: unavailable} · deal ${lastSignal?.optString("deal", unavailable) ?: unavailable}", color = TextSecondary, fontSize = 10.sp)
    snapshot?.raw?.optString("last_error")?.takeIf { it.isNotBlank() }?.let { Text("EA ERROR: $it", color = LossRed, fontSize = 10.sp) }
}

private suspend fun sendBridgeCommand(
    client: MT5RemoteBridgeClient, action: String, config: JSONObject, secrets: com.example.security.SecretsManager,
    profile: String,
    busy: (Boolean) -> Unit, pending: (Boolean) -> Unit, requestId: (String) -> Unit,
    pendingAction: (String) -> Unit, expected: (String) -> Unit, message: (String) -> Unit
) {
    val unresolvedId = secrets.getMT5BridgePendingCommandId(profile)
    if (unresolvedId.isNotBlank() && action != "STOP") {
        pending(true); requestId(unresolvedId); pendingAction(secrets.getMT5BridgePendingCommandAction(profile))
        message("A bridge command is already unresolved. Reconcile its saved request ID or use STOP as the one safety override; no other command was sent.")
        busy(false)
        return
    }
    val id = java.util.UUID.randomUUID().toString()
    if (unresolvedId.isNotBlank() && action == "STOP" && !secrets.saveMT5BridgeSafetyStop(unresolvedId, secrets.getMT5BridgePendingCommandAction(profile), id, profile)) {
        message("Secure safety-stop storage failed; no STOP command was sent.")
        busy(false)
        return
    }
    if (!secrets.saveMT5BridgePendingCommand(id, action, profile)) {
        message("Secure pending-command storage failed; no command was sent.")
        busy(false)
        return
    }
    requestId(id); pendingAction(action)
    expected(when (action) { "START", "RESUME" -> "RUNNING"; "PAUSE" -> "PAUSED"; else -> "STOPPED" })
    pending(true)
    busy(true)

    fun handleDefinitiveRejection(detail: String) {
        val original = secrets.getMT5BridgeSafetyOriginalId(profile)
        val originalAction = secrets.getMT5BridgeSafetyOriginalAction(profile)
        if (action == "STOP" && original.isNotBlank() && originalAction.isNotBlank()) {
            secrets.saveMT5BridgePendingCommand(original, originalAction, profile)
            secrets.clearMT5BridgeSafetyStop(profile)
            requestId(original)
            pendingAction(originalAction)
            expected(when (originalAction) { "START", "RESUME" -> "RUNNING"; "PAUSE" -> "PAUSED"; else -> "STOPPED" })
            pending(true)
        } else {
            secrets.clearMT5BridgePendingCommand(profile)
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
    val unresolvedId = secrets.getMT5BridgePendingCommandId(profile)
    if (unresolvedId.isNotBlank()) {
        pending(true); requestId(unresolvedId); pendingAction(secrets.getMT5BridgePendingCommandAction(profile))
        message("A bridge command is unresolved. Reconcile its request ID before sending any position/order action.")
        busy(false)
        return
    }
    val id = java.util.UUID.randomUUID().toString()
    if (!secrets.saveMT5BridgePendingCommand(id, action, profile)) {
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
                secrets.clearMT5BridgePendingCommand(profile)
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
            secrets.clearMT5BridgePendingCommand(profile)
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
