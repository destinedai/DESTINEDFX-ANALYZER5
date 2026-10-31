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
import com.example.ui.DestinedFXViewModel
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LossRed
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.launch

@Composable
fun DerivDemoAccountCard(viewModel: DestinedFXViewModel) {
    val secrets = viewModel.app.secretsManager
    val gateway = viewModel.app.derivBrokerGateway
    val connection by gateway.connectionState.collectAsStateWithLifecycle()
    val account by gateway.accountInfo.collectAsStateWithLifecycle()
    val availableAccounts by gateway.availableAccounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var appId by remember { mutableStateOf(secrets.getDerivAppId()) }
    var selectedMode by remember { mutableStateOf(secrets.getDerivAccountMode()) }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var realArmed by remember { mutableStateOf(secrets.isDerivRealExecutionEnabled()) }
    var selectedAccountId by remember { mutableStateOf("") }
    val gatewayReady = connection == DerivConnectionState.READY

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
                "Account: ${account?.loginId ?: "NOT VERIFIED"} · ${if (account != null) "${account?.mode?.uppercase()} VERIFIED" else "NOT CONNECTED"} · Connection: ${connection.name}",
                color = if (account != null && gatewayReady) ElectricCyan else WarningAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            if (account != null) {
                Text("Broker-returned currency: ${account?.currency} · balance snapshot: ${account?.balance}", color = TextPrimary, fontSize = 10.sp)
                if (account?.mode == "real") {
                    Text(
                        if (realArmed) "LIVE ARM: ARMED for ${account?.loginId}" else "LIVE ARM: DISARMED — connection alone cannot place REAL orders",
                        color = if (realArmed) LossRed else WarningAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        enabled = !busy && !realArmed,
                        onClick = {
                            realArmed = account?.let {
                                secrets.enableDerivRealExecution(
                                    explicitConfirmation = true,
                                    accountId = it.loginId,
                                    accountType = it.accountType,
                                    currency = it.currency,
                                    authenticatedSessionId = it.authenticatedSessionId,
                                    credentialGeneration = it.credentialGeneration
                                )
                            } == true
                            message = if (realArmed) "LIVE TRADING ARMED for ${account?.loginId}. Start still requires the verified account shown above." else "Live arming failed; no REAL order can be sent."
                        },
                        modifier = Modifier.fillMaxWidth().testTag("deriv_arm_live")
                    ) { Text(if (realArmed) "LIVE TRADING ARMED" else "ARM LIVE TRADING FOR ${account?.loginId}") }
                }
            }
            if (!secrets.isSecureStorageAvailable) {
                Text("CONNECT BLOCKED — Android encrypted storage is unavailable. Credentials will not be saved or sent.", color = LossRed, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            } else if (!gatewayReady) {
                Text("ACCOUNT PROFILE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { selectedMode = "demo"; realArmed = false; secrets.disableDerivRealExecution() },
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
                                if (!secrets.saveDerivApiToken(token) || !secrets.saveDerivAppId(appId) || !secrets.saveDerivAccountMode(selectedMode)) {
                                    message = "Credentials could not be saved securely; no connection was attempted."
                                } else {
                                    if (selectedMode == "demo") {
                                        secrets.disableDerivRealExecution()
                                        realArmed = false
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
                        realArmed = false
                        secrets.disableDerivRealExecution()
                        message = "Deriv connection closed. Stored credentials remain encrypted on this device."
                    },
                    modifier = Modifier.fillMaxWidth().testTag("deriv_demo_disconnect")
                ) { Text("DISCONNECT") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        gateway.disconnect()
                        realArmed = false
                        secrets.disableDerivRealExecution()
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
