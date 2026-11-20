package com.example.ui.workstations

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.codespaces.CodespacesLifecycleClient
import com.example.data.codespaces.deviceFlowPollIntervalSeconds
import com.example.data.codespaces.nextDeviceFlowPollIntervalSeconds
import com.example.data.codespaces.isSafeHostStopPreflight
import com.example.data.codespaces.codespacesBridgeConfigurationAllowsOriginSave
import com.example.data.codespaces.codespacesBridgeIsolationNotice
import com.example.data.trade.MT5RemoteBridgeClient
import com.example.security.SecretsManager
import kotlinx.coroutines.launch

@Composable
fun CodespacesHostControls(
    secrets: SecretsManager,
    bridgeClient: MT5RemoteBridgeClient,
    modifier: Modifier = Modifier,
    onBridgeOriginDiscovered: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val github = remember(secrets) { CodespacesLifecycleClient(secrets) }
    val packagedClientId = BuildConfig.CODESPACES_GITHUB_CLIENT_ID.trim()
    var clientId by remember {
        mutableStateOf(packagedClientId.ifBlank { secrets.getCodespacesGitHubClientId() })
    }
    var device by remember { mutableStateOf<CodespacesLifecycleClient.DeviceAuthorization?>(null) }
    var message by remember { mutableStateOf("Connect your GitHub App to control the DestinedFX Codespace from this phone.") }
    var busy by remember { mutableStateOf(false) }
    var authorized by remember { mutableStateOf(secrets.getCodespacesGitHubAccessToken().isNotBlank()) }
    var showStopHostConfirmation by remember { mutableStateOf(false) }
    var showRevokeGitHubConfirmation by remember { mutableStateOf(false) }

    // GitHub Device Flow is polled automatically. The user still approves the device code
    // in GitHub's own page; the app never simulates or skips that owner consent.
    LaunchedEffect(device?.deviceCode) {
        val request = device ?: return@LaunchedEffect
        val expiresAt = System.currentTimeMillis() + request.expiresInSeconds * 1_000L
        var intervalSeconds = deviceFlowPollIntervalSeconds(request.intervalSeconds, request.expiresInSeconds)
        while (System.currentTimeMillis() < expiresAt) {
            kotlinx.coroutines.delay(intervalSeconds * 1_000L)
            if (device?.deviceCode != request.deviceCode) return@LaunchedEffect
            try {
                val result = github.checkDeviceAuthorization(request.deviceCode)
                if (result.authorized) {
                    authorized = true
                    device = null
                    message = result.message
                    return@LaunchedEffect
                }
                message = result.message + " Approval is checked automatically."
                if (result.slowDown) intervalSeconds = nextDeviceFlowPollIntervalSeconds(intervalSeconds, request.expiresInSeconds)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                device = null
                message = error.message ?: "GitHub device authorization failed."
                return@LaunchedEffect
            }
        }
        if (device?.deviceCode == request.deviceCode) {
            device = null
            message = "The GitHub device code expired. Tap AUTHORIZE GITHUB to start again."
        }
    }

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
            "Owner-only GitHub App Device Flow. This app requests the codespace and offline_access scopes so it can manage this repository's Codespace and refresh its short-lived token. No OAuth client secret is bundled.",
            fontSize = 10.sp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (packagedClientId.isBlank()) {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text("GitHub App Client ID (public)") },
                singleLine = true
            )
        } else {
            Text("GitHub App Client ID: configured in this APK (public identifier; no client secret is bundled).",
                fontSize = 10.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (packagedClientId.isBlank()) {
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
            }
            Button(
                enabled = !busy && (packagedClientId.isNotBlank() || clientId.isNotBlank()) && secrets.isSecureStorageAvailable,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            if (packagedClientId.isBlank()) {
                                check(secrets.saveCodespacesGitHubClientId(clientId)) {
                                    "Enter a valid public GitHub App Client ID first."
                                }
                            }
                            val result = github.beginDeviceAuthorization()
                            device = result
                            message = "Approve the one-time device code in GitHub. This app requests Codespaces access and refresh-token access, checks authorization automatically, and saves the token securely."
                        } catch (error: Exception) {
                            message = error.message ?: "GitHub device authorization could not start."
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text(if (authorized) "REAUTHORIZE GITHUB" else "AUTHORIZE GITHUB", fontSize = 9.sp) }
        }

        device?.let { request ->
            Text("One-time code: " + request.userCode, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (clipboard == null) {
                            message = "Clipboard unavailable. Enter the one-time code manually on GitHub."
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("GitHub device code", request.userCode))
                            message = "GitHub device code copied. Complete approval in the GitHub device page."
                        }
                    }
                ) { Text("COPY CODE") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        runCatching { uriHandler.openUri(request.verificationUri) }
                            .onFailure { message = "Open github.com/login/device in your browser and enter the code above." }
                    }
                ) { Text("OPEN GITHUB DEVICE PAGE") }
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    device = null
                    message = "GitHub device authorization cancelled. No token was saved."
                }
            ) { Text("CANCEL DEVICE AUTHORIZATION") }
            Text("Expires in about " + (request.expiresInSeconds / 60L) +
                " minute(s). Authorization is checked automatically; no repeated tap is required.", fontSize = 9.sp)
        }

        Text(
            if (authorized) "GitHub App authorization: saved securely on this device"
            else "GitHub App authorization: not connected",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && authorized,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16834A)),
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val result = github.startRepositoryCodespace()
                            message = result.message + " Codespace: " + result.codespaceName + " · discovering the secured Bridge URL…"
                            authorized = true
                            val origin = github.discoverAndSaveBridge()
                            val configuration = bridgeClient.checkServiceConfiguration()
                            check(configuration.httpReachable && configuration.httpStatus == 200 &&
                                codespacesBridgeConfigurationAllowsOriginSave(
                                    configuration.serviceStatus,
                                    configuration.database,
                                    configuration.managedExecution,
                                    configuration.perUserTerminalIsolation
                                )) {
                                "Bridge URL was discovered, but Android could not verify the Bridge configuration and isolation status."
                            }
                            onBridgeOriginDiscovered(origin)
                            message = "Bridge URL automatically discovered, health-checked and connected to the Bot: $origin · ${codespacesBridgeIsolationNotice(configuration.perUserTerminalIsolation)} · host remains EPHEMERAL/DEMO; broker heartbeat and DEMO acceptance are separate checks."
                        } catch (error: Exception) {
                            message = error.message ?: "Codespaces START was not confirmed."
                            authorized = secrets.getCodespacesGitHubAccessToken().isNotBlank()
                        } finally {
                            busy = false
                        }
                    }
                }
            ) {
                Text("START HOST", fontSize = 10.sp)
            }
            OutlinedButton(
                enabled = !busy && authorized && bridgeClient.managedConfigured(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F)),
                onClick = { showStopHostConfirmation = true }
            ) { Text("STOP HOST", fontSize = 10.sp) }
        }
        OutlinedButton(
            enabled = !busy && authorized,
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        val origin = github.discoverAndSaveBridge()
                        val configuration = bridgeClient.checkServiceConfiguration()
                        check(configuration.httpReachable && configuration.httpStatus == 200 &&
                            codespacesBridgeConfigurationAllowsOriginSave(
                                configuration.serviceStatus,
                                configuration.database,
                                configuration.managedExecution,
                                configuration.perUserTerminalIsolation
                            )) {
                            "Bridge health passed, but Android could not verify the Bridge configuration and isolation status."
                        }
                        onBridgeOriginDiscovered(origin)
                        message = "Bridge URL discovered, health-checked and connected to the Bot: $origin · ${codespacesBridgeIsolationNotice(configuration.perUserTerminalIsolation)} · EPHEMERAL/DEMO only."
                    } catch (error: Exception) {
                        message = error.message ?: "Automatic Bridge URL discovery failed."
                    } finally {
                        busy = false
                    }
                }
            }
        ) { Text("DISCOVER BRIDGE URL", fontSize = 9.sp) }
        OutlinedButton(
            enabled = !busy && authorized,
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        val status = bridgeClient.hostStatus()
                        message = "Host status: " + status.optString("status", "UNKNOWN") +
                            " · lifecycle: " + status.optString("host_lifecycle_status", "UNKNOWN") +
                            " · shutdown prepared: " + status.optBoolean("shutdown_prepared", false)
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
                    try {
                        val status = bridgeClient.hostStatus()
                        check(status.optBoolean("shutdown_prepared", false)) {
                            "No host shutdown preparation is pending."
                        }
                        val githubState = github.repositoryCodespaceState()
                        check(githubState.equals("Available", ignoreCase = true)) {
                            "GitHub reports $githubState. Shutdown preparation is retained because the stop request may already be in progress."
                        }
                        bridgeClient.cancelHostStop()
                        message = "Shutdown preparation cancelled. GitHub confirms the Codespace is Available and the Bridge command gate is cleared."
                    } catch (error: Exception) {
                        message = error.message ?: "Shutdown preparation could not be cancelled safely."
                    } finally {
                        busy = false
                    }
                }
            }
        ) { Text("CANCEL STOP PREPARATION", fontSize = 9.sp) }
        OutlinedButton(
            enabled = !busy && authorized && bridgeClient.managedConfigured(),
            onClick = { showRevokeGitHubConfirmation = true }
        ) { Text("REVOKE GITHUB APP ACCESS", fontSize = 9.sp) }
        OutlinedButton(
            enabled = !busy && authorized,
            onClick = {
                scope.launch {
                    busy = true
                    val cleared = github.revokeLocalAuthorization()
                    authorized = !cleared
                    message = if (cleared) {
                        "Saved GitHub App tokens cleared from this phone only. The remote OAuth grant remains active until revoked in GitHub Settings."
                    } else {
                        "Device token clearing failed; the local authorization was not reported as cleared."
                    }
                    busy = false
                }
            }
        ) { Text("CLEAR DEVICE TOKENS ONLY", fontSize = 9.sp) }
        OutlinedButton(
            enabled = !busy,
            onClick = {
                val safeClientId = clientId.trim()
                val target = if (safeClientId.matches(Regex("[A-Za-z0-9._-]{8,128}"))) {
                    "https://github.com/settings/connections/applications/" + safeClientId
                } else {
                    "https://github.com/settings/applications"
                }
                runCatching { uriHandler.openUri(target) }
                    .onFailure { message = "Open GitHub Settings → Applications to revoke DestinedFX's OAuth grant." }
            }
        ) { Text("GITHUB AUTHORIZATION SETTINGS", fontSize = 9.sp) }
        if (showStopHostConfirmation) {
            AlertDialog(
                onDismissRequest = { if (!busy) showStopHostConfirmation = false },
                title = { Text("Safe shutdown? Check positions") },
                text = {
                    Text(
                        "DestinedFX will check the live EA heartbeat, broker account state, open positions, pending orders, unresolved commands, and an integrity-verified backup. " +
                            "The Bridge must also confirm deploy/codespaces/stop.sh preflight. If any state is unsafe, missing, or unknown, the Codespace will remain running. " +
                            "This action does not close trades or cancel orders."
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            showStopHostConfirmation = false
                            scope.launch {
                                busy = true
                                var shutdownPrepared = false
                                try {
                                    val preflight = bridgeClient.prepareHostStop()
                                    check(isSafeHostStopPreflight(preflight)) {
                                        "STOP HOST BLOCKED: the Bridge did not prove current and backup broker safety, integrity-verified backup, and stop.sh preflight acknowledgement. The Codespace was not stopped."
                                    }
                                    shutdownPrepared = true
                                    val stopped = github.stopRepositoryCodespace()
                                    message = stopped.message +
                                        " · verified backup: " + preflight.optString("backup_name") +
                                        " · stop.sh preflight: " + preflight.optString("stop_script_status")
                                } catch (error: Exception) {
                                    val detail = error.message ?: "STOP HOST failed; the Codespace was not reported as stopped."
                                    message = if (shutdownPrepared) {
                                        "$detail The result may be uncertain; the Bridge safety lock remains closed. Check GitHub host status before cancelling preparation."
                                    } else {
                                        detail
                                    }
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text("CHECK SAFETY AND STOP HOST") }
                },
                dismissButton = {
                    TextButton(
                        enabled = !busy,
                        onClick = { showStopHostConfirmation = false }
                    ) { Text("CANCEL") }
                }
            )
        }
        if (showRevokeGitHubConfirmation) {
            AlertDialog(
                onDismissRequest = { if (!busy) showRevokeGitHubConfirmation = false },
                title = { Text("Revoke DestinedFX GitHub App access?") },
                text = {
                    Text(
                        "This asks the configured Bridge to revoke the complete GitHub App authorization grant using the server-held App credentials. " +
                            "The grant is cleared from this phone only after GitHub confirms revocation. Codespaces start/stop from this phone will then require new authorization."
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            showRevokeGitHubConfirmation = false
                            scope.launch {
                                busy = true
                                try {
                                    val accessToken = secrets.getCodespacesGitHubAccessToken()
                                    check(accessToken.isNotBlank()) { "No saved GitHub App access token is available for revocation." }
                                    val response = bridgeClient.revokeCodespacesGitHubAuthorization(accessToken)
                                    check(response.optBoolean("revoked", false)) {
                                        "GitHub did not confirm revocation of the App authorization grant."
                                    }
                                    authorized = false
                                    device = null
                                    val cleared = github.revokeLocalAuthorization()
                                    message = if (cleared) {
                                        "GitHub confirmed DestinedFX App grant revocation; local access and refresh tokens were cleared."
                                    } else {
                                        "GitHub confirmed grant revocation, but local token clearing failed. This phone cannot use the revoked grant; clear residual device tokens in app storage."
                                    }
                                } catch (error: Exception) {
                                    message = error.message ?: "GitHub App grant revocation was not confirmed; saved tokens were retained."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text("REVOKE APP GRANT") }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = { showRevokeGitHubConfirmation = false }) {
                        Text("CANCEL")
                    }
                }
            )
        }
        Text(message, fontSize = 10.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
