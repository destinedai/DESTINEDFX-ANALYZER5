package com.example.ui.workstations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.data.codespaces.CodespacesLifecycleClient
import com.example.data.trade.MT5RemoteBridgeClient
import com.example.security.SecretsManager
import kotlinx.coroutines.launch

@Composable
fun CodespacesHostControls(
    secrets: SecretsManager,
    bridgeClient: MT5RemoteBridgeClient,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val github = remember(secrets) { CodespacesLifecycleClient(secrets) }
    var clientId by remember { mutableStateOf(secrets.getCodespacesGitHubClientId()) }
    var device by remember { mutableStateOf<CodespacesLifecycleClient.DeviceAuthorization?>(null) }
    var message by remember { mutableStateOf("Connect the GitHub App once to control the DestinedFX Codespace from this phone.") }
    var busy by remember { mutableStateOf(false) }
    var authorized by remember { mutableStateOf(secrets.getCodespacesGitHubAccessToken().isNotBlank()) }
    var deviceCodeExpiresAt by remember { mutableStateOf(0L) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            "CODESPACES HOST · PHONE CONTROL",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
        )
        Text(
            "Owner-only GitHub control. The GitHub App must enable Device Flow and grant Codespaces read plus Codespaces lifecycle admin write. Only this repository's Codespace is selectable.",
            fontSize = 10.sp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("GitHub App Client ID (public)") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy && secrets.isSecureStorageAvailable,
                onClick = {
                    if (secrets.saveCodespacesGitHubClientId(clientId)) {
                        message = "GitHub App Client ID saved in encrypted app storage."
                    } else {
                        message = "Client ID was not saved. Check the value and secure storage."
                    }
                }
            ) { Text("SAVE CLIENT ID", fontSize = 9.sp) }
            Button(
                enabled = !busy && clientId.isNotBlank() && secrets.isSecureStorageAvailable,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            check(secrets.saveCodespacesGitHubClientId(clientId)) {
                                "Save a valid GitHub App Client ID first."
                            }
                            val result = github.beginDeviceAuthorization()
                            device = result
                            deviceCodeExpiresAt = System.currentTimeMillis() + result.expiresInSeconds * 1000L
                            message = "Enter the one-time code at GitHub, approve the requested Codespaces permissions, then tap CHECK AUTHORIZATION."
                        } catch (error: Exception) {
                            message = error.message ?: "GitHub device authorization could not start."
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("AUTHORIZE GITHUB", fontSize = 9.sp) }
        }

        device?.let { request ->
            Text("One-time code: " + request.userCode, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    runCatching { uriHandler.openUri(request.verificationUri) }
                        .onFailure { message = "Open github.com/login/device in your browser and enter the code above." }
                }
            ) { Text("OPEN GITHUB DEVICE PAGE") }
            OutlinedButton(
                enabled = !busy && System.currentTimeMillis() < deviceCodeExpiresAt,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val result = github.checkDeviceAuthorization(request.deviceCode)
                            message = result.message
                            authorized = result.authorized
                            if (result.authorized) device = null
                        } catch (error: Exception) {
                            device = null
                            message = error.message ?: "GitHub authorization could not be completed."
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("CHECK AUTHORIZATION") }
            Text("The code expires in about " + (request.expiresInSeconds / 60L) + " minute(s).", fontSize = 9.sp)
        }

        Text(
            if (authorized) "GitHub owner token: saved securely on this device"
            else "GitHub owner token: not connected",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && authorized,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val result = github.startRepositoryCodespace()
                            message = result.message + " Codespace: " + result.codespaceName
                            authorized = true
                        } catch (error: Exception) {
                            message = error.message ?: "Codespaces START was not confirmed."
                            authorized = secrets.getCodespacesGitHubAccessToken().isNotBlank()
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("START HOST", fontSize = 10.sp) }
            OutlinedButton(
                enabled = !busy && authorized && bridgeClient.managedConfigured(),
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val preflight = bridgeClient.prepareHostStop()
                            check(preflight.optString("status") == "PREPARED" &&
                                preflight.optBoolean("backup_verified", false)) {
                                "Bridge did not confirm a safe, backed-up shutdown."
                            }
                            val stopped = github.stopRepositoryCodespace()
                            message = stopped.message + " Verified backup: " + preflight.optString("backup_name")
                        } catch (error: Exception) {
                            message = error.message ?: "STOP HOST failed; the Codespace was not reported as stopped."
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("STOP HOST", fontSize = 10.sp) }
        }
        OutlinedButton(
            enabled = !busy && authorized,
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        val status = bridgeClient.hostStatus()
                        message = "Host status: " + status.optString("status", "UNKNOWN") +
                            " · lifecycle: " + status.optString("host_lifecycle_status", "UNKNOWN")
                    } catch (error: Exception) {
                        message = error.message ?: "Host status is unavailable."
                    } finally {
                        busy = false
                    }
                }
            }
        ) { Text("CHECK HOST STATUS", fontSize = 9.sp) }
        OutlinedButton(
            enabled = !busy && authorized,
            onClick = {
                scope.launch {
                    busy = true
                    github.revokeLocalAuthorization()
                    authorized = false
                    message = "GitHub tokens cleared from this phone. Revoke the GitHub App authorization separately in GitHub Settings."
                    busy = false
                }
            }
        ) { Text("CLEAR SAVED GITHUB TOKENS", fontSize = 9.sp) }
        Text(message, fontSize = 10.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
