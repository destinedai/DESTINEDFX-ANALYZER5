package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.broker.BrokerType
import com.example.data.broker.LoginConnector
import com.example.data.ctrader.CTraderAccountOption
import com.example.data.ctrader.CTraderVerifiedAccount
import com.example.data.ctrader.CTraderSymbolMetadata
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
import com.example.ui.bot.ConnectOutcome
import com.example.ui.bot.ConnectUseCase
import com.example.ui.bot.MT5BridgeConnectBackend
import com.example.ui.bot.MetaTraderBrokerFlow
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
    val derivBotServiceState by DerivDemoBotService.state.collectAsStateWithLifecycle()
    val derivBotServiceReason by DerivDemoBotService.statusReason.collectAsStateWithLifecycle()
    var batteryOptimizationExempt by remember { mutableStateOf(false) }
    var broker by remember(activeBroker) { mutableStateOf(activeBroker) }
    var mt5Mode by remember { mutableStateOf("DEMO") }
    var selectedMt5Broker by remember { mutableStateOf(viewModel.app.secretsManager.getSelectedMT5BrokerBrand()) }
    val mt5Profile = "${selectedMt5Broker}_${mt5Mode}"
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var accessType by remember { mutableStateOf("MASTER") }
    var derivToken by remember { mutableStateOf("") }
    var derivSaveToken by remember { mutableStateOf(false) }
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
    var showModernBrokerFlow by remember { mutableStateOf(true) }
    var mt5Snapshot by remember { mutableStateOf<MT5RemoteBridgeClient.Snapshot?>(null) }
    var showPairingRevokeDialog by remember(mt5Profile) { mutableStateOf(false) }
    var showLiveAuthorizationDialog by remember(mt5Profile) { mutableStateOf(false) }
    var liveTypedConfirmation by remember(mt5Profile) { mutableStateOf("") }
    var liveMinimumLot by remember(mt5Profile) { mutableStateOf("") }
    var derivLiveTypedConfirmation by remember { mutableStateOf("") }
    var derivMinimumStake by remember { mutableStateOf("") }
    var liveAuthorizationExpiry by remember(mt5Profile) { mutableStateOf("") }
    var selectedOpportunity by remember { mutableStateOf<com.example.domain.model.TradeSignal?>(null) }
    var commandPending by remember { mutableStateOf(false) }
    var demoPassDigests by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingMt5DemoPassDigest by remember { mutableStateOf<String?>(null) }
    var pendingMt5DemoPassIdentity by remember { mutableStateOf<com.example.data.broker.ModeAccountIdentity?>(null) }
    val secrets = viewModel.app.secretsManager
    val cTraderGateway = viewModel.app.cTraderGateway
    val cTraderCallback by viewModel.app.cTraderCallback.collectAsStateWithLifecycle()
    val cTraderQuote by cTraderGateway.quotes.collectAsStateWithLifecycle()
    var cTraderClientId by remember { mutableStateOf(secrets.getCTraderClientId()) }
    var cTraderClientSecret by remember { mutableStateOf(secrets.getCTraderClientSecret()) }
    var cTraderSpotwareApproved by remember { mutableStateOf(false) }
    var cTraderRedirectUri by remember { mutableStateOf(secrets.getCTraderRedirectUri().ifBlank { "destinedfx://ctrader-callback" }) }
    var cTraderBroker by remember { mutableStateOf("") }
    var cTraderLive by remember { mutableStateOf(false) }
    var cTraderSymbolId by remember { mutableStateOf(0L) }
    var cTraderSymbolName by remember { mutableStateOf("") }
    var cTraderSymbolMetadata by remember { mutableStateOf<CTraderSymbolMetadata?>(null) }
    var cTraderAccounts by remember { mutableStateOf<List<CTraderAccountOption>>(emptyList()) }
    var cTraderSelectedAccountId by remember { mutableStateOf<Long?>(null) }
    var cTraderVerification by remember { mutableStateOf<CTraderVerifiedAccount?>(null) }
    var cTraderFailure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cTraderCallback) {
        val callback = cTraderCallback ?: return@LaunchedEffect
        viewModel.app.clearCTraderCallback()
        if (!cTraderSpotwareApproved) {
            cTraderFailure = "WAITING FOR SPOTWARE APPROVAL. Confirm the approval email before authorizing this application."
            return@LaunchedEffect
        }
        val code = viewModel.app.cTraderOAuthClient.consumeAuthorizationCallback(callback)
            .getOrElse { cTraderFailure = it.message ?: "cTrader callback was rejected."; return@LaunchedEffect }
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
        derivSaveToken = secrets.isDerivApiTokenSaveEnabled()
    }
    LaunchedEffect(context) {
        DerivDemoBotService.restorePersistedStatus(context)
        while (true) {
            batteryOptimizationExempt = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
            delay(5_000L)
        }
    }
    LaunchedEffect(context, selectedMt5Broker, derivAccount?.loginId, derivAccount?.credentialGeneration) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val channelId = "live_lease_renewal"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(android.app.NotificationChannel(
                channelId, "LIVE lease renewal reminders", android.app.NotificationManager.IMPORTANCE_HIGH
            ))
        }
        val prefs = context.getSharedPreferences("mode_renewal_reminders", android.content.Context.MODE_PRIVATE)
        while (true) {
            val now = System.currentTimeMillis()
            val activeLeases = buildList {
                listOf("FBS", "EXNESS", "HFM", "DERIV").forEach { brand ->
                    val generation = secrets.getManagedMT5CredentialGeneration("${brand}_LIVE")
                    val identity = com.example.data.broker.ModeAccountIdentity(
                        com.example.data.broker.ModePath.valueOf("${brand}_MT5"), brand,
                        secrets.getManagedMT5AccountLogin("${brand}_LIVE"),
                        secrets.getManagedMT5AccountServer("${brand}_LIVE"),
                        com.example.data.broker.AccountMode.LIVE, generation
                    )
                    viewModel.app.modeArmingPolicy.liveLease(identity, now)?.let(::add)
                }
                val account = derivAccount?.takeIf { it.mode.equals("real", true) && it.accountType.equals("real", true) }
                if (account != null) {
                    val identity = com.example.data.broker.ModeAccountIdentity(
                        com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", account.loginId, "",
                        com.example.data.broker.AccountMode.LIVE, secrets.getDerivCredentialGeneration("real")
                    )
                    viewModel.app.modeArmingPolicy.liveLease(identity, now)?.let(::add)
                }
            }
            activeLeases.filter { it.expiresAtMs - now in 1..120_000L }.forEach { lease ->
                val reminderKey = lease.identity.fingerprint() + ":" + lease.expiresAtMs
                val preferenceKey = "notified_" + reminderKey
                if (!prefs.getBoolean(preferenceKey, false) && DerivDemoBotService.hasNotificationPermission(context)) {
                    val openApp = android.app.PendingIntent.getActivity(
                        context, reminderKey.hashCode(),
                        Intent(context, com.example.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    val minutes = ((lease.expiresAtMs - now + 59_999L) / 60_000L).coerceAtLeast(1L)
                    manager?.notify(reminderKey.hashCode(), androidx.core.app.NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(com.example.R.drawable.ic_launcher_foreground)
                        .setContentTitle("DestinedFX · LIVE lease renewal required")
                        .setContentText("${lease.identity.broker}: $minutes min remain. Reverify and renew; no silent extension.")
                        .setContentIntent(openApp).setAutoCancel(true)
                        .addAction(0, "Review renewal", openApp)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_STATUS).build())
                    prefs.edit().putBoolean(preferenceKey, true).apply()
                }
            }
            delay(10_000L)
        }
    }
    val bridge = remember(mt5Profile) { if (selectedMt5Broker in setOf("FBS", "EXNESS", "HFM", "DERIV")) MT5RemoteBridgeClient(secrets, mt5Profile, { AppSecurityGuard.authorizeSensitiveAction() }, accountMode = mt5Mode, brokerBrand = selectedMt5Broker, modeArmingPolicy = viewModel.app.modeArmingPolicy) else null }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val reason = if (granted) "BOT NOT RUNNING — notifications are enabled. Tap Start again to begin the foreground service."
        else "BOT NOT RUNNING — notification permission was denied. Allow DestinedFX notifications in Android Settings, then tap Start again."
        DerivDemoBotService.recordStartBlocked(context, reason)
        message = reason
    }
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
                require(mt5Mode == "DEMO") { "Select and verify the DEMO slot before importing a DEMO PASS." }
                val fresh = mt5Snapshot ?: throw IllegalStateException("A current MT5 account snapshot is required before DEMO acceptance.")
                require(isVerifiedMt5Connection(
                    snapshotConnected = fresh.connected,
                    workerReady = fresh.workerStatus == "READY",
                    terminalOnline = fresh.terminalOnline,
                    heartbeatFresh = fresh.heartbeatFresh,
                    accountVerified = fresh.accountVerified,
                    brokerIdentityVerified = fresh.brokerIdentityVerified,
                    actualMode = fresh.actualAccountMode,
                    expectedMode = "DEMO",
                    actualLogin = fresh.actualLogin,
                    expectedLogin = demoLogin,
                    actualServer = fresh.actualServer,
                    expectedServer = demoServer
                ) && fresh.snapshotsValid) { "Fresh terminal identity, heartbeat or account snapshots do not match the selected DEMO account." }
                val generation = secrets.getManagedMT5CredentialGeneration("${broker}_DEMO")
                require(generation.isNotBlank()) { "Current MT5 credential generation is unavailable; reconnect the DEMO account." }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val modeIdentity = com.example.data.broker.ModeAccountIdentity(
                    path = com.example.data.broker.ModePath.valueOf("${broker}_MT5"),
                    broker = broker, accountId = demoLogin, server = demoServer,
                    mode = com.example.data.broker.AccountMode.DEMO, credentialGeneration = generation
                )
                modeIdentity to digest
            }
            result.onSuccess { (identity, digest) ->
                pendingMt5DemoPassIdentity = identity
                pendingMt5DemoPassDigest = digest
                message = "DEMO PASS evidence validated read-only for $selectedMt5Broker. Explicit owner acceptance is still required; no execution was enabled."
            }.onFailure {
                message = "DEMO PASS file rejected: ${it.message?.take(180) ?: "invalid evidence"}. LIVE remains locked."
            }
        }
    }
    val selectMt5Slot: (String, String) -> Unit = { brand, mode ->
        val storedDemoIdentity = com.example.data.broker.ModeAccountIdentity(
            com.example.data.broker.ModePath.valueOf(brand + "_MT5"), brand,
            secrets.getManagedMT5AccountLogin(brand + "_DEMO"),
            secrets.getManagedMT5AccountServer(brand + "_DEMO"),
            com.example.data.broker.AccountMode.DEMO,
            secrets.getManagedMT5CredentialGeneration(brand + "_DEMO")
        )
        val storedDemoPass = secrets.getPathDemoAcceptance(storedDemoIdentity) != null
        if (mode == "LIVE" && !storedDemoPass && !canSelectMt5Mode(mode, brand, demoPassDigests)) {
            message = "LIVE is locked. Import and accept a matching broker-specific DEMO PASS from the verified DEMO slot first."
        } else {
        val changed = selectedMt5Broker != brand || mt5Mode != mode
        val previousBridge = bridge
        val applySelection = {
            selectedMt5Broker = brand; mt5Mode = mode
            secrets.saveSelectedMT5BrokerBrand(brand)
            broker = BrokerType.FBS; viewModel.selectBroker(BrokerType.FBS); message = null
        }
        if (changed && broker.usesManagedMt5Terminal && mt5Mode == "LIVE" && previousBridge?.hasPairing() == true) {
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
        if (type != broker && broker.usesManagedMt5Terminal && mt5Mode == "LIVE" && previousBridge?.hasPairing() == true) {
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
    val derivDemoAccepted = derivAccount?.let { account ->
        account.mode.equals("demo", true) && secrets.hasCurrentDerivDemoAcceptance(
            account.loginId, account.currency, account.authenticatedSessionId, account.credentialGeneration
        )
    } == true
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
        if (broker.usesManagedMt5Terminal && bridge?.managedConfigured() == true && bridge.hasPairing()) {
            mt5Snapshot = null
            while (true) {
                mt5Snapshot = runCatching { bridge.state() }.getOrNull() ?: mt5Snapshot?.copy(managedState = "OFFLINE", workerStatus = "OFFLINE", connected = false, terminalOnline = false, heartbeatFresh = false, state = "OFFLINE", liveAuthorized = false)
                delay(5_000L)
            }
        } else {
            mt5Snapshot = null
        }
    }

    val connected = when (broker) {
        in setOf(BrokerType.MT5, BrokerType.FBS, BrokerType.EXNESS, BrokerType.HFM) -> mt5Snapshot?.let {
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
        in setOf(BrokerType.MT5, BrokerType.FBS, BrokerType.EXNESS, BrokerType.HFM) -> mt5Snapshot?.state in setOf("RUNNING", "PAUSED")
        BrokerType.DERIV -> derivBotServiceState in setOf("RUNNING", "PAUSED")
        else -> botActive
    }
    val botUiState = deriveBotUiState(
        busy = busy,
        connected = connected,
        pairingRequired = broker.usesManagedMt5Terminal && !bridge?.hasPairing().orFalse(),
        terminalOnline = if (broker.usesManagedMt5Terminal) mt5Snapshot?.terminalOnline else null,
        heartbeatFresh = if (broker.usesManagedMt5Terminal) mt5Snapshot?.heartbeatFresh else null,
        terminalState = if (broker.usesManagedMt5Terminal) mt5Snapshot?.state else if (running) "RUNNING" else null,
        blocked = broker.usesManagedMt5Terminal && mt5Mode == "LIVE" && mt5Snapshot?.liveAuthorized != true,
        error = message?.contains("failed", ignoreCase = true) == true,
    )

    if (showModernBrokerFlow) {
        MetaTraderBrokerFlow(
            secrets = secrets,
            modeArmingPolicy = viewModel.app.modeArmingPolicy,
            context = context,
            onConnect = { spec, form ->
                if (spec.connector != LoginConnector.MT5_TERMINAL) {
                    ConnectOutcome.Failed("LOGIN_FAILED_UNCLASSIFIED", "This connector requires its official grant or token flow; no MT5 password was sent.")
                } else {
                    val brand = spec.id.removeSuffix("_MT5")
                    val client = MT5RemoteBridgeClient(
                        secrets,
                        "${brand}_DEMO",
                        { AppSecurityGuard.authorizeSensitiveAction() },
                        accountMode = "DEMO",
                        brokerBrand = brand,
                        modeArmingPolicy = viewModel.app.modeArmingPolicy,
                    )
                    ConnectUseCase(MT5BridgeConnectBackend(client)).connect(spec, form)
                }
            },
            onOpenModes = { showModernBrokerFlow = false },
            onClose = { showModernBrokerFlow = false },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DESTINEDFX BOT", color = Color(0xFFE5B85C), fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("Connect an account, then let the engine scan eligible markets automatically.", color = TextSecondary, fontSize = 11.sp)
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CardBorder)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("MODE ARMING · ALL CONNECTOR PATHS", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text("LIVE leases exist only in this app process and are cleared on restart, account change, credential change or emergency stop. A saved credential is never a connection.", color = TextSecondary, fontSize = 9.sp)
                val derivDemoIdentity = derivAccount?.let {
                    com.example.data.broker.ModeAccountIdentity(
                        com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", it.loginId, "",
                        com.example.data.broker.AccountMode.DEMO, secrets.getDerivCredentialGeneration("demo")
                    )
                }
                val derivDemoAcceptedNow = derivDemoIdentity?.let { secrets.getPathDemoAcceptance(it) != null } == true
                val derivLiveIdentity = derivAccount?.let {
                    com.example.data.broker.ModeAccountIdentity(
                        com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", it.loginId, "",
                        com.example.data.broker.AccountMode.LIVE, secrets.getDerivCredentialGeneration("real")
                    )
                }
                val derivLiveState = derivLiveIdentity?.let { viewModel.app.modeArmingPolicy.liveDecision(it).reason } ?: "LIVE_ACCOUNT_NOT_VERIFIED"
                Text("Deriv Options / Multipliers · DEMO: " + (if (derivDemoAcceptedNow) "ACCEPTED FOR CURRENT ACCOUNT" else "BLOCKED · verify and accept exact DEMO account") + " · LIVE: " + derivLiveState, color = if (derivDemoAcceptedNow) ProfitGreen else WarningAmber, fontSize = 9.sp)
                val cTraderIdentity = cTraderVerification?.takeIf { !it.isLive }?.let {
                    com.example.data.broker.ModeAccountIdentity(
                        com.example.data.broker.ModePath.CTRADER_ACCOUNT, it.brokerName, it.accountId.toString(), "",
                        com.example.data.broker.AccountMode.DEMO, secrets.getCTraderCredentialGeneration()
                    )
                }
                val cTraderAccepted = cTraderIdentity?.let { secrets.getPathDemoAcceptance(it) != null } == true
                Text("cTrader · DEMO: " + when {
                    !cTraderSpotwareApproved -> "BLOCKED · Spotware approval required"
                    cTraderAccepted -> "ACCEPTED FOR CURRENT ACCOUNT"
                    else -> "BLOCKED · read-only verify and accept a fresh matching quote"
                } + " · LIVE: TRADING NOT IMPLEMENTED", color = if (cTraderAccepted) ProfitGreen else WarningAmber, fontSize = 9.sp)
                listOf("FBS", "EXNESS", "HFM", "DERIV").forEach { brand ->
                    val path = com.example.data.broker.ModePath.valueOf(brand + "_MT5")
                    val demoIdentity = com.example.data.broker.ModeAccountIdentity(
                        path, brand, secrets.getManagedMT5AccountLogin(brand + "_DEMO"),
                        secrets.getManagedMT5AccountServer(brand + "_DEMO"),
                        com.example.data.broker.AccountMode.DEMO, secrets.getManagedMT5CredentialGeneration(brand + "_DEMO")
                    )
                    val demoAccepted = secrets.getPathDemoAcceptance(demoIdentity) != null
                    val liveIdentity = com.example.data.broker.ModeAccountIdentity(
                        path, brand, secrets.getManagedMT5AccountLogin(brand + "_LIVE"),
                        secrets.getManagedMT5AccountServer(brand + "_LIVE"),
                        com.example.data.broker.AccountMode.LIVE, secrets.getManagedMT5CredentialGeneration(brand + "_LIVE")
                    )
                    val liveReason = if (!liveIdentity.isValid()) "LIVE ACCOUNT NOT CONFIGURED" else viewModel.app.modeArmingPolicy.liveDecision(liveIdentity).reason
                    Text("$brand MT5 · DEMO: " + (if (demoAccepted) "ACCEPTED · fresh heartbeat required at Start" else "BLOCKED · verify DEMO and import owner PASS") + " · LIVE: " + liveReason, color = if (demoAccepted) ProfitGreen else WarningAmber, fontSize = 9.sp)
                }
                Text("Next owner actions: Deriv Options—verify/accept the exact DEMO account; cTrader—obtain Spotware approval and complete read-only owner testing (trading is not implemented); FBS, Exness, HFM, Deriv MT5—configure broker-issued DEMO identity, verify fresh terminal evidence and import/accept broker PASS. LIVE remains subject to exact LIVE verification, typed confirmation, minimum stake/lot and short lease.", color = TextSecondary, fontSize = 9.sp)
            }
        }
        val setupStep = when {
            !connected && login.isBlank() -> 1
            !connected -> 2
            !running -> 3
            else -> 4
        }
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CardBorder)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CTRADER ACCOUNT · READ ONLY", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text(if (cTraderSpotwareApproved) "OWNER CONFIRMED SPOTWARE APPROVAL EMAIL" else "WAITING FOR SPOTWARE APPROVAL", color = if (cTraderSpotwareApproved) ProfitGreen else WarningAmber, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text("Spotware reviews each new Open API application. A new app remains submitted until Spotware emails approval or requests details. Add redirect URIs only after approval; Spotware's default URI is for the playground only. Provide a detailed app description to support review.", color = TextSecondary, fontSize = 9.sp, lineHeight = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = cTraderSpotwareApproved, onCheckedChange = { cTraderSpotwareApproved = it })
                    Text("I received Spotware's approval email for this app", color = TextSecondary, fontSize = 9.sp)
                }
                LoginField("Client ID", cTraderClientId, { cTraderClientId = it }, KeyboardType.Number)
                LoginField("Client Secret", cTraderClientSecret, { cTraderClientSecret = it }, KeyboardType.Password, true)
                LoginField("Exact registered redirect URI", cTraderRedirectUri, { cTraderRedirectUri = it }, KeyboardType.Uri)
                Text("This app's custom redirect is not Spotware's default playground URI. Register this exact URI after approval. HTTPS redirect fallback requires an owner-controlled domain. The custom-scheme path remains UNVERIFIED until an owner-run test succeeds. cTrader does not document a state parameter; exact redirect matching, one-time codes and code age remain enforced.", color = WarningAmber, fontSize = 9.sp, lineHeight = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !cTraderLive, onClick = { cTraderLive = false }, label = { Text("DEMO") })
                    FilterChip(selected = cTraderLive, enabled = false, onClick = {}, label = { Text("LIVE LOCKED") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val previousAccountId = cTraderSelectedAccountId?.toString().orEmpty()
                        val previousGeneration = secrets.getCTraderCredentialGeneration()
                        if (!secrets.saveCTraderApplication(cTraderClientId.trim(), cTraderClientSecret, cTraderRedirectUri.trim())) { cTraderFailure = "Secure storage unavailable or application details are incomplete." }
                        else {
                            val nextGeneration = secrets.getCTraderCredentialGeneration()
                            if (previousGeneration.isNotBlank() && previousGeneration != nextGeneration && previousAccountId.isNotBlank()) {
                                secrets.clearPathDemoAcceptance(com.example.data.broker.ModeAccountIdentity(
                                    com.example.data.broker.ModePath.CTRADER_ACCOUNT, cTraderBroker, previousAccountId, "",
                                    com.example.data.broker.AccountMode.DEMO, previousGeneration
                                ))
                                viewModel.app.modeArmingPolicy.onCredentialChanged(
                                    com.example.data.broker.ModePath.CTRADER_ACCOUNT, cTraderBroker, previousAccountId
                                )
                            }
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, viewModel.app.cTraderOAuthClient.authorizationUri(com.example.data.ctrader.CTraderOAuthClient.VIEW_SCOPE))) }
                                .onFailure { cTraderFailure = it.message ?: "Could not open cTrader authorization." }
                        }
                    }, enabled = !busy && cTraderSpotwareApproved && cTraderClientId.isNotBlank() && cTraderClientSecret.isNotBlank(), modifier = Modifier.weight(1f)) { Text("GRANT ACCESS") }
                    OutlinedButton(onClick = {
                        scope.launch { busy = true; cTraderFailure = null; cTraderAccounts = cTraderGateway.listAccounts(cTraderLive).getOrElse { cTraderFailure = it.message ?: "Account list failed."; emptyList() }; busy = false }
                    }, enabled = !busy && cTraderSpotwareApproved && secrets.getCTraderAccessToken().isNotBlank(), modifier = Modifier.weight(1f)) { Text("LOAD ACCOUNTS") }
                }
                cTraderAccounts.filter { it.isLive == cTraderLive }.forEach { option ->
                    FilterChip(selected = cTraderSelectedAccountId == option.accountId, onClick = { cTraderSelectedAccountId?.takeIf { it != option.accountId }?.let { viewModel.app.modeArmingPolicy.onAccountSwitch(com.example.data.broker.ModePath.CTRADER_ACCOUNT, option.brokerName, option.accountId.toString()) }; cTraderSelectedAccountId = option.accountId; cTraderBroker = option.brokerName }, label = { Text("${option.brokerName.ifBlank { "broker unverified" }} · ${maskAccountId(option.traderLogin.toString())} · ${if (option.isLive) "LIVE" else "DEMO"}", fontSize = 9.sp) })
                }
                LoginField("Broker name exactly as cTrader reports", cTraderBroker, { cTraderBroker = it }, KeyboardType.Text)
                LoginField("Exact symbol name reported by cTrader", cTraderSymbolName, { cTraderSymbolName = it }, KeyboardType.Text)
                cTraderSymbolMetadata?.let { metadata ->
                    Text("Verified symbol: ${metadata.name} · ID ${metadata.symbolId} · ${metadata.digits} price digits", color = TextSecondary, fontSize = 9.sp)
                }
                Button(onClick = {
                    scope.launch {
                        busy = true; cTraderFailure = null; cTraderVerification = null
                        val result = cTraderGateway.verify(cTraderBroker.trim(), cTraderLive, cTraderSelectedAccountId)
                        result.onSuccess { verified ->
                            cTraderVerification = verified
                            val metadata = cTraderGateway.listSymbols().getOrElse { cTraderFailure = it.message ?: "Symbol metadata failed."; emptyList() }
                                .firstOrNull { it.name.equals(cTraderSymbolName.trim(), ignoreCase = true) }
                            if (metadata == null) cTraderFailure = "BLOCKED: the exact symbol name was not returned by this cTrader account. No quote subscription was sent."
                            else {
                                cTraderSymbolMetadata = metadata
                                cTraderSymbolId = metadata.symbolId
                                cTraderGateway.subscribeReadOnlySpot(metadata.symbolId).onFailure { cTraderFailure = it.message ?: "Quote subscription failed." }
                                message = "cTrader account and exact symbol verified read-only. No order was sent."
                            }
                        }.onFailure { cTraderFailure = it.message ?: "cTrader verification failed." }
                        busy = false
                    }
                }, enabled = !busy && cTraderSpotwareApproved && cTraderBroker.isNotBlank() && cTraderSelectedAccountId != null && cTraderSymbolName.isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)) { Text("VERIFY CTRADER, READ ONLY", fontWeight = FontWeight.Black) }
                cTraderVerification?.let { verified ->
                    val quoteReceived = cTraderQuote?.isFresh(verified.accountId, cTraderSymbolMetadata?.symbolId) == true
                    Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), border = BorderStroke(1.dp, if (quoteReceived) ProfitGreen else LossRed)) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("cTrader verification ${if (quoteReceived) "PASSED" else "BLOCKED"}", color = if (quoteReceived) ProfitGreen else LossRed, fontWeight = FontWeight.Black, fontSize = 11.sp)
                            Text("Broker: ${verified.brokerName} · ${if (verified.isLive) "LIVE" else "DEMO"} · Account: ${maskAccountId(verified.accountId.toString())}", color = TextPrimary, fontSize = 10.sp)
                            Text("Balance received: YES · One quote received: ${if (quoteReceived) "YES" else "NO"}", color = TextSecondary, fontSize = 10.sp)
                            Text("Checked: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(java.util.Date(verified.receivedAtMs))}", color = TextSecondary, fontSize = 9.sp)
                            if (!quoteReceived) Text("Reason: a fresh account-matched quote was not received. CONNECTED remains blocked.", color = LossRed, fontSize = 9.sp)
                            val modeIdentity = com.example.data.broker.ModeAccountIdentity(
                                com.example.data.broker.ModePath.CTRADER_ACCOUNT,
                                verified.brokerName, verified.accountId.toString(), "",
                                com.example.data.broker.AccountMode.DEMO, secrets.getCTraderCredentialGeneration()
                            )
                            val quoteAt = cTraderQuote?.receivedAtMs ?: 0L
                            val checkedAt = System.currentTimeMillis()
                            val readOnly = com.example.data.broker.ModeReadOnlyVerification(
                                modeIdentity, checkedAt,
                                authenticatedSession = quoteReceived,
                                freshAccountData = verified.receivedAtMs > 0L && checkedAt - verified.receivedAtMs in 0..15_000L,
                                freshQuote = quoteReceived && quoteAt > 0L,
                                freshHeartbeat = false,
                                accountIdentityMatched = verified.accountId == cTraderSelectedAccountId && !verified.isLive
                            )
                            val acceptance = secrets.getPathDemoAcceptance(modeIdentity)
                            val dispatcherResult = viewModel.app.universalConnectorDispatcher.validateVerification(
                                com.example.data.broker.ConnectorKind.CTRADER_ACCOUNT,
                                com.example.data.broker.ConnectorVerification(
                                    kind = com.example.data.broker.ConnectorKind.CTRADER_ACCOUNT,
                                    evidence = com.example.data.broker.ConnectorEvidence(
                                        authenticatedSession = readOnly.authenticatedSession,
                                        freshAccountData = readOnly.freshAccountData,
                                        accountIdentityMatched = readOnly.accountIdentityMatched,
                                        freshQuote = readOnly.freshQuote
                                    ),
                                    detail = "cTrader read-only account and identity-matched fresh quote",
                                    identity = modeIdentity,
                                    readOnlyVerification = readOnly,
                                    demoAcceptance = acceptance,
                                    currentDemoCredentialGeneration = secrets.getCTraderCredentialGeneration()
                                )
                            )
                            val dispatcherReason = dispatcherResult.exceptionOrNull()?.message.orEmpty()
                            val modePassed = dispatcherResult.isSuccess
                            Text("DEMO acceptance: " + if (modePassed) "SAVED AND READ-ONLY EVIDENCE PASSED" else dispatcherReason + " · LIVE: trading not implemented", color = if (modePassed) ProfitGreen else WarningAmber, fontSize = 9.sp)
                            if (!verified.isLive && quoteReceived && acceptance == null && dispatcherReason.contains("OWNER_DEMO_ACCEPTANCE_REQUIRED")) {
                                OutlinedButton(onClick = {
                                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                                        .digest((modeIdentity.fingerprint() + verified.receivedAtMs + quoteAt).toByteArray())
                                        .joinToString("") { "%02x".format(it) }
                                    val saved = secrets.savePathDemoAcceptance(modeIdentity, digest)
                                    cTraderFailure = if (saved) null else "Secure cTrader DEMO acceptance could not be saved."
                                    message = if (saved) "cTrader DEMO accepted for this exact account and app credential generation." else "cTrader DEMO acceptance failed closed."
                                }, modifier = Modifier.fillMaxWidth()) { Text("ACCEPT VERIFIED CTRADER DEMO") }
                            }
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
        Button(
                onClick = {
                    scope.launch {
                        commandPending = true
                        try {
                            val disarmed = runCatching { viewModel.app.modeArmingPolicy.emergencyStop() }.getOrDefault(0)
                            val derivResult = runCatching {
                                DerivDemoBotService.stop(context)
                                secrets.invalidateDerivRealAuthorization()
                                viewModel.setBotStatus(false)
                                "Deriv service stop requested"
                            }.getOrElse { "Deriv service stop failed: ${it.message?.take(120) ?: "verify and stop manually"}" }
                            val mt5Result = if (bridge?.hasPairing() == true) {
                                runCatching { requireNotNull(bridge).command(UUID.randomUUID().toString(), "EMERGENCY_STOP") }
                                    .fold(
                                        { if (it.queued) "MT5 bridge stop acknowledged; verify fresh EA stop state" else "MT5 bridge stop not acknowledged: ${it.detail.take(160)}" },
                                        { "MT5 bridge stop failed: ${it.message?.take(120) ?: "verify and stop at the host"}" }
                                    )
                            } else "MT5 bridge not paired; stop at the owner host if active"
                            message = "Emergency stop requested; $disarmed LIVE lease(s) disarmed. $derivResult. $mt5Result. Verify broker state; existing positions are not liquidated."
                        } catch (e: Exception) { message = e.message?.take(240) ?: "Emergency stop was not confirmed; stop manually at the broker/host." }
                        finally { commandPending = false }
                    }
                },
                enabled = !commandPending,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LossRed, contentColor = Color.White)
        ) { Icon(Icons.Default.Stop, contentDescription = null); Text("EMERGENCY STOP · STOP NEW ENTRIES", fontWeight = FontWeight.Black) }
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
                if (broker.usesManagedMt5Terminal && mt5Snapshot?.connected != true) Text("HOST OFFLINE", color = LossRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Worker job: ${mt5Snapshot?.workerStatus ?: "not checked"} · Managed readiness: ${mt5Snapshot?.managedState ?: "not checked"} · Terminal: ${mt5Snapshot?.state ?: "not checked"} · Fresh heartbeat: ${mt5Snapshot?.heartbeatFresh ?: false}", color = TextSecondary, fontSize = 10.sp)
                val idleTimeoutSeconds: Long = mt5Snapshot?.raw?.optInt("idle_timeout_seconds")
                    ?.takeIf { it > 0 }
                    ?.toLong() ?: 0L
                val hostStartedAt: Long = mt5Snapshot?.raw?.optLong("host_started_at_epoch_seconds")
                    ?.takeIf { it > 0L } ?: 0L
                val idleCountdownEstimate: Long = if (idleTimeoutSeconds > 0L && hostStartedAt > 0L) {
                    (idleTimeoutSeconds - (System.currentTimeMillis() / 1000L - hostStartedAt).coerceAtLeast(0L)).coerceAtLeast(0L)
                } else idleTimeoutSeconds
                val idleCountdownLabel: String = if (idleCountdownEstimate > 0L) {
                    " · estimated idle countdown ${idleCountdownEstimate / 60}m ${idleCountdownEstimate % 60}s; user activity resets GitHub's timer"
                } else ""
                Text("Test host, DEMO only, stops when idle$idleCountdownLabel", color = WarningAmber, fontSize = 10.sp)
                Text("A successful HTTP probe is not proof of broker authentication or trading readiness. Do not enter credentials until /health/ready confirms durable storage and configured execution.", color = WarningAmber, fontSize = 9.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CardBorder)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("BROKER CONNECTION", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(BrokerType.FBS, BrokerType.EXNESS, BrokerType.DERIV, BrokerType.HFM).forEach { type ->
                        FilterChip(selected = broker == type, onClick = { selectBrokerType(type) }, label = { Text(when (type) { BrokerType.DERIV -> "Deriv"; BrokerType.FBS -> "FBS"; BrokerType.EXNESS -> "Exness"; BrokerType.HFM -> "HFM"; else -> type.name }, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ElectricCyan, selectedLabelColor = DarkBackground, containerColor = DarkSurfaceVariant, labelColor = TextSecondary))
                    }
                }
                if (broker.usesManagedMt5Terminal) {
                    Text("MT5 ACCOUNTS", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { demoPassLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = mt5Mode == "DEMO" && secrets.getManagedMT5AccountLogin("${selectedMt5Broker}_DEMO").isNotBlank() && secrets.getManagedMT5AccountServer("${selectedMt5Broker}_DEMO").isNotBlank()) {
                            Text("IMPORT DEMO PASS FILE")
                        }
                        Text(if (selectedMt5Broker in demoPassDigests) "OWNER ACCEPTED · ${demoPassDigests[selectedMt5Broker]?.take(12)}" else "LIVE LOCKED", color = if (selectedMt5Broker in demoPassDigests) ProfitGreen else WarningAmber, fontSize = 9.sp)
                    }
                    if (pendingMt5DemoPassDigest != null && mt5Mode == "DEMO") {
                        Text("Read-only PASS evidence validated for the current saved DEMO identity/generation. Confirming records that this owner accepts this exact account; it does not authorize LIVE.", color = WarningAmber, fontSize = 9.sp)
                        OutlinedButton(onClick = {
                            runCatching {
                                val expectedLogin = secrets.getManagedMT5AccountLogin("${selectedMt5Broker}_DEMO")
                                val expectedServer = secrets.getManagedMT5AccountServer("${selectedMt5Broker}_DEMO")
                                val fresh = mt5Snapshot ?: error("Current DEMO terminal snapshot is unavailable; reconnect and verify again.")
                                require(isVerifiedMt5Connection(fresh.connected, fresh.workerStatus == "READY", fresh.terminalOnline, fresh.heartbeatFresh, fresh.accountVerified, fresh.brokerIdentityVerified, fresh.actualAccountMode, "DEMO", fresh.actualLogin, expectedLogin, fresh.actualServer, expectedServer) && fresh.snapshotsValid) { "Current read-only DEMO identity/heartbeat/snapshot check no longer passes; verify again." }
                                val generation = secrets.getManagedMT5CredentialGeneration("${selectedMt5Broker}_DEMO")
                                require(generation.isNotBlank()) { "Current credential generation unavailable; reconnect DEMO." }
                                val identity = com.example.data.broker.ModeAccountIdentity(com.example.data.broker.ModePath.valueOf("${selectedMt5Broker}_MT5"), selectedMt5Broker, expectedLogin, expectedServer, com.example.data.broker.AccountMode.DEMO, generation)
                                require(identity == pendingMt5DemoPassIdentity) { "Account identity or credential generation changed after PASS validation; import and verify again." }
                                require(secrets.savePathDemoAcceptance(identity, requireNotNull(pendingMt5DemoPassDigest))) { "Encrypted owner acceptance could not be saved for this exact identity and generation." }
                                demoPassDigests = demoPassDigests + (selectedMt5Broker to requireNotNull(pendingMt5DemoPassDigest))
                                pendingMt5DemoPassDigest = null
                                pendingMt5DemoPassIdentity = null
                                message = "Owner DEMO acceptance saved for the exact verified identity and credential generation. LIVE remains separately blocked."
                            }.onFailure { message = "Owner DEMO acceptance blocked: ${it.message ?: "verification unavailable"}. LIVE remains locked." }
                        }, enabled = !busy && mt5Snapshot != null) { Text("OWNER ACCEPT VERIFIED DEMO PASS") }
                    }
                    com.example.data.broker.BrokerLoginSpecs.all
                        .filter { it.connector == com.example.data.broker.LoginConnector.MT5_TERMINAL }
                        .forEach { brokerSpec ->
                        val brand = brokerSpec.id.removeSuffix("_MT5")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(brokerSpec.displayName, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                        if (snapshot?.liveAuthorized == true) Text("Authorization expiry: ${serverLiveExpiry.ifBlank { liveAuthorizationExpiry.ifBlank { "server confirms active; expiry not returned" } }} · renew/re-authorize before expiry; lease is never silently extended.", color = WarningAmber, fontSize = 10.sp)
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
                                if (derivMode != mode) {
                                    if (DerivDemoBotService.state.value in setOf("STARTING", "CONNECTING", "RECONCILING", "RUNNING")) {
                                        DerivDemoBotService.stop(context)
                                    }
                                    viewModel.app.derivBrokerGateway.disconnect()
                                }
                                if (secrets.saveDerivAccountMode(mode)) {
                                    derivMode = mode
                                    derivToken = secrets.getDerivApiToken(mode)
                                    derivDemoVerification = null
                                    selectedDerivAccountId = ""
                                    message = "Deriv $mode selected. Existing authorization invalidated; connect and verify this mode explicitly."
                                } else message = "Could not securely select Deriv account mode."
                            }, label = { Text(mode.uppercase()) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = if (mode == "real") WarningAmber else ElectricCyan, selectedLabelColor = DarkBackground, containerColor = DarkSurfaceVariant, labelColor = TextSecondary))
                        }
                    }
                    LoginField("Deriv ${derivMode.uppercase()} API token", derivToken, { derivToken = it }, KeyboardType.Password, true)
                    LoginField("Registered numeric App ID", derivAppId, { derivAppId = it.filter(Char::isDigit) }, KeyboardType.Number)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = derivSaveToken, onCheckedChange = { derivSaveToken = it })
                        Text("Save password in encrypted device storage", color = TextSecondary, fontSize = 10.sp)
                    }
                    Text("Status: ${if (derivState == com.example.data.deriv.DerivConnectionState.READY) "CONNECTED" else derivLifecycleLabel}", color = if (derivAccountReady) ProfitGreen else WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://doc-deriv.readme.io/docs/account-setup"))) }.onFailure { message = "Could not open Deriv account setup help." } }, modifier = Modifier.weight(1f)) { Text("App ID and PAT help", fontSize = 9.sp) }
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.deriv.com/docs/options/"))) }.onFailure { message = "Could not open Deriv API documentation." } }, modifier = Modifier.weight(1f)) { Text("Options API docs", fontSize = 9.sp) }
                    }
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
                                    if (derivSaveToken) require(secrets.isSecureStorageAvailable) { "Secure Android storage is unavailable; password was not saved." }
                                    val previousGeneration = secrets.getDerivCredentialGeneration("demo")
                                    val previousAccountId = derivAccount?.loginId ?: selectedDerivAccountId
                                    require(secrets.saveDerivApiTokenIfAllowed(derivToken, "demo", derivSaveToken)) { "Deriv DEMO PAT could not be processed in encrypted storage." }
                                    require(secrets.saveDerivAppId(derivAppId)) { "Registered Deriv App ID could not be saved." }
                                    val nextGeneration = secrets.getDerivCredentialGeneration("demo")
                                    if (previousGeneration.isNotBlank() && previousGeneration != nextGeneration && previousAccountId.isNotBlank()) {
                                        secrets.clearPathDemoAcceptance(com.example.data.broker.ModeAccountIdentity(
                                            com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", previousAccountId, "",
                                            com.example.data.broker.AccountMode.DEMO, previousGeneration
                                        ))
                                        viewModel.app.modeArmingPolicy.onCredentialChanged(com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", previousAccountId)
                                    }
                                    val account = viewModel.app.derivBrokerGateway.connectAndAuthorize(derivToken, selectedDerivAccountId.takeIf { it.isNotBlank() }).getOrThrow()
                                    if (previousAccountId.isNotBlank() && previousAccountId != account.loginId) {
                                        viewModel.app.modeArmingPolicy.onAccountSwitch(com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", account.loginId)
                                    }
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
                                    message = failure ?: "Deriv DEMO verification passed. Read-only evidence only; no order was sent. Review and explicitly accept this exact account to enable DEMO."
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    val mapped = DerivConnectionFailureMapper.from(e)
                                    derivDemoVerification = DerivDemoVerificationResult(selectedAccountId = selectedDerivAccountId, accountId = "", accountType = "DEMO", currency = "", balanceValue = Double.NaN, balanceReceived = false, balanceAccountId = "", balanceCurrency = "", balanceUpdatedAtMs = 0L, portfolioReceived = false, portfolioAccountId = "", portfolioMode = "", portfolioCurrency = "", portfolioRetrievedAtMs = 0L, tickEvidence = null, checkedAtMs = System.currentTimeMillis(), failureMessage = mapped.message)
                                    message = "${mapped.message} ${mapped.recovery}"
                                } finally { busy = false }
                            }
                        },
                        enabled = !busy && derivMode == "demo" && derivToken.isNotBlank() && derivAppId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5B85C), contentColor = DarkBackground)
                    ) { Text("LOGIN · VERIFY DEMO READ ONLY", fontWeight = FontWeight.Black) }
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
                                if (verification.state == com.example.data.deriv.DerivDemoVerificationState.PASS) {
                                    val verifiedAccount = derivAccount
                                    val identityMatches = verifiedAccount != null &&
                                        verifiedAccount.mode.equals("demo", true) && verifiedAccount.accountType.equals("demo", true) &&
                                        verifiedAccount.loginId == verification.accountId && verifiedAccount.loginId == verification.selectedAccountId &&
                                        verifiedAccount.currency == verification.currency
                                    val accepted = verifiedAccount?.let { secrets.hasCurrentDerivDemoAcceptance(
                                        it.loginId, it.currency, it.authenticatedSessionId, it.credentialGeneration
                                    ) } == true
                                    Text(if (accepted) "Owner acceptance: SAVED for this verified account and credential generation." else "Owner acceptance is separate from verification. No bot start is allowed until you accept this exact DEMO account.", color = if (accepted) ProfitGreen else WarningAmber, fontSize = 9.sp)
                                    Button(onClick = {
                                        val current = derivAccount
                                        val saved = current != null && current.mode.equals("demo", true) &&
                                            current.loginId == verification.accountId && current.loginId == verification.selectedAccountId &&
                                            current.currency == verification.currency &&
                                            secrets.saveDerivDemoAcceptance(current.loginId, current.currency, current.authenticatedSessionId, current.credentialGeneration) &&
                                            secrets.savePathDemoAcceptance(
                                                com.example.data.broker.ModeAccountIdentity(
                                                    com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", current.loginId, "",
                                                    com.example.data.broker.AccountMode.DEMO, current.credentialGeneration
                                                ),
                                                java.security.MessageDigest.getInstance("SHA-256").digest(
                                                    (current.loginId + "|" + current.currency + "|" + verification.checkedAtMs + "|" + current.credentialGeneration).toByteArray()
                                                ).joinToString("") { "%02x".format(it) }
                                            )
                                        message = if (saved) "DEMO acceptance recorded for " + current?.loginId + ". No order was sent." else "DEMO acceptance blocked because the verified account identity or secure storage changed. Verify again."
                                    }, enabled = !busy && identityMatches && !accepted, modifier = Modifier.fillMaxWidth().testTag("deriv_accept_demo")) {
                                        Text(if (accepted) "DEMO ACCEPTED" else "ACCEPT VERIFIED DEMO ACCOUNT")
                                    }
                                }
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
                                    derivDemoVerification = null
                                    message = "Account selection changed; previous Deriv session and REAL authorization cleared. Connect to verify the selected ID."
                                }
                            }, label = { Text("${option.accountId} · ${option.accountType.uppercase()} · ${option.currency} · ${option.balance}", fontSize = 9.sp) })
                        }
                    }
                    Text("Deriv lifecycle: $derivLifecycleLabel · connection ${derivState.name}", color = if (derivLifecycleLabel == "READY") ProfitGreen else WarningAmber, fontSize = 10.sp)
                    Text("DEMO owner acceptance: ${if (derivDemoAccepted) "SAVED for this authenticated account and credential generation" else "NOT ACCEPTED; service start remains blocked"}", color = if (derivDemoAccepted) ProfitGreen else WarningAmber, fontSize = 9.sp)
                    Text("DEMO bot service: ${if (derivBotServiceState == "RUNNING") "RUNNING" else "BOT NOT RUNNING"} · $derivBotServiceState", color = if (derivBotServiceState == "RUNNING") ProfitGreen else WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(derivBotServiceReason, color = if (derivBotServiceState == "RUNNING") TextSecondary else WarningAmber, fontSize = 9.sp)
                    Text("Notifications: ${if (DerivDemoBotService.hasNotificationPermission(context)) "ALLOWED" else "DENIED, bot start blocked"} · Battery optimization: ${if (batteryOptimizationExempt) "not restricting this app" else "active; service may be delayed"}", color = if (DerivDemoBotService.hasNotificationPermission(context) && batteryOptimizationExempt) ProfitGreen else WarningAmber, fontSize = 9.sp)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !batteryOptimizationExempt) {
                        OutlinedButton(onClick = {
                            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                            runCatching { context.startActivity(request) }.onFailure {
                                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                                    .onFailure { message = "Android battery-optimization settings could not be opened; no setting was changed." }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("REQUEST BATTERY-OPTIMIZATION EXEMPTION") }
                    }
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
                                if (broker.usesManagedMt5Terminal) {
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
                                    val previousDerivGeneration = secrets.getDerivCredentialGeneration(derivMode)
                                    val previousDerivAccount = derivAccount?.loginId ?: selectedDerivAccountId
                                    require(secrets.saveDerivApiTokenIfAllowed(derivToken, derivMode, derivSaveToken)) { "Deriv $derivMode PAT could not be processed in encrypted storage." }
                                    require(secrets.saveDerivAppId(derivAppId)) { "Registered Deriv App ID could not be saved." }
                                    val nextDerivGeneration = secrets.getDerivCredentialGeneration(derivMode)
                                    if (previousDerivGeneration.isNotBlank() && previousDerivGeneration != nextDerivGeneration && previousDerivAccount.isNotBlank()) {
                                        if (derivMode == "demo") secrets.clearPathDemoAcceptance(com.example.data.broker.ModeAccountIdentity(
                                            com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", previousDerivAccount, "",
                                            com.example.data.broker.AccountMode.DEMO, previousDerivGeneration
                                        ))
                                        viewModel.app.modeArmingPolicy.onCredentialChanged(
                                            com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", previousDerivAccount
                                        )
                                    }
                                    val previousAccountId = derivAccount?.loginId.orEmpty()
                                    val result = viewModel.app.derivBrokerGateway.connectAndAuthorize(derivToken, selectedDerivAccountId.takeIf { it.isNotBlank() })
                                    result.onSuccess {
                                        if (previousAccountId.isNotBlank() && previousAccountId != it.loginId) {
                                            viewModel.app.modeArmingPolicy.onAccountSwitch(com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", it.loginId)
                                        }
                                        message = "Connected to Deriv account " + it.loginId + "."
                                    }.onFailure { throw it }
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
                            if (broker.usesManagedMt5Terminal) {
                                require(mt5Mode != "LIVE" || mt5Snapshot?.liveAuthorized == true) { "LIVE START blocked: explicitly authorize this exact LIVE profile first." }
                                val config = JSONObject().put("strategy", viewModel.selectedStrategyId.value).put("symbols", JSONArray().put(opportunity.symbol)).put("timeframes", JSONArray().put("H1")).put("risk_percent", 1.0).put("max_daily_loss_percent", 2.0).put("max_concurrent_positions", 2)
                                val result = requireNotNull(bridge).command(UUID.randomUUID().toString(), "START", config)
                                message = if (result.queued) "Scanning started for ${opportunity.symbol}; waiting for broker acknowledgement." else result.detail
                            } else {
                                val account = requireNotNull(derivAccount) { "Deriv account is not authenticated." }
                                require(derivMode.equals("demo", true) && account.mode.equals("demo", true) && account.accountType.equals("demo", true)) {
                                    "BOT NOT RUNNING — this foreground service accepts DEMO accounts only. REAL remains blocked here."
                                }
                                require(derivDemoAccepted) { "BOT NOT RUNNING — complete fresh DEMO verification and explicitly accept this exact account before Start." }
                                val unresolved = viewModel.app.db.derivExecutionDao().unresolved(account.loginId, "DEMO").size
                                require(viewModel.app.derivBrokerGateway.isAccountReadyForTrading(account, unresolved)) { "BOT NOT RUNNING — Deriv DEMO needs fresh authenticated balance, account-matched portfolio, and clear reconciliation." }
                                if (!DerivDemoBotService.hasNotificationPermission(context)) {
                                    val reason = "BOT NOT RUNNING — notification permission is required for the persistent foreground-service status."
                                    DerivDemoBotService.recordStartBlocked(context, reason)
                                    message = reason
                                    if (android.os.Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    return@launch
                                }
                                DerivDemoBotService.start(context, opportunity.symbol, viewModel.selectedStrategyId.value, "H1")
                                message = "DEMO start requested for ${opportunity.symbol}. The foreground service will re-verify the session, fresh risk data, and reconciliation before any entry."
                            }
                        } catch (e: Exception) {
                            val detail = listOf(derivToken, cTraderClientSecret).filter { it.isNotBlank() }
                                .fold(e.message ?: "Start blocked.") { safe, secret -> safe.replace(secret, "[redacted]") }
                                .take(240)
                            if (broker == BrokerType.DERIV) DerivDemoBotService.recordStartBlocked(context, detail)
                            message = detail
                        } finally { busy = false; commandPending = false }
                    }
                },
                onPause = {
                    scope.launch {
                        commandPending = true
                        try {
                            if (broker == BrokerType.DERIV) {
                                if (derivBotServiceState == "PAUSED") DerivDemoBotService.resume(context) else DerivDemoBotService.pause(context)
                                message = if (derivBotServiceState == "PAUSED") "DEMO resume requested; the service will continue only after its safety checks." else "DEMO paused; monitoring continues and no new entries will be submitted."
                            } else {
                                val result = requireNotNull(bridge).command(UUID.randomUUID().toString(), "PAUSE")
                                message = if (result.queued) "Pause requested; waiting for fresh broker state." else result.detail
                            }
                        } catch (e: Exception) { message = e.message?.take(240) ?: "Pause was not confirmed." }
                        finally { commandPending = false }
                    }
                },
                onStop = {
                    scope.launch {
                        busy = true; commandPending = true
                        try {
                            if (broker.usesManagedMt5Terminal) {
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
            title = { Text("Arm Deriv REAL for this account?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Requires a DEMO PASS for the same Deriv account ID, a fresh authenticated REAL session and quote, a clear daily-risk baseline, explicit re-authentication and the broker minimum stake. Lease: 60 minutes. No order is sent by arming.")
                    OutlinedTextField(value = derivLiveTypedConfirmation, onValueChange = { derivLiveTypedConfirmation = it }, label = { Text("Type ARM LIVE DERIV " + (derivAccount?.loginId ?: "")) }, singleLine = true)
                    OutlinedTextField(value = derivMinimumStake, onValueChange = { derivMinimumStake = it }, label = { Text("Broker minimum stake from contract details") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDerivRealArmDialog = false
                    scope.launch {
                        val account = derivAccount
                        try {
                            require(account != null && account.mode.equals("real", true) && account.accountType.equals("real", true)) { "Authenticated Deriv REAL account required." }
                            require(selectedDerivAccountId.isBlank() || selectedDerivAccountId == account.loginId) { "Selected account does not match the authenticated account." }
                            val unresolved = viewModel.app.db.derivExecutionDao().unresolved(account.loginId, "REAL").size
                            val ready = viewModel.app.derivBrokerGateway.isAccountReadyForTrading(account, unresolved)
                            val quote = viewModel.app.derivBrokerGateway.getQuote("R_100").getOrNull()
                            val now = System.currentTimeMillis()
                            val quoteFresh = quote?.timestampMs?.let { it > 0L && it <= now && now - it <= 15_000L } == true
                            val demoGeneration = secrets.getDerivCredentialGeneration("demo")
                            val demoIdentity = com.example.data.broker.ModeAccountIdentity(
                                com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", account.loginId, "",
                                com.example.data.broker.AccountMode.DEMO, demoGeneration
                            )
                            val liveIdentity = com.example.data.broker.ModeAccountIdentity(
                                com.example.data.broker.ModePath.DERIV_OPTIONS, "DERIV", account.loginId, "",
                                com.example.data.broker.AccountMode.LIVE, account.credentialGeneration
                            )
                            val demoPass = secrets.getPathDemoAcceptance(demoIdentity)
                            val verification = com.example.data.broker.ModeReadOnlyVerification(
                                liveIdentity, now,
                                authenticatedSession = derivState == com.example.data.deriv.DerivConnectionState.READY,
                                freshAccountData = ready,
                                freshQuote = quoteFresh,
                                freshHeartbeat = false,
                                accountIdentityMatched = account.loginId.isNotBlank() && account.mode.equals("real", true) &&
                                    account.accountType.equals("real", true) && secrets.getDerivCredentialGeneration("real") == account.credentialGeneration
                            )
                            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(now))
                            val dailyBaseline = secrets.getDerivDailyRiskStartingEquity(account.loginId, "REAL", day)
                            val minimumStake = derivMinimumStake.toDoubleOrNull()
                            val lease = viewModel.app.modeArmingPolicy.armLive(com.example.data.broker.LiveArmRequest(
                                identity = liveIdentity,
                                demoAcceptance = demoPass,
                                currentDemoCredentialGeneration = demoGeneration,
                                liveVerification = verification,
                                typedConfirmation = derivLiveTypedConfirmation,
                                ownerReauthenticated = AppSecurityGuard.authorizeSensitiveAction(),
                                firstMinimumSizeVerified = minimumStake != null && minimumStake.isFinite() && minimumStake > 0.0,
                                dailyRiskClear = ready && dailyBaseline != null && !secrets.isDerivDailyRiskLocked(account.loginId, "REAL", day),
                                riskCapsClear = com.example.domain.risk.TradingSafetyLimits.MAX_TRADE_RISK_PERCENT <= 1.0 &&
                                    com.example.domain.risk.TradingSafetyLimits.MIN_REWARD_TO_RISK >= 2.0
                            )).getOrElse { throw it }
                            if (!secrets.enableDerivRealExecution(true, account.loginId, account.accountType, account.currency, account.authenticatedSessionId, account.credentialGeneration)) {
                                viewModel.app.modeArmingPolicy.disarm(liveIdentity, "DERIV_SESSION_LEASE_FAILED")
                                throw IllegalStateException("Deriv's authenticated-session REAL lease could not be armed.")
                            }
                            derivLiveTypedConfirmation = ""
                            derivMinimumStake = ""
                            message = "Deriv REAL lease armed until " + java.text.SimpleDateFormat("HH:mm:ss z", java.util.Locale.US).format(java.util.Date(lease.expiresAtMs)) + ". No order was sent."
                        } catch (e: Exception) { message = e.message ?: "Deriv LIVE arming failed closed." }
                    }
                }) { Text("ARM REAL SESSION") }
            },
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
            title = { Text("Arm MT5 LIVE for this account?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Requires a DEMO PASS for the same broker and login, a fresh LIVE EA heartbeat, explicit re-authentication, typed confirmation and clear daily-risk and risk-cap evidence from the bridge. Lease: 15 minutes. The first minimum lot must be read from the broker's contract specification.")
                    OutlinedTextField(value = liveTypedConfirmation, onValueChange = { liveTypedConfirmation = it }, label = { Text("Type ARM LIVE " + selectedMt5Broker + " " + secrets.getManagedMT5AccountLogin(mt5Profile)) }, singleLine = true)
                    OutlinedTextField(value = liveMinimumLot, onValueChange = { liveMinimumLot = it }, label = { Text("Broker minimum lot from symbol specification") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLiveAuthorizationDialog = false
                    scope.launch {
                        busy = true
                        try {
                            val snapshot = requireNotNull(bridge).state()
                            val livePath = com.example.data.broker.ModePath.valueOf(selectedMt5Broker + "_MT5")
                            val liveIdentity = com.example.data.broker.ModeAccountIdentity(
                                livePath, selectedMt5Broker, snapshot.actualLogin, snapshot.actualServer,
                                com.example.data.broker.AccountMode.LIVE, secrets.getManagedMT5CredentialGeneration(mt5Profile)
                            )
                            val demoIdentity = com.example.data.broker.ModeAccountIdentity(
                                livePath, selectedMt5Broker,
                                secrets.getManagedMT5AccountLogin(selectedMt5Broker + "_DEMO"),
                                secrets.getManagedMT5AccountServer(selectedMt5Broker + "_DEMO"),
                                com.example.data.broker.AccountMode.DEMO,
                                secrets.getManagedMT5CredentialGeneration(selectedMt5Broker + "_DEMO")
                            )
                            val demoPass = secrets.getPathDemoAcceptance(demoIdentity)
                            val risk = snapshot.raw.optJSONObject("risk_controls")
                            val firstLot = liveMinimumLot.toDoubleOrNull()
                            val verification = com.example.data.broker.ModeReadOnlyVerification(
                                liveIdentity, System.currentTimeMillis(),
                                authenticatedSession = snapshot.connected && snapshot.terminalConnected,
                                freshAccountData = snapshot.snapshotsValid,
                                freshQuote = false,
                                freshHeartbeat = snapshot.heartbeatFresh,
                                accountIdentityMatched = snapshot.accountVerified && snapshot.brokerIdentityVerified &&
                                    snapshot.actualLogin == secrets.getManagedMT5AccountLogin(mt5Profile) &&
                                    snapshot.actualServer == secrets.getManagedMT5AccountServer(mt5Profile) &&
                                    snapshot.actualAccountMode == "LIVE" && snapshot.accessType == "MASTER"
                            )
                            val lease = viewModel.app.modeArmingPolicy.armLive(com.example.data.broker.LiveArmRequest(
                                identity = liveIdentity,
                                demoAcceptance = demoPass,
                                currentDemoCredentialGeneration = demoIdentity.credentialGeneration,
                                liveVerification = verification,
                                typedConfirmation = liveTypedConfirmation,
                                ownerReauthenticated = AppSecurityGuard.authorizeSensitiveAction(),
                                firstMinimumSizeVerified = firstLot != null && firstLot.isFinite() && firstLot > 0.0,
                                dailyRiskClear = risk?.optBoolean("daily_loss_clear", false) == true,
                                riskCapsClear = risk?.optBoolean("risk_caps_clear", false) == true,
                                hostProfile = snapshot.hostProfile
                            )).getOrElse { throw it }
                            try {
                                val expiry = requireNotNull(bridge).authorizeLiveSession()
                                liveAuthorizationExpiry = expiry
                                mt5Snapshot = bridge.state()
                                liveTypedConfirmation = ""
                                liveMinimumLot = ""
                                message = "MT5 LIVE local lease armed until " + lease.expiresAtMs + " and the bridge lease until " + expiry + "."
                            } catch (error: Exception) {
                                viewModel.app.modeArmingPolicy.disarm(liveIdentity, "BRIDGE_LIVE_AUTH_FAILED")
                                throw error
                            }
                        } catch (e: Exception) { message = e.message ?: "LIVE authorization failed closed." }
                        finally { busy = false }
                    }
                }) { Text("ARM LIVE · 15 MIN") }
            },
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
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, ProfitGreen.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VERIFIED CONNECTED ACCOUNT", color = ProfitGreen, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(if (broker.usesManagedMt5Terminal) "MetaTrader 5 · ${mt5Snapshot?.actualAccountMode ?: "unverified"}" else "Deriv · ${derivAccount?.mode?.uppercase() ?: "unverified"}", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            val accountId = if (broker.usesManagedMt5Terminal) mt5Snapshot?.actualLogin else derivAccount?.loginId
            Text("Account: ${maskAccountId(accountId)}", color = TextSecondary, fontSize = 11.sp)
            if (broker.usesManagedMt5Terminal) {
                Text("Server: ${mt5Snapshot?.actualServer ?: "unverified"} · Currency: ${mt5Snapshot?.currency ?: "unverified"}", color = TextSecondary, fontSize = 11.sp)
                Text("Balance: ${mt5Snapshot?.balance?.toString() ?: "unavailable"} · Equity: ${mt5Snapshot?.equity?.toString() ?: "unavailable"}", color = TextSecondary, fontSize = 11.sp)
                Text("Free margin: ${mt5Snapshot?.freeMargin?.toString() ?: "unavailable"} · Leverage: ${mt5Snapshot?.leverage?.toString() ?: "unavailable"}", color = TextSecondary, fontSize = 11.sp)
                Text("Terminal: ${if (mt5Snapshot?.terminalConnected == true) "CONNECTED" else "OFFLINE"} · EA heartbeat: ${if (mt5Snapshot?.heartbeatFresh == true) "FRESH" else "STALE"} · Trade permission: ${if (mt5Snapshot?.tradeAllowed == true) "ALLOWED" else "BLOCKED"}", color = TextSecondary, fontSize = 10.sp)
            } else {
                Text("Balance: ${derivAccount?.balance?.toString() ?: "unavailable"} · Currency: ${derivAccount?.currency ?: "unverified"}", color = TextSecondary, fontSize = 11.sp)
            }
            Text(if (running) "Scanning eligible markets" else "Connected and ready", color = if (running) ElectricCyan else ProfitGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            selectedOpportunity?.let { Text("Selected opportunity: ${it.symbol} · ${userFacingSignalLabel(it.signalState, it.direction)}", color = TextPrimary, fontSize = 11.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onStart, enabled = !running && !commandPending, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen, contentColor = DarkBackground)) { Icon(Icons.Default.PlayArrow, contentDescription = null); Text("START", fontWeight = FontWeight.Black) }
                OutlinedButton(onClick = onPause, enabled = running && !commandPending, modifier = Modifier.weight(1f)) { Text("PAUSE / RESUME", fontWeight = FontWeight.Black) }
                Button(onClick = onStop, enabled = running && !commandPending, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = LossRed, contentColor = Color.White)) { Icon(Icons.Default.Stop, contentDescription = null); Text("STOP", fontWeight = FontWeight.Black) }
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
