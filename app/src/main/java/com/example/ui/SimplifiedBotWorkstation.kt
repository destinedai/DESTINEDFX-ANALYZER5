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
    var broker by remember(activeBroker) { mutableStateOf(activeBroker) }
    var mt5Mode by remember { mutableStateOf("DEMO") }
    var selectedMt5Broker by remember { mutableStateOf("") }
    val mt5Profile = "${selectedMt5Broker}_${mt5Mode}"
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var accessType by remember { mutableStateOf("MASTER") }
    var derivToken by remember { mutableStateOf("") }
    var derivAppId by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var mt5Snapshot by remember { mutableStateOf<MT5RemoteBridgeClient.Snapshot?>(null) }
    var selectedOpportunity by remember { mutableStateOf<com.example.domain.model.TradeSignal?>(null) }
    var commandPending by remember { mutableStateOf(false) }
    val secrets = viewModel.app.secretsManager
    val bridge = remember(mt5Profile) { if (selectedMt5Broker in setOf("FBS", "EXNESS", "HFM", "DERIV")) MT5RemoteBridgeClient(secrets, mt5Profile, accountMode = mt5Mode, brokerBrand = selectedMt5Broker) else null }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(broker, mt5Profile) {
        if (selectedMt5Broker in setOf("FBS", "EXNESS", "HFM", "DERIV")) {
            login = secrets.getManagedMT5AccountLogin(mt5Profile)
            server = secrets.getManagedMT5AccountServer(mt5Profile)
            accessType = secrets.getManagedMT5AccountAccessType(mt5Profile)
            savePassword = secrets.isManagedMT5AccountPasswordSaveEnabled(mt5Profile)
            password = if (savePassword) secrets.getManagedMT5AccountPassword(mt5Profile) else ""
        }
        if (broker == BrokerType.MT5 && bridge?.managedConfigured() == true && bridge.hasPairing()) {
            while (true) {
                mt5Snapshot = runCatching { bridge.state() }.getOrNull()
                delay(5_000L)
            }
        } else {
            mt5Snapshot = null
        }
    }

    val connected = when (broker) {
        BrokerType.MT5 -> mt5Snapshot?.let { it.connected && it.terminalOnline && it.heartbeatFresh && it.accountVerified && it.brokerIdentityVerified } == true
        BrokerType.DERIV -> derivState == com.example.data.deriv.DerivConnectionState.READY
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
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("BROKER CONNECTION", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(BrokerType.MT5, BrokerType.DERIV).forEach { type ->
                        FilterChip(selected = broker == type, onClick = { broker = type; viewModel.selectBroker(type); message = null }, label = { Text(if (type == BrokerType.MT5) "MetaTrader 5" else "Deriv", fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ElectricCyan, selectedLabelColor = DarkBackground, containerColor = DarkSurfaceVariant, labelColor = TextSecondary))
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
                                val status = if (selected && connected) "Connected" else if (secrets.getMT5BridgeAccountAppToken(slot).isNotBlank()) "Paired" else if (saved) "Configured" else "Not configured"
                                FilterChip(selected = selected, onClick = {
                                    selectedMt5Broker = brand; mt5Mode = mode
                                    secrets.saveSelectedMT5BrokerBrand(brand)
                                    broker = BrokerType.MT5; viewModel.selectBroker(BrokerType.MT5); message = null
                                }, label = { Text("$mode · $status", fontSize = 9.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = if (mode == "LIVE") WarningAmber else ElectricCyan, selectedLabelColor = DarkBackground, containerColor = DarkSurfaceVariant, labelColor = TextSecondary))
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = savePassword, onCheckedChange = { savePassword = it })
                        Text("Save password in encrypted device storage", color = TextSecondary, fontSize = 10.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = consent, onCheckedChange = { consent = it })
                        Text("I consent to send these credentials to the configured HTTPS bridge", color = TextSecondary, fontSize = 10.sp)
                    }
                } else {
                    LoginField("Deriv API token", derivToken, { derivToken = it }, KeyboardType.Password, true)
                    LoginField("Application ID", derivAppId, { derivAppId = it.filter(Char::isDigit) }, KeyboardType.Number)
                    Text("Deriv uses token authentication, not an MT5 login. Account mode and trading permissions are verified by the broker.", color = TextSecondary, fontSize = 10.sp)
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
                                    secrets.saveDerivApiToken(derivToken)
                                    secrets.saveDerivAppId(derivAppId)
                                    val result = viewModel.app.derivBrokerGateway.connectAndAuthorize(derivToken)
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
                                val config = JSONObject().put("strategy", viewModel.selectedStrategyId.value).put("symbols", JSONArray().put(opportunity.symbol)).put("timeframes", JSONArray().put("H1")).put("risk_percent", 1.0).put("max_daily_loss_percent", 2.0).put("max_concurrent_positions", 2)
                                val result = requireNotNull(bridge).command(UUID.randomUUID().toString(), "START", config)
                                message = if (result.queued) "Scanning started for ${opportunity.symbol}; waiting for broker acknowledgement." else result.detail
                            } else {
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
