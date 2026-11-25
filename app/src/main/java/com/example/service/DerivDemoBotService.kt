package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.Context
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
import com.example.data.deriv.DerivMarketDataGateway
import com.destinedfx.core.marketdata.MarketQuote
import com.example.domain.model.Candle
import com.example.data.deriv.DerivReconciliationStatus
import com.example.data.deriv.DerivDailyRiskMonitor
import com.example.domain.risk.TradingSafetyLimits
import com.example.domain.model.SignalDirection
import com.destinedfx.core.strategy.StrategyRegistry
import com.destinedfx.core.marketdata.Timeframe
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


/** A Bot-owned signal contract. It deliberately does not depend on Analyzer TradeSignal. */
private data class BotStrategySignal(
    val id: String,
    val direction: SignalDirection,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val timestampMs: Long,
    val strategyVersion: String
)

/**
 * Independent OHLC strategy evaluator used by the Deriv Bot.
 * Every strategy returns null (WAIT) unless a closed-candle setup and a fresh authenticated quote agree.
 */
private object BotStrategy {
    const val VERSION = "bot-rules-1.0.0"
    private const val MIN_REWARD_RISK = 2.2

    fun aggregateWeekly(dailyCandles: List<Candle>): List<Candle> {
        val dayMs = 86_400_000L
        val weekMs = dayMs * 7L
        val mondayOffsetMs = dayMs * 3L // Unix epoch begins on Thursday.
        return dailyCandles.asSequence()
            .filter { it.complete && it.timestamp > 0L }
            .groupBy { Math.floorDiv(it.timestamp + mondayOffsetMs, weekMs) * weekMs - mondayOffsetMs }
            .toSortedMap()
            .mapNotNull { (weekStart, rows) ->
                val week = rows.sortedBy { it.timestamp }
                if (week.isEmpty()) null else Candle(
                    timestamp = weekStart,
                    open = week.first().open,
                    high = week.maxOf { it.high },
                    low = week.minOf { it.low },
                    close = week.last().close,
                    volume = week.sumOf { it.volume },
                    complete = true
                )
            }
    }

    fun generateSignal(
        strategyId: String,
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        quote: MarketQuote,
        nowMs: Long
    ): BotStrategySignal? {
        if (strategyId !in StrategyRegistry.managedStrategyIds) return null
        if (quote.symbol != symbol || !quote.isStructurallyValid ||
            quote.timestampMs <= 0L || quote.timestampMs > nowMs ||
            nowMs - quote.timestampMs !in 0L..15_000L || quote.ageMs(nowMs) !in 0L..15_000L
        ) return null

        val intervalMs = timeframe.millis
        val closed = candles.asSequence()
            .filter { candle ->
                candle.complete && candle.timestamp > 0L &&
                    candle.timestamp + intervalMs <= nowMs &&
                    candle.open.isFinite() && candle.high.isFinite() &&
                    candle.low.isFinite() && candle.close.isFinite() &&
                    candle.volume.isFinite() && candle.open > 0.0 &&
                    candle.low > 0.0 && candle.high >= maxOf(candle.open, candle.close) &&
                    candle.low <= minOf(candle.open, candle.close)
            }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
            .toList()
        if (closed.size < 30) return null

        val last = closed.last()
        val candleCloseTime = last.timestamp + intervalMs
        val maximumClosedBarAge = maxOf(60_000L, intervalMs * 2L)
        if (nowMs - candleCloseTime !in 0L..maximumClosedBarAge) return null

        val atr = averageTrueRange(closed) ?: return null
        val entry = quote.mid?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (kotlin.math.abs(entry - last.close) > atr * 3.0) return null

        val direction = when (strategyId) {
            StrategyRegistry.SMC_CONFLUENCE.id -> smartMoneyStructure(closed, atr)
            StrategyRegistry.LIQUIDITY_STOP_HUNTS.id -> liquidityStopHunt(closed, atr)
            StrategyRegistry.ICT_SILVER_BULLET.id -> silverBullet(closed, atr)
            StrategyRegistry.WYCKOFF_METHOD.id -> wyckoffSpringOrUtad(closed, atr)
            StrategyRegistry.SUPPLY_AND_DEMAND.id -> supplyDemandRetest(closed, atr)
            StrategyRegistry.BREAKOUT_MOMENTUM.id -> breakoutMomentum(closed, atr)
            StrategyRegistry.MEAN_REVERSION.id -> meanReversion(closed, atr)
            StrategyRegistry.TREND_FOLLOWING.id -> trendFollowing(closed, atr)
            else -> null
        } ?: return null

        val recentStructure = closed.takeLast(7)
        var stopLoss = if (direction == SignalDirection.LONG) {
            recentStructure.minOf { it.low } - atr * 0.15
        } else {
            recentStructure.maxOf { it.high } + atr * 0.15
        }
        var risk = if (direction == SignalDirection.LONG) entry - stopLoss else stopLoss - entry
        if (!risk.isFinite() || risk <= 0.0) return null
        if (risk < atr * 0.35) {
            stopLoss = if (direction == SignalDirection.LONG) entry - atr * 1.25 else entry + atr * 1.25
            risk = atr * 1.25
        }
        if (risk > atr * 6.0) return null

        val takeProfit = if (direction == SignalDirection.LONG) {
            entry + risk * MIN_REWARD_RISK
        } else {
            entry - risk * MIN_REWARD_RISK
        }
        if (!takeProfit.isFinite() || takeProfit <= 0.0 ||
            DerivMultiplierRules.planConsistencyError(direction, entry, stopLoss, takeProfit) != null
        ) return null

        // Identity is tied to the closed candle so rescans cannot invent a new setup ID.
        val stableId = "bot:$strategyId:$symbol:${timeframe.label}:${last.timestamp}:${direction.name}"
        return BotStrategySignal(
            id = stableId,
            direction = direction,
            entryPrice = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            // The signal is created at the fresh quote boundary; candle identity remains in stableId.
            timestampMs = nowMs,
            strategyVersion = VERSION
        )
    }

    private fun averageTrueRange(candles: List<Candle>): Double? {
        val sample = candles.takeLast(15)
        if (sample.size < 15) return null
        val ranges = (1 until sample.size).map { index ->
            val current = sample[index]
            val previousClose = sample[index - 1].close
            maxOf(
                current.high - current.low,
                kotlin.math.abs(current.high - previousClose),
                kotlin.math.abs(current.low - previousClose)
            )
        }
        return ranges.average().takeIf { it.isFinite() && it > 0.0 }
    }

    private fun bullish(candle: Candle): Boolean {
        val range = candle.high - candle.low
        return range > 0.0 && candle.close > candle.open &&
            candle.close >= candle.low + range * 0.60
    }

