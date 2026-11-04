package com.example.ui.workstations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.broker.BrokerType
import com.example.data.deriv.DerivConnectionState
import com.example.data.deriv.DerivExecutionEntity
import com.example.ui.DestinedFXViewModel
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LossRed
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

@Composable
fun DerivDemoAccountCard(viewModel: DestinedFXViewModel) {
    val secrets = viewModel.app.secretsManager
    val gateway = viewModel.app.derivBrokerGateway
    val connection by gateway.connectionState.collectAsStateWithLifecycle()
    val account by gateway.accountInfo.collectAsStateWithLifecycle()
    val accountSnapshot = account
    val accountMetrics by gateway.accountMetrics.collectAsStateWithLifecycle()
    val availableAccounts by gateway.availableAccounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var appId by remember { mutableStateOf(secrets.getDerivAppId()) }
    var selectedMode by remember { mutableStateOf(secrets.getDerivAccountMode()) }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selectedAccountId by remember { mutableStateOf("") }
    var readinessNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var armStatusRevision by remember { mutableStateOf(0) }
    val executionRecordsFlow = remember(viewModel.app.db) {
        viewModel.app.db.derivExecutionDao().observeAll()
            .map<List<DerivExecutionEntity>, List<DerivExecutionEntity>?> { it }
    }
    val executionRecords by executionRecordsFlow.collectAsStateWithLifecycle(initialValue = null)
    val unresolvedStatuses = setOf("ORDER_SUBMITTING", "ORDER_PENDING", "EXECUTION_UNKNOWN", "RECONCILIATION_REQUIRED")
    val unresolvedExecutionCount = executionRecords?.count { record ->
            record.accountId == "LEGACY_UNKNOWN" ||
                (accountSnapshot != null && record.accountId == accountSnapshot.loginId &&
                    record.accountMode.equals(accountSnapshot.mode, ignoreCase = true) && record.status in unresolvedStatuses)
        } ?: 1
    val gatewayReady = connection == DerivConnectionState.READY
    LaunchedEffect(accountSnapshot?.loginId, connection) {
        if (accountSnapshot != null) {
            while (true) {
                readinessNowMs = System.currentTimeMillis()
                if (connection == DerivConnectionState.READY) {
                    try {
                        gateway.fetchPortfolio()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The readiness predicate remains false until a successful fresh snapshot arrives.
                    }
                }
                readinessNowMs = System.currentTimeMillis()
                delay(5_000L)
            }
        }
    }
    val accountReady = gateway.isAccountReadyForTrading(accountSnapshot, unresolvedExecutionCount, readinessNowMs)
    val realArmed = remember(accountSnapshot, readinessNowMs, armStatusRevision) {
        accountSnapshot?.takeIf { it.mode == "real" }?.let {
            secrets.isDerivRealExecutionAuthorized(
                it.loginId, it.accountType, it.currency, it.authenticatedSessionId, it.credentialGeneration,
                nowMs = readinessNowMs
            )
        } == true
    }
    val accountStatus = when {
        accountSnapshot == null -> "NOT VERIFIED"
        accountSnapshot != null && accountReady -> "${accountSnapshot.mode.uppercase()} VERIFIED"
        connection in setOf(DerivConnectionState.ERROR, DerivConnectionState.AUTH_FAILED, DerivConnectionState.DISCONNECTED) -> "NOT READY"
        else -> "SYNCHRONIZING"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("deriv_demo_account_card")
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AccountBalance, contentDescription = null, tint = ElectricCyan)
                Column {
                    Text("DERIV API — MULTIPLIER ACCOUNT", color = ElectricCyan, fontWeight = FontWeight.Black, fontSize = 12.sp)
                    Text("Direct Deriv Options API · account-specific authenticated endpoint", color = TextSecondary, fontSize = 10.sp)
                }
            }
            Text(
                "Account: ${accountSnapshot?.loginId ?: "NOT VERIFIED"} · $accountStatus · Connection: ${connection.name}",
                color = if (accountReady) ElectricCyan else WarningAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            if (accountSnapshot != null) {
                Text(
                    "Broker-returned currency: ${accountSnapshot.currency} · ${if (accountReady) "fresh balance ${accountMetrics?.balance}" else "initial balance ${accountSnapshot.balance}"} · ${if (accountReady) "portfolio READY" else "not yet ready for trading"}",
                    color = TextPrimary,
                    fontSize = 10.sp
                )
                if (accountSnapshot.mode == "real") {
                    Text(
                        if (realArmed) "LIVE ARM: ARMED for ${accountSnapshot.loginId} · 60-MINUTE LEASE" else "LIVE ARM: DISARMED — connection alone cannot place REAL orders",
                        color = if (realArmed) LossRed else WarningAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        enabled = !busy && accountReady && !realArmed,
                        onClick = {
                            val currentAccount = accountSnapshot
                            val isStillReady = gateway.isAccountReadyForTrading(
                                currentAccount, unresolvedExecutionCount, System.currentTimeMillis()
                            )
                            val armed = currentAccount != null && isStillReady && secrets.enableDerivRealExecution(
                                explicitConfirmation = true,
                                accountId = currentAccount.loginId,
                                accountType = currentAccount.accountType,
                                currency = currentAccount.currency,
                                authenticatedSessionId = currentAccount.authenticatedSessionId,
                                credentialGeneration = currentAccount.credentialGeneration
                            )
                            armStatusRevision++
                            message = when {
                                armed -> "LIVE TRADING ARMED for ${currentAccount?.loginId}. Authorization expires after 60 minutes; re-arm explicitly."
                                !isStillReady -> "Live arming blocked — wait for fresh authenticated balance/portfolio readiness and resolve any pending execution."
                                else -> "Live arming failed; no REAL order can be sent."
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("deriv_arm_live")
                    ) { Text(if (realArmed) "LIVE TRADING ARMED" else "ARM LIVE TRADING FOR ${accountSnapshot.loginId}") }
                    if (!accountReady) {
                        Text(
                            if (unresolvedExecutionCount > 0) "SYNCHRONIZING — resolve the pending or uncertain execution before live arming."
                            else "SYNCHRONIZING — arming waits for a current authenticated session, fresh matching balance, successful fresh portfolio and READY state.",
                            color = WarningAmber,
                            fontSize = 9.sp
                        )
                    }
                }
            }
            if (!secrets.isSecureStorageAvailable) {
                Text("CONNECT BLOCKED — Android encrypted storage is unavailable. Credentials will not be saved or sent.", color = LossRed, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            } else if (!gatewayReady) {
                Text("ACCOUNT PROFILE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { selectedMode = "demo"; secrets.disableDerivRealExecution(); armStatusRevision++ },
                        modifier = Modifier.weight(1f).testTag("deriv_select_demo")
                    ) { Text("DEMO") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { selectedMode = "real" },
                        modifier = Modifier.weight(1f).testTag("deriv_select_real")
                    ) { Text("REAL") }
                }
                OutlinedTextField(
                    value = appId,
                    onValueChange = { appId = it.filter(Char::isDigit).take(20) },
                    label = { Text("Deriv App ID") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("deriv_demo_app_id")
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Deriv API key / Personal Access Token") },
                    placeholder = { Text("Trade scope for the selected account profile") },
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("deriv_demo_token"),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (showToken) "Hide token" else "Show token")
                        }
                    }
                )
                if (availableAccounts.isNotEmpty()) {
                    Text("SELECT EXACT BROKER ACCOUNT", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    availableAccounts.filter { it.accountType == selectedMode }.forEach { option ->
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { selectedAccountId = option.accountId },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${if (selectedAccountId == option.accountId) "✓ " else ""}${option.accountId} · ${option.accountType.uppercase()} · ${option.currency} · balance ${option.balance}",
                                color = if (selectedAccountId == option.accountId) ElectricCyan else TextPrimary
                            )
                        }
                    }
                }
                Text("Create a Deriv PAT for your registered app and grant only the scopes required to read the account and trade. Selecting REAL connects only; live orders remain blocked until you explicitly arm the exact broker account.", color = TextSecondary, fontSize = 9.sp)
            }
            if (!gatewayReady) {
                Button(
                    enabled = !busy && secrets.isSecureStorageAvailable && appId.isNotBlank() && token.isNotBlank(),
                    onClick = {
                        busy = true
                        message = ""
                        scope.launch {
                            try {
                                if (!secrets.saveDerivApiToken(token, selectedMode) || !secrets.saveDerivAppId(appId) || !secrets.saveDerivAccountMode(selectedMode)) {
                                    message = "Credentials could not be saved securely; no connection was attempted."
                                } else {
                                    if (selectedMode == "demo") {
                                        secrets.disableDerivRealExecution()
                                        armStatusRevision++
                                    }
                                    viewModel.selectBroker(BrokerType.DERIV)
                                    val result = gateway.connectAndAuthorize(selectedAccountId = selectedAccountId.ifBlank { null })
                                    message = result.fold(
                                        onSuccess = { info -> "Deriv returned the active ${info.mode.uppercase()} account ${info.loginId}. Connection is separate from live arming and bot state." },
                                        onFailure = { error ->
                                            val safe = (error.message ?: "Deriv verification failed")
                                                .replace(token, "[redacted]")
                                                .take(240)
                                            "${selectedMode.uppercase()} verification failed: $safe"
                                        }
                                    )
                                }
                            } finally {
                                token = ""
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("deriv_demo_connect")
                ) { Text(if (busy) "VERIFYING ${selectedMode.uppercase()} ACCOUNT…" else "VERIFY ${selectedMode.uppercase()} ACCOUNT") }
            } else {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        gateway.disconnect()
                        secrets.disableDerivRealExecution()
                        armStatusRevision++
                        message = "Deriv connection closed. Stored credentials remain encrypted on this device."
                    },
                    modifier = Modifier.fillMaxWidth().testTag("deriv_demo_disconnect")
                ) { Text("DISCONNECT") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        gateway.disconnect()
                        secrets.disableDerivRealExecution()
                        armStatusRevision++
                        val cleared = secrets.clearDerivCredentials()
                        message = if (cleared) "Deriv disconnected and local API credentials deleted." else "Disconnected, but encrypted credential deletion could not be confirmed."
                    },
                    modifier = Modifier.fillMaxWidth().testTag("deriv_demo_delete_credentials")
                ) { Text("DISCONNECT & DELETE LOCAL CREDENTIALS") }
            }
            if (message.isNotBlank()) Text(message, color = if (message.contains("failed", true) || message.contains("could not", true)) LossRed else TextSecondary, fontSize = 10.sp)
            Text(
                "Safety status: DEMO is the default profile. REAL connection and REAL live arming are separate. The connector keeps broker account ID/type/mode separate and supports only validated MULTUP/MULTDOWN contracts; unsupported Forex/CFD plans remain blocked.",
                color = WarningAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
            Text("Multipliers are not conventional Forex/CFD positions and are not Deriv MT5 execution. Android/network availability cannot guarantee continuous operation.", color = TextSecondary, fontSize = 9.sp)
        }
    }
}
