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
        private const val PREFS = "deriv_bot_config"
        private const val MAX_RECONNECT_ATTEMPTS = 5
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
        startForeground(NOTIFICATION_ID, notification("Starting — verifying selected Deriv account"))
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
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(EXTRA_SYMBOL, symbol).putString(EXTRA_STRATEGY, strategy)
                    .putString(EXTRA_TIMEFRAME, timeframe).putBoolean("enabled", true).apply()
                loopJob = serviceScope.launch { runBot(symbol, strategy, timeframe) }
            }
        }
        return START_REDELIVER_INTENT
    }

    private suspend fun runBot(symbol: String, strategyId: String, timeframe: String) {
        val app = application as MyApplication
        val gateway = app.derivBrokerGateway
        if (!DerivMultiplierRules.isSupportedUnderlying(symbol)) {
            stopSafely("Safety blocked — $symbol is not a supported Deriv Multiplier underlying")
            return
        }
        if (gateway.connectionState.value != com.example.data.deriv.DerivConnectionState.CONNECTED) {
            if (!connectWithBoundedRetry(gateway)) {
                update("ERROR — authentication or reconnect attempts exhausted; trading remains stopped")
                return
            }
        }
        val executor = DerivMultiplierExecutor(gateway, app.db.derivExecutionDao())
        if (!executor.reconcileBeforeNewEntries()) {
            stopSafely("Safety blocked — unresolved prior execution requires broker reconciliation")
            return
        }
        update("Running — scanning $symbol; no order is assumed confirmed")
        while (currentCoroutineContext().isActive && !stopRequested) {
            try {
                val account = gateway.accountInfo.value
                if (account == null) {
                    stopSafely("Safety blocked — verified DEMO account snapshot is unavailable")
                    return
                }
                val balance = gateway.accountMetrics.value?.balance ?: account.balance
                if (!DerivMultiplierRules.isVerifiedDemoAccount(account) || !balance.isFinite() || balance <= 0.0) {
                    stopSafely("Safety blocked — this Android bot requires a verified DEMO account; LIVE execution is disabled")
                    return
                }
                val stake = DerivMultiplierRules.safeStakeForBalance(balance)
                val minimumStake = com.destinedfx.core.broker.DerivContractLimits.getMinimumStake(
                    symbol, DerivMultiplierProduct.MULTUP.contractType
                )
                if (stake < minimumStake) {
                    stopSafely("Safety blocked — 1% risk cap permits $stake, below broker minimum $minimumStake; no order was sent")
                    return
                }
                // Reconcile existing exposure on each scan. The mobile bot is deliberately
                // single-contract-at-a-time so repeated candles cannot accumulate open risk.
                if (!executor.reconcileBeforeNewEntries()) {
                    stopSafely("Safety blocked — an earlier execution is unresolved; no new entry will be submitted")
                    return
                }
                executor.syncKnownContracts()
                if (executor.hasActiveContracts()) {
                    update("Running — an existing contract is still active; waiting for broker settlement before another entry")
                    delay(10_000)
                    continue
                }
                val signal = app.signalGenerationUseCase.generateSignal(symbol, timeframe, strategyId)
                val planError = if (signal.executable && signal.direction in setOf(SignalDirection.LONG, SignalDirection.SHORT))
                    DerivMultiplierRules.planConsistencyError(signal.direction, signal.entryPrice, signal.stopLoss, signal.takeProfit) else null
                if (planError != null) {
                    update("Running — signal rejected: ${planError.take(120)}")
                } else if (signal.executable && signal.direction in setOf(SignalDirection.LONG, SignalDirection.SHORT)) {
                    val stableId = signal.id.takeIf { it.isNotBlank() }
                    if (stableId == null) {
                        update("Running — signal rejected: analyzer did not supply a stable identity")
                        delay(10_000)
                        continue
                    }
                    val intent = DerivMultiplierIntent(
                        signalId = stableId,
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
                    else {
                        val reason = result.exceptionOrNull()?.message?.take(180) ?: "Execution blocked"
                        // An unresolved outcome must stop new entries; an ordinary skip (duplicate, open position,
                        // rejected proposal) is not a connection problem and the bot keeps scanning.
                        if (reason.startsWith("EXECUTION_UNKNOWN") || reason.startsWith("RECONCILIATION_REQUIRED")) update("Safety blocked — $reason")
                        else update("Running — entry skipped: $reason")
                    }
                } else {
                    update("Running — no valid fresh Multiplier signal")
                }
                executor.syncKnownContracts()
                delay(30_000)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: Exception) {
                update("RECONNECTING — purchases paused: ${error.message?.take(140)}")
                if (!connectWithBoundedRetry(gateway)) {
                    update("ERROR — bounded reconnect policy exhausted; reconciliation required")
                    return
                }
            }
        }
    }

    private suspend fun connectWithBoundedRetry(gateway: com.example.data.deriv.DerivBrokerGateway): Boolean {
        var delayMs = 2_000L
        repeat(MAX_RECONNECT_ATTEMPTS) { attempt ->
            if (stopRequested || !currentCoroutineContext().isActive) return false
            update(if (attempt == 0) "CONNECTING — verifying selected DEMO account" else "RECONNECTING — attempt ${attempt + 1}/$MAX_RECONNECT_ATTEMPTS")
            if (gateway.connectAndAuthorize().isSuccess) {
                update("RECONCILING — fresh authenticated broker session established")
                return true
            }
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000L)
        }
        return false
    }

    private fun stopSafely(message: String = "Stopped — no new entry requests") {
        stopRequested = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        loopJob?.cancel()
        loopJob = null
        update(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(text: String) {
        _state.value = when {
            text.startsWith("Running") || text.startsWith("Submitting") || text.startsWith("Contract") -> "RUNNING"
            text.startsWith("CONNECTING") || text.startsWith("Connecting") -> "AUTHENTICATING"
            text.startsWith("RECONCILING") -> "RECONCILING"
            text.startsWith("RECONNECTING") -> "RECONNECTING"
            text.startsWith("ERROR") -> "ERROR"
            text.startsWith("Stopped") -> "STOPPED"
            text.startsWith("Safety blocked") -> "PAUSED"
            else -> "PAUSED"
        }
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("DestinedFX · Deriv ${applicationContext.getSharedPreferences("destinedfx_service", 0).getString("account_mode", "selected")?.uppercase()} Multiplier")
        .setContentText(text)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Deriv account bot", NotificationManager.IMPORTANCE_LOW)
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
