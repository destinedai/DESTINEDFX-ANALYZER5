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
    val scope = rememberCoroutineScope()
    var appId by remember { mutableStateOf(secrets.getDerivAppId()) }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

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
                color = if (account != null && connection == DerivConnectionState.CONNECTED) ElectricCyan else WarningAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            if (account != null) {
                Text("Broker-returned currency: ${account?.currency} · balance snapshot: ${account?.balance}", color = TextPrimary, fontSize = 10.sp)
            }
            if (!secrets.isSecureStorageAvailable) {
                Text("CONNECT BLOCKED — Android encrypted storage is unavailable. Credentials will not be saved or sent.", color = LossRed, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            } else if (connection != DerivConnectionState.CONNECTED) {
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
                    label = { Text("Deriv Personal Access Token") },
                    placeholder = { Text("Trade scope; use Demo account only") },
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
                Text("Create a Deriv PAT for your registered app and grant only the scopes required to read the account and trade. DEMO is the default. Do not paste a password or use a REAL account token here.", color = TextSecondary, fontSize = 9.sp)
            }
            if (connection != DerivConnectionState.CONNECTED) {
                Button(
                    enabled = !busy && secrets.isSecureStorageAvailable && appId.isNotBlank() && token.isNotBlank(),
                    onClick = {
                        busy = true
                        message = ""
                        scope.launch {
                            try {
                                if (!secrets.saveDerivApiToken(token) || !secrets.saveDerivAppId(appId) || !secrets.saveDerivAccountMode("demo")) {
                                    message = "Credentials could not be saved securely; no connection was attempted."
                                } else {
                                    viewModel.selectBroker(BrokerType.DERIV)
                                    val result = gateway.connectAndAuthorize()
                                    message = result.fold(
                                        onSuccess = { "Deriv returned an active Demo account. Account connection is separate from bot state." },
                                        onFailure = { error ->
                                            val safe = (error.message ?: "Deriv verification failed")
                                                .replace(token, "[redacted]")
                                                .take(240)
                                            "Demo verification failed: $safe"
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
                ) { Text(if (busy) "VERIFYING DEMO ACCOUNT…" else "VERIFY DEMO ACCOUNT") }
            } else {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        gateway.disconnect()
                        message = "Deriv connection closed. Stored credentials remain encrypted on this device."
                    },
                    modifier = Modifier.fillMaxWidth().testTag("deriv_demo_disconnect")
                ) { Text("DISCONNECT") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        gateway.disconnect()
                        val cleared = secrets.clearDerivCredentials()
                        message = if (cleared) "Deriv disconnected and local API credentials deleted." else "Disconnected, but encrypted credential deletion could not be confirmed."
                    },
                    modifier = Modifier.fillMaxWidth().testTag("deriv_demo_delete_credentials")
                ) { Text("DISCONNECT & DELETE LOCAL CREDENTIALS") }
            }
            if (message.isNotBlank()) Text(message, color = if (message.contains("failed", true) || message.contains("could not", true)) LossRed else TextSecondary, fontSize = 10.sp)
            Text(
                "Safety status: DEMO is the default and only enabled profile in this UI. The connector keeps account ID and mode separate and supports only validated MULTUP/MULTDOWN contracts. REAL execution remains disabled pending independent acceptance; unsupported Forex/CFD plans remain blocked.",
                color = WarningAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
            Text("Multipliers are not conventional Forex/CFD positions and are not Deriv MT5 execution. Android/network availability cannot guarantee continuous operation.", color = TextSecondary, fontSize = 9.sp)
        }
    }
}
