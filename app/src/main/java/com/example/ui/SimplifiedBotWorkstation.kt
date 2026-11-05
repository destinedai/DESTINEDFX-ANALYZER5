package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    var showDerivRealArmDialog by remember { mutableStateOf(false) }
    var savePassword by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var serviceUrl by remember { mutableStateOf("") }
    var serviceReadiness by remember { mutableStateOf<MT5RemoteBridgeClient.ServiceReadiness?>(null) }
    var mt5Snapshot by remember { mutableStateOf<MT5RemoteBridgeClient.Snapshot?>(null) }
    var showPairingRevokeDialog by remember(mt5Profile) { mutableStateOf(false) }
    var showLiveAuthorizationDialog by remember(mt5Profile) { mutableStateOf(false) }
    var liveAuthorizationExpiry by remember(mt5Profile) { mutableStateOf("") }
    var selectedOpportunity by remember { mutableStateOf<com.example.domain.model.TradeSignal?>(null) }
    var commandPending by remember { mutableStateOf(false) }
    val secrets = viewModel.app.secretsManager
    LaunchedEffect(secrets) {
        serviceUrl = secrets.getManagedMT5ServiceUrl()
        derivMode = secrets.getDerivAccountMode()
        derivToken = secrets.getDerivApiToken(derivMode)
        derivAppId = secrets.getDerivAppId()
    }
    val bridge = remember(mt5Profile) { if (selectedMt5Broker in setOf("FBS", "EXNESS", "HFM", "DERIV")) MT5RemoteBridgeClient(secrets, mt5Profile, { AppSecurityGuard.authorizeSensitiveAction() }, accountMode = mt5Mode, brokerBrand = selectedMt5Broker) else null }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val selectMt5Slot: (String, String) -> Unit = { brand, mode ->
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
            it.connected && it.workerStatus == "READY" && it.terminalOnline && it.heartbeatFresh && it.accountVerified && it.brokerIdentityVerified &&
                it.actualAccountMode == mt5Mode && it.actualLogin == secrets.getManagedMT5AccountLogin(mt5Profile) &&
                it.actualServer == secrets.getManagedMT5AccountServer(mt5Profile)
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

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DESTINEDFX BOT", color = Color(0xFFE5B85C), fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("Connect an account, then let the engine scan eligible markets automatically.", color = TextSecondary, fontSize = 11.sp)

        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CardBorder)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MANAGED HTTPS BRIDGE", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                OutlinedTextField(value = serviceUrl, onValueChange = { serviceUrl = it }, label = { Text("Owner-managed HTTPS origin") }, placeholder = { Text("https://your-host.example") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
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
                Text("Service: ${serviceReadiness?.serviceStatus ?: if (MT5RemoteBridgeClient.validServiceOrigin(serviceUrl).isNullOrBlank()) "CONFIGURATION_REQUIRED" else "not checked"} · HTTP ${serviceReadiness?.httpStatus ?: "—"}", color = WarningAmber, fontSize = 10.sp)
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
                                FilterChip(selected = selected, onClick = { selectMt5Slot(brand, mode) }, label = { Text("$mode · $status", fontSize = 9.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = if (mode == "LIVE") WarningAmber else ElectricCyan, selectedLabelColor = DarkBackground, containerColor = DarkSurfaceVariant, labelColor = TextSecondary))
                            }
                        }
                    }
                    Text("Broker selection is not identity proof. Use the exact server assigned to this MT5 account; verify terminal-reported company separately.", color = WarningAmber, fontSize = 9.sp)
                    LoginField("Login", login, { login = it.filter(Char::isDigit) }, KeyboardType.Number)
                    LoginField("Password", password, { password = it }, KeyboardType.Password, true)
                    LoginField("Server", server, { server = it }, KeyboardType.Text)
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
                        Text("Fresh terminal heartbeat: ${snapshot?.heartbeatFresh == true} · broker identity: ${snapshot?.brokerIdentityVerified == true} · service state: ${snapshot?.managedState ?: "unknown"} · LIVE authorization: ${if (snapshot?.liveAuthorized == true) "ACTIVE" else "inactive"}", color = if (snapshot?.liveAuthorized == true) WarningAmber else TextSecondary, fontSize = 10.sp)
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
                    Text("PATs are encrypted and stored separately by account mode. REAL selection alone does not authorize trading; no REAL session restarts automatically after process death.", color = WarningAmber, fontSize = 10.sp)
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
            Text("CONNECTED ACCOUNT", color = ProfitGreen, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(if (broker == BrokerType.MT5) "MetaTrader 5 · ${mt5Snapshot?.actualAccountMode ?: "unverified"}" else "Deriv · ${derivAccount?.mode?.uppercase() ?: "unverified"}", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Account: ${if (broker == BrokerType.MT5) mt5Snapshot?.actualLogin ?: "unverified" else derivAccount?.loginId ?: "unverified"}", color = TextSecondary, fontSize = 11.sp)
            Text("Balance: ${if (broker == BrokerType.MT5) mt5Snapshot?.balance ?: accountMetrics?.balance ?: Double.NaN else derivAccount?.balance ?: Double.NaN}", color = TextSecondary, fontSize = 11.sp)
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
