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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.example.data.trade.MT5RemoteBridgeClient
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun BotWorkstationTab(viewModel: DestinedFXViewModel) {
    var bridgeMode by remember { mutableStateOf(viewModel.app.secretsManager.getMT5BridgeAppToken().isNotBlank()) }
    val bridgeClient = remember { MT5RemoteBridgeClient(viewModel.app.secretsManager) }
    var bridgeUrl by remember { mutableStateOf(bridgeClient.baseUrl()) }
    var bridgeCode by remember { mutableStateOf("") }
    var bridgeMessage by remember { mutableStateOf(viewModel.app.secretsManager.getMT5BridgePendingCommandId().let { id -> if (id.isBlank()) "SETUP REQUIRED" else "Unresolved saved request $id; checking its acknowledgement. Do not resend." }) }
    var bridgeSnapshot by remember { mutableStateOf<MT5RemoteBridgeClient.Snapshot?>(null) }
    var bridgeBusy by remember { mutableStateOf(false) }
    val bridgeSecrets = viewModel.app.secretsManager
    var bridgePendingId by remember { mutableStateOf(bridgeSecrets.getMT5BridgePendingCommandId()) }
    var bridgePendingAction by remember { mutableStateOf(bridgeSecrets.getMT5BridgePendingCommandAction()) }
    var bridgeCommandPending by remember { mutableStateOf(bridgePendingId.isNotBlank() && bridgePendingAction.isNotBlank()) }
    var bridgeExpectedState by remember { mutableStateOf(when (bridgePendingAction) { "START", "RESUME" -> "RUNNING"; "PAUSE" -> "PAUSED"; "STOP", "EMERGENCY_STOP" -> "STOPPED"; else -> "" }) }
    val bridgeToken = viewModel.app.secretsManager.getMT5BridgeAppToken()
    val bridgeTerminalFresh = bridgeSnapshot?.let { it.connected && it.terminalOnline && it.heartbeatFresh && it.terminalConnected } == true
    val bridgeTerminalReady = bridgeSnapshot?.let { bridgeTerminalFresh && it.managedState == "READY" && it.accessType == "MASTER" && it.tradeAllowed && it.accountVerified && it.account.isNotBlank() } == true
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
    var mt5LoginInput by remember { mutableStateOf(viewModel.getMT5Login()) }
    var mt5PasswordInput by remember { mutableStateOf("") }
    var mt5ServerInput by remember { mutableStateOf(viewModel.getMT5BrokerServer()) }
    var mt5AccessType by remember { mutableStateOf("MASTER") }
    var managedCredentialConsent by remember { mutableStateOf(false) }
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

    LaunchedEffect(bridgeMode, bridgeToken) {
        if (bridgeMode && bridgeClient.baseUrl().isNotBlank() && bridgeToken.isNotBlank()) {
            while (true) {
                try {
                    val fresh = bridgeClient.state()
                    bridgeSnapshot = fresh
                    val cmd = fresh.raw.optJSONObject("last_command")
                    if (bridgeCommandPending && cmd != null && cmd.optString("request_id") == bridgePendingId &&
                        cmd.optString("action") == bridgePendingAction && cmd.optString("status") == "ACKNOWLEDGED") {
                        when (cmd.optString("outcome")) {
                            "REJECTED" -> { val action = bridgePendingAction; bridgeSecrets.clearMT5BridgePendingCommand(); bridgeCommandPending = false; bridgePendingId = ""; bridgePendingAction = ""; bridgeMessage = "$action rejected by EA: ${cmd.optString("message", "No reason supplied")}" }
                            "APPLIED" -> if (fresh.heartbeatFresh) {
                                val action = bridgePendingAction
                                val expected = bridgeExpectedState
                                bridgeSecrets.clearMT5BridgePendingCommand(); bridgeCommandPending = false; bridgePendingId = ""; bridgePendingAction = ""
                                bridgeMessage = if (fresh.state == expected && cmd.optString("state") == expected) "$action applied; fresh EA heartbeat confirms ${fresh.state}." else "$action acknowledged as applied, but fresh EA heartbeat reports actual state ${fresh.state}; target state is not confirmed."
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("REMOTE MT5 EA BRIDGE", modifier = Modifier.weight(1f), color = ElectricCyan, fontWeight = FontWeight.Bold)
                        Switch(checked = bridgeMode, enabled = bridgeToken.isBlank() && !bridgeBusy, onCheckedChange = { bridgeMode = it })
                    }
                    Text("Bridge: ${if (bridgeSnapshot?.connected == true) "CONNECTED" else "DISCONNECTED"} · Terminal: ${bridgeSnapshot?.let { if (!it.terminalOnline) "OFFLINE" else if (!it.heartbeatFresh) "STALE HEARTBEAT" else "ONLINE" } ?: "UNVERIFIED"} · EA: ${bridgeSnapshot?.state ?: "UNKNOWN"}", color = TextPrimary, fontSize = 11.sp)
                    Text("Verified account: ${bridgeSnapshot?.account?.ifBlank { "not verified" } ?: "not verified"} · Trade permission: ${if (bridgeSnapshot?.tradeAllowed == true) "YES" else "NO"}", color = TextSecondary, fontSize = 10.sp)
                    Text("Managed service: ${if (bridgeClient.managedConfigured()) "configured" else "CONFIGURATION_REQUIRED"}", color = TextSecondary, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {

                        Button(enabled = !bridgeBusy && viewModel.app.secretsManager.getMT5BridgeAppToken().isNotBlank(), onClick = { coroutineScope.launch { bridgeBusy = true; try { bridgeClient.revoke(); bridgeSecrets.clearMT5BridgePendingCommand(); bridgeCommandPending = false; bridgePendingId = ""; bridgePendingAction = ""; bridgeExpectedState = ""; bridgeSnapshot = null; bridgeMode = false; bridgeCode = ""; bridgeMessage = "Terminal pairing revoked" } catch (_: Exception) { bridgeMessage = "Revoke not confirmed" }; bridgeBusy = false } }) { Text("Revoke") }
                    }
                    if (bridgeMessage.isNotBlank()) Text(bridgeMessage, color = WarningAmber, fontSize = 10.sp)
                    val provisioningError = bridgeSnapshot?.raw?.optJSONObject("managed_execution")?.optString("error_message").orEmpty()
                    if (provisioningError.isNotBlank()) Text("Provisioning error: $provisioningError", color = LossRed, fontSize = 10.sp)
                    val bridgeFresh = bridgeSnapshot?.let { it.connected && it.terminalOnline && it.heartbeatFresh && it.terminalConnected } == true
                    val bridgeReady = bridgeSnapshot?.let { MT5RemoteBridgeClient.canStart(it.managedState, it.accessType, bridgeFresh, it.terminalConnected, it.tradeAllowed, it.accountVerified) && it.account.isNotBlank() } == true
                    val supportedStrategy = selectedStrategyId == "ict-silver-bullet"
                    val validBridgeRisk = MT5RemoteBridgeClient.isValidRiskPercent(riskPercent)
                    Text("EA SUPPORTED SUBSET: ICT Silver Bullet only. Other selected strategies cannot be started by this EA.", color = WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    BridgeTelemetry(bridgeSnapshot)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("PAUSE", "RESUME", "STOP", "EMERGENCY_STOP").forEach { action ->
                            val needsTradingReady = action == "PAUSE" || action == "RESUME"
                            OutlinedButton(enabled = bridgeMode && (if (needsTradingReady) bridgeReady else bridgeSnapshot?.connected == true) && !bridgeBusy && !bridgeCommandPending, onClick = {
                                coroutineScope.launch { sendBridgeCommand(bridgeClient, action, JSONObject(), bridgeSecrets, { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgeExpectedState = it }, { bridgeMessage = it }) }
                            }) { Text(action.replace('_', ' '), fontSize = 9.sp) }
                        }
                    }
                    Text("POSITION SAFETY: STOP, PAUSE and EMERGENCY STOP block new entries only. They do not close existing positions or move broker SL/TP; manage open positions directly on the MT5 terminal.", color = WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (!viewModel.app.secretsManager.isSecureStorageAvailable) Text("Secure storage unavailable; pairing and app-token persistence are disabled.", color = LossRed, fontSize = 10.sp)
                    if (bridgeMode && !bridgeReady) Text("SETUP REQUIRED — START needs a verified account, connected/trading-ready terminal and fresh heartbeat.", color = LossRed, fontSize = 10.sp)
                    if (bridgeMode && !supportedStrategy) Text("START BLOCKED — select ict-silver-bullet; this EA does not support the other app strategies.", color = LossRed, fontSize = 10.sp)
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
            val isRunning = if (bridgeMode) bridgeDisplayState in setOf("RUNNING", "PAUSED") else botSummary.isRunning || botActive
            val bridgeFresh = bridgeTerminalFresh
            val bridgeReady = bridgeTerminalReady
            val bridgeCanStart = bridgeReady && (bridgeSnapshot?.state == "READY" || bridgeSnapshot?.state == "STOPPED")
            val supportedStrategy = selectedStrategyId == "ict-silver-bullet"
            val validBridgeRisk = MT5RemoteBridgeClient.isValidRiskPercent(riskPercent)
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
                            enabled = !bridgeMode || (!bridgeBusy && !bridgeCommandPending && (if (isRunning) bridgeFresh else bridgeCanStart && supportedStrategy && validBridgeRisk)),
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
                                    if (active && !supportedStrategy) { bridgeMessage = "Blocked: the EA supports ict-silver-bullet only."; bridgeBusy = false; return@launch }
                                    if (active && (!MT5RemoteBridgeClient.isValidRiskPercent(riskPercent))) { bridgeMessage = "Blocked: risk must be finite and in (0, 1]%; no clamping applied."; bridgeBusy = false; return@launch }
                                    if (active && !bridgeCanStart) { bridgeMessage = "Blocked: verified account must be READY or STOPPED with a fresh, trade-authorized heartbeat."; bridgeBusy = false; return@launch }
                                    sendBridgeCommand(bridgeClient, action, config, bridgeSecrets, { bridgeBusy = it }, { bridgeCommandPending = it }, { bridgePendingId = it }, { bridgePendingAction = it }, { bridgeExpectedState = it }, { bridgeMessage = it })
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
            val capital by viewModel.accountCapital.collectAsStateWithLifecycle()
            val dynamicAnalysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
            val ticker = viewModel.tickers.collectAsStateWithLifecycle().value[activeSymbol]
            val cmp = ticker?.price ?: dynamicAnalysis?.entry ?: 0.0
            val isBullish = (dynamicAnalysis?.direction ?: "BUY").contains("BUY", true) || (dynamicAnalysis?.direction ?: "").contains("LONG", true)
            val effectiveEntry = if (dynamicAnalysis != null && dynamicAnalysis!!.entry > 0.0) dynamicAnalysis!!.entry else cmp
            val effectiveSl = if (dynamicAnalysis != null && dynamicAnalysis!!.stopLoss > 0.0) dynamicAnalysis!!.stopLoss else 0.0
            val effectiveTp1 = if (dynamicAnalysis != null && dynamicAnalysis!!.tp1 > 0.0) dynamicAnalysis!!.tp1 else 0.0
            val effectiveTp2 = dynamicAnalysis?.tp2 ?: 0.0
            val effectiveTp3 = dynamicAnalysis?.tp3 ?: 0.0

            var reqMarginState by remember(activeSymbol, cmp) { mutableStateOf(0.0) }
            LaunchedEffect(activeSymbol, cmp) {
                val res = activeBrokerGateway.calculateRequiredMargin(activeSymbol, 0.01)
                if (res.isSuccess && (res.getOrNull() ?: 0.0) > 0.0) {
                    reqMarginState = res.getOrNull()!!
                } else {
                    val spec = com.destinedfx.core.instrument.SpecRegistry.get(activeSymbol)
                    val contractSize = spec?.contractSize ?: 100000.0
                    reqMarginState = if (cmp > 0.0) (0.01 * contractSize * cmp) / 100.0 else 20.0
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
                                text = "Select pair to trade • Bot audits balance & margin automatically",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ProfitGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "LIVE AUDIT",
                                fontSize = 8.sp,
                                color = ProfitGreen,
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

                    val freeMarginVal = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null)
                        accountMetrics!!.freeMargin else capital

                    val sizedResult = remember(activeSymbol, freeMarginVal, riskPercent, effectiveEntry, effectiveSl) {
                        viewModel.sizeFor(activeSymbol, freeMarginVal, riskPercent, effectiveEntry, effectiveSl)
                    }
                    val lotDisplay = when (sizedResult) {
                        is com.destinedfx.core.risk.SizingResult.Sized -> "%.2f Lots".format(sizedResult.lots)
                        is com.destinedfx.core.risk.SizingResult.Rejected -> "0.01 Lots"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StatChip(
                            label = "BALANCE",
                            value = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null)
                                "$${String.format(java.util.Locale.US, "%.2f", accountMetrics!!.balance)}"
                            else if (capital > 0.0) "$${String.format(java.util.Locale.US, "%.2f", capital)}"
                            else "OFFLINE",
                            modifier = Modifier.weight(1f),
                            color = TextPrimary
                        )
                        StatChip(
                            label = "EQUITY",
                            value = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null)
                                "$${String.format(java.util.Locale.US, "%.2f", accountMetrics!!.equity)}"
                            else if (capital > 0.0) "$${String.format(java.util.Locale.US, "%.2f", capital)}"
                            else "OFFLINE",
                            modifier = Modifier.weight(1f),
                            color = TextPrimary
                        )
                        StatChip(
                            label = "LEVERAGE",
                            value = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null)
                                "1:${accountMetrics!!.leverage}"
                            else "1:500",
                            modifier = Modifier.weight(1f),
                            color = CyanPrimary
                        )
                        StatChip(
                            label = "AUTO LOT",
                            value = lotDisplay,
                            modifier = Modifier.weight(1f),
                            color = ElectricCyan
                        )
                    }

                    val isMarginSafe = freeMarginVal >= reqMarginState && reqMarginState > 0.0

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isMarginSafe || freeMarginVal > 0.0) ProfitGreen.copy(alpha = 0.12f) else LossRed.copy(alpha = 0.12f))
                            .border(1.dp, if (isMarginSafe || freeMarginVal > 0.0) ProfitGreen.copy(alpha = 0.4f) else LossRed.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (brokerConnStatus != BrokerConnectionStatus.CONNECTED && capital <= 0.0)
                                    "DISCONNECTED • CONNECT BROKER TO VERIFY MARGIN"
                                else if (isMarginSafe) "MARGIN CHECK PASSED • SAFE TO EXECUTE" else "LOW FREE MARGIN WARNING",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = if (isMarginSafe || freeMarginVal > 0.0) ProfitGreen else LossRed
                            )
                            Text(
                                text = if (brokerConnStatus != BrokerConnectionStatus.CONNECTED && capital <= 0.0)
                                    "Connect your MetaTrader 5 demo/real account below to execute live trades."
                                else if (isMarginSafe)
                                    "Free margin ($${String.format(java.util.Locale.US, "%.2f", freeMarginVal)}) is sufficient to cover required margin ($${String.format(java.util.Locale.US, "%.2f", reqMarginState)}) for $activeSymbol."
                                else "Free margin ($${String.format(java.util.Locale.US, "%.2f", freeMarginVal)}) is low. Reduce lot size or deposit funds.",
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
            var savePasswordChecked by remember { mutableStateOf(viewModel.app.secretsManager.isMT5PasswordSaveEnabled()) }
            var showOpenDemoDialog by remember { mutableStateOf(false) }
            var showOpenRealDialog by remember { mutableStateOf(false) }

            var selectedAccountMode by remember { mutableStateOf(viewModel.app.secretsManager.getMT5AccountType().ifBlank { "Demo" }) }
            val storedBal = remember {
                viewModel.app.secretsManager.getMT5AccountBalance().takeIf { it > 0.0 } ?: 0.0
            }
            var mt5BalanceInput by remember { mutableStateOf(String.format(java.util.Locale.US, "%.2f", storedBal)) }
            var selectedLeverage by remember { mutableStateOf(viewModel.app.secretsManager.getMT5Leverage().takeIf { it > 0 } ?: 500) }
            var expandedLeverage by remember { mutableStateOf(false) }
            val currentSessionStatus = viewModel.app.secretsManager.getMT5SessionStatus()
            val hasSavedLogin = viewModel.getMT5Login().isNotBlank() && viewModel.getMT5BrokerServer().isNotBlank()
            val isSessionExpired = currentSessionStatus == "SESSION_EXPIRED"
            var isEditingCredentials by remember { mutableStateOf(!hasSavedLogin) }

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
                                text = "METAAPI BACKEND",
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
                                        Text("SESSION EXPIRED", fontSize = 11.sp, fontWeight = FontWeight.Black, color = LossRed)
                                        Text("Authorization expired for account ${viewModel.getMT5Login()}. Tap Reconnect to refresh.", fontSize = 9.sp, color = TextPrimary)
                                    }
                                }
                                Button(
                                    onClick = {
                                        isConnectingBroker = true
                                        brokerConnectionMessage = null
                                        viewModel.reconnectMT5Session { success, msg ->
                                            isConnectingBroker = false
                                            brokerConnectionMessage = msg
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = LossRed, contentColor = Color.White),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("RECONNECT", fontSize = 10.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }

                    // Active MT5 Account Overview (Official MT5 Account Card)
                    if (brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null && !isEditingCredentials) {
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
                                                .background(if (selectedAccountMode.equals("Real", true)) ProfitGreen.copy(alpha = 0.2f) else ElectricCyan.copy(alpha = 0.2f))
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
                                onClick = { brokerConnectionMessage = "Demo accounts must be opened directly with a broker; this app cannot create one." },
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
                                onClick = { brokerConnectionMessage = "Real accounts must be opened directly with a broker." },
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
                                onClick = { selectedAccountMode = "Demo" },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selectedAccountMode.equals("Demo", true)) ElectricCyan else DarkSurfaceVariant,
                                    contentColor = if (selectedAccountMode.equals("Demo", true)) DarkBackground else TextSecondary
                                ),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f).height(38.dp)
                            ) {
                                Text("DEMO ACCOUNT", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { selectedAccountMode = "Real" },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selectedAccountMode.equals("Real", true)) ProfitGreen else DarkSurfaceVariant,
                                    contentColor = if (selectedAccountMode.equals("Real", true)) DarkBackground else TextSecondary
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
                                isConnectingBroker = true
                                brokerConnectionMessage = null
                                coroutineScope.launch {
                                    val secret = mt5PasswordInput
                                    try {
                                        if (!bridgeClient.managedConfigured()) {
                                            brokerConnectionMessage = "CONFIGURATION_REQUIRED"
                                        } else {
                                            val snapshot = bridgeClient.registerManagedAccount(mt5LoginInput, secret, mt5ServerInput, mt5AccessType, managedCredentialConsent)
                                            bridgeMode = true
                                            bridgeSnapshot = snapshot
                                            bridgeMessage = snapshot.managedState.ifBlank { "PROVISIONING" }
                                            brokerConnectionMessage = "${snapshot.managedState.ifBlank { "PROVISIONING" }} — broker connection is not confirmed until a fresh authenticated EA heartbeat."
                                            viewModel.app.secretsManager.saveMT5Login(mt5LoginInput.trim())
                                            viewModel.app.secretsManager.saveMT5BrokerServer(mt5ServerInput)
                                            viewModel.app.secretsManager.saveMT5PasswordIfAllowed(secret, savePasswordChecked)
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
                                    selectedAccountMode = "Real"
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

                    // 1-Tap Dual Action Buttons (SELL BID / BUY ASK)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // SELL BUTTON
                        Button(
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
                                    instantExecutionStatus = "✅ Deal executed: SELL ${String.format(java.util.Locale.US, "%.2f", instantLotSize)} $activeSymbol @ ${formatPrice(bidPrice, activeSymbol)} | SL: ${formatPrice(sellSl, activeSymbol)} | TP: ${formatPrice(sellTp, activeSymbol)}"
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
                                    instantExecutionStatus = "✅ Deal executed: BUY ${String.format(java.util.Locale.US, "%.2f", instantLotSize)} $activeSymbol @ ${formatPrice(askPrice, activeSymbol)} | SL: ${formatPrice(buySl, activeSymbol)} | TP: ${formatPrice(buyTp, activeSymbol)}"
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

                    // Full-Auto Smart Instant Execution Button (Zero Manual Inputs)
                    Button(
                        onClick = {
                            if (!isConnected) {
                                instantExecutionStatus = "⚠️ MT5 Terminal is offline. Connect your broker above before executing orders."
                            } else if (askPrice <= 0.0 || bidPrice <= 0.0) {
                                instantExecutionStatus = "⚠️ Waiting for live streaming quotes on $activeSymbol..."
                            } else {
                                val bal = accountMetrics?.balance ?: 0.0
                                val eq = accountMetrics?.equity ?: bal
                                val lev = accountMetrics?.leverage ?: 500
                                val szRes = viewModel.sizeFor(activeSymbol, bal, riskPercent, askPrice, askPrice - 0.0030)
                                val autoLots = when (szRes) {
                                    is com.destinedfx.core.risk.SizingResult.Sized -> szRes.lots
                                    else -> 0.02
                                }
                                val isBuy = askPrice >= bidPrice
                                val execPrice = if (isBuy) askPrice else bidPrice
                                val sl = if (isBuy) execPrice - 0.0030 else execPrice + 0.0030
                                val tp = if (isBuy) execPrice + 0.0075 else execPrice - 0.0075
                                val dirStr = if (isBuy) "BUY" else "SELL"

                                viewModel.executeTrade(
                                    symbol = activeSymbol,
                                    positionType = dirStr,
                                    entryPrice = execPrice,
                                    stopLoss = sl,
                                    takeProfit = tp,
                                    rationale = "Full-Auto Smart Execution (Balance: $${String.format(java.util.Locale.US, "%.2f", bal)}, Equity: $${String.format(java.util.Locale.US, "%.2f", eq)}, Leverage: 1:$lev)",
                                    lotSize = autoLots
                                )
                                instantExecutionStatus = "⚡ Full-Auto Executed: $dirStr ${String.format(java.util.Locale.US, "%.2f", autoLots)} lots $activeSymbol @ ${formatPrice(execPrice, activeSymbol)} (Bal: $${String.format(java.util.Locale.US, "%.2f", bal)}, Lev: 1:$lev)"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("full_auto_execute_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = DarkBackground)
                            Text(
                                text = "⚡ SMART FULL-AUTO EXECUTE (AUTO BALANCE & LEVERAGE)",
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
                val cmp = ticker?.price ?: pos.entryPrice
                val isLong = pos.direction == com.example.domain.model.SignalDirection.LONG
                val mult = if (isLong) 1.0 else -1.0
                val spec = com.destinedfx.core.instrument.SpecRegistry.get(pos.symbol)
                val contractSize = spec?.contractSize ?: 100_000.0
                val liveFloatingPnl = if (pos.entryPrice > 0.0 && cmp > 0.0) {
                    (cmp - pos.entryPrice) * mult * contractSize * pos.lotSize
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
                                text = "Entry: ${formatPrice(pos.entryPrice, pos.symbol)} → CMP: ${formatPrice(cmp, pos.symbol)}",
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

                        Button(
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
    val positions = snapshot?.positions.orEmpty()
    val floatingValues = positions.mapNotNull { p -> if (p.has("floating_profit") && !p.isNull("floating_profit")) p.optDouble("floating_profit").takeIf { it.isFinite() } else null }
    val floatingTotal = when { snapshot == null -> unavailable; positions.isEmpty() -> "0.0"; floatingValues.size == positions.size -> floatingValues.sum().toString(); else -> unavailable }
    Text("Floating P/L: $floatingTotal ${snapshot?.currency.orEmpty()}", color = TextPrimary, fontSize = 11.sp)
    Text("LIVE POSITIONS (${positions.size})", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    if (positions.isEmpty()) Text(unavailable, color = TextSecondary, fontSize = 10.sp)
    positions.forEach { p -> Text("${p.optString("symbol", unavailable)} ${p.optString("direction", unavailable)} · volume ${p.optString("volume", unavailable)} · SL ${p.optString("stop_loss", unavailable)} · TP ${p.optString("take_profit", unavailable)}", color = TextPrimary, fontSize = 10.sp) }
    val deals = snapshot?.closedDeals.orEmpty()
    val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    val recent = deals.filter { deal ->
        val raw = deal.optString("closed_at", "")
        val millis = runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
        millis != null && millis >= cutoff
    }
    val net = recent.sumOf { it.optDouble("profit", 0.0) + it.optDouble("commission", 0.0) + it.optDouble("swap", 0.0) }
    Text("REALIZED DEAL NET (last 7 days / available deals): ${if (deals.isEmpty()) unavailable else net.toString()} ${snapshot?.currency.orEmpty()}", color = TextPrimary, fontSize = 10.sp)
    Text("Last signal: ${snapshot?.lastSignal?.toString() ?: unavailable}", color = TextSecondary, fontSize = 10.sp)
    snapshot?.raw?.optString("last_error")?.takeIf { it.isNotBlank() }?.let { Text("EA ERROR: $it", color = LossRed, fontSize = 10.sp) }
}

private suspend fun sendBridgeCommand(
    client: MT5RemoteBridgeClient, action: String, config: JSONObject, secrets: com.example.security.SecretsManager,
    busy: (Boolean) -> Unit, pending: (Boolean) -> Unit, requestId: (String) -> Unit,
    pendingAction: (String) -> Unit, expected: (String) -> Unit, message: (String) -> Unit
) {
    if (secrets.getMT5BridgePendingCommandId().isNotBlank()) {
        pending(true); requestId(secrets.getMT5BridgePendingCommandId()); pendingAction(secrets.getMT5BridgePendingCommandAction())
        message("A bridge command is already unresolved. Reconcile its saved request ID or revoke pairing; no new command was sent.")
        busy(false)
        return
    }
    val id = java.util.UUID.randomUUID().toString()
    if (!secrets.saveMT5BridgePendingCommand(id, action)) {
        message("Secure pending-command storage failed; no command was sent.")
        busy(false)
        return
    }
    requestId(id); pendingAction(action)
    expected(when (action) { "START", "RESUME" -> "RUNNING"; "PAUSE" -> "PAUSED"; else -> "STOPPED" })
    pending(true)
    busy(true)
    val result = client.command(id, action, config)
    message(if (result.queued) "$action queued (request ${result.requestId}); awaiting matching acknowledgement and fresh heartbeat; not confirmed." else "UNKNOWN request ${result.requestId}: ${result.detail}. Do not resend; reconcile this ID or recover manually.")
    busy(false)
}