    private fun bearish(candle: Candle): Boolean {
        val range = candle.high - candle.low
        return range > 0.0 && candle.close < candle.open &&
            candle.close <= candle.low + range * 0.40
    }

    private fun body(candle: Candle): Double = kotlin.math.abs(candle.close - candle.open)

    private fun recentSweep(
        candles: List<Candle>,
        direction: SignalDirection,
        atr: Double,
        referenceCount: Int = 12,
        sweepWindow: Int = 6
    ): Boolean {
        if (candles.size < referenceCount + sweepWindow + 1) return false
        val reference = candles.dropLast(sweepWindow).takeLast(referenceCount)
        if (reference.size < referenceCount) return false
        val window = candles.takeLast(sweepWindow).dropLast(1)
        return when (direction) {
            SignalDirection.LONG -> {
                val level = reference.minOf { it.low }
                window.any { it.low < level - atr * 0.05 && it.close > level }
            }
            SignalDirection.SHORT -> {
                val level = reference.maxOf { it.high }
                window.any { it.high > level + atr * 0.05 && it.close < level }
            }
            else -> false
        }
    }

    private fun smartMoneyStructure(candles: List<Candle>, atr: Double): SignalDirection? {
        if (candles.size < 35) return null
        val last = candles.last()
        val structure = candles.dropLast(1).takeLast(10)
        if (structure.size < 10) return null
        if (recentSweep(candles.dropLast(1), SignalDirection.LONG, atr) &&
            last.close > structure.maxOf { it.high } + atr * 0.05 && bullish(last)
        ) return SignalDirection.LONG
        if (recentSweep(candles.dropLast(1), SignalDirection.SHORT, atr) &&
            last.close < structure.minOf { it.low } - atr * 0.05 && bearish(last)
        ) return SignalDirection.SHORT
        return null
    }

    private fun liquidityStopHunt(candles: List<Candle>, atr: Double): SignalDirection? {
        val last = candles.last()
        val prior = candles.dropLast(1).takeLast(15)
        if (prior.size < 15) return null
        val priorLow = prior.minOf { it.low }
        val priorHigh = prior.maxOf { it.high }
        if (last.low < priorLow - atr * 0.05 && last.close > priorLow && bullish(last)) {
            return SignalDirection.LONG
        }
        if (last.high > priorHigh + atr * 0.05 && last.close < priorHigh && bearish(last)) {
            return SignalDirection.SHORT
        }
        return null
    }

    private fun silverBullet(candles: List<Candle>, atr: Double): SignalDirection? {
        if (candles.size < 30) return null
        val last = candles.last()
        val twoBack = candles[candles.lastIndex - 2]
        if (!inSilverBulletWindow(last.timestamp)) return null
        val sweptLong = recentSweep(candles, SignalDirection.LONG, atr, referenceCount = 12, sweepWindow = 6)
        val sweptShort = recentSweep(candles, SignalDirection.SHORT, atr, referenceCount = 12, sweepWindow = 6)
        if (sweptLong && twoBack.high < last.low && bullish(last) && body(last) >= atr * 0.75) {
            return SignalDirection.LONG
        }
        if (sweptShort && twoBack.low > last.high && bearish(last) && body(last) >= atr * 0.75) {
            return SignalDirection.SHORT
        }
        return null
    }

    private fun inSilverBulletWindow(timestampMs: Long): Boolean {
        val calendar = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("America/New_York"),
            java.util.Locale.US
        ).apply { timeInMillis = timestampMs }
        val minuteOfDay = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            calendar.get(java.util.Calendar.MINUTE)
        return minuteOfDay in 180 until 240 || minuteOfDay in 600 until 660 || minuteOfDay in 840 until 900
    }

    private fun wyckoffSpringOrUtad(candles: List<Candle>, atr: Double): SignalDirection? {
        val last = candles.last()
        val prior = candles.dropLast(1).takeLast(20)
        if (prior.size < 20) return null
        val averageVolume = prior.map { it.volume }.average()
        if (!averageVolume.isFinite() || averageVolume <= 0.0 ||
            last.volume < averageVolume * 1.20
        ) return null
        val rangeLow = prior.minOf { it.low }
        val rangeHigh = prior.maxOf { it.high }
        if (last.low < rangeLow - atr * 0.05 && last.close > rangeLow && bullish(last)) {
            return SignalDirection.LONG
        }
        if (last.high > rangeHigh + atr * 0.05 && last.close < rangeHigh && bearish(last)) {
            return SignalDirection.SHORT
        }
        return null
    }

    private fun supplyDemandRetest(candles: List<Candle>, atr: Double): SignalDirection? {
        if (candles.size < 12) return null
        val last = candles.last()
        val departure = candles[candles.lastIndex - 1]
        val base = candles.subList(candles.lastIndex - 4, candles.lastIndex - 1)
        val baseHigh = base.maxOf { it.high }
        val baseLow = base.minOf { it.low }
        val compactBase = baseHigh - baseLow <= atr * 1.50
        if (!compactBase) return null
        if (bullish(departure) && departure.close > baseHigh + atr * 0.70 &&
            last.low <= baseHigh + atr * 0.25 && last.close > baseHigh && bullish(last)
        ) return SignalDirection.LONG
        if (bearish(departure) && departure.close < baseLow - atr * 0.70 &&
            last.high >= baseLow - atr * 0.25 && last.close < baseLow && bearish(last)
        ) return SignalDirection.SHORT
        return null
    }

    private fun breakoutMomentum(candles: List<Candle>, atr: Double): SignalDirection? {
        val last = candles.last()
        val prior = candles.dropLast(1).takeLast(20)
        if (prior.size < 20) return null
        val channelHigh = prior.maxOf { it.high }
        val channelLow = prior.minOf { it.low }
        if (last.close > channelHigh + atr * 0.05 && bullish(last) && body(last) >= atr * 0.55) {
            return SignalDirection.LONG
        }
        if (last.close < channelLow - atr * 0.05 && bearish(last) && body(last) >= atr * 0.55) {
            return SignalDirection.SHORT
        }
        return null
    }

    private fun meanReversion(candles: List<Candle>, atr: Double): SignalDirection? {
        val sample = candles.takeLast(20)
        if (sample.size < 20) return null
        val mean = sample.map { it.close }.average()
        val variance = sample.map { (it.close - mean) * (it.close - mean) }.average()
        val deviation = kotlin.math.sqrt(variance)
        if (!mean.isFinite() || !deviation.isFinite() || deviation <= 0.0) return null
        val lowerBand = mean - deviation * 2.0
        val upperBand = mean + deviation * 2.0
        val last = candles.last()
        val rsi = relativeStrengthIndex(candles, 14) ?: return null
        if (last.low <= lowerBand && last.close > lowerBand && rsi <= 38.0 && bullish(last)) {
            return SignalDirection.LONG
        }
        if (last.high >= upperBand && last.close < upperBand && rsi >= 62.0 && bearish(last)) {
            return SignalDirection.SHORT
        }
        return null
    }

    private fun trendFollowing(candles: List<Candle>, atr: Double): SignalDirection? {
        if (candles.size < 200) return null
        val closes = candles.map { it.close }
        val ema20 = exponentialMovingAverage(closes, 20) ?: return null
        val ema50 = exponentialMovingAverage(closes, 50) ?: return null
        val ema200 = exponentialMovingAverage(closes, 200) ?: return null
        val last = candles.last()
        val previous = candles[candles.lastIndex - 1]
        if (ema20 > ema50 && ema50 > ema200 &&
            last.low <= ema20 + atr * 0.30 && last.close > ema20 &&
            last.close > previous.close && bullish(last)
        ) return SignalDirection.LONG
        if (ema20 < ema50 && ema50 < ema200 &&
            last.high >= ema20 - atr * 0.30 && last.close < ema20 &&
            last.close < previous.close && bearish(last)
        ) return SignalDirection.SHORT
        return null
    }

    private fun exponentialMovingAverage(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val alpha = 2.0 / (period + 1.0)
        var result = values.take(period).average()
        for (index in period until values.size) {
            result += alpha * (values[index] - result)
        }
        return result.takeIf { it.isFinite() }
    }

    private fun relativeStrengthIndex(candles: List<Candle>, period: Int): Double? {
        if (candles.size < period + 1) return null
        val closes = candles.takeLast(period + 1).map { it.close }
        val changes = (1 until closes.size).map { closes[it] - closes[it - 1] }
        val averageGain = changes.map { it.coerceAtLeast(0.0) }.average()
        val averageLoss = changes.map { (-it).coerceAtLeast(0.0) }.average()
        if (!averageGain.isFinite() || !averageLoss.isFinite()) return null
        if (averageLoss <= 0.0) return 100.0
        val relativeStrength = averageGain / averageLoss
        return (100.0 - 100.0 / (1.0 + relativeStrength)).takeIf { it.isFinite() }
    }
}

