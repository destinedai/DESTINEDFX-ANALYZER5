package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MyApplication
import com.example.R
import com.example.data.deriv.DerivMultiplierExecutor
import com.example.data.deriv.DerivMultiplierIntent
import com.example.data.deriv.DerivMultiplierProduct
import com.example.data.deriv.DerivMultiplierRules
import com.example.domain.model.SignalDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DerivDemoBotService : Service() {
    companion object {
        const val ACTION_START = "com.example.service.DERIV_DEMO_START"
        const val ACTION_STOP = "com.example.service.DERIV_DEMO_STOP"
        const val EXTRA_SYMBOL = "symbol"
        const val EXTRA_STRATEGY = "strategy"
        const val EXTRA_TIMEFRAME = "timeframe"
        private const val CHANNEL_ID = "deriv_demo_bot"
        private const val NOTIFICATION_ID = 1701
        private val _state = MutableStateFlow("STOPPED")
        val state: StateFlow<String> = _state.asStateFlow()

        fun start(context: android.content.Context, symbol: String, strategy: String, timeframe: String) {
            val intent = Intent(context, DerivDemoBotService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SYMBOL, symbol)
                putExtra(EXTRA_STRATEGY, strategy)
                putExtra(EXTRA_TIMEFRAME, timeframe)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, DerivDemoBotService::class.java).setAction(ACTION_STOP))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null
    @Volatile private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting — verifying Demo account"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSafely()
            ACTION_START -> {
                stopRequested = false
                loopJob?.cancel()
                val symbol = intent.getStringExtra(EXTRA_SYMBOL).orEmpty()
                val strategy = intent.getStringExtra(EXTRA_STRATEGY).orEmpty()
                val timeframe = intent.getStringExtra(EXTRA_TIMEFRAME).orEmpty()
                loopJob = serviceScope.launch { runBot(symbol, strategy, timeframe) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runBot(symbol: String, strategyId: String, timeframe: String) {
        val app = application as MyApplication
        val gateway = app.derivBrokerGateway
        if (!DerivMultiplierRules.isSupportedUnderlying(symbol)) {
            update("Safety blocked — $symbol is not a supported Deriv Multiplier underlying")
            stopSafely()
            return
        }
        if (gateway.connectionState.value != com.example.data.deriv.DerivConnectionState.CONNECTED) {
            update("Connecting — Demo account verification required")
            if (gateway.connectAndAuthorize().isFailure) {
                update("Stopped — Demo account verification failed")
                stopSafely()
                return
            }
        }
        val executor = DerivMultiplierExecutor(gateway, app.db.derivExecutionDao())
        if (!executor.reconcileBeforeNewEntries()) {
            update("Safety blocked — unresolved prior execution requires broker reconciliation")
            stopSafely()
            return
        }
        update("Running — scanning $symbol; no order is assumed confirmed")
        while (currentCoroutineContext().isActive && !stopRequested) {
            try {
                val account = gateway.accountInfo.value
                val balance = gateway.accountMetrics.value?.balance ?: account?.balance ?: 0.0
                if (account == null || !account.demoVerified || balance <= 0.0) {
                    update("Safety blocked — verified Demo account snapshot unavailable")
                    delay(10_000)
                    continue
                }
                val signal = app.signalGenerationUseCase.generateSignal(symbol, timeframe, strategyId)
                if (signal.executable && signal.direction in setOf(SignalDirection.LONG, SignalDirection.SHORT)) {
                    val stake = balance * DerivMultiplierRules.MAX_RISK_PERCENT / 100.0
                    val intent = DerivMultiplierIntent(
                        signalId = signal.id,
                        symbol = symbol,
                        strategyId = strategyId,
                        strategyVersion = signal.strategyVersion.ifBlank { "unknown" },
                        direction = signal.direction,
                        product = if (signal.direction == SignalDirection.LONG) DerivMultiplierProduct.MULTUP else DerivMultiplierProduct.MULTDOWN,
                        stake = stake,
                        currency = account.currency,
                        multiplier = 10,
                        stopLossAmount = stake,
                        takeProfitAmount = stake * 2.0,
                        signalTimestampMs = signal.timestamp
                    )
                    update("Submitting — broker proposal required for ${intent.product.contractType}")
                    val result = executor.submit(intent)
                    if (result.isSuccess) update("Contract confirmed — ${result.getOrThrow().contractId}; monitoring broker state")
                    else update(result.exceptionOrNull()?.message?.take(180) ?: "Execution blocked")
                } else {
                    update("Running — no valid fresh Multiplier signal")
                }
                executor.syncKnownContracts()
                delay(30_000)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: Exception) {
                update("Connection or execution error — additional purchases paused: ${error.message?.take(140)}")
                delay(10_000)
            }
        }
    }

    private fun stopSafely() {
        stopRequested = true
        loopJob?.cancel()
        loopJob = null
        update("Stopped — no new entry requests")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(text: String) {
        _state.value = when {
            text.startsWith("Running") || text.startsWith("Submitting") || text.startsWith("Contract") -> "RUNNING"
            text.startsWith("Connecting") -> "STARTING"
            text.startsWith("Stopped") -> "STOPPED"
            text.startsWith("Safety blocked") -> "SAFETY_BLOCKED"
            else -> "CONNECTION_LOST"
        }
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("DestinedFX · Deriv DEMO Multiplier")
        .setContentText(text)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Deriv DEMO bot", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        stopRequested = true
        loopJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
