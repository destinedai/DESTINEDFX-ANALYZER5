package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MyApplication
import com.example.R
import com.example.data.trade.MT5RemoteBridgeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Convert an MT5 Bridge snapshot into account data only after exact account, server, mode,
 * broker identity and a fresh terminal heartbeat have all been verified.
 */
internal fun verifiedManagedMt5AccountSnapshot(
    profileKey: String,
    expectedLogin: String,
    expectedServer: String,
    expectedMode: String,
    snapshot: MT5RemoteBridgeClient.Snapshot,
    nowMs: Long = System.currentTimeMillis()
): com.example.data.broker.BrokerAccountSnapshot? {
    val (brand, profileMode) = AccountSessionRegistry.brandAndMode(profileKey) ?: return null
    val heartbeatAge = snapshot.raw.optLong("heartbeat_age_seconds", Long.MAX_VALUE)
    val staleAfter = snapshot.raw.optLong("heartbeat_stale_seconds", 15L).coerceIn(1L, 15L)
    val retrievedAt = if (heartbeatAge in 0L..staleAfter) nowMs - heartbeatAge * 1_000L else 0L
    val exactIdentity = expectedLogin.isNotBlank() && expectedServer.isNotBlank() &&
        expectedMode.equals(profileMode, ignoreCase = true) &&
        snapshot.actualLogin == expectedLogin &&
        snapshot.actualServer == expectedServer &&
        snapshot.actualAccountMode == profileMode &&
        snapshot.profileMode == profileMode
    val terminalReady = snapshot.connected && snapshot.terminalOnline &&
        snapshot.heartbeatFresh && snapshot.terminalConnected &&
        snapshot.accountVerified && snapshot.brokerIdentityVerified &&
        snapshot.managedState.equals("READY", ignoreCase = true)
    val values = listOf(snapshot.balance, snapshot.equity, snapshot.margin, snapshot.freeMargin)
    if (!exactIdentity || !terminalReady || retrievedAt <= 0L ||
        values.any { value -> value == null || !value.isFinite() || value < 0.0 } ||
        snapshot.currency.isBlank() || snapshot.leverage == null || snapshot.leverage <= 0
    ) return null

    return com.example.data.broker.BrokerAccountSnapshot(
        providerId = brand,
        origin = com.example.data.broker.DataOrigin.BROKER_API,
        status = com.example.data.broker.BrokerConnectionStatus.CONNECTED,
        retrievedAtMs = retrievedAt,
        environment = profileMode,
        currency = snapshot.currency,
        balance = snapshot.balance,
        equity = snapshot.equity,
        margin = snapshot.margin,
        freeMargin = snapshot.freeMargin,
        validity = com.example.data.broker.DataValidity.FRESH,
        leverage = snapshot.leverage
    ).takeIf { it.isDisplayable(nowMs = nowMs, maxAgeMs = staleAfter * 1_000L) }
}

/**
 * App-side monitor only. The Windows MT5 terminal/EA remains responsible for autonomous
 * trading and broker-side safety enforcement. This service keeps account telemetry alive
 * when the Bot screen is not composed.
 */
object AccountSessionRegistry {
    private val _snapshots = MutableStateFlow<Map<String, MT5RemoteBridgeClient.Snapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, MT5RemoteBridgeClient.Snapshot>> = _snapshots.asStateFlow()

    internal fun brandAndMode(profileKey: String): Pair<String, String>? {
        val profile = profileKey.trim().uppercase()
        val mode = profile.substringAfterLast('_', "")
        val brand = profile.substringBeforeLast('_', "")
        if (brand !in setOf("FBS", "EXNESS", "HFM", "DERIV") || mode !in setOf("DEMO", "LIVE")) return null
        return brand to mode
    }

