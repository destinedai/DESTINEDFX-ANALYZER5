package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.broker.BrokerType
import com.example.data.ctrader.CTraderAccountOption
import com.example.data.ctrader.CTraderVerifiedAccount
import com.example.data.deriv.DerivConnectionFailureMapper
import com.example.data.deriv.DerivDemoVerificationResult
import com.example.data.deriv.verifyFreshDerivPublicTick
import com.example.data.deriv.derivDemoVerificationLabel
import com.example.data.trade.MT5RemoteBridgeClient
import com.example.service.DerivDemoBotService
import com.example.security.AppSecurityGuard
import com.example.ui.theme.CardBorder
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LossRed
import com.example.ui.theme.ProfitGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

sealed interface BotUiState {
    val label: String
    data object NOT_CONFIGURED : BotUiState { override val label = "NOT CONFIGURED" }
    data object CONNECTING : BotUiState { override val label = "CONNECTING" }
    data object PAIRING_REQUIRED : BotUiState { override val label = "PAIRING REQUIRED" }
    data object CONNECTED : BotUiState { override val label = "CONNECTED" }
    data object TERMINAL_OFFLINE : BotUiState { override val label = "TERMINAL OFFLINE" }
    data object EA_OFFLINE : BotUiState { override val label = "EA OFFLINE" }
    data object READY : BotUiState { override val label = "READY" }
    data object RUNNING : BotUiState { override val label = "RUNNING" }
    data object PAUSED : BotUiState { override val label = "PAUSED" }
    data object STOPPED : BotUiState { override val label = "STOPPED" }
    data object ERROR : BotUiState { override val label = "ERROR" }
    data object BLOCKED : BotUiState { override val label = "BLOCKED" }
}

fun deriveBotUiState(
    busy: Boolean,
    connected: Boolean,
    pairingRequired: Boolean,
    terminalOnline: Boolean?,
    heartbeatFresh: Boolean?,
    terminalState: String?,
    blocked: Boolean,
    error: Boolean = false,
): BotUiState = when {
    blocked -> BotUiState.BLOCKED
    error -> BotUiState.ERROR
    busy && !connected -> BotUiState.CONNECTING
    pairingRequired -> BotUiState.PAIRING_REQUIRED
    terminalOnline == false -> BotUiState.TERMINAL_OFFLINE
    heartbeatFresh == false -> BotUiState.EA_OFFLINE
    terminalState == "PAUSED" -> BotUiState.PAUSED
    terminalState == "STOPPED" -> BotUiState.STOPPED
    terminalState == "RUNNING" -> BotUiState.RUNNING
    connected && terminalState == "READY" -> BotUiState.READY
    connected -> BotUiState.CONNECTED
    else -> BotUiState.NOT_CONFIGURED
}

fun isVerifiedMt5Connection(
    snapshotConnected: Boolean,
    workerReady: Boolean,
    terminalOnline: Boolean,
    heartbeatFresh: Boolean,
    accountVerified: Boolean,
    brokerIdentityVerified: Boolean,
    actualMode: String,
    expectedMode: String,
    actualLogin: String,
    expectedLogin: String,
    actualServer: String,
    expectedServer: String,
): Boolean = snapshotConnected && workerReady && terminalOnline && heartbeatFresh && accountVerified &&
    brokerIdentityVerified && actualMode == expectedMode && actualLogin.isNotBlank() && actualLogin == expectedLogin &&
    actualServer.isNotBlank() && actualServer == expectedServer

fun isValidDemoPassEvidence(
    expectedBroker: String,
    expectedLogin: String,
    expectedServer: String,
    broker: String,
    mode: String,
    status: String,
    login: String,
    server: String,
    soakDays: Int,
    orderLifecyclePass: Boolean,
    dailyReconciliationPass: Boolean,
    restartTestPass: Boolean,
    riskControlsPass: Boolean,
    evidencePathCount: Int,
): Boolean = broker == expectedBroker && mode == "DEMO" && status == "PASS" &&
    expectedLogin.isNotBlank() && login == expectedLogin && expectedServer.isNotBlank() && server == expectedServer &&
    soakDays in 5..7 && orderLifecyclePass && dailyReconciliationPass && restartTestPass && riskControlsPass && evidencePathCount > 0

fun canSelectMt5Mode(mode: String, broker: String, acceptedDemoPassDigests: Map<String, String>): Boolean =
    mode != "LIVE" || broker in acceptedDemoPassDigests

fun liveLeaseStatusLabel(serverLeaseActive: Boolean): String = if (serverLeaseActive) {
    "LIVE ARMED · SERVER LEASE ACTIVE"
} else {
    "LIVE NOT ARMED · SERVER LEASE INACTIVE"
}

