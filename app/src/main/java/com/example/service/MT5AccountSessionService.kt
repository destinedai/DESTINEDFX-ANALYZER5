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
                        ) ?: return@async null
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
        private const val ALERT_PREFS = "mt5_daily_risk_alerts"

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
        val prefs = getSharedPreferences(ALERT_PREFS, MODE_PRIVATE)
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
            val key = "${profile}_${day}_${threshold}"
            if (prefs.getBoolean(key, false)) return@forEach
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
            prefs.edit().putBoolean(key, true).apply()
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
