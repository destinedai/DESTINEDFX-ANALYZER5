package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.LossRed
import com.example.ui.theme.ProfitGreen
import com.example.ui.theme.TabBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TelegramQuickInfoPanel(viewModel: DestinedFXViewModel) {
    val context = LocalContext.current
    val appNameSaved by viewModel.telegramAppName.collectAsStateWithLifecycle()
    val apiIdSaved by viewModel.telegramApiId.collectAsStateWithLifecycle()
    val apiHashSaved by viewModel.telegramApiHash.collectAsStateWithLifecycle()
    val status by viewModel.telegramStatus.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.telegramNotificationsEnabled.collectAsStateWithLifecycle()
    val lastAt by viewModel.telegramLastMessageAtMs.collectAsStateWithLifecycle()
    val result by viewModel.telegramTestMessageResult.collectAsStateWithLifecycle()
    val loading by viewModel.isTelegramLoading.collectAsStateWithLifecycle()

    var appName by remember(appNameSaved) { mutableStateOf(appNameSaved) }
    var apiId by remember(apiIdSaved) { mutableStateOf(apiIdSaved) }
    var apiHash by remember(apiHashSaved) { mutableStateOf(apiHashSaved) }
    var botToken by remember(viewModel) { mutableStateOf(viewModel.getTelegramBotToken()) }
    var reveal by remember { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf("") }

    val configured = appNameSaved.isNotBlank() && apiIdSaved.isNotBlank() &&
        apiHashSaved.isNotBlank() && viewModel.getTelegramBotToken().isNotBlank()
    val lastMessage = if (lastAt > 0L) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastAt))
    } else "Never"

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, TabBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("TELEGRAM QUICK INFO", color = TextPrimary, fontSize = 13.sp)
            Text(
                "Enter these four values once on this phone. They are stored in encrypted Android Keystore-backed storage. api_id and api_hash are reference values; the Bot API test uses the Bot Token.",
                color = TextSecondary,
                fontSize = 10.sp
            )
            OutlinedTextField(appName, { appName = it }, label = { Text("App Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                apiId, { apiId = it.filter(Char::isDigit) }, label = { Text("api_id") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                apiHash, { apiHash = it }, label = { Text("api_hash") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation()
            )
            OutlinedTextField(
                botToken, { botToken = it }, label = { Text("Bot Token") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation()
            )
            Button(
                onClick = {
                    localMessage = if (viewModel.saveTelegramQuickInfo(appName, apiId, apiHash, botToken)) {
                        "Saved in encrypted storage on this phone."
                    } else {
                        "SAVE FAILED: enter App Name, numeric api_id, api_hash and Bot Token; encrypted storage must be available."
                    }
                },
                enabled = appName.isNotBlank() && apiId.toLongOrNull() != null &&
                    apiHash.isNotBlank() && botToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("SAVE") }

            Text("QUICK INFO", color = TextPrimary, fontSize = 12.sp)
            TelegramInfoLine("App Name", appNameSaved.ifBlank { "Not saved" })
            TelegramInfoLine("api_id", if (apiIdSaved.isBlank()) "Not saved" else if (reveal) apiIdSaved else "****")
            TelegramInfoLine("api_hash", if (apiHashSaved.isBlank()) "Not saved" else if (reveal) apiHashSaved else "********")
            TelegramInfoLine("Status", status)
            TelegramInfoLine("Last Message", lastMessage)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Steady Telegram Messages", color = TextPrimary, fontSize = 11.sp)
                    Text("Broker, bridge, order and balance alerts", color = TextSecondary, fontSize = 9.sp)
                }
                Switch(
                    checked = notificationsEnabled && status == "Connected",
                    onCheckedChange = { viewModel.setTelegramNotificationsEnabled(it) },
                    enabled = configured && status == "Connected" && !loading
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.sendTelegramTestNotification() },
                    enabled = configured && !loading,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen)
                ) {
                    Text(if (loading) "TESTING..." else "TEST TELEGRAM", fontSize = 10.sp, color = DarkBackground)
                }
                OutlinedButton(onClick = { reveal = !reveal }, modifier = Modifier.weight(1f)) {
                    Text(if (reveal) "HIDE" else "REVEAL", fontSize = 10.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        val summary = "App Name: $appNameSaved\napi_id: $apiIdSaved\napi_hash: $apiHashSaved\nStatus: $status\nLast Message: $lastMessage"
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                            ClipData.newPlainText("DestinedFX Telegram Quick Info", summary)
                        )
                        localMessage = "Quick Info copied. Bot Token was excluded."
                    },
                    enabled = configured,
                    modifier = Modifier.weight(1f)
                ) { Text("COPY INSIDE APP", fontSize = 10.sp) }
                OutlinedButton(
                    onClick = {
                        viewModel.unlinkTelegram()
                        appName = ""; apiId = ""; apiHash = ""; botToken = ""; reveal = false
                        localMessage = "Telegram Quick Info deleted from encrypted phone storage."
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LossRed)
                ) { Text("DELETE", fontSize = 10.sp) }
            }
            if (localMessage.isNotBlank()) Text(localMessage, color = TextSecondary, fontSize = 10.sp)
            if (!result.isNullOrBlank()) {
                Text(result.orEmpty(), color = if (result.orEmpty().startsWith("SUCCESS:")) ProfitGreen else TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun TelegramInfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 10.sp)
        Text(value, color = TextPrimary, fontSize = 10.sp)
    }
}