@Composable
fun SimplifiedBotWorkstation(viewModel: DestinedFXViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeBroker by viewModel.activeBrokerType.collectAsStateWithLifecycle()
    val gateway by viewModel.activeBrokerGateway.collectAsStateWithLifecycle()
    val connectionStatus by gateway.connectionStatus.collectAsStateWithLifecycle()
    val accountMetrics by gateway.accountMetrics.collectAsStateWithLifecycle()
    val botSummary by viewModel.botStatusSummary.collectAsStateWithLifecycle()
    val botActive by viewModel.botActive.collectAsStateWithLifecycle()
    val derivAccount by viewModel.app.derivBrokerGateway.accountInfo.collectAsStateWithLifecycle()
    val derivState by viewModel.app.derivBrokerGateway.connectionState.collectAsStateWithLifecycle()
    val derivAccounts by viewModel.app.derivBrokerGateway.availableAccounts.collectAsStateWithLifecycle()
    val derivPortfolio by viewModel.app.derivBrokerGateway.portfolioSnapshot.collectAsStateWithLifecycle()
    val derivBalanceUpdatedAt by viewModel.app.derivBrokerGateway.accountMetricsUpdatedAtMs.collectAsStateWithLifecycle()
    val derivExecutionRecords by viewModel.app.db.derivExecutionDao().observeAll().collectAsStateWithLifecycle(emptyList<com.example.data.deriv.DerivExecutionEntity>())
    var broker by remember(activeBroker) { mutableStateOf(activeBroker) }
    var mt5Mode by remember { mutableStateOf("DEMO") }
    var selectedMt5Broker by remember { mutableStateOf(viewModel.app.secretsManager.getSelectedMT5BrokerBrand()) }
    val mt5Profile = "${selectedMt5Broker}_${mt5Mode}"
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var accessType by remember { mutableStateOf("MASTER") }
    var derivToken by remember { mutableStateOf("") }
    var derivAppId by remember { mutableStateOf("") }
    var derivMode by remember { mutableStateOf("demo") }
    var selectedDerivAccountId by remember { mutableStateOf("") }
    var derivDemoVerification by remember { mutableStateOf<DerivDemoVerificationResult?>(null) }
    var showDerivRealArmDialog by remember { mutableStateOf(false) }
    var savePassword by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var serviceUrl by remember { mutableStateOf("") }
    var cloudflareClientId by remember { mutableStateOf("") }
    var cloudflareClientSecret by remember { mutableStateOf("") }
    var serviceReadiness by remember { mutableStateOf<MT5RemoteBridgeClient.ServiceReadiness?>(null) }
    var showAdvancedConnection by remember { mutableStateOf(false) }
    var mt5Snapshot by remember { mutableStateOf<MT5RemoteBridgeClient.Snapshot?>(null) }
    var showPairingRevokeDialog by remember(mt5Profile) { mutableStateOf(false) }
    var showLiveAuthorizationDialog by remember(mt5Profile) { mutableStateOf(false) }
    var liveAuthorizationExpiry by remember(mt5Profile) { mutableStateOf("") }
    var selectedOpportunity by remember { mutableStateOf<com.example.domain.model.TradeSignal?>(null) }
    var commandPending by remember { mutableStateOf(false) }
    var demoPassDigests by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val secrets = viewModel.app.secretsManager
    val cTraderGateway = viewModel.app.cTraderGateway
    val cTraderCallback by viewModel.app.cTraderCallback.collectAsStateWithLifecycle()
    val cTraderQuote by cTraderGateway.quotes.collectAsStateWithLifecycle()
    var cTraderClientId by remember { mutableStateOf(secrets.getCTraderClientId()) }
    var cTraderClientSecret by remember { mutableStateOf(secrets.getCTraderClientSecret()) }
    var cTraderBroker by remember { mutableStateOf("") }
    var cTraderLive by remember { mutableStateOf(false) }
    var cTraderSymbolId by remember { mutableStateOf(1L) }
    var cTraderAccounts by remember { mutableStateOf<List<CTraderAccountOption>>(emptyList()) }
    var cTraderSelectedAccountId by remember { mutableStateOf<Long?>(null) }
    var cTraderVerification by remember { mutableStateOf<CTraderVerifiedAccount?>(null) }
    var cTraderFailure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cTraderCallback) {
        val callback = cTraderCallback ?: return@LaunchedEffect
        val code = callback.getQueryParameter("code")
        val error = callback.getQueryParameter("error")
        viewModel.app.clearCTraderCallback()
        if (!error.isNullOrBlank()) { cTraderFailure = "cTrader authorization failed: $error"; return@LaunchedEffect }
        if (code.isNullOrBlank()) { cTraderFailure = "cTrader callback did not contain an authorization code."; return@LaunchedEffect }
        busy = true
        viewModel.app.cTraderOAuthClient.exchangeAuthorizationCode(code).onSuccess {
            message = "cTrader authorization saved in encrypted device storage. Load the account list before verification."
        }.onFailure { cTraderFailure = it.message ?: "cTrader authorization exchange failed." }
        busy = false
    }
    LaunchedEffect(secrets) {
        serviceUrl = secrets.getManagedMT5ServiceUrl()
        cloudflareClientId = secrets.getCloudflareAccessClientId()
        cloudflareClientSecret = secrets.getCloudflareAccessClientSecret()
        derivMode = secrets.getDerivAccountMode()
        derivToken = secrets.getDerivApiToken(derivMode)
        derivAppId = secrets.getDerivAppId()
    }
    val bridge = remember(mt5Profile) { if (selectedMt5Broker in setOf("FBS", "EXNESS", "HFM", "DERIV")) MT5RemoteBridgeClient(secrets, mt5Profile, { AppSecurityGuard.authorizeSensitiveAction() }, accountMode = mt5Mode, brokerBrand = selectedMt5Broker) else null }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val demoPassLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            message = "No DEMO evidence file was selected. LIVE remains locked."
        } else {
            val result = runCatching {
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                require(bytes.size in 1..65536) { "Evidence file must be between 1 byte and 64 KiB." }
                val evidence = JSONObject(String(bytes, StandardCharsets.UTF_8))
                val broker = selectedMt5Broker
                val demoLogin = secrets.getManagedMT5AccountLogin("${broker}_DEMO")
                val demoServer = secrets.getManagedMT5AccountServer("${broker}_DEMO")
                val paths = evidence.optJSONArray("evidence_paths")
                val valid = isValidDemoPassEvidence(
                    expectedBroker = broker,
                    expectedLogin = demoLogin,
                    expectedServer = demoServer,
                    broker = evidence.optString("broker"),
                    mode = evidence.optString("mode"),
                    status = evidence.optString("status"),
                    login = evidence.optString("account_login"),
                    server = evidence.optString("server"),
                    soakDays = evidence.optInt("soak_days", -1),
                    orderLifecyclePass = evidence.optBoolean("order_lifecycle_pass", false),
                    dailyReconciliationPass = evidence.optBoolean("daily_reconciliation_pass", false),
                    restartTestPass = evidence.optBoolean("restart_test_pass", false),
                    riskControlsPass = evidence.optBoolean("risk_controls_pass", false),
                    evidencePathCount = paths?.length() ?: 0,
                )
                require(valid) { "DEMO PASS evidence is incomplete or does not match the selected broker's saved DEMO account." }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                digest
            }
            result.onSuccess { digest ->
                demoPassDigests = demoPassDigests + (selectedMt5Broker to digest)
                message = "DEMO PASS file accepted for $selectedMt5Broker. SHA-256 $digest. LIVE still requires exact LIVE identity, short lease, owner approval, and all other gates."
            }.onFailure {
                message = "DEMO PASS file rejected: ${it.message?.take(180) ?: "invalid evidence"}. LIVE remains locked."
            }
        }
    }
    val selectMt5Slot: (String, String) -> Unit = { brand, mode ->
        if (!canSelectMt5Mode(mode, brand, demoPassDigests)) {
            message = "LIVE is locked. Import a matching broker-specific DEMO PASS file after the required soak and evidence gates."
        } else {
        val changed = selectedMt5Broker != brand || mt5Mode != mode
        val previousBridge = bridge
        val applySelection = {
            selectedMt5Broker = brand; mt5Mode = mode
            secrets.saveSelectedMT5BrokerBrand(brand)
            broker = BrokerType.MT5; viewModel.selectBroker(BrokerType.MT5); message = null
        }
        if (changed && broker == BrokerType.MT5 && mt5Mode == "LIVE" && previousBridge?.hasPairing() == true) {
            scope.launch {
                if (busy) { message = "Wait for the current account action before switching slots."; return@launch }
                busy = true
                try { requireNotNull(previousBridge).clearLiveAuthorization(); applySelection() }
                catch (e: Exception) { message = "Slot switch blocked: previous LIVE authorization could not be confirmed revoked. ${e.message.orEmpty()}" }
                finally { busy = false }
            }
        } else applySelection()
        }
    }
    val selectBrokerType: (BrokerType) -> Unit = { type ->
        val previousBridge = bridge
        if (type != broker && broker == BrokerType.MT5 && mt5Mode == "LIVE" && previousBridge?.hasPairing() == true) {
            scope.launch {
                if (busy) { message = "Wait for the current account action before switching brokers."; return@launch }
                busy = true
                try { requireNotNull(previousBridge).clearLiveAuthorization(); broker = type; viewModel.selectBroker(type); message = null }
                catch (e: Exception) { message = "Broker switch blocked: LIVE authorization could not be confirmed revoked. ${e.message.orEmpty()}" }
                finally { busy = false }
            }
        } else { broker = type; viewModel.selectBroker(type); message = null }
    }
    val derivUnresolvedCount = derivAccount?.let { account ->
        derivExecutionRecords.count { it.accountId == account.loginId && it.accountMode.equals(account.mode, true) && it.status in setOf("ORDER_SUBMITTING", "ORDER_PENDING", "EXECUTION_UNKNOWN", "RECONCILIATION_REQUIRED") }
    } ?: 0
    val derivAccountReady = derivAccount?.let { viewModel.app.derivBrokerGateway.isAccountReadyForTrading(it, derivUnresolvedCount) } == true
    val derivLifecycleLabel = when {
        derivState == com.example.data.deriv.DerivConnectionState.AUTH_FAILED -> "AUTH FAILED"
        derivState in setOf(com.example.data.deriv.DerivConnectionState.CONNECTING, com.example.data.deriv.DerivConnectionState.AUTHENTICATING, com.example.data.deriv.DerivConnectionState.ACCOUNT_VERIFICATION, com.example.data.deriv.DerivConnectionState.SYNCHRONIZING, com.example.data.deriv.DerivConnectionState.RECONNECTING) -> "SYNCHRONIZING"
        derivAccountReady -> "READY"
        else -> "NOT READY"
    }

    LaunchedEffect(broker, mt5Profile) {
        if (selectedMt5Broker in setOf("FBS", "EXNESS", "HFM", "DERIV")) {
            login = secrets.getManagedMT5AccountLogin(mt5Profile)
            server = secrets.getManagedMT5AccountServer(mt5Profile)
            accessType = secrets.getManagedMT5AccountAccessType(mt5Profile)
            savePassword = secrets.isManagedMT5AccountPasswordSaveEnabled(mt5Profile)
            password = if (savePassword) secrets.getManagedMT5AccountPassword(mt5Profile) else ""
        }
        if (broker == BrokerType.MT5 && bridge?.managedConfigured() == true && bridge.hasPairing()) {
            mt5Snapshot = null
            while (true) {
                mt5Snapshot = runCatching { bridge.state() }.getOrNull()
                delay(5_000L)
            }
        } else {
            mt5Snapshot = null
        }
    }

    val connected = when (broker) {
        BrokerType.MT5 -> mt5Snapshot?.let {
            isVerifiedMt5Connection(
                snapshotConnected = it.connected,
                workerReady = it.workerStatus == "READY",
                terminalOnline = it.terminalOnline,
                heartbeatFresh = it.heartbeatFresh,
                accountVerified = it.accountVerified,
                brokerIdentityVerified = it.brokerIdentityVerified,
                actualMode = it.actualAccountMode,
                expectedMode = mt5Mode,
                actualLogin = it.actualLogin,
                expectedLogin = secrets.getManagedMT5AccountLogin(mt5Profile),
                actualServer = it.actualServer,
                expectedServer = secrets.getManagedMT5AccountServer(mt5Profile),
            )
        } == true
        BrokerType.DERIV -> derivAccountReady &&
            derivAccount?.let { it.mode.equals(derivMode, true) && it.accountType.equals(derivMode, true) &&
                (selectedDerivAccountId.isBlank() || selectedDerivAccountId == it.loginId) } == true
        else -> connectionStatus == com.example.data.broker.BrokerConnectionStatus.CONNECTED
    }
    val running = when (broker) {
        BrokerType.MT5 -> mt5Snapshot?.state in setOf("RUNNING", "PAUSED")
        BrokerType.DERIV -> botActive || DerivDemoBotService.state.value == "RUNNING"
        else -> botActive
    }
    val botUiState = deriveBotUiState(
        busy = busy,
        connected = connected,
        pairingRequired = broker == BrokerType.MT5 && !bridge?.hasPairing().orFalse(),
        terminalOnline = if (broker == BrokerType.MT5) mt5Snapshot?.terminalOnline else null,
        heartbeatFresh = if (broker == BrokerType.MT5) mt5Snapshot?.heartbeatFresh else null,
        terminalState = if (broker == BrokerType.MT5) mt5Snapshot?.state else if (running) "RUNNING" else null,
        blocked = broker == BrokerType.MT5 && mt5Mode == "LIVE" && mt5Snapshot?.liveAuthorized != true,
        error = message?.contains("failed", ignoreCase = true) == true,
    )

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DESTINEDFX BOT", color = Color(0xFFE5B85C), fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("Connect an account, then let the engine scan eligible markets automatically.", color = TextSecondary, fontSize = 11.sp)
        val setupStep = when {
            !connected && login.isBlank() -> 1
            !connected -> 2
            !running -> 3
            else -> 4
        }
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CardBorder)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CTRADER ACCOUNT · READ ONLY", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                LoginField("Client ID", cTraderClientId, { cTraderClientId = it }, KeyboardType.Number)
                LoginField("Client Secret", cTraderClientSecret, { cTraderClientSecret = it }, KeyboardType.Password, true)
                Text("Redirect URI: destinedfx://ctrader-callback", color = TextSecondary, fontSize = 10.sp)
                Text("The official cTrader documentation lists only client_id, redirect_uri, scope and product for authorization. A state parameter is not documented, so this release does not claim state protection; the exact redirect URI and one-minute code expiry remain enforced.", color = WarningAmber, fontSize = 9.sp, lineHeight = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !cTraderLive, onClick = { cTraderLive = false }, label = { Text("DEMO") })
                    FilterChip(selected = cTraderLive, enabled = false, onClick = {}, label = { Text("LIVE LOCKED") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        if (!secrets.saveCTraderApplication(cTraderClientId.trim(), cTraderClientSecret, "destinedfx://ctrader-callback")) { cTraderFailure = "Secure storage unavailable or application details are incomplete." }
                        else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, viewModel.app.cTraderOAuthClient.authorizationUri(com.example.data.ctrader.CTraderOAuthClient.VIEW_SCOPE))) }.onFailure { cTraderFailure = it.message ?: "Could not open cTrader authorization." }
                    }, enabled = !busy && cTraderClientId.isNotBlank() && cTraderClientSecret.isNotBlank(), modifier = Modifier.weight(1f)) { Text("GRANT ACCESS") }
                    OutlinedButton(onClick = {
                        scope.launch { busy = true; cTraderFailure = null; cTraderAccounts = cTraderGateway.listAccounts(cTraderLive).getOrElse { cTraderFailure = it.message ?: "Account list failed."; emptyList() }; busy = false }
                    }, enabled = !busy && secrets.getCTraderAccessToken().isNotBlank(), modifier = Modifier.weight(1f)) { Text("LOAD ACCOUNTS") }
                }
                cTraderAccounts.filter { it.isLive == cTraderLive }.forEach { option ->
                    FilterChip(selected = cTraderSelectedAccountId == option.accountId, onClick = { cTraderSelectedAccountId = option.accountId; cTraderBroker = option.brokerName }, label = { Text("${option.brokerName.ifBlank { "broker unverified" }} · ${maskAccountId(option.traderLogin.toString())} · ${if (option.isLive) "LIVE" else "DEMO"}", fontSize = 9.sp) })
                }
                LoginField("Broker name exactly as cTrader reports", cTraderBroker, { cTraderBroker = it }, KeyboardType.Text)
                OutlinedTextField(value = cTraderSymbolId.toString(), onValueChange = { cTraderSymbolId = it.filter(Char::isDigit).toLongOrNull() ?: 0L }, label = { Text("Read-only quote symbol ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Button(onClick = {
                    scope.launch {
                        busy = true; cTraderFailure = null; cTraderVerification = null
                        val result = cTraderGateway.verify(cTraderBroker.trim(), cTraderLive, cTraderSelectedAccountId)
                        result.onSuccess { verified ->
                            cTraderVerification = verified
                            cTraderGateway.subscribeReadOnlySpot(cTraderSymbolId).onFailure { cTraderFailure = it.message ?: "Quote subscription failed." }
                            message = "cTrader account verification completed read-only. No order was sent."
                        }.onFailure { cTraderFailure = it.message ?: "cTrader verification failed." }
                        busy = false
                    }
                }, enabled = !busy && cTraderBroker.isNotBlank() && cTraderSelectedAccountId != null && cTraderSymbolId > 0L, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)) { Text("VERIFY CTRADER, READ ONLY", fontWeight = FontWeight.Black) }
                cTraderVerification?.let { verified ->
                    val quoteReceived = cTraderQuote?.isFresh() == true && cTraderQuote?.accountId == verified.accountId
                    Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), border = BorderStroke(1.dp, if (quoteReceived) ProfitGreen else LossRed)) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("cTrader verification ${if (quoteReceived) "PASSED" else "BLOCKED"}", color = if (quoteReceived) ProfitGreen else LossRed, fontWeight = FontWeight.Black, fontSize = 11.sp)
                            Text("Broker: ${verified.brokerName} · ${if (verified.isLive) "LIVE" else "DEMO"} · Account: ${maskAccountId(verified.accountId.toString())}", color = TextPrimary, fontSize = 10.sp)
                            Text("Balance received: YES · One quote received: ${if (quoteReceived) "YES" else "NO"}", color = TextSecondary, fontSize = 10.sp)
                            Text("Checked: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(java.util.Date(verified.receivedAtMs))}", color = TextSecondary, fontSize = 9.sp)
                            if (!quoteReceived) Text("Reason: a fresh account-matched quote was not received. CONNECTED remains blocked.", color = LossRed, fontSize = 9.sp)
                        }
                    }
                }
                cTraderFailure?.let { Text("Reason: $it", color = LossRed, fontSize = 9.sp) }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CardBorder)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("SETUP · STEP $setupStep OF 4", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text("1 Broker and mode  →  2 Account identity  →  3 Verify fresh EA heartbeat  →  4 DEMO acceptance and soak", color = TextSecondary, fontSize = 10.sp)
                Text("LIVE remains subject to a broker-specific DEMO PASS and a separate short lease. No LIVE order is authorized by this screen.", color = WarningAmber, fontSize = 9.sp)
            }
        }
        if ((broker == BrokerType.MT5 && bridge?.hasPairing() == true) || running) {
            Button(
                onClick = {
                    scope.launch {
                        commandPending = true
                        try {
                            if (broker == BrokerType.MT5) {
                                val result = requireNotNull(bridge).command(UUID.randomUUID().toString(), "EMERGENCY_STOP")
                                message = if (result.queued) "EMERGENCY STOP acknowledged by bridge request; verify fresh EA acknowledgement and stop state. Open positions are not liquidated." else result.detail
                            } else {
                                DerivDemoBotService.stop(context)
                                secrets.invalidateDerivRealAuthorization()
                                viewModel.setBotStatus(false)
                                message = "Emergency stop requested locally. Verify broker state; existing positions are not liquidated."
                            }
                        } catch (e: Exception) { message = e.message?.take(240) ?: "Emergency stop was not confirmed; stop manually in the terminal." }
                        finally { commandPending = false }
                    }
                },
                enabled = !commandPending,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LossRed, contentColor = Color.White)
            ) { Icon(Icons.Default.Stop, contentDescription = null); Text("EMERGENCY STOP · STOP NEW ENTRIES", fontWeight = FontWeight.Black) }
        }
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CardBorder)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("BOT STATE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text(botUiState.label, color = if (botUiState in setOf(BotUiState.READY, BotUiState.RUNNING, BotUiState.CONNECTED)) ProfitGreen else WarningAmber, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }

        OutlinedButton(onClick = { showAdvancedConnection = !showAdvancedConnection }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAdvancedConnection) "HIDE ADVANCED CONNECTION SETTINGS" else "ADVANCED CONNECTION SETTINGS")
        }
        if (showAdvancedConnection) Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CardBorder)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MANAGED HTTPS BRIDGE", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                OutlinedTextField(value = serviceUrl, onValueChange = { serviceUrl = it }, label = { Text("Owner-managed HTTPS origin") }, placeholder = { Text("https://your-host.example") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Text("Optional Cloudflare Access service token. Use this only if Cloudflare is the single selected network path and bridge-side JWT validation is enabled. Leave both fields blank for private Tailscale Serve.", color = WarningAmber, fontSize = 9.sp)
                OutlinedTextField(value = cloudflareClientId, onValueChange = { cloudflareClientId = it }, label = { Text("CF Access client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = cloudflareClientSecret, onValueChange = { cloudflareClientSecret = it }, label = { Text("CF Access client secret") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val saved = if (cloudflareClientId.isBlank() && cloudflareClientSecret.isBlank()) secrets.clearCloudflareAccessServiceToken()
                        else secrets.saveCloudflareAccessServiceToken(cloudflareClientId, cloudflareClientSecret)
                        message = if (saved) "Cloudflare Access service token saved in encrypted device storage. It is sent only with bridge requests." else "Cloudflare Access token was not saved. Check that both fields are present and secure storage is available."
                    }, modifier = Modifier.weight(1f)) { Text("SAVE ACCESS TOKEN") }
                    OutlinedButton(onClick = {
                        val cleared = secrets.clearCloudflareAccessServiceToken()
                        if (cleared) { cloudflareClientId = ""; cloudflareClientSecret = "" }
                        message = if (cleared) "Cloudflare Access service token cleared." else "Secure storage unavailable; token was not cleared."
                    }, modifier = Modifier.weight(1f)) { Text("CLEAR TOKEN") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val normalized = MT5RemoteBridgeClient.validServiceOrigin(serviceUrl).orEmpty()
                        if (normalized.isBlank() || !secrets.saveManagedMT5ServiceUrl(normalized)) {
                            message = if (!secrets.isSecureStorageAvailable) "Secure storage unavailable; bridge URL was not saved." else "Enter a valid public HTTPS origin (loopback HTTP is allowed only for local development)."
                        } else { serviceUrl = normalized; serviceReadiness = null; message = "Bridge origin saved securely on this device." }
                    }, modifier = Modifier.weight(1f)) { Text("SAVE URL") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val probe = bridge?.checkServiceReadiness()
                                    ?: MT5RemoteBridgeClient(secrets, "FBS_DEMO", accountMode = "DEMO", brokerBrand = "FBS").checkServiceReadiness()
                                serviceReadiness = probe
                                message = if (probe.serviceStatus == "ready" && probe.database == "ok" && probe.managedExecution == "configured") "Service readiness confirmed; broker/worker readiness is still checked separately." else "Bridge reachable or configured status is incomplete; trading readiness is NOT confirmed."
                            } finally { busy = false }
                        }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("CHECK CONNECTION") }
                }
                Text("Service: ${serviceReadiness?.serviceStatus ?: if (MT5RemoteBridgeClient.validServiceOrigin(serviceUrl).isNullOrBlank()) "CONFIGURATION_REQUIRED" else "not checked"} · HTTP ${serviceReadiness?.httpStatus ?: "-"}", color = WarningAmber, fontSize = 10.sp)
                Text("Durable storage: ${serviceReadiness?.database ?: "not checked"} · Execution config: ${serviceReadiness?.managedExecution ?: "not checked"}", color = TextSecondary, fontSize = 10.sp)
                serviceReadiness?.errorCode?.takeIf { it.isNotBlank() }?.let { Text("Readiness blocker: $it", color = LossRed, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                Text("Worker job: ${mt5Snapshot?.workerStatus ?: "not checked"} · Managed readiness: ${mt5Snapshot?.managedState ?: "not checked"} · Terminal: ${mt5Snapshot?.state ?: "not checked"} · Fresh heartbeat: ${mt5Snapshot?.heartbeatFresh ?: false}", color = TextSecondary, fontSize = 10.sp)
                Text("A successful HTTP probe is not proof of broker authentication or trading readiness. Do not enter credentials until /health/ready confirms durable storage and configured execution.", color = WarningAmber, fontSize = 9.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CardBorder)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("BROKER CONNECTION", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(BrokerType.MT5, BrokerType.DERIV).forEach { type ->
                        FilterChip(selected = broker == type, onClick = { selectBrokerType(type) }, label = { Text(if (type == BrokerType.MT5) "MetaTrader 5" else "Deriv", fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ElectricCyan, selectedLabelColor = DarkBackground, containerColor = DarkSurfaceVariant, labelColor = TextSecondary))
                    }
                }
                if (broker == BrokerType.MT5) {
                    Text("MT5 ACCOUNTS", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { demoPassLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = mt5Mode == "DEMO" && secrets.getManagedMT5AccountLogin("${selectedMt5Broker}_DEMO").isNotBlank() && secrets.getManagedMT5AccountServer("${selectedMt5Broker}_DEMO").isNotBlank()) {
                            Text("IMPORT DEMO PASS FILE")
                        }
                        Text(if (selectedMt5Broker in demoPassDigests) "PASS · ${demoPassDigests[selectedMt5Broker]?.take(12)}" else "LIVE LOCKED", color = if (selectedMt5Broker in demoPassDigests) ProfitGreen else WarningAmber, fontSize = 9.sp)
                    }
                    listOf("FBS", "EXNESS", "HFM", "DERIV").forEach { brand ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(brand, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            listOf("DEMO", "LIVE").forEach { mode ->
                                val slot = "${brand}_${mode}"
                                val selected = selectedMt5Broker == brand && mt5Mode == mode
                                val saved = secrets.getManagedMT5AccountLogin(slot).isNotBlank()
                                val status = if (selected && mt5Snapshot != null) when {
                                    mt5Snapshot?.actualLogin?.isNotBlank() == true && mt5Snapshot?.actualLogin != secrets.getManagedMT5AccountLogin(slot) -> "Mismatch"
                                    mt5Snapshot?.actualServer?.isNotBlank() == true && mt5Snapshot?.actualServer != secrets.getManagedMT5AccountServer(slot) -> "Mismatch"
                                    mt5Snapshot?.managedState in setOf("PROVISIONING", "QUEUED", "STARTING") -> "Provisioning"
                                    mt5Snapshot?.managedState == "AUTHENTICATING" -> "Authenticating"
                                    mt5Snapshot?.terminalOnline != true -> "Reconnect"
                                    mt5Snapshot?.heartbeatFresh != true -> "Stale"
                                    mt5Snapshot?.heartbeatFresh == true && mt5Snapshot?.brokerIdentityVerified != true -> "Identity mismatch"
                                    mt5Snapshot?.state == "OFFLINE" -> "Reconnect"
                                    connected -> "Connected"
                                    else -> "Authenticating"
                                } else if (secrets.getMT5BridgeAccountAppToken(slot).isNotBlank()) "Paired" else if (saved) "Registered" else "Not configured"
                                FilterChip(selected = selected, enabled = canSelectMt5Mode(mode, brand, demoPassDigests), onClick = { selectMt5Slot(brand, mode) }, label = { Text(if (!canSelectMt5Mode(mode, brand, demoPassDigests)) "$mode · DEMO PASS REQUIRED" else "$mode · $status", fontSize = 9.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = if (mode == "LIVE") WarningAmber else ElectricCyan, selectedLabelColor = DarkBackground, containerColor = DarkSurfaceVariant, labelColor = TextSecondary))
                            }
                        }
                    }
                    Text("Broker selection is not identity proof. Use the exact server assigned to this MT5 account; verify terminal-reported company separately.", color = WarningAmber, fontSize = 9.sp)
                    LoginField("Login", login, { login = it.filter(Char::isDigit) }, KeyboardType.Number)
                    LoginField("Password", password, { password = it }, KeyboardType.Password, true)
                    LoginField("Exact broker server", server, { server = it }, KeyboardType.Text)
                    val savedServerSuggestions = listOf("FBS", "EXNESS", "HFM", "DERIV").flatMap { brand ->
                        listOf("DEMO", "LIVE").map { mode -> secrets.getManagedMT5AccountServer("${brand}_${mode}").trim() }
                    }.filter { it.isNotBlank() && !it.equals(server.trim(), ignoreCase = true) }.distinct().take(6)
                    if (savedServerSuggestions.isNotEmpty()) {
                        Text("Previously saved server suggestions only. Confirm the exact server in the broker-issued terminal.", color = TextSecondary, fontSize = 9.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            savedServerSuggestions.forEach { suggestion ->
                                FilterChip(selected = false, onClick = { server = suggestion }, label = { Text(suggestion, fontSize = 9.sp) })
                            }
                        }
                    }
                    Text("Access type", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("MASTER", "INVESTOR").forEach { access ->
                            FilterChip(selected = accessType == access, onClick = { accessType = access }, label = { Text(access, fontSize = 10.sp) })
                        }
                    }
                    if (bridge?.hasPairing() == true) OutlinedButton(onClick = { showPairingRevokeDialog = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("REVOKE THIS ACCOUNT PAIRING") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = savePassword, onCheckedChange = { savePassword = it })
                        Text("Save password in encrypted device storage", color = TextSecondary, fontSize = 10.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = consent, onCheckedChange = { consent = it })
                        Text("I consent to send these credentials to the configured HTTPS bridge", color = TextSecondary, fontSize = 10.sp)
                    }
                    if (mt5Mode == "LIVE") {
                        val snapshot = mt5Snapshot
                        val liveIdentityReady = snapshot?.let {
                            it.actualAccountMode == "LIVE" && it.actualLogin == secrets.getManagedMT5AccountLogin(mt5Profile) &&
                                it.actualServer == secrets.getManagedMT5AccountServer(mt5Profile) && it.accountVerified &&
                                it.workerStatus == "READY" && it.brokerIdentityVerified && it.heartbeatFresh && it.terminalConnected && it.tradeAllowed &&
                                it.managedState == "READY" && it.accessType == "MASTER"
                        } == true
                        val serverLiveExpiry = snapshot?.raw?.optJSONObject("managed_execution")?.optString("live_authorized_until")?.takeIf { it.isNotBlank() }.orEmpty()
                        Text("Selected LIVE account: ${secrets.getManagedMT5AccountLogin(mt5Profile).ifBlank { "not registered" }} @ ${secrets.getManagedMT5AccountServer(mt5Profile).ifBlank { "server not configured" }}", color = TextPrimary, fontSize = 10.sp)
                        Text("Terminal identity: ${snapshot?.actualLogin ?: "unverified"} @ ${snapshot?.actualServer ?: "unverified"} · mode ${snapshot?.actualAccountMode ?: "unverified"}", color = TextSecondary, fontSize = 10.sp)
                        Text("${liveLeaseStatusLabel(snapshot?.liveAuthorized == true)} · fresh terminal heartbeat: ${snapshot?.heartbeatFresh == true} · broker identity: ${snapshot?.brokerIdentityVerified == true} · service state: ${snapshot?.managedState ?: "unknown"}", color = if (snapshot?.liveAuthorized == true) WarningAmber else TextSecondary, fontSize = 10.sp)
                        if (snapshot?.liveAuthorized == true) Text("Authorization expiry: ${serverLiveExpiry.ifBlank { liveAuthorizationExpiry.ifBlank { "server confirms active; expiry not returned" } }}", color = WarningAmber, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = {
                                scope.launch { try { mt5Snapshot = bridge?.state() } catch (e: Exception) { message = e.message ?: "Could not refresh LIVE account state." } }
                            }, enabled = bridge?.managedConfigured() == true && bridge.hasPairing(), modifier = Modifier.weight(1f)) { Text("REFRESH LIVE STATE") }
                            if (snapshot?.liveAuthorized == true) OutlinedButton(onClick = {
                                scope.launch { busy = true; try { bridge?.clearLiveAuthorization(); mt5Snapshot = bridge?.state(); liveAuthorizationExpiry = ""; message = "LIVE authorization revoked; new entries require a new explicit lease." } catch (e: Exception) { message = e.message ?: "LIVE revocation not confirmed." } finally { busy = false } }
                            }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("REVOKE LIVE") }
                            else Button(onClick = { showLiveAuthorizationDialog = true }, enabled = !busy && liveIdentityReady, modifier = Modifier.weight(1f)) { Text("AUTHORIZE LIVE · 15 MIN") }
                        }
                        if (!liveIdentityReady) Text("LIVE authorization is unavailable until this exact registered account is verified by a fresh broker-authenticated terminal heartbeat.", color = WarningAmber, fontSize = 9.sp)
                    }
                    if (selectedMt5Broker == "EXNESS") Text("Connection method: Exness MT5 via its genuine desktop terminal. The separate Exness Trader API route is not implemented in this release; standard MT5 accounts are not eligible for that API. No API keys are requested here.", color = WarningAmber, fontSize = 9.sp)
                } else {
                    Text("DERIV OPTIONS / MULTIPLIERS · separate from Deriv MT5", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("demo", "real").forEach { mode ->
                            FilterChip(selected = derivMode == mode, onClick = {
                                if (secrets.saveDerivAccountMode(mode)) {
                                    viewModel.app.derivBrokerGateway.disconnect()
                                    derivMode = mode
                                    derivToken = secrets.getDerivApiToken(mode)
                                    selectedDerivAccountId = ""
                                    message = "Deriv $mode selected. Existing authorization invalidated; connect and verify this mode explicitly."
                                } else message = "Could not securely select Deriv account mode."
                            }, label = { Text(mode.uppercase()) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = if (mode == "real") WarningAmber else ElectricCyan, selectedLabelColor = DarkBackground, containerColor = DarkSurfaceVariant, labelColor = TextSecondary))
                        }
                    }
                    LoginField("Deriv ${derivMode.uppercase()} API token", derivToken, { derivToken = it }, KeyboardType.Password, true)
                    LoginField("Registered numeric App ID", derivAppId, { derivAppId = it.filter(Char::isDigit) }, KeyboardType.Number)
                    Text("Options account listing and OTP require the PAT trade scope. This verifier does not trade, but a trade-scoped PAT is not inherently read-only; keep it private and enter it only here. Options and Deriv MT5 credentials/readiness are separate. PATs are encrypted and stored separately by account mode. REAL selection alone does not authorize trading; no REAL session restarts automatically after process death.", color = WarningAmber, fontSize = 10.sp)
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                derivDemoVerification = null
                                message = null
                                try {
                                    require(derivMode == "demo") { "Verify Deriv DEMO is available only while DEMO mode is selected. LIVE remains blocked." }
                                    require(derivToken.isNotBlank() && derivAppId.isNotBlank()) { "Enter a Deriv DEMO PAT and registered numeric App ID." }
                                    require(secrets.saveDerivApiToken(derivToken, "demo")) { "Deriv DEMO PAT could not be saved in encrypted storage." }
                                    require(secrets.saveDerivAppId(derivAppId)) { "Registered Deriv App ID could not be saved." }
                                    val account = viewModel.app.derivBrokerGateway.connectAndAuthorize(derivToken, selectedDerivAccountId.takeIf { it.isNotBlank() }).getOrThrow()
                                    val expectedAccountId = selectedDerivAccountId
                                    val metrics = viewModel.app.derivBrokerGateway.accountMetrics.value
                                    val checkedBeforeTickMs = System.currentTimeMillis()
                                    val balanceUpdatedAtMs = viewModel.app.derivBrokerGateway.accountMetricsUpdatedAtMs.value
                                    val balanceAge = checkedBeforeTickMs - balanceUpdatedAtMs
                                    val balanceValue = metrics?.balance ?: Double.NaN
                                    val balanceReceived = expectedAccountId.isNotBlank() && account.loginId == expectedAccountId && account.loginId.isNotBlank() && account.accountType.equals("demo", true) && account.currency.isNotBlank() && metrics?.currency.equals(account.currency, ignoreCase = true) && balanceValue.isFinite() && balanceValue >= 0.0 && balanceAge in 0..8_000L
                                    val portfolio = viewModel.app.derivBrokerGateway.portfolioSnapshot.value
                                    val portfolioRetrievedAtMs = portfolio?.retrievedAtMs ?: 0L
                                    val portfolioAge = checkedBeforeTickMs - portfolioRetrievedAtMs
                                    val portfolioReceived = portfolio?.successful == true && portfolio.accountId == account.loginId && portfolio.accountId == expectedAccountId && portfolio.accountMode.equals("DEMO", true) && portfolio.currency.equals(account.currency, true) && portfolioAge in 0..8_000L
                                    // This opens a new unauthenticated public socket; never reads market cache or changes chart source.
                                    val tickEvidence = verifyFreshDerivPublicTick("R_100")
                                    val checkedAt = System.currentTimeMillis()
                                    val provisional = DerivDemoVerificationResult(
                                        selectedAccountId = expectedAccountId, accountId = account.loginId,
                                        accountType = account.accountType, currency = account.currency, balanceValue = balanceValue,
                                        balanceReceived = balanceReceived, balanceAccountId = if (balanceReceived) account.loginId else "",
                                        balanceCurrency = metrics?.currency.orEmpty(), balanceUpdatedAtMs = balanceUpdatedAtMs,
                                        portfolioReceived = portfolioReceived, portfolioAccountId = portfolio?.accountId.orEmpty(),
                                        portfolioMode = portfolio?.accountMode.orEmpty(), portfolioCurrency = portfolio?.currency.orEmpty(),
                                        portfolioRetrievedAtMs = portfolioRetrievedAtMs, tickEvidence = tickEvidence,
                                        checkedAtMs = checkedAt,
                                    )
                                    val failure = if (provisional.state == com.example.data.deriv.DerivDemoVerificationState.PASS) null else "Fresh DEMO evidence was incomplete or did not match the selected account."
                                    derivDemoVerification = provisional.copy(failureMessage = failure)
                                    message = failure ?: "Deriv DEMO verification passed. Read-only evidence only; no order was sent."
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    val mapped = DerivConnectionFailureMapper.from(e)
                                    derivDemoVerification = DerivDemoVerificationResult(selectedAccountId = selectedDerivAccountId, accountId = "", accountType = "DEMO", currency = "", balanceValue = Double.NaN, balanceReceived = false, balanceAccountId = "", balanceCurrency = "", balanceUpdatedAtMs = 0L, portfolioReceived = false, portfolioAccountId = "", portfolioMode = "", portfolioCurrency = "", portfolioRetrievedAtMs = 0L, tickEvidence = null, checkedAtMs = System.currentTimeMillis(), failureMessage = mapped.message)
                                    message = "${mapped.message} ${mapped.recovery}"
                                } finally { busy = false }
                            }
                        },
                        enabled = !busy && derivMode == "demo",
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)
                    ) { Text("VERIFY DERIV DEMO · READ ONLY", fontWeight = FontWeight.Black) }
                    derivDemoVerification?.let { verification ->
                        Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (verification.state.name == "PASS") ProfitGreen else WarningAmber)) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(derivDemoVerificationLabel(verification), color = if (verification.state.name == "PASS") ProfitGreen else WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                Text("Account: ${verification.accountPrefix} · ${verification.accountType} · Currency: ${verification.currency.ifBlank { "unverified" }}", color = TextPrimary, fontSize = 10.sp)
                                Text("Balance received: ${if (verification.balanceReceived) "YES" else "NO"} · Portfolio received: ${if (verification.portfolioReceived) "YES" else "NO"} · Public tick received: ${if (verification.tickReceived) "YES" else "NO"}", color = TextSecondary, fontSize = 10.sp)
                                Text("Selected/account ID match: ${verification.selectedAccountId.isNotBlank() && verification.selectedAccountId == verification.accountId} · Balance ID match: ${verification.balanceAccountId == verification.accountId} · Portfolio ID match: ${verification.portfolioAccountId == verification.accountId}", color = TextSecondary, fontSize = 9.sp)
                                verification.tickEvidence?.let { tick -> Text("Tick ${tick.symbol} · quote ${tick.quote} · provider ${tick.providerTimestampMs} · received ${tick.receivedAtMs} · started ${tick.verificationStartedAtMs}", color = TextSecondary, fontSize = 9.sp) }
                                Text("Checked: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(java.util.Date(verification.checkedAtMs))}", color = TextSecondary, fontSize = 9.sp)
                                verification.failureMessage?.let { Text("Reason: $it", color = LossRed, fontSize = 9.sp) }
                            }
                        }
                    }
                    val matchingDerivAccounts = derivAccounts.filter { it.accountType.equals(derivMode, true) }
                    if (matchingDerivAccounts.isNotEmpty()) {
                        Text("Select exact active ${derivMode.uppercase()} account", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        matchingDerivAccounts.forEach { option ->
                            FilterChip(selected = selectedDerivAccountId == option.accountId, onClick = {
                                if (selectedDerivAccountId != option.accountId || derivAccount?.loginId != option.accountId) {
                                    secrets.invalidateDerivRealAuthorization()
                                    viewModel.app.derivBrokerGateway.disconnect()
                                    selectedDerivAccountId = option.accountId
                                    message = "Account selection changed; previous Deriv session and REAL authorization cleared. Connect to verify the selected ID."
                                }
                            }, label = { Text("${option.accountId} · ${option.accountType.uppercase()} · ${option.currency} · ${option.balance}", fontSize = 9.sp) })
                        }
                    }
                    Text("Deriv lifecycle: $derivLifecycleLabel · connection ${derivState.name}", color = if (derivLifecycleLabel == "READY") ProfitGreen else WarningAmber, fontSize = 10.sp)
                    derivAccount?.let { account ->
                        Text("Selected: ${account.loginId} · ${account.accountType.uppercase()} · ${account.currency} · balance ${account.balance}", color = TextPrimary, fontSize = 10.sp)
                        val readyPortfolio = derivPortfolio?.let { snap -> snap.successful && snap.accountId == account.loginId && snap.accountMode.equals(account.mode, true) && snap.currency.equals(account.currency, true) && System.currentTimeMillis() - snap.retrievedAtMs in 0..8_000L } == true
                        Text("Fresh balance: ${derivBalanceUpdatedAt > 0L && System.currentTimeMillis() - derivBalanceUpdatedAt in 0..8_000L} · fresh account-matched portfolio: $readyPortfolio", color = if (readyPortfolio) ProfitGreen else WarningAmber, fontSize = 10.sp)
                        if (derivMode == "real") {
                            val lease = secrets.getDerivRealAuthorizationLease()
                            val authorized = secrets.isDerivRealExecutionAuthorized(account.loginId, "real", account.currency, account.authenticatedSessionId, account.credentialGeneration)
                            if (authorized && lease != null) Text("REAL authorization: ACTIVE until ${java.text.SimpleDateFormat("HH:mm:ss z", java.util.Locale.US).format(java.util.Date(lease.armedAtMs + com.example.data.deriv.DerivRealAuthorizationLease.DEFAULT_VALIDITY_MS))}", color = WarningAmber, fontSize = 10.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                if (!authorized) Button(onClick = { showDerivRealArmDialog = true }, enabled = derivAccountReady && account.mode.equals("real", true) && account.accountType.equals("real", true) && readyPortfolio && derivBalanceUpdatedAt > 0L && System.currentTimeMillis() - derivBalanceUpdatedAt in 0..8_000L, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = WarningAmber, contentColor = DarkBackground)) { Text("ARM REAL · EXPLICIT") }
                                else OutlinedButton(onClick = { secrets.invalidateDerivRealAuthorization(); message = "Deriv REAL authorization revoked; REAL mode remains selected but is not armed." }, modifier = Modifier.weight(1f)) { Text("REVOKE REAL") }
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true; message = null
                            try {
                                if (broker == BrokerType.MT5) {
                                    require(selectedMt5Broker in setOf("FBS", "EXNESS", "HFM", "DERIV")) { "Select an MT5 broker before connecting." }
                                    val snapshot = requireNotNull(bridge) { "Select an MT5 broker." }.registerManagedAccount(login = login, password = password, server = server, accessType = accessType, consent = consent, savePasswordLocally = savePassword)
                                    mt5Snapshot = snapshot
                                    message = when {
                                        snapshot.connected && snapshot.terminalOnline && snapshot.heartbeatFresh && snapshot.accountVerified && snapshot.brokerIdentityVerified -> "Broker identity verified; account is connected."
                                        snapshot.terminalOnline && snapshot.heartbeatFresh -> "BROKER IDENTITY UNCONFIRMED; execution remains blocked."
                                        else -> "Credentials accepted; waiting for a fresh terminal heartbeat."
                                    }
                                } else {
                                    require(derivToken.isNotBlank() && derivAppId.isNotBlank()) { "Enter a Deriv API token and application ID." }
                                    require(secrets.saveDerivApiToken(derivToken, derivMode)) { "Deriv $derivMode PAT could not be saved in encrypted storage." }
                                    require(secrets.saveDerivAppId(derivAppId)) { "Registered Deriv App ID could not be saved." }
                                    val result = viewModel.app.derivBrokerGateway.connectAndAuthorize(derivToken, selectedDerivAccountId.takeIf { it.isNotBlank() })
                                    result.onSuccess { message = "Connected to Deriv account ${it.loginId}." }.onFailure { throw it }
                                }
                            } catch (e: Exception) { message = e.message?.take(220) ?: "Connection failed." } finally { busy = false }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = DarkBackground)
                    else { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)); Text("CONNECT", fontWeight = FontWeight.Black) }
                }
            }
        }

        if (connected) {
            ConnectedBotDashboard(
                broker = broker,
                mt5Snapshot = mt5Snapshot,
                derivAccount = derivAccount,
                accountMetrics = accountMetrics,
                botSummary = botSummary,
                running = running,
                selectedOpportunity = selectedOpportunity,
                commandPending = commandPending,
                onStart = {
                    scope.launch {
                        busy = true; commandPending = true; message = "Scanning eligible markets…"
                        try {
                            val opportunity = viewModel.findBestBotOpportunity()
                            selectedOpportunity = opportunity
                            require(opportunity != null) { "No qualified setup yet. Scanning continues." }
                            if (broker == BrokerType.MT5) {
                                require(mt5Mode != "LIVE" || mt5Snapshot?.liveAuthorized == true) { "LIVE START blocked: explicitly authorize this exact LIVE profile first." }
                                val config = JSONObject().put("strategy", viewModel.selectedStrategyId.value).put("symbols", JSONArray().put(opportunity.symbol)).put("timeframes", JSONArray().put("H1")).put("risk_percent", 1.0).put("max_daily_loss_percent", 2.0).put("max_concurrent_positions", 2)
                                val result = requireNotNull(bridge).command(UUID.randomUUID().toString(), "START", config)
                                message = if (result.queued) "Scanning started for ${opportunity.symbol}; waiting for broker acknowledgement." else result.detail
                            } else {
                                val account = requireNotNull(derivAccount) { "Deriv account is not authenticated." }
                                require(account.mode.equals(derivMode, true) && account.accountType.equals(derivMode, true)) { "Deriv account mode does not match the selected mode." }
                                if (derivMode == "real") {
                                    val unresolved = viewModel.app.db.derivExecutionDao().unresolved(account.loginId, "REAL").size
                                    require(viewModel.app.derivBrokerGateway.isAccountReadyForTrading(account, unresolved) && secrets.isDerivRealExecutionAuthorized(account.loginId, "real", account.currency, account.authenticatedSessionId, account.credentialGeneration)) { "Deriv REAL is blocked: explicit active account/session authorization and fresh balance/portfolio reconciliation are required." }
                                } else {
                                    val unresolved = viewModel.app.db.derivExecutionDao().unresolved(account.loginId, "DEMO").size
                                    require(viewModel.app.derivBrokerGateway.isAccountReadyForTrading(account, unresolved)) { "Deriv DEMO is not READY: fresh account and portfolio synchronization required." }
                                }
                                if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                DerivDemoBotService.start(context, opportunity.symbol, viewModel.selectedStrategyId.value, "H1")
                                message = "Scanning started for ${opportunity.symbol}; the worker will verify broker state before any entry."
                            }
                        } catch (e: Exception) { message = e.message?.take(240) ?: "Start blocked." } finally { busy = false; commandPending = false }
                    }
                },
                onStop = {
                    scope.launch {
                        busy = true; commandPending = true
                        try {
                            if (broker == BrokerType.MT5) {
                                val result = requireNotNull(bridge).command(UUID.randomUUID().toString(), "STOP")
                                message = if (result.queued) "Stop requested; waiting for fresh broker state. Existing positions remain protected." else result.detail
                            } else { DerivDemoBotService.stop(context); viewModel.setBotStatus(false); message = "Stop requested. Existing positions remain broker-tracked." }
                        } catch (e: Exception) { message = e.message?.take(240) ?: "Stop was not confirmed." } finally { busy = false; commandPending = false }
                    }
                }
            )
        }
        if (showDerivRealArmDialog) AlertDialog(
            onDismissRequest = { showDerivRealArmDialog = false },
            title = { Text("Arm Deriv REAL for this session?") },
            text = { Text("This authorizes only the currently authenticated account ${derivAccount?.loginId ?: "unknown"} (${derivAccount?.currency ?: "unknown"}) in the current process session. The lease expires after 60 minutes and is not restored after process death. No REAL order is sent by arming.") },
            confirmButton = { TextButton(onClick = {
                showDerivRealArmDialog = false
                scope.launch {
                    val account = derivAccount
                    try {
                        require(account != null && account.mode.equals("real", true) && account.accountType.equals("real", true)) { "Authenticated Deriv REAL account required." }
                        require(selectedDerivAccountId.isBlank() || selectedDerivAccountId == account.loginId) { "Selected account does not match the authenticated account." }
                        val unresolved = viewModel.app.db.derivExecutionDao().unresolved(account.loginId, "REAL").size
                        require(viewModel.app.derivBrokerGateway.isAccountReadyForTrading(account, unresolved)) { "Fresh balance, account-matched portfolio and clear reconciliation are required." }
                        require(secrets.enableDerivRealExecution(true, account.loginId, account.accountType, account.currency, account.authenticatedSessionId, account.credentialGeneration)) { "Session-bound REAL authorization could not be armed." }
                        message = "Deriv REAL authorization armed for the current account session only."
                    } catch (e: Exception) { message = e.message ?: "Deriv REAL arm failed closed." }
                }
            }) { Text("ARM REAL SESSION") } },
            dismissButton = { TextButton(onClick = { showDerivRealArmDialog = false }) { Text("CANCEL") } }
        )
        if (showPairingRevokeDialog) AlertDialog(
            onDismissRequest = { showPairingRevokeDialog = false },
            title = { Text("Revoke this managed account?") },
            text = { Text("This revokes the selected $selectedMt5Broker $mt5Mode pairing at the bridge and requests worker cleanup for its isolated terminal. It does not place an order. Locally saved form values remain on this device.") },
            confirmButton = { TextButton(onClick = {
                showPairingRevokeDialog = false
                scope.launch {
                    busy = true
                    try { requireNotNull(bridge).revoke(); mt5Snapshot = null; liveAuthorizationExpiry = ""; message = "Pairing revoked; worker cleanup requested. Check bridge/worker status before reuse." }
                    catch (e: Exception) { message = e.message ?: "Pairing revocation was not confirmed." }
                    finally { busy = false }
                }
            }) { Text("REVOKE PAIRING") } },
            dismissButton = { TextButton(onClick = { showPairingRevokeDialog = false }) { Text("CANCEL") } }
        )
        if (showLiveAuthorizationDialog) AlertDialog(
            onDismissRequest = { showLiveAuthorizationDialog = false },
            title = { Text("Authorize LIVE trading?") },
            text = { Text("Authorize only ${secrets.getManagedMT5AccountLogin(mt5Profile)} @ ${secrets.getManagedMT5AccountServer(mt5Profile)} for broker $selectedMt5Broker. The server validates the actual terminal identity and issues a lease for up to 15 minutes. No order is sent by authorizing.") },
            confirmButton = { TextButton(onClick = {
                showLiveAuthorizationDialog = false
                scope.launch { busy = true; try { val expiry = requireNotNull(bridge).authorizeLiveSession(); liveAuthorizationExpiry = expiry; mt5Snapshot = bridge.state(); message = "LIVE authorization confirmed until $expiry." } catch (e: Exception) { message = e.message ?: "LIVE authorization failed closed." } finally { busy = false } }
            }) { Text("AUTHORIZE LIVE") } },
            dismissButton = { TextButton(onClick = { showLiveAuthorizationDialog = false }) { Text("CANCEL") } }
        )
        message?.let { Text(it, color = if (it.contains("failed", true) || it.contains("blocked", true)) LossRed else WarningAmber, fontSize = 11.sp, lineHeight = 16.sp) }
    }
}

