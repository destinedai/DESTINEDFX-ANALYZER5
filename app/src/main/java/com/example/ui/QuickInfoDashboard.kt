package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.broker.HostProfile
import com.example.data.deriv.DerivConnectionState
import com.example.data.trade.MT5RemoteBridgeClient
import com.example.security.AppSecurityGuard
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.LossRed
import com.example.ui.theme.TabBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

@Composable
fun QuickInfoDashboard(viewModel: DestinedFXViewModel) {
    val secrets = viewModel.app.secretsManager
    val brand = remember {
        secrets.getSelectedMT5BrokerBrand().uppercase()
            .takeIf { it in setOf("FBS", "EXNESS", "HFM", "DERIV") } ?: "FBS"
    }
    val profile = remember(brand) { "${brand}_DEMO" }
    val client = remember(brand) {
        MT5RemoteBridgeClient(
            secrets, profile, { AppSecurityGuard.authorizeSensitiveAction() },
            accountMode = "DEMO", brokerBrand = brand,
            modeArmingPolicy = viewModel.app.modeArmingPolicy,
            managementIdentityAssertionProvider = { viewModel.issueBridgeIdentityAssertion() }
        )
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(client) {
        viewModel.startManagedMt5BridgeMonitor(client, profile, "DEMO")
        viewModel.checkManagementApiHealth()
    }

    val snapshot by viewModel.managedMt5BridgeSnapshot.collectAsStateWithLifecycle()
    val hostHealth by viewModel.quickHostHealth.collectAsStateWithLifecycle()
    val hostError by viewModel.quickHostError.collectAsStateWithLifecycle()
    val telegramStatus by viewModel.telegramStatus.collectAsStateWithLifecycle()
    val lastTelegramMessage by viewModel.telegramLastMessageAtMs.collectAsStateWithLifecycle()
    val derivAccount by viewModel.app.derivBrokerGateway.accountInfo.collectAsStateWithLifecycle()
    val derivState by viewModel.app.derivBrokerGateway.connectionState.collectAsStateWithLifecycle()
    val derivMetrics by viewModel.app.derivBrokerGateway.accountMetrics.collectAsStateWithLifecycle()
    val derivMetricsAt by viewModel.app.derivBrokerGateway.accountMetricsUpdatedAtMs.collectAsStateWithLifecycle()

    var actionMessage by remember { mutableStateOf("") }
    var actionBusy by remember { mutableStateOf(false) }
    var confirmDemoOrder by remember { mutableStateOf(false) }

    val heartbeatAge = snapshot?.raw?.optLong("heartbeat_age_seconds", Long.MAX_VALUE) ?: Long.MAX_VALUE
    val heartbeatFresh = snapshot?.let {
        it.heartbeatFresh && it.terminalOnline && it.terminalConnected && heartbeatAge in 0..60
    } == true
    val balanceVerified = snapshot?.let {
        it.balance?.isFinite() == true && it.balance > 0.0 &&
            it.equity?.isFinite() == true && it.equity > 0.0
    } == true
    val expectedLogin = secrets.getManagedMT5AccountLogin(profile)
    val expectedServer = secrets.getManagedMT5AccountServer(profile)
    val paired = client.hasPairing() && snapshot?.let {
        it.connected && it.accountVerified && it.brokerIdentityVerified &&
            it.actualAccountMode == "DEMO" && it.actualLogin == expectedLogin &&
            it.actualServer == expectedServer && it.actualCompany.isNotBlank() &&
            it.accessType == "MASTER" && heartbeatFresh && balanceVerified
    } == true
    val bridgeReady = client.managedConfigured() && paired
    val lastHeartbeat = if (snapshot != null && heartbeatAge != Long.MAX_VALUE) {
        "${heartbeatAge}s ago"
    } else "No verified heartbeat"
    val balance = snapshot?.balance?.takeIf { paired && it.isFinite() && it > 0.0 }
    val equity = snapshot?.equity?.takeIf { paired && it.isFinite() && it > 0.0 }

    val derivFresh = derivMetricsAt > 0L && System.currentTimeMillis() - derivMetricsAt in 0..15_000L
    val derivDemoReady = derivState == DerivConnectionState.READY &&
        derivAccount?.mode.equals("demo", true) && derivAccount?.accountType.equals("demo", true) &&
        derivAccount?.demoVerified == true && derivFresh &&
        derivMetrics?.balance?.isFinite() == true && (derivMetrics?.balance ?: 0.0) > 0.0
    val derivBalance = derivMetrics?.balance?.takeIf { derivDemoReady && it.isFinite() && it > 0.0 }
    val derivEquity = derivMetrics?.equity?.takeIf { derivDemoReady && it.isFinite() && it > 0.0 }
    val pendingBridgeCommand = secrets.getMT5BridgeAccountPendingCommandId(profile)
    val demoOrderReady = paired && balanceVerified &&
        snapshot?.hostProfile == HostProfile.PERSISTENT && snapshot?.snapshotsValid == true &&
        pendingBridgeCommand.isBlank() && !actionBusy
    val lastMessage = if (lastTelegramMessage > 0L) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastTelegramMessage))
    } else "Never"

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, TabBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DESTINEDFX · QUICK INFO", color = TextPrimary, fontSize = 14.sp)
            Text(com.example.BuildConfig.APK_BUILD_LABEL, color = WarningAmber, fontSize = 10.sp)

            Text("PRODUCTION CONNECTION", color = TextPrimary, fontSize = 11.sp)
            DashboardLine("Management API /health", hostHealth)
            if (hostError.isNotBlank()) Text(hostError, color = LossRed, fontSize = 10.sp)

            Text("BRIDGE", color = TextPrimary, fontSize = 11.sp)
            DashboardLine("Status", if (bridgeReady) "READY" else "NOT READY")
            DashboardLine("URL", client.baseUrl().ifBlank { "Not configured" })
            DashboardLine("Last Heartbeat", lastHeartbeat)
            DashboardLine("MT5 Pairing", if (paired) "PAIRED" else "NOT PAIRED")

            Text("BROKER", color = TextPrimary, fontSize = 11.sp)
            DashboardLine("$brand DEMO", if (paired) "PAIRED" else "DISCONNECTED")
            DashboardLine("MT5 Account", if (paired) snapshot?.actualLogin.orEmpty() else "DISCONNECTED")
            DashboardLine("MT5 Server", if (paired) snapshot?.actualServer.orEmpty() else "DISCONNECTED")
            DashboardLine("MT5 Balance", balance?.let { String.format(Locale.US, "%.2f", it) } ?: "DISCONNECTED")
            DashboardLine("MT5 Equity", equity?.let { String.format(Locale.US, "%.2f", it) } ?: "DISCONNECTED")
            DashboardLine("Deriv DEMO", if (derivDemoReady) "CONNECTED" else "OFF")
            DashboardLine("Deriv Account", if (derivDemoReady) derivAccount?.loginId.orEmpty() else "DISCONNECTED")
            DashboardLine("Deriv Balance", derivBalance?.let { String.format(Locale.US, "%.2f", it) } ?: "DISCONNECTED")
            DashboardLine("Deriv Equity", derivEquity?.let { String.format(Locale.US, "%.2f", it) } ?: "DISCONNECTED")

            Text("TELEGRAM", color = TextPrimary, fontSize = 11.sp)
            DashboardLine("Status", if (telegramStatus == "Connected") "Connected" else "OFF")
            DashboardLine(
                "Last Message",
                if (lastTelegramMessage > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(lastTelegramMessage)) else "Never"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        viewModel.checkManagementApiHealth()
                        viewModel.selectScreen(DestinedFXScreen.Bot)
                    },
                    enabled = !actionBusy,
                    modifier = Modifier.weight(1f)
                ) { Text("START HOST", fontSize = 10.sp) }
                OutlinedButton(
                    onClick = { viewModel.selectScreen(DestinedFXScreen.Bot) },
                    enabled = hostHealth == "READY" && !actionBusy,
                    modifier = Modifier.weight(1f)
                ) { Text("TEST LOGIN", fontSize = 10.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { confirmDemoOrder = true },
                    enabled = demoOrderReady,
                    modifier = Modifier.weight(1f)
                ) { Text("TEST DEMO ORDER 0.01", fontSize = 9.sp) }
                OutlinedButton(
                    onClick = { viewModel.sendTelegramTestNotification() },
                    enabled = viewModel.getTelegramBotToken().isNotBlank() && !actionBusy,
                    modifier = Modifier.weight(1f)
                ) { Text("TEST TELEGRAM", fontSize = 10.sp) }
            }
            if (pendingBridgeCommand.isNotBlank()) {
                Text("Unresolved saved MT5 request $pendingBridgeCommand · open Bot to reconcile it; do not resend.", color = WarningAmber, fontSize = 10.sp)
            }
            if (!demoOrderReady) {
                Text(
                    "DEMO test blocked: requires identity-matched DEMO pairing, heartbeat under 60 seconds, positive verified balance/equity, complete snapshots and a persistent Windows host. LIVE is unavailable.",
                    color = WarningAmber,
                    fontSize = 10.sp
                )
            }
            if (actionMessage.isNotBlank()) Text(actionMessage, color = TextSecondary, fontSize = 10.sp)
            Text(
                "START HOST opens Bot > owner Host Controls. Codespaces is development-only; production execution requires the owner's always-on Windows MT5 host.",
                color = TextSecondary,
                fontSize = 10.sp
            )
        }
    }

    if (confirmDemoOrder) {
        AlertDialog(
            onDismissRequest = { if (!actionBusy) confirmDemoOrder = false },
            title = { Text("Run one 0.01 DEMO order and close it?") },
            text = {
                Text(
                    "One BUY market request will be sent to the selected persistent DEMO terminal. The app then attempts to close only the new broker-identified position. Fresh quote, verified SL/TP, EA risk and news checks are required. Ambiguous outcomes are never retried automatically. No LIVE order is available."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !actionBusy,
                    onClick = {
                        confirmDemoOrder = false
                        actionBusy = true
                        actionMessage = "Checking current DEMO evidence…"
                        scope.launch {
                            try {
                                actionMessage = runQuickDemoOrder(client, secrets, profile)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                actionMessage = "DEMO test blocked: ${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                actionBusy = false
                            }
                        }
                    }
                ) { Text("RUN DEMO TEST") }
            },
            dismissButton = { TextButton(onClick = { confirmDemoOrder = false }) { Text("CANCEL") } }
        )
    }
}