class DerivDemoBotService : Service() {
    companion object {
        const val ACTION_START = "com.example.service.DERIV_DEMO_START"
        const val ACTION_PAUSE = "com.example.service.DERIV_DEMO_PAUSE"
        const val ACTION_RESUME = "com.example.service.DERIV_DEMO_RESUME"
        const val ACTION_STOP = "com.example.service.DERIV_DEMO_STOP"
        const val EXTRA_SYMBOL = "symbol"
        const val EXTRA_STRATEGY = "strategy"
        const val EXTRA_TIMEFRAME = "timeframe"
        private const val CHANNEL_ID = "deriv_demo_bot"
        private const val NOTIFICATION_ID = 1701
        private const val STATUS_STATE = "service_status_state"
        private const val STATUS_REASON = "service_status_reason"
        private const val MAX_RECONNECT_ATTEMPTS = 5

        private fun secureStore(context: Context): com.example.security.SecretsManager? {
            val applicationStore = (context.applicationContext as? MyApplication)
                ?.let { app -> runCatching { app.secretsManager }.getOrNull() }
            val store = applicationStore
                ?: runCatching { com.example.security.SecretsManager(context.applicationContext) }.getOrNull()
            return store?.takeIf { it.isSecureStorageAvailable }
        }
        private fun derivUtcDayKey(nowMs: Long = System.currentTimeMillis()): String {
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return formatter.format(java.util.Date(nowMs))
        }
        private val _state = MutableStateFlow("STOPPED")
        val state: StateFlow<String> = _state.asStateFlow()
        private val _statusReason = MutableStateFlow("BOT NOT RUNNING — not started")
        val statusReason: StateFlow<String> = _statusReason.asStateFlow()

        fun hasNotificationPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        fun recordStartBlocked(context: Context, reason: String) {
            val exact = reason.trim().take(240).let {
                if (it.startsWith("BOT NOT RUNNING")) it else "BOT NOT RUNNING — $it"
            }
            publishStatus(context, "STOPPED", exact, disableRecovery = true)
        }

        fun restorePersistedStatus(context: Context) {
            if (_state.value in setOf("STARTING", "CONNECTING", "RECONCILING", "RUNNING")) return
            val secure = secureStore(context)
            val savedState = secure?.getDerivBotServiceState() ?: "STOPPED"
            val savedReason = secure?.getDerivBotServiceReason() ?: "BOT NOT RUNNING — secure storage unavailable"
            if (savedState in setOf("STARTING", "CONNECTING", "RECONCILING", "RUNNING")) {
                publishStatus(context, "STOPPED", "BOT NOT RUNNING — previous service state was not re-verified after process restart. Tap Start to reconnect.")
            } else {
                _state.value = savedState
                _statusReason.value = savedReason.ifBlank { "BOT NOT RUNNING — no active service status" }
            }
        }

        private fun publishStatus(context: Context, state: String, reason: String, disableRecovery: Boolean = false) {
            _state.value = state
            _statusReason.value = reason
            secureStore(context)?.saveDerivBotServiceStatus(state, reason, disableRecovery)
        }

        fun start(context: android.content.Context, symbol: String, strategy: String, timeframe: String) {
            if (!hasNotificationPermission(context)) {
                val reason = "BOT NOT RUNNING — enable DestinedFX notifications, then tap Start again."
                recordStartBlocked(context, reason)
                throw SecurityException(reason)
            }
            val intent = Intent(context, DerivDemoBotService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SYMBOL, symbol)
                putExtra(EXTRA_STRATEGY, strategy)
                putExtra(EXTRA_TIMEFRAME, timeframe)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                val reason = "Android restricted foreground-service startup. Open DestinedFX, allow notifications, and start again."
                recordStartBlocked(context, reason)
                throw IllegalStateException(reason, error)
            }
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, DerivDemoBotService::class.java).setAction(ACTION_STOP))
        }

        fun pause(context: android.content.Context) {
            context.startService(Intent(context, DerivDemoBotService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: android.content.Context) {
            context.startService(Intent(context, DerivDemoBotService::class.java).setAction(ACTION_RESUME))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null
    private var healthJob: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var pauseRequested = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        publishStatus(this, "STARTING", "BOT NOT RUNNING — service is starting and has not verified broker readiness")
        startForeground(NOTIFICATION_ID, notification("Starting — verifying selected Deriv account"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSafely()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseRequested = true
                update("PAUSED — monitoring continues; no new DEMO entries will be submitted")
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                pauseRequested = false
                update("RUNNING — DEMO entry scanning resumed after explicit owner action")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                stopRequested = false
                pauseRequested = false
                loopJob?.cancel()
                val symbol = intent.getStringExtra(EXTRA_SYMBOL)?.trim().orEmpty()
                val strategy = intent.getStringExtra(EXTRA_STRATEGY)?.trim().orEmpty()
                val timeframe = intent.getStringExtra(EXTRA_TIMEFRAME)?.trim().orEmpty()
                if (symbol.isBlank() || strategy.isBlank() || timeframe.isBlank() ||
                    !DerivMultiplierRules.isSupportedUnderlying(symbol) ||
                    strategy !in StrategyRegistry.managedStrategyIds ||
                    Timeframe.parse(timeframe) == null) {
                    (application as? MyApplication)?.secretsManager?.setDerivBotRecoveryEnabled(false)
                    stopSafely("BOT NOT RUNNING — incomplete or unsupported start intent; trading remains stopped")
                    return START_NOT_STICKY
                }
                if (!hasNotificationPermission(this)) {
                    stopSafely("BOT NOT RUNNING — notification permission is denied; allow notifications and tap Start again")
                    return START_NOT_STICKY
                }
                val startApp = application as? MyApplication
                val currentMode = startApp?.secretsManager?.getDerivAccountMode().orEmpty()
                val accepted = startApp?.secretsManager?.hasCurrentDerivDemoAcceptance() == true
                val policyReady = runCatching { startApp?.modeArmingPolicy }.getOrNull() != null
                val currentGeneration = runCatching { startApp?.secretsManager?.getDerivCredentialGeneration("demo") }.getOrNull().orEmpty()
                val hasCredentials = currentGeneration.isNotBlank() &&
                    startApp?.secretsManager?.getDerivAppId()?.isNotBlank() == true
                if (!policyReady || !hasCredentials || !DerivBotRecoveryPolicy.canStartExplicitDemo(currentMode, accepted, hasNotificationPermission(this))) {
                    stopSafely("BOT NOT RUNNING — select DEMO and complete current account verification and explicit acceptance first")
                    return START_NOT_STICKY
                }
                val secure = startApp?.secretsManager
                if (secure == null || !secure.isSecureStorageAvailable ||
                    !secure.saveDerivBotRecoveryConfiguration(symbol, strategy, timeframe) ||
                    !secure.setDerivBotRecoveryEnabled(true)) {
                    stopSafely("BOT NOT RUNNING — encrypted Bot recovery settings could not be saved; trading remains stopped")
                    return START_NOT_STICKY
                }
                update("STARTING — verifying selected account; no order requests will be sent")
                loopJob = serviceScope.launch { runBot(symbol, strategy, timeframe) }
            }
            null -> {
                // Recovery is deliberately DEMO-only. A process restart must never turn a
                // persisted REAL configuration into a new live session.
                val app = application as? MyApplication
                val mode = app?.secretsManager?.getDerivAccountMode()?.lowercase()
                val secure = app?.secretsManager
                val symbol = secure?.getDerivBotRecoverySymbol().orEmpty()
                val strategy = secure?.getDerivBotRecoveryStrategy().orEmpty()
                val timeframe = secure?.getDerivBotRecoveryTimeframe().orEmpty()
                val hasCredentials = app?.secretsManager?.getDerivApiToken("demo")?.isNotBlank() == true &&
                    app?.secretsManager?.getDerivAppId()?.isNotBlank() == true &&
                    app?.secretsManager?.getDerivCredentialGeneration("demo")?.isNotBlank() == true &&
                    app?.let { runCatching { it.modeArmingPolicy }.isSuccess } == true
                val accepted = app?.secretsManager?.hasCurrentDerivDemoAcceptance() == true
                if (DerivBotRecoveryPolicy.canRecoverAfterProcessDeath(
                        mode.orEmpty(), secure?.isDerivBotRecoveryEnabled() == true, hasCredentials, accepted,
                        symbol, strategy, timeframe, hasNotificationPermission(this)
                    )) {
                    stopRequested = false
                    loopJob?.cancel()
                    loopJob = serviceScope.launch { runBot(symbol, strategy, timeframe) }
                } else {
                    stopSafely(if (mode == "real") "BOT NOT RUNNING — persisted REAL configuration is never restarted automatically" else "BOT NOT RUNNING — saved DEMO configuration, current acceptance, credentials, or notifications are unavailable")
                    return START_NOT_STICKY
                }
            }
            else -> {
                stopSafely("BOT NOT RUNNING — unsupported or missing service action; explicit DEMO start is required")
                return START_NOT_STICKY
            }
        }
        return if (intent?.action == ACTION_START || intent == null) START_STICKY else START_NOT_STICKY
    }

    private suspend fun runBot(symbol: String, strategyId: String, timeframe: String) {
        val app = application as MyApplication
        val gateway = app.derivBrokerGateway
        if (!DerivMultiplierRules.isSupportedUnderlying(symbol)) {
            stopSafely("Safety blocked — $symbol is not a supported Deriv Multiplier underlying")
            return
        }
        // This public Deriv OHLC feed is Bot-owned and deliberately independent of Analyzer.
        val botMarketDataGateway = DerivMarketDataGateway()
        currentCoroutineContext()[Job]?.invokeOnCompletion { botMarketDataGateway.disconnect() }
        // Keep the user-started foreground service alive through transient broker outages.
        // This is especially important after a persisted daily-loss lock: reconnect and retry
        // broker-confirmed closure rather than silently abandoning an unresolved managed contract.
        while (currentCoroutineContext().isActive && !stopRequested &&
            gateway.connectionState.value != com.example.data.deriv.DerivConnectionState.READY
        ) {
            if (!app.secretsManager.getDerivAccountMode().equals("demo", true)) {
                stopSafely("BOT NOT RUNNING — Deriv mode changed from DEMO; this service will not open a REAL session")
                return
            }
            if (connectWithBoundedRetry(gateway)) break
            update("RECONNECTING — Deriv connection retries continue; no new purchases are permitted")
            delay(15_000L)
        }
        if (!currentCoroutineContext().isActive || stopRequested) return
        val acceptedAccount = gateway.accountInfo.value
        if (acceptedAccount == null || !DerivBotRecoveryPolicy.isDemoAccount(acceptedAccount.mode, acceptedAccount.accountType) ||
            acceptedAccount.credentialGeneration.isBlank() ||
            acceptedAccount.credentialGeneration != app.secretsManager.getDerivCredentialGeneration("demo") ||
            !app.secretsManager.hasCurrentDerivDemoAcceptance(acceptedAccount.loginId, acceptedAccount.currency, credentialGeneration = acceptedAccount.credentialGeneration)) {
            stopSafely("PAUSED — current authenticated DEMO account does not match the accepted verification record")
            return
        }
        val modeIdentity = com.example.data.broker.ModeAccountIdentity(
            path = com.example.data.broker.ModePath.DERIV_OPTIONS,
            broker = "DERIV",
            accountId = acceptedAccount.loginId,
            server = "",
            mode = com.example.data.broker.AccountMode.DEMO,
            credentialGeneration = acceptedAccount.credentialGeneration
        )
        val checkedAtMs = System.currentTimeMillis()
        val currentMetrics = gateway.accountMetrics.value
        val currentPortfolio = gateway.portfolioSnapshot.value
        val balanceFresh = gateway.accountMetricsUpdatedAtMs.value > 0L &&
            checkedAtMs - gateway.accountMetricsUpdatedAtMs.value in 0..8_000L &&
            currentMetrics?.currency.equals(acceptedAccount.currency, ignoreCase = true) &&
            currentMetrics?.balance?.let { it.isFinite() && it >= 0.0 } == true
        val portfolioFresh = currentPortfolio?.let {
            it.successful && it.accountId == acceptedAccount.loginId &&
                it.accountMode == "DEMO" && it.currency.equals(acceptedAccount.currency, true) &&
                checkedAtMs - it.retrievedAtMs in 0..8_000L
        } == true
        val quote = gateway.getQuote(symbol).getOrNull()
        val quoteFresh = quote?.timestampMs?.let {
            it > 0L && it <= checkedAtMs && checkedAtMs - it <= 15_000L
        } == true
        val readOnlyVerification = com.example.data.broker.ModeReadOnlyVerification(
            identity = modeIdentity,
            checkedAtMs = checkedAtMs,
            authenticatedSession = gateway.connectionState.value == com.example.data.deriv.DerivConnectionState.READY,
            freshAccountData = balanceFresh && portfolioFresh,
            freshQuote = quoteFresh,
            freshHeartbeat = false,
            accountIdentityMatched = gateway.accountInfo.value?.let {
                it.loginId == acceptedAccount.loginId && it.mode.equals("demo", true) &&
                    it.accountType.equals("demo", true) && it.currency.equals(acceptedAccount.currency, true)
            } == true
        )
        val pathAcceptance = app.secretsManager.getPathDemoAcceptance(modeIdentity)
        val connectorValidation = app.universalConnectorDispatcher.validateVerification(
            com.example.data.broker.ConnectorKind.DERIV_OPTIONS,
            com.example.data.broker.ConnectorVerification(
                kind = com.example.data.broker.ConnectorKind.DERIV_OPTIONS,
                evidence = com.example.data.broker.ConnectorEvidence(
                    authenticatedSession = readOnlyVerification.authenticatedSession,
                    freshAccountData = readOnlyVerification.freshAccountData,
                    accountIdentityMatched = readOnlyVerification.accountIdentityMatched,
                    freshQuote = readOnlyVerification.freshQuote
                ),
                detail = "Deriv Options account, balance, portfolio and quote evidence",
                identity = modeIdentity,
                readOnlyVerification = readOnlyVerification,
                demoAcceptance = pathAcceptance,
                currentDemoCredentialGeneration = app.secretsManager.getDerivCredentialGeneration("demo")
            )
        )
        if (connectorValidation.isFailure) {
            stopSafely("BOT NOT RUNNING - DEMO arming blocked: " + (connectorValidation.exceptionOrNull()?.message ?: "MODE_POLICY_BLOCKED"))
            return
        }
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            while (currentCoroutineContext().isActive && !stopRequested) {
                if (_state.value == "RUNNING") {
                    val account = gateway.accountInfo.value
                    val metrics = gateway.accountMetrics.value
                    val portfolio = gateway.portfolioSnapshot.value
                    val now = System.currentTimeMillis()
                    val accountMatches = account != null && DerivBotRecoveryPolicy.isDemoAccount(account.mode, account.accountType) &&
                        account.loginId == acceptedAccount.loginId && account.currency == acceptedAccount.currency &&
                        account.credentialGeneration == acceptedAccount.credentialGeneration &&
                        account.credentialGeneration == app.secretsManager.getDerivCredentialGeneration("demo") &&
                        app.secretsManager.getDerivAccountMode().equals("demo", true) &&
                        app.secretsManager.hasCurrentDerivDemoAcceptance(account.loginId, account.currency, credentialGeneration = account.credentialGeneration)
                    val balanceFresh = metrics != null && metrics.balance.isFinite() && metrics.balance > 0.0 &&
                        account != null && metrics.currency.equals(account.currency, true) &&
                        now - gateway.accountMetricsUpdatedAtMs.value in 0..8_000L
                    val portfolioFresh = portfolio?.successful == true && account != null &&
                        portfolio.accountId == acceptedAccount.loginId && portfolio.accountId == account.loginId &&
                        portfolio.accountMode == "DEMO" && portfolio.currency.equals(account.currency, true) &&
                        now - portfolio.retrievedAtMs in 0..8_000L
                    if (gateway.connectionState.value != com.example.data.deriv.DerivConnectionState.READY ||
                        !accountMatches || !balanceFresh || !portfolioFresh
                    ) update("RECONNECTING — authenticated accepted DEMO session or fresh matched risk data is unavailable; purchases paused")
                }
                delay(1_000L)
            }
        }
        suspend fun verifyDerivDailyRiskAtEntryBoundary(accountId: String, mode: String): Boolean {
            val utcDay = derivUtcDayKey()
            if (app.secretsManager.isDerivDailyRiskLocked(accountId, mode, utcDay)) return false
            val currentAccount = gateway.accountInfo.value ?: return false
            if (currentAccount.loginId != accountId || currentAccount.mode.uppercase() != mode.uppercase()) return false
            if (gateway.connectionState.value != com.example.data.deriv.DerivConnectionState.READY) return false
            gateway.fetchPortfolio()
            val metrics = gateway.accountMetrics.value ?: return false
            val balanceAge = System.currentTimeMillis() - gateway.accountMetricsUpdatedAtMs.value
            val portfolio = gateway.portfolioSnapshot.value ?: return false
            val portfolioAge = System.currentTimeMillis() - portfolio.retrievedAtMs
            if (!metrics.balance.isFinite() || metrics.balance < 0.0 ||
                !metrics.currency.equals(currentAccount.currency, ignoreCase = true) ||
                balanceAge !in 0..8_000L ||
                !portfolio.successful || portfolio.accountId != accountId ||
                portfolio.accountMode != mode.uppercase() ||
                !portfolio.currency.equals(currentAccount.currency, ignoreCase = true) ||
                portfolioAge !in 0..8_000L || !portfolio.exposure.isFinite() ||
                portfolio.contracts.any { !it.profit.isFinite() }
            ) return false
            val currentEquity = metrics.balance + portfolio.contracts.filterNot { it.isSold }.sumOf { it.profit }
            val baseline = app.secretsManager.getDerivDailyRiskStartingEquity(accountId, mode, utcDay) ?: return false
            val snapshot = DerivDailyRiskMonitor.evaluate(
                baseline, currentEquity, TradingSafetyLimits.MAX_DAILY_LOSS_PERCENT,
                app.secretsManager.isDerivDailyRiskLocked(accountId, mode, utcDay)
            )
            return when (snapshot.status) {
                com.example.data.deriv.DerivDailyRiskSnapshot.Status.NORMAL -> true
                com.example.data.deriv.DerivDailyRiskSnapshot.Status.WARNING_80 -> {
                    if (app.secretsManager.markDerivDailyRiskWarningSent(accountId, mode, utcDay, 80)) {
                        update("Running — 80% daily-loss warning; ${"%.1f".format(snapshot.limitUsedPercent)}% of daily limit consumed")
                    }
                    true
                }
                com.example.data.deriv.DerivDailyRiskSnapshot.Status.WARNING_90 -> {
                    app.secretsManager.markDerivDailyRiskWarningSent(accountId, mode, utcDay, 80)
                    if (app.secretsManager.markDerivDailyRiskWarningSent(accountId, mode, utcDay, 90)) {
                        update("Running — 90% daily-loss critical warning; ${"%.1f".format(snapshot.limitUsedPercent)}% of daily limit consumed")
                    }
                    true
                }
                com.example.data.deriv.DerivDailyRiskSnapshot.Status.LOCKED -> {
                    app.secretsManager.lockDerivDailyRisk(accountId, mode, utcDay)
                    false
                }
                com.example.data.deriv.DerivDailyRiskSnapshot.Status.DATA_UNAVAILABLE -> false
            }
        }

        val executor = DerivMultiplierExecutor(
            gateway,
            app.db.derivExecutionDao(),
            sensitiveActionAuthorized = { com.example.security.AppSecurityGuard.authorizeSensitiveAction() },
            dailyRiskEntryAllowed = { accountId, mode ->
                mode.equals("demo", ignoreCase = true) && accountId == acceptedAccount.loginId &&
                    app.secretsManager.hasCurrentDerivDemoAcceptance(
                        acceptedAccount.loginId, acceptedAccount.currency, credentialGeneration = acceptedAccount.credentialGeneration
                    ) &&
                    verifyDerivDailyRiskAtEntryBoundary(accountId, mode)
            }
        )
        if (!reconcileWithRecovery(executor, gateway)) return
        update("Running — scanning $symbol; no order is assumed confirmed")
        while (currentCoroutineContext().isActive && !stopRequested) {
            try {
                if (!app.secretsManager.getDerivAccountMode().equals("demo", true)) {
                    stopSafely("BOT NOT RUNNING — Deriv mode changed from DEMO; this service will not reconnect or trade REAL")
                    return
                }
                // Reconcile the portfolio and all prior intents before every new scan.
                // Retryable transport failures reconnect; ambiguous execution outcomes
                // stop the bot for explicit manual review.
                if (!reconcileWithRecovery(executor, gateway)) return
                val account = gateway.accountInfo.value
                if (account == null) {
                    stopSafely("Safety blocked — verified Deriv account snapshot is unavailable")
                    return
                }
                if (account.loginId != acceptedAccount.loginId || account.currency != acceptedAccount.currency ||
                    account.credentialGeneration != acceptedAccount.credentialGeneration ||
                    !app.secretsManager.hasCurrentDerivDemoAcceptance(
                        acceptedAccount.loginId, acceptedAccount.currency, credentialGeneration = acceptedAccount.credentialGeneration
                    )
                ) {
                    stopSafely("Safety blocked — authenticated account or accepted credential generation changed; no new purchase is permitted")
                    return
                }
                if (!DerivBotRecoveryPolicy.isDemoAccount(account.mode, account.accountType) || !DerivMultiplierRules.isVerifiedAccount(account)) {
                    stopSafely("Safety blocked — broker account identity/type is not verified; execution is disabled")
                    return
                }
                val metrics = gateway.accountMetrics.value
                val balanceAge = System.currentTimeMillis() - gateway.accountMetricsUpdatedAtMs.value
                if (metrics == null || !metrics.balance.isFinite() || metrics.balance < 0.0 ||
                    !metrics.currency.equals(account.currency, ignoreCase = true) || balanceAge !in 0..8_000L) {
                    update("RECONNECTING — fresh verified ${account.mode.uppercase()} balance is unavailable; purchases paused")
                    if (!connectWithBoundedRetry(gateway)) {
                        stopSafely("ERROR — could not refresh the authenticated ${account.mode.uppercase()} balance; trading remains stopped")
                        return
                    }
                    continue
                }
                val balance = metrics.balance
                val utcDay = derivUtcDayKey()
                val portfolio = gateway.portfolioSnapshot.value
                val portfolioAge = portfolio?.let { System.currentTimeMillis() - it.retrievedAtMs } ?: Long.MAX_VALUE
                if (portfolio == null || !portfolio.successful || portfolio.accountId != account.loginId ||
                    portfolio.accountMode != account.mode.uppercase() ||
                    !portfolio.currency.equals(account.currency, ignoreCase = true) ||
                    portfolioAge !in 0..8_000L || portfolio.contracts.any { !it.profit.isFinite() }
                ) {
                    update("RECONNECTING — daily risk cannot be verified from a fresh account-matched Deriv portfolio; purchases paused")
                    if (!connectWithBoundedRetry(gateway)) {
                        stopSafely("ERROR — Deriv daily risk data could not be refreshed; trading remains stopped")
                        return
                    }
                    continue
                }
                val floatingProfit = portfolio.contracts.filterNot { it.isSold }.sumOf { it.profit }
                val currentEquity = balance + floatingProfit
                if (!currentEquity.isFinite()) {
                    stopSafely("Safety blocked — Deriv daily equity is not finite; no new entry is permitted")
                    return
                }
                val existingDailyBaseline = app.secretsManager.getDerivDailyRiskStartingEquity(account.loginId, account.mode, utcDay)
                if (existingDailyBaseline == null &&
                    !DerivDailyRiskMonitor.canInitializeDailyBaseline(System.currentTimeMillis())
                ) {
                    stopSafely("Safety blocked — Deriv daily-risk baseline was not captured near UTC midnight; new entries remain blocked until the next UTC day or a supported broker-history baseline is available")
                    return
                }
                if (!app.secretsManager.initializeDerivDailyRiskDay(account.loginId, account.mode, utcDay, currentEquity)) {
                    stopSafely("Safety blocked — secure Deriv daily-risk baseline could not be persisted")
                    return
                }
                val startEquity = app.secretsManager.getDerivDailyRiskStartingEquity(account.loginId, account.mode, utcDay)
                val dailyLocked = app.secretsManager.isDerivDailyRiskLocked(account.loginId, account.mode, utcDay)
                val dailyRisk = DerivDailyRiskMonitor.evaluate(
                    startEquity,
                    currentEquity,
                    TradingSafetyLimits.MAX_DAILY_LOSS_PERCENT,
                    dailyLocked
                )
                if (dailyRisk.status == com.example.data.deriv.DerivDailyRiskSnapshot.Status.DATA_UNAVAILABLE) {
                    stopSafely("Safety blocked — Deriv daily-loss calculation is unavailable; no new entry is permitted")
                    return
                }
                if (dailyRisk.status == com.example.data.deriv.DerivDailyRiskSnapshot.Status.LOCKED) {
                    if (!app.secretsManager.lockDerivDailyRisk(account.loginId, account.mode, utcDay)) {
                        stopSafely("Safety blocked — Deriv daily-loss lock could not be persisted; trading remains stopped")
                        return
                    }
                    update("Safety blocked — DERIV DAILY LOSS LIMIT REACHED; new entries locked, attempting broker-confirmed close of DestinedFX contracts")
                    val confirmedClosed = executor.closeBotOwnedContracts()
                    update(if (confirmedClosed) {
                        "Safety blocked — DAILY LOSS LOCKED; broker confirms all DestinedFX-managed contracts are settled. Lock remains until the next UTC day."
                    } else {
                        "Safety blocked — DAILY LOSS LOCKED; one or more DestinedFX contract closures remain unconfirmed. Monitoring and retrying; check broker account immediately."
                    })
                    delay(10_000)
                    continue
                }
                val warningMessages = mutableListOf<String>()
                if (dailyRisk.limitUsedPercent >= TradingSafetyLimits.DAILY_WARNING_80_PERCENT &&
                    app.secretsManager.markDerivDailyRiskWarningSent(account.loginId, account.mode, utcDay, 80)
                ) warningMessages += "80% daily-loss warning"
                if (dailyRisk.limitUsedPercent >= TradingSafetyLimits.DAILY_WARNING_90_PERCENT &&
                    app.secretsManager.markDerivDailyRiskWarningSent(account.loginId, account.mode, utcDay, 90)
                ) warningMessages += "90% daily-loss critical warning"
                if (warningMessages.isNotEmpty()) {
                    update("Running — ${warningMessages.joinToString(" + ")}; ${"%.1f".format(dailyRisk.limitUsedPercent)}% of daily limit consumed")
                }
                if (pauseRequested) {
                    update("PAUSED — monitoring continues; no new DEMO entries will be submitted")
                    delay(5_000L)
                    continue
                }
                val stake = DerivMultiplierRules.safeStakeForBalance(balance)
                val minimumStake = com.destinedfx.core.broker.DerivContractLimits.getMinimumStake(
                    symbol, DerivMultiplierProduct.MULTUP.contractType
                )
                if (stake < minimumStake) {
                    stopSafely("Safety blocked — 1% risk cap permits $stake, below broker minimum $minimumStake; no order was sent")
                    return
                }
                // syncKnownContracts() is already performed by reconcileWithRecovery()
                // against the same fresh, account-matched portfolio snapshot.
                if (executor.hasActiveContracts()) {
                    update("Running — an existing contract is still active; waiting for broker settlement before another entry")
                    delay(10_000)
                    continue
                }
                val selectedTimeframe = Timeframe.parse(timeframe)
                if (selectedTimeframe == null) {
                    update("Running — BotStrategy WAIT: unsupported timeframe; no order was sent")
                    delay(30_000)
                    continue
                }
                // Deriv serves W1 as daily history; aggregate complete daily bars into UTC weeks.
                val historyGranularitySeconds = if (selectedTimeframe == Timeframe.W1) {
                    Timeframe.D1.minutes * 60
                } else {
                    selectedTimeframe.minutes * 60
                }
                val historyCount = if (selectedTimeframe == Timeframe.W1) 1_800 else 300
                val rawBotCandles = botMarketDataGateway.fetchCandles(
                    symbol,
                    historyGranularitySeconds,
                    historyCount
                )
                val botCandles = if (selectedTimeframe == Timeframe.W1) {
                    BotStrategy.aggregateWeekly(rawBotCandles)
                } else {
                    rawBotCandles
                }
                val currentQuote = gateway.getQuote(symbol).getOrNull()
                val signal = currentQuote?.let {
                    BotStrategy.generateSignal(
                        strategyId = strategyId,
                        symbol = symbol,
                        timeframe = selectedTimeframe,
                        candles = botCandles,
                        quote = it,
                        nowMs = System.currentTimeMillis()
                    )
                }
                val planError = signal?.let {
                    DerivMultiplierRules.planConsistencyError(it.direction, it.entryPrice, it.stopLoss, it.takeProfit)
                }
                if (planError != null) {
                    update("Running — BotStrategy signal rejected: ${planError.take(120)}")
                } else if (signal != null && signal.direction in setOf(SignalDirection.LONG, SignalDirection.SHORT)) {
                    val stableId = signal.id.takeIf { it.isNotBlank() }
                    if (stableId == null) {
                        update("Running — BotStrategy signal rejected: stable setup identity is unavailable")
                        delay(10_000)
                        continue
                    }
                    val intent = DerivMultiplierIntent(
                        signalId = stableId,
                        symbol = symbol,
                        strategyId = strategyId,
                        strategyVersion = signal.strategyVersion,
                        direction = signal.direction,
                        product = if (signal.direction == SignalDirection.LONG) DerivMultiplierProduct.MULTUP else DerivMultiplierProduct.MULTDOWN,
                        stake = stake,
                        currency = account.currency,
                        multiplier = 10,
                        stopLossAmount = stake,
                        takeProfitAmount = stake * TradingSafetyLimits.MIN_REWARD_TO_RISK,
                        signalTimestampMs = signal.timestampMs
                    )
                    update("Submitting — broker proposal required for ${intent.product.contractType}")
                    val result = executor.submit(intent)
                    if (result.isSuccess) update("Contract confirmed — ${result.getOrThrow().contractId}; monitoring broker state")
                    else {
                        val reason = result.exceptionOrNull()?.message?.take(180) ?: "Execution blocked"
                        // An ambiguous purchase or unresolved ledger record requires manual review.
                        // A transient broker/portfolio/balance failure must reconnect and reconcile
                        // before another scan; it must never be presented as a normal skipped entry.
                        when {
                            reason.startsWith("EXECUTION_UNKNOWN") || reason.startsWith("RECONCILIATION_REQUIRED") ->
                                stopSafely("Safety blocked — $reason. Manual reconciliation is required.")
                            reason.contains("REAL_EXECUTION_DISABLED") ->
                                stopSafely("Safety blocked — REAL authorization expired or changed. Re-verify readiness and explicitly arm this account again.")
                            reason.startsWith("PORTFOLIO_RETRYABLE") || reason.startsWith("PORTFOLIO_REQUIRED") || reason.startsWith("BALANCE_REQUIRED") -> {
                                update("RECONNECTING — $reason; purchases paused")
                                if (!connectWithBoundedRetry(gateway)) {
                                    stopSafely("ERROR — reconnect exhausted after portfolio validation failure; trading remains stopped")
                                    return
                                }
                            }
                            reason.contains("DAILY_RISK_RECHECK_FAILED") -> {
                                val utcDay = derivUtcDayKey()
                                val dailyLocked = app.secretsManager.isDerivDailyRiskLocked(account.loginId, account.mode, utcDay)
                                if (dailyLocked) {
                                    update("Safety blocked — Deriv daily-loss limit locked at the purchase boundary; attempting to close DestinedFX-managed contracts")
                                    val closed = executor.closeBotOwnedContracts()
                                    update(if (closed) {
                                        "Safety blocked — daily lock remains active; broker confirms DestinedFX-managed contracts settled"
                                    } else {
                                        "Safety blocked — daily lock remains active; broker has not confirmed all DestinedFX-managed contracts settled"
                                    })
                                    delay(10_000)
                                } else {
                                    stopSafely("Safety blocked — Deriv risk could not be reverified immediately before purchase; trading remains stopped")
                                    return
                                }
                            }
                            else -> update("Running — entry skipped: $reason")
                        }
                    }
                } else {
                    update("Running — BotStrategy WAIT: no confirmed setup from fresh closed Deriv candles and quote")
                }
                delay(30_000)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: Exception) {
                update("RECONNECTING — purchases paused: ${error.message?.take(140)}")
                if (!connectWithBoundedRetry(gateway)) {
                    stopSafely("ERROR — bounded reconnect policy exhausted; reconciliation required")
                    return
                }
            }
        }
    }

    private suspend fun reconcileWithRecovery(
        executor: DerivMultiplierExecutor,
        gateway: com.example.data.deriv.DerivBrokerGateway
    ): Boolean {
        // Retryable transport/portfolio failures are not terminal service failures. Keep the
        // foreground monitor alive, preserve the account/mode daily lock, and retry until the
        // broker is reachable. A manual reconciliation requirement remains a hard stop.
        while (currentCoroutineContext().isActive && !stopRequested) {
            if ((application as? MyApplication)?.secretsManager?.getDerivAccountMode()?.equals("demo", true) != true) {
                stopSafely("BOT NOT RUNNING — Deriv mode changed from DEMO; reconciliation and REAL login are blocked")
                return false
            }
            val result = executor.reconcileBeforeNewEntriesResult()
            when (result.status) {
                DerivReconciliationStatus.READY -> {
                    if (executor.syncKnownContracts()) return true
                    update("RECONNECTING — fresh portfolio refresh failed; purchases remain paused")
                }
                DerivReconciliationStatus.MANUAL_RECONCILIATION_REQUIRED -> {
                    stopSafely("Safety blocked — ${result.reason}. Manual broker reconciliation is required; no new purchase will be sent.")
                    return false
                }
                DerivReconciliationStatus.RETRYABLE_FAILURE -> {
                    update("RECONNECTING — ${result.reason.take(140)}; purchases remain paused")
                }
            }
            if (gateway.connectionState.value != com.example.data.deriv.DerivConnectionState.READY) {
                if (!connectWithBoundedRetry(gateway)) {
                    update("RECONNECTING — bounded attempt batch exhausted; the foreground service will retry")
                    delay(15_000L)
                }
            } else {
                delay(5_000L)
            }
        }
        return false
    }

    private suspend fun connectWithBoundedRetry(gateway: com.example.data.deriv.DerivBrokerGateway): Boolean {
        val job = currentCoroutineContext()[Job]
        val connected = retryFreshDerivAuthorization(
            maxAttempts = MAX_RECONNECT_ATTEMPTS,
            shouldContinue = {
                !stopRequested && job?.isActive != false &&
                    (application as? MyApplication)?.secretsManager?.getDerivAccountMode()?.equals("demo", true) == true
            },
            onAttempt = { attempt ->
                update(if (attempt == 0) "CONNECTING — verifying selected Deriv account" else "RECONNECTING — attempt ${attempt + 1}/$MAX_RECONNECT_ATTEMPTS")
            },
            pause = { delay(it) },
            reconnect = {
                // Each call repeats account listing and OTP retrieval; never reuse an earlier one-time OTP.
                gateway.connectAndAuthorize(expectedMode = "demo")
            }
        )
        if (connected) update("RECONCILING — fresh authenticated broker session established")
        return connected
    }

    private fun stopSafely(message: String = "Stopped — no new entry requests") {
        stopRequested = true
        (application as? MyApplication)?.secretsManager?.setDerivBotRecoveryEnabled(false)
        loopJob?.cancel()
        loopJob = null
        healthJob?.cancel()
        healthJob = null
        update(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(text: String) {
        val nextState = when {
            text.startsWith("Running") || text.startsWith("Submitting") || text.startsWith("Contract") -> "RUNNING"
            text.startsWith("STARTING") -> "STARTING"
            text.startsWith("CONNECTING") || text.startsWith("Connecting") || text.startsWith("RECONNECTING") -> "CONNECTING"
            text.startsWith("RECONCILING") -> "RECONCILING"
            text.startsWith("ERROR") -> "ERROR"
            text.startsWith("BOT NOT RUNNING") -> "STOPPED"
            text.startsWith("Stopped") -> "STOPPED"
            text.startsWith("Safety blocked") -> "PAUSED"
            else -> "PAUSED"
        }
        val reason = when {
            nextState == "RUNNING" -> text
            text.startsWith("BOT NOT RUNNING") -> text
            else -> "BOT NOT RUNNING — $text"
        }
        publishStatus(this, nextState, reason)
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("DestinedFX · Deriv DEMO bot · ${_state.value}")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
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
        healthJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