@Composable
private fun LoginField(label: String, value: String, onChange: (String) -> Unit, keyboardType: KeyboardType, password: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = keyboardType), visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}

@Composable
private fun ConnectedBotDashboard(
    broker: BrokerType,
    mt5Snapshot: MT5RemoteBridgeClient.Snapshot?,
    derivAccount: com.example.data.deriv.DerivAccountInfo?,
    accountMetrics: com.example.domain.repository.AccountMetrics?,
    botSummary: com.destinedfx.core.bot.BotStatusSummary,
    running: Boolean,
    selectedOpportunity: com.example.domain.model.TradeSignal?,
    commandPending: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, ProfitGreen.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VERIFIED CONNECTED ACCOUNT", color = ProfitGreen, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(if (broker == BrokerType.MT5) "MetaTrader 5 · ${mt5Snapshot?.actualAccountMode ?: "unverified"}" else "Deriv · ${derivAccount?.mode?.uppercase() ?: "unverified"}", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            val accountId = if (broker == BrokerType.MT5) mt5Snapshot?.actualLogin else derivAccount?.loginId
            Text("Account: ${maskAccountId(accountId)}", color = TextSecondary, fontSize = 11.sp)
            if (broker == BrokerType.MT5) {
                Text("Server: ${mt5Snapshot?.actualServer ?: "unverified"} · Currency: ${mt5Snapshot?.currency ?: "unverified"}", color = TextSecondary, fontSize = 11.sp)
                Text("Balance: ${mt5Snapshot?.balance?.toString() ?: "unavailable"} · Equity: ${mt5Snapshot?.equity?.toString() ?: "unavailable"}", color = TextSecondary, fontSize = 11.sp)
                Text("Free margin: ${mt5Snapshot?.freeMargin?.toString() ?: "unavailable"} · Leverage: ${mt5Snapshot?.leverage?.toString() ?: "unavailable"}", color = TextSecondary, fontSize = 11.sp)
                Text("Terminal: ${if (mt5Snapshot?.terminalConnected == true) "CONNECTED" else "OFFLINE"} · EA heartbeat: ${if (mt5Snapshot?.heartbeatFresh == true) "FRESH" else "STALE"} · Trade permission: ${if (mt5Snapshot?.tradeAllowed == true) "ALLOWED" else "BLOCKED"}", color = TextSecondary, fontSize = 10.sp)
            } else {
                Text("Balance: ${derivAccount?.balance?.toString() ?: "unavailable"} · Currency: ${derivAccount?.currency ?: "unverified"}", color = TextSecondary, fontSize = 11.sp)
            }
            Text(if (running) "Scanning eligible markets" else "Connected and ready", color = if (running) ElectricCyan else ProfitGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            selectedOpportunity?.let { Text("Selected opportunity: ${it.symbol} · ${userFacingSignalLabel(it.signalState, it.direction)}", color = TextPrimary, fontSize = 11.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onStart, enabled = !running && !commandPending, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen, contentColor = DarkBackground)) { Icon(Icons.Default.PlayArrow, contentDescription = null); Text("START BOT", fontWeight = FontWeight.Black) }
                Button(onClick = onStop, enabled = running && !commandPending, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = LossRed, contentColor = Color.White)) { Icon(Icons.Default.Stop, contentDescription = null); Text("STOP BOT", fontWeight = FontWeight.Black) }
            }
            Text("No qualified setup means no order. RiskEngine, account permissions, fresh data, duplicate prevention and broker confirmation remain required.", color = WarningAmber, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}

private fun maskAccountId(value: String?): String {
    val normalized = value?.trim().orEmpty()
    if (normalized.isBlank()) return "unverified"
    return if (normalized.length <= 4) "••••" else "••••${normalized.takeLast(4)}"
}

private fun Boolean?.orFalse(): Boolean = this == true