    suspend fun refreshAll(context: Context): Map<String, MT5RemoteBridgeClient.Snapshot> {
        val app = context.applicationContext as? MyApplication ?: return _snapshots.value
        val secrets = app.secretsManager
        val origin = secrets.getManagedMT5ServiceUrl()
        if (origin.isBlank()) return _snapshots.value
        val profiles = secrets.getConfiguredMT5AccountProfileKeys()
        val previous = _snapshots.value
        val refreshed = coroutineScope {
            profiles.map { profileKey ->
                async(Dispatchers.IO) {
                    val profileResult = runCatching {
                        val (brand, mode) = brandAndMode(profileKey)
                            ?: throw IllegalArgumentException("Invalid broker account profile")
                        val client = MT5RemoteBridgeClient(
                            secrets = secrets,
                            profile = profileKey,
                            sensitiveActionAuthorized = { false },
                            accountMode = mode,
                            brokerBrand = brand
                        )
                        profileKey to client.state()
                    }
                    profileResult.getOrElse { error ->
                        if (error is CancellationException) throw error
                        previous[profileKey]?.copy(
                            connected = false,
                            terminalOnline = false,
                            heartbeatFresh = false,
                            state = "UNKNOWN",
                            liveAuthorized = false,
                            tradeAllowed = false,
                            terminalConnected = false,
                            accountVerified = false
                        )?.let { profileKey to it } ?: return@async null
                    }
                }
            }.awaitAll()
        }
        _snapshots.value = refreshed.filterNotNull().toMap()
        return _snapshots.value
    }
}

class MT5AccountSessionService : Service() {
    companion object {
        private const val CHANNEL_ID = "destinedfx_mt5_account_monitor"
        private const val NOTIFICATION_ID = 1702

        fun start(context: Context) {
            val intent = Intent(context, MT5AccountSessionService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, serviceNotification("Monitoring configured MT5 accounts"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob?.isActive != true) {
            monitorJob = serviceScope.launch {
                while (isActive) {
                    try {
                        val snapshots = AccountSessionRegistry.refreshAll(applicationContext)
                        publishRiskAlerts(snapshots)
                        val online = snapshots.values.count { it.connected && it.terminalOnline && it.heartbeatFresh && it.accountVerified }
                        updateForeground("Monitoring ${snapshots.size} configured profile(s) · ${online} verified online")
                    } catch (_: CancellationException) {
                        throw CancellationException()
                    } catch (_: Exception) {
                        updateForeground("Reconnecting · monitoring remains active")
                    }
                    delay(5_000L)
                }
            }
        }
        return START_STICKY
    }

    private fun publishRiskAlerts(snapshots: Map<String, MT5RemoteBridgeClient.Snapshot>) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val secrets = (application as? MyApplication)?.secretsManager ?: return
        if (!secrets.isSecureStorageAvailable) return
        val day = utcDayKey()
        snapshots.forEach { (profile, snapshot) ->
            val config = snapshot.raw.optJSONObject("configuration") ?: return@forEach
            val status = config.optString("daily_risk_status", "UNKNOWN").uppercase()
            val used = config.optDouble("daily_loss_used_percent", Double.NaN)
            if (!used.isFinite() || used < 0.0) return@forEach
            val threshold = when {
                status == "BREACHED_LOCKED" || used >= 100.0 -> 100
                status == "WARNING_90" || used >= 90.0 -> 90
                status == "WARNING_80" || used >= 80.0 -> 80
                else -> return@forEach
            }
            if (secrets.wasMt5DailyRiskAlertSent(profile, day, threshold)) return@forEach
            // Commit the encrypted dedupe marker before delivery so a process restart cannot
            // fan out duplicate risk alerts for the same account/day/threshold.
            if (!secrets.markMt5DailyRiskAlertSent(profile, day, threshold)) return@forEach
            val title = when (threshold) {
                100 -> "DestinedFX · Daily loss lock reached"
                90 -> "DestinedFX · 90% daily loss warning"
                else -> "DestinedFX · 80% daily loss warning"
            }
            val detail = "$profile: ${"%.1f".format(Locale.US, used)}% of the configured daily loss limit used."
            manager.notify((profile.hashCode() xor threshold) and 0x7fffffff, NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build())
        }
    }

    private fun utcDayKey(): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return format.format(Date())
    }

    private fun updateForeground(message: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, serviceNotification(message))
    }

    private fun serviceNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("DestinedFX · MT5 account monitor")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MT5 account monitoring", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