private suspend fun runQuickDemoOrder(
    client: MT5RemoteBridgeClient,
    secrets: com.example.security.SecretsManager,
    profile: String
): String {
    val before = client.state()
    val age = before.raw.optLong("heartbeat_age_seconds", Long.MAX_VALUE)
    require(before.hostProfile == HostProfile.PERSISTENT) { "persistent Windows host required" }
    require(before.connected && before.terminalOnline && before.terminalConnected &&
        before.heartbeatFresh && age in 0..60) { "MT5 heartbeat is stale or terminal is disconnected" }
    require(before.accountVerified && before.brokerIdentityVerified && before.actualCompany.isNotBlank()) {
        "broker identity is not verified"
    }
    require(before.actualAccountMode == "DEMO") { "only broker-reported DEMO mode is allowed" }
    require(before.actualLogin == secrets.getManagedMT5AccountLogin(profile) &&
        before.actualServer == secrets.getManagedMT5AccountServer(profile)) { "paired account/server identity mismatch" }
    require(before.accessType == "MASTER" && before.tradeAllowed && before.managedState == "READY") {
        "verified DEMO trade access and READY worker are required"
    }
    require(before.balance?.isFinite() == true && before.balance > 0.0 &&
        before.equity?.isFinite() == true && before.equity > 0.0) {
        "broker balance/equity is zero or unavailable; DISCONNECTED"
    }
    require(before.snapshotsValid && before.positions != null && before.positions.isEmpty() &&
        before.activeOrders != null && before.activeOrders.isEmpty()) {
        "complete empty position/order snapshot required to isolate a single test order"
    }
    require(secrets.getMT5BridgeAccountPendingCommandId(profile).isBlank() &&
        secrets.getMT5BridgeAccountPendingCommandAction(profile).isBlank()) {
        "an earlier bridge command is unresolved; reconcile it before testing"
    }

    val symbols = before.raw.optJSONObject("configuration")?.optJSONArray("symbols")
    val symbol = symbols?.takeIf { it.length() > 0 }?.optString(0).orEmpty()
    require(symbol.isNotBlank()) { "EA has no configured test symbol" }
    val quote = before.brokerQuotes?.singleOrNull { it.symbol == symbol }
        ?: error("no unambiguous MT5 quote for the first EA-configured symbol")
    require(quote.ageMs in 0..15_000L && quote.timestampMs > 0L) { "broker quote is stale" }

    val riskControls = before.raw.optJSONObject("risk_controls")
        ?: error("fresh EA risk controls are unavailable")
    val configuredRisk = riskControls.optDouble("risk_percent", Double.NaN)
    require(configuredRisk.isFinite() && configuredRisk > 0.0) { "EA risk cap is unavailable" }
    val risk = minOf(0.10, configuredRisk)
    val point = 10.0.pow(-quote.digits)
    val distance = max(100.0 * point, (quote.ask - quote.bid) * 8.0)
    val factor = 10.0.pow(quote.digits)
    val sl = round((quote.ask - distance) * factor) / factor
    val tp = round((quote.ask + distance * 2.0) * factor) / factor
    val config = MT5RemoteBridgeClient.marketOrderConfig(
        before, side = "BUY", stopLoss = sl, takeProfit = tp,
        riskPercent = risk, requestedVolume = 0.01
    )

    val orderRequestId = UUID.randomUUID().toString()
    require(secrets.saveMT5BridgeAccountPendingCommand(orderRequestId, "PLACE_MARKET_ORDER", profile)) {
        "Could not persist the one-shot order request ID; no order was sent"
    }
    val orderRequest = client.command(orderRequestId, "PLACE_MARKET_ORDER", config)
    if (orderRequest.rejected) {
        secrets.clearMT5BridgeAccountPendingCommand(profile)
        error(orderRequest.detail.ifBlank { "market order was explicitly rejected" })
    }
    require(orderRequest.queued) {
        orderRequest.detail.ifBlank { "order acknowledgement is ambiguous; request ID retained; do not resend" }
    }

    var opened: JSONObject? = null
    for (attempt in 0 until 45) {
        delay(1_000L)
        val current = client.state()
        val currentAge = current.raw.optLong("heartbeat_age_seconds", Long.MAX_VALUE)
        require(current.actualAccountMode == "DEMO" && current.actualLogin == before.actualLogin &&
            current.actualServer == before.actualServer && current.brokerIdentityVerified &&
            current.heartbeatFresh && currentAge in 0..60 && current.snapshotsValid) {
            "DEMO account/heartbeat changed while order was reconciled; do not resend"
        }
        val rows = current.positions ?: error("terminal position snapshot unavailable; do not resend")
        if (rows.isNotEmpty()) {
            require(rows.size == 1) { "more than one position appeared; refusing to guess which one to close" }
            opened = rows.single()
            break
        }
    }
    val position = opened ?: error("order outcome unresolved after 45 seconds; do not resend")
    val positionId = position.optString("position_id")
    require(positionId.matches(Regex("[1-9][0-9]{0,19}"))) {
        "new position has no canonical broker position ID; manual reconciliation required"
    }
    val ticket = position.optString("ticket").takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) } ?: positionId

    val beforeClose = client.state()
    require(beforeClose.positions?.singleOrNull()?.optString("position_id") == positionId &&
        beforeClose.actualAccountMode == "DEMO" && beforeClose.brokerIdentityVerified &&
        beforeClose.heartbeatFresh) {
        "new position identity changed before close; close was not submitted"
    }
    val closeConfig = MT5RemoteBridgeClient.actionConfig(
        beforeClose, "CLOSE_POSITION_SELECTED", listOf(positionId)
    )
    val closeRequestId = UUID.randomUUID().toString()
    require(secrets.saveMT5BridgeAccountPendingCommand(closeRequestId, "CLOSE_POSITION_SELECTED", profile)) {
        "Could not persist close request ID; position $ticket remains open for manual reconciliation"
    }
    val closeResult = client.command(closeRequestId, "CLOSE_POSITION_SELECTED", closeConfig)
    require(!closeResult.rejected && closeResult.queued) {
        closeResult.detail.ifBlank { "close acknowledgement is ambiguous; request ID retained; do not retry automatically" }
    }

    var closedDeal: JSONObject? = null
    for (attempt in 0 until 45) {
        delay(1_000L)
        val after = client.state()
        require(after.actualAccountMode == "DEMO" && after.actualLogin == before.actualLogin &&
            after.actualServer == before.actualServer && after.brokerIdentityVerified &&
            after.heartbeatFresh && after.snapshotsValid) {
            "terminal evidence changed while close was reconciled; do not resend"
        }
        if (after.positions?.any { it.optString("position_id") == positionId } == true) continue
        closedDeal = after.closedDeals?.lastOrNull { it.optString("position_id") == positionId }
        if (closedDeal != null) break
    }
    val deal = closedDeal ?: error("close unresolved; ticket $ticket requires manual reconciliation")
    val profit = deal.optDouble("profit", Double.NaN)
    val profitText = if (profit.isFinite()) String.format(Locale.US, "%.2f", profit) else "UNAVAILABLE"
    require(secrets.clearMT5BridgeAccountPendingCommand(profile)) {
        "Position closed, but encrypted pending-command state could not be cleared. Further test orders remain blocked."
    }
    return "DEMO test closed and reconciled · ticket $ticket · broker-reported deal profit $profitText"
}

@Composable
private fun DashboardLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.9f),
            color = TextSecondary,
            fontSize = 10.sp
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.1f),
            color = TextPrimary,
            fontSize = 10.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
