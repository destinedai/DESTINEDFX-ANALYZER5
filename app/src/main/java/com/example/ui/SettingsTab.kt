package com.example.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.*
import com.example.ui.theme.*
import java.util.Locale

@Composable
fun SettingsTab(viewModel: DestinedFXViewModel) {

    var savedMessage by remember { mutableStateOf("") }
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val secureStorageAvailable by viewModel.secureStorageAvailable.collectAsStateWithLifecycle()
    val managementMessage by viewModel.managementMessage.collectAsStateWithLifecycle()
    val managementBusy by viewModel.managementBusy.collectAsStateWithLifecycle()
    val managementEmail by viewModel.managementEmail.collectAsStateWithLifecycle()
    val managementServiceState by viewModel.managementServiceState.collectAsStateWithLifecycle()
    var accountEmail by remember { mutableStateOf("") }
    var accountPassword by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                            .background(ElectricCyan.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(24.dp))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("TERMINAL CONTROL", fontSize = 9.sp, color = ElectricCyan,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.1.sp)
                            Text("Settings", fontSize = 25.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                            Text("Feed status and notifications", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                    HorizontalDivider(color = CardBorder)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(DarkSurfaceVariant).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("CHART ACCESS", fontSize = 8.sp, color = TextSecondary,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            Text("TradingView", fontSize = 12.sp, color = ProfitGreen, fontWeight = FontWeight.Black)
                            Text("No market-data key needed", fontSize = 9.sp, color = TextSecondary)
                        }
                    }
                    HorizontalDivider(color = CardBorder)
                    Text("APPEARANCE", fontSize = 9.sp, color = ElectricCyan,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("System", "Light", "Dark").forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = {
                                    savedMessage = if (viewModel.setThemeMode(mode)) "${mode} appearance saved."
                                    else "Unable to save appearance preference."
                                    if (savedMessage.endsWith("saved.")) viewModel.syncThemePreference(mode)
                                },
                                modifier = Modifier.weight(1f).testTag("theme_$mode"),
                                label = { Text(mode, maxLines = 1, fontSize = 10.sp) }
                            )
                        }
                    }
                    if (savedMessage.isNotBlank()) Text(savedMessage, fontSize = 9.sp, color = TextSecondary)
                    HorizontalDivider(color = CardBorder, modifier = Modifier.padding(vertical = 4.dp))
                    Text("ACCOUNT & MANAGEMENT SERVICE", fontSize = 9.sp, color = ElectricCyan,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    if (managementEmail == "Saved session") Text("Encrypted session is saved; account identity has not been refreshed yet.", fontSize = 10.sp, color = TextSecondary)
                    else if (managementEmail.isNotBlank()) Text("Signed in as $managementEmail", fontSize = 10.sp, color = ProfitGreen)
                    OutlinedTextField(value = accountEmail, onValueChange = { accountEmail = it },
                        label = { Text("Account email") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("management_email"))
                    OutlinedTextField(value = accountPassword, onValueChange = { accountPassword = it },
                        label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("management_password"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(enabled = !managementBusy && accountEmail.isNotBlank() && accountPassword.isNotEmpty(),
                            onClick = { viewModel.managementRegister(accountEmail, accountPassword); accountPassword = "" }, modifier = Modifier.weight(1f)) { Text("Register", fontSize = 10.sp) }
                        OutlinedButton(enabled = !managementBusy && accountEmail.isNotBlank() && accountPassword.isNotEmpty(),
                            onClick = { viewModel.managementLogin(accountEmail, accountPassword); accountPassword = "" }, modifier = Modifier.weight(1f)) { Text("Sign in", fontSize = 10.sp) }
                        OutlinedButton(enabled = !managementBusy && managementEmail.isNotBlank(),
                            onClick = { viewModel.managementLogout() }, modifier = Modifier.weight(1f)) { Text("Sign out", fontSize = 10.sp) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = !managementBusy, onClick = { viewModel.refreshManagementStatus() }) { Text("Check service status", fontSize = 10.sp) }
                        if (managementBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    if (managementServiceState.isNotBlank()) Text(managementServiceState, fontSize = 9.sp, color = TextSecondary)
                    if (managementMessage.isNotBlank()) Text(managementMessage, fontSize = 9.sp, color = TextSecondary)
                    Text("Theme is saved locally first; account sync requires sign-in. Offline changes stay on this device.", fontSize = 9.sp, color = TextMuted)
                    HorizontalDivider(color = CardBorder, modifier = Modifier.padding(vertical = 4.dp))

                    val scanningActive by viewModel.isBackgroundScanningActive.collectAsStateWithLifecycle()
                    val scanStatus by viewModel.backgroundScanStatus.collectAsStateWithLifecycle()

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.QueryStats, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("APP-SESSION MARKET SCAN", fontSize = 10.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp)
                            Text("App-session scan status; not a persistent background service.", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("App-session market scan", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(if (scanningActive) "ACTIVE" else if (viewModel.isPreview) "NOT RUNNING" else "PAUSED",
                                    fontSize = 9.sp, fontWeight = FontWeight.Black,
                                    color = if (scanningActive) ProfitGreen else WarningAmber,
                                    fontFamily = FontFamily.Monospace)
                            }
                            Text(scanStatus, fontSize = 10.sp, color = TextSecondary)
                            HorizontalDivider(color = CardBorder)
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("High-Confluence Alert Threshold", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("85/100 RULE SCORE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = ElectricCyan, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, CardBorder), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WORKSPACE SHORTCUTS", fontSize = 9.sp, color = ElectricCyan,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { viewModel.selectScreen(DestinedFXScreen.Bot) }, modifier = Modifier.weight(1f).testTag("settings_open_bot")) { Text("Broker / Bot", fontSize = 10.sp) }
                        OutlinedButton(onClick = { viewModel.selectScreen(DestinedFXScreen.Analyzer) }, modifier = Modifier.weight(1f).testTag("settings_open_analyzer")) { Text("Market data", fontSize = 10.sp) }
                        OutlinedButton(onClick = { viewModel.selectScreen(DestinedFXScreen.Calendar) }, modifier = Modifier.weight(1f).testTag("settings_open_calendar")) { Text("Calendar", fontSize = 10.sp) }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, if (secureStorageAvailable) ProfitGreen.copy(alpha = 0.4f) else LossRed),
                shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("SECURITY & CREDENTIAL STORAGE", fontSize = 9.sp, color = ElectricCyan,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Text(if (secureStorageAvailable) "Encrypted credential storage available on this device."
                        else "Encrypted credential storage unavailable. Credential persistence and sensitive actions must remain blocked.",
                        color = if (secureStorageAvailable) TextSecondary else LossRed, fontSize = 10.sp)
                    Text("No credential values are displayed in Settings.", color = TextMuted, fontSize = 9.sp)
                }
            }
        }
        item {
            HorizontalDivider(color = TabBorder)
            Spacer(modifier = Modifier.height(8.dp))
            Text("TELEGRAM ALERTS", fontWeight = FontWeight.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = CyanPrimary)
            Text("Connection, delivery test and alert controls.", fontSize = 10.sp, color = TextSecondary)
        }

        item {
            val telegramStatus by viewModel.telegramStatus.collectAsStateWithLifecycle()
            val telegramNotifEnabled by viewModel.telegramNotificationsEnabled.collectAsStateWithLifecycle()
            val telegramResult by viewModel.telegramTestMessageResult.collectAsStateWithLifecycle()
            val isTelegramLoading by viewModel.isTelegramLoading.collectAsStateWithLifecycle()

            var botTokenInput by remember { mutableStateOf(viewModel.getTelegramBotToken()) }
            var chatIdInput by remember { mutableStateOf(viewModel.getTelegramChatId()) }
            var showBotFatherGuide by remember { mutableStateOf(false) }

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, TabBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Connection Status", fontSize = 11.sp, color = TextSecondary)
                            Text(
                                text = telegramStatus,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = when (telegramStatus) {
                                    "Connected" -> ProfitGreen
                                    "Connection failed" -> LossRed
                                    else -> OrangeAccent
                                }
                            )
                        }
                        TextButton(onClick = { showBotFatherGuide = !showBotFatherGuide }) {
                            Text(if (showBotFatherGuide) "Hide BotFather Guide" else "How to Setup BotFather?", fontSize = 11.sp, color = CyanPrimary)
                        }
                    }

                    if (showBotFatherGuide) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkBackground),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("BotFather Setup Guide:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = CyanPrimary)
                                Text("1. Open Telegram and search for official @BotFather.", fontSize = 10.sp, color = TextPrimary)
                                Text("2. Send /newbot and follow instructions to name your bot.", fontSize = 10.sp, color = TextPrimary)
                                Text("3. Copy the HTTP API Token provided by BotFather.", fontSize = 10.sp, color = TextPrimary)
                                Text("4. Send a message to your bot in Telegram, then enter your Chat ID.", fontSize = 10.sp, color = TextPrimary)
                                Text("5. Enter Bot Token and Chat ID below and tap Connect Telegram.", fontSize = 10.sp, color = TextPrimary)
                                Text("6. Handshake verifies delivery and activates secure alerts.", fontSize = 10.sp, color = TextPrimary)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = botTokenInput,
                        onValueChange = { botTokenInput = it },
                        label = { Text("Telegram Bot Token") },
                        modifier = Modifier.fillMaxWidth().testTag("telegram_token_input"),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )

                    OutlinedTextField(
                        value = chatIdInput,
                        onValueChange = { chatIdInput = it },
                        label = { Text("Telegram Chat ID") },
                        modifier = Modifier.fillMaxWidth().testTag("telegram_chat_id_input"),
                        singleLine = true,
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    viewModel.autoDetectTelegramChatId(botTokenInput) { detected ->
                                        if (detected != null) {
                                            chatIdInput = detected
                                        }
                                    }
                                },
                                enabled = !isTelegramLoading && botTokenInput.isNotBlank()
                            ) {
                                Text("Auto-Detect", fontSize = 11.sp, color = CyanPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                    Text(
                        text = "Tip: Send /start to your bot in Telegram, then tap Auto-Detect to fetch your numeric Chat ID instantly without third-party lookup tools.",
                        fontSize = 10.sp,
                        color = TextSecondary,
                        lineHeight = 14.sp
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.connectTelegram(botTokenInput, chatIdInput) },
                            enabled = !isTelegramLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            modifier = Modifier.weight(1f).testTag("connect_telegram_button")
                        ) {
                            Text(if (isTelegramLoading) "Connecting..." else "Connect Telegram", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { viewModel.sendTelegramTestNotification() },
                            enabled = !isTelegramLoading && telegramStatus == "Connected",
                            colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen),
                            modifier = Modifier.weight(1f).testTag("test_telegram_button")
                        ) {
                            Text("Test Message", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.refreshTelegramStatus() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Refresh Status", color = TextPrimary, fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = { viewModel.unlinkTelegram() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = LossRed),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Unlink", fontSize = 11.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Enable Automated Alerts", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Requires verified connection status", fontSize = 10.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = telegramNotifEnabled,
                            onCheckedChange = { viewModel.setTelegramNotificationsEnabled(it) },
                            enabled = telegramStatus == "Connected",
                            modifier = Modifier.testTag("telegram_switch")
                        )
                    }

                    val res = telegramResult
                    if (res != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(res, fontSize = 11.sp, color = if (telegramStatus == "Connected") ProfitGreen else LossRed)
                    }
                }
            }
        }
    }
}
