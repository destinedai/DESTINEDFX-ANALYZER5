package com.destinedfx.core.bot

import android.util.Log
import com.destinedfx.core.analysis.*
import com.destinedfx.core.instrument.AssetClassifier
import com.destinedfx.core.instrument.SpecRegistry
import com.destinedfx.core.marketdata.*
import com.destinedfx.core.news.NewsFilter
import com.destinedfx.core.news.NewsStatus
import com.destinedfx.core.strategy.StrategyRegistry
import com.example.data.broker.BrokerGateway
import com.example.data.broker.BrokerManager
import com.example.data.trade.TradeRepository
import com.example.domain.model.CanonicalTradePlan
import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal
import com.example.domain.repository.MarketDataRepository
import com.example.domain.repository.NewsRepository
import com.example.domain.usecase.TradeUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class BotLifecycleState {
    STOPPED,
    STARTING,
    AUTHENTICATING,
    CONNECTING_BROKER,
    ACTIVE_SCANNING,
    EXECUTING,
    ERROR
}

data class BotStatusSummary(
    val state: BotLifecycleState = BotLifecycleState.STOPPED,
    val isRunning: Boolean = false,
    val activeBrokerName: String = "Deriv WebSocket",
    val selectedSymbol: String = "EURUSD",
    val selectedStrategy: String = "smc-confluence-1.0.0",
    val timeframe: String = "H1",
    val riskPercent: Double = 1.0,
    val marketState: String = "DISCONNECTED",
    val currentSignal: String = "WAIT",
    val lastAction: String = "Bot is idle",
    val lastError: String? = null,
    val lastExecutionRef: String? = null,
    val openPositionsCount: Int = 0
)

/**
 * Autonomous Bot Engine with strict institutional safety gates.
 * Refuses execution unless:
 * 1. The selected broker is authenticated and connected with the user's broker credentials
 * 2. Market data is LIVE (stale/historical data is analysis-only)
 * 3. The selected strategy is implemented
 * 4. Signal is VALID (not WAIT / INVALIDATED)
 * 5. Economic calendar news gate is CLEAR
 * 6. Risk is no more than 1.0%
 * 7. Reward-to-risk is at least 2.0R
 * 8. No duplicate position exists on the active instrument
 *
 * DestinedFX/Supabase app login is optional and is deliberately not an execution gate.
 */
class AutonomousBotEngine(
    private val marketDataRepo: MarketDataRepository,
    private val newsRepo: NewsRepository,
    private val tradeUseCase: TradeUseCase,
    private val tradeRepo: TradeRepository,
    private val brokerManager: BrokerManager
) {
    companion object {
        private const val TAG = "AutonomousBotEngine"
    }

    private val _status = MutableStateFlow(BotStatusSummary())
    val status: StateFlow<BotStatusSummary> = _status.asStateFlow()

    private var botJob: Job? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun startBot(
        symbol: String,
        strategyId: String,
        timeframe: String,
        riskPercent: Double
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        // Gate 1: Strategy implementation
        val strat = StrategyRegistry.allStrategies.find { it.id == strategyId }
        if (strat == null || !strat.isImplemented) {
            val err = "STRATEGY_NOT_IMPLEMENTED: Strategy '$strategyId' is not implemented."
            _status.value = _status.value.copy(state = BotLifecycleState.ERROR, lastError = err, lastAction = "Activation rejected: strategy unimplemented")
            return@withContext Result.failure(Exception(err))
        }

        // Gate 2: Timeframe execution eligibility (15m minimum for website/institutional strategy workflows)
        val tfParsed = Timeframe.parse(timeframe)
        val executionEligibleTimeframes = setOf(Timeframe.M15, Timeframe.M30, Timeframe.H1, Timeframe.H4, Timeframe.D1, Timeframe.W1)
        if (tfParsed == null || tfParsed !in executionEligibleTimeframes) {
            val err = "TIMEFRAME_INELIGIBLE: Timeframe '$timeframe' is view-only. Execution requires minimum 15m (M15, M30, H1, H4, D1, W1)."
            _status.value = _status.value.copy(state = BotLifecycleState.ERROR, lastError = err, lastAction = "Activation rejected: timeframe ineligible")
            return@withContext Result.failure(Exception(err))
        }

        // Gate 3: Instrument resolution
        val isDerivActive = brokerManager.activeBroker.value.brokerType == com.example.data.broker.BrokerType.DERIV
        if (isDerivActive && !com.destinedfx.core.broker.DerivInstrumentResolver.isSupported(symbol)) {
            val err = "UNSUPPORTED_INSTRUMENT: Symbol '$symbol' is not supported on Deriv."
            _status.value = _status.value.copy(state = BotLifecycleState.ERROR, lastError = err, lastAction = "Activation rejected: unsupported instrument")
            return@withContext Result.failure(Exception(err))
        }

        // Gate 4: Risk limit strictly <= 1%
        val clampedRisk = riskPercent.coerceIn(0.1, 1.0)

        // Cancel previous job if running
        botJob?.cancelAndJoin()
        botJob = null

        val broker = brokerManager.activeBroker.value
        _status.value = _status.value.copy(
            state = BotLifecycleState.CONNECTING_BROKER,
            activeBrokerName = broker.displayName,
            selectedSymbol = symbol,
            selectedStrategy = strategyId,
            timeframe = timeframe,
            riskPercent = clampedRisk,
            lastAction = "Connecting to ${broker.displayName}...",
            lastError = null,
            isRunning = true
        )

        // Connect broker if not already connected
        if (!broker.isConnected()) {
            val connectResult = broker.connect()
            if (connectResult.isFailure) {
                val err = "BROKER_DISCONNECTED: Failed to authenticate with ${broker.displayName}: ${connectResult.exceptionOrNull()?.message}"
                _status.value = _status.value.copy(state = BotLifecycleState.ERROR, lastError = err, isRunning = false, lastAction = "Broker connect failed")
                return@withContext Result.failure(Exception(err))
            }
        }

        _status.value = _status.value.copy(
            state = BotLifecycleState.ACTIVE_SCANNING,
            lastAction = "Bot active and scanning $symbol on $timeframe"
        )

        // Launch autonomous evaluation loop
        botJob = engineScope.launch {
            runAutonomousLoop(this, symbol, strategyId, timeframe, clampedRisk)
        }

        Result.success(true)
    }

    fun stopBot() {
        botJob?.cancel()
        botJob = null
        _status.value = _status.value.copy(
            state = BotLifecycleState.STOPPED,
            isRunning = false,
            lastAction = "Bot stopped cleanly by user",
            lastError = null
        )
        Log.i(TAG, "Autonomous bot stopped cleanly.")
    }

    private suspend fun runAutonomousLoop(
        scope: CoroutineScope,
        symbol: String,
        strategyId: String,
        timeframe: String,
        riskPercent: Double
    ) {
        val broker = brokerManager.activeBroker.value
        while (scope.isActive) {
            try {
                val now = System.currentTimeMillis()
                val tf = Timeframe.parse(timeframe) ?: Timeframe.H1

                // 1. Check broker connection
                if (!broker.isConnected()) {
                    _status.value = _status.value.copy(
                        lastError = "BROKER_DISCONNECTED: Reconnecting to ${broker.displayName}...",
                        lastAction = "Waiting for broker reconnection"
                    )
                    broker.connect()
                    delay(5_000)
                    continue
                }

                // 2. Market Quote & Health Check
                val ticker = marketDataRepo.tickerFlow.value[symbol]
                val quote = ticker?.let {
                    if (it.bid != null && it.ask != null && it.hasRealSpread)
                        MarketQuote.twoSided(symbol, AssetClassifier.of(symbol), it.bid, it.ask, it.lastUpdateMillis, it.lastUpdateMillis, it.source)
                    else
                        MarketQuote.lastOnly(symbol, AssetClassifier.of(symbol), it.price, it.lastUpdateMillis, it.lastUpdateMillis, it.source)
                }

                val health = MarketDataHealthEvaluator.evaluate(quote, now, ticker != null && ticker.kind != "DAILY_REFERENCE")
                val isLive = health == MarketDataHealth.LIVE
                _status.value = _status.value.copy(marketState = health.name)

                if (!isLive || quote == null) {
                    _status.value = _status.value.copy(
                        lastAction = "DATA_STALE: Execution prohibited on ${health.name} data. Requires MarketDataHealth.LIVE"
                    )
                    delay(10_000)
                    continue
                }

                // 3. Economic News Gate Check (CLEAR allowed; HIGH_IMPACT and UNKNOWN block automated execution)
                val calendar = newsRepo.calendar()
                val newsVerdict = NewsFilter.evaluate(calendar, AssetClassifier.newsCurrencies(symbol), now)
                if (newsVerdict.blocksAutomation(manualRiskMode = false)) {
                    val blockReason = if (newsVerdict.status == NewsStatus.HIGH_IMPACT_WINDOW) "NEWS_BLOCKED: High-impact event window" else "NEWS_UNKNOWN: News status unknown/unverified"
                    _status.value = _status.value.copy(
                        lastAction = "$blockReason (${newsVerdict.detail})"
                    )
                    delay(30_000)
                    continue
                }

                // 4. Duplicate Position Check
                val openPositions = broker.openPositions.value
                _status.value = _status.value.copy(openPositionsCount = openPositions.size)
                val alreadyOpen = openPositions.any { it.symbol.replace("/", "").equals(symbol.replace("/", ""), ignoreCase = true) }
                if (alreadyOpen) {
                    _status.value = _status.value.copy(
                        lastAction = "DUPLICATE_GUARD: Position already active on $symbol"
                    )
                    delay(30_000)
                    continue
                }

                // 5. Candles & Technical Analysis
                val candles = marketDataRepo.getCandles(symbol, tf.label, 150)
                if (candles.size < 60) {
                    _status.value = _status.value.copy(
                        lastAction = "WAIT: Accumulating candles (${candles.size}/60)"
                    )
                    delay(15_000)
                    continue
                }

                val coreCandles = candles.map {
                    Candle(
                        timeMs = it.timestamp,
                        open = it.open,
                        high = it.high,
                        low = it.low,
                        close = it.close,
                        volume = it.volume,
                        complete = it.complete
                    )
                }

                val spec = SpecRegistry.get(symbol) ?: broker.loadSymbolSpec(symbol).getOrNull()
                val analysisInput = AnalysisInput(
                    symbol = symbol,
                    tf = tf,
                    candles = coreCandles,
                    htf = emptyMap(),
                    quote = quote,
                    spec = spec,
                    nowMs = now,
                    strategyId = strategyId
                )

                val report = SignalEngine.analyse(analysisInput, SignalConfig(minRR = 2.0))
                val signalStateStr = report.state.name
                _status.value = _status.value.copy(currentSignal = signalStateStr)

                // 6. Signal State Evaluation
                if (report.state == SignalState.WAIT || report.state == SignalState.INVALIDATED || report.state == SignalState.DATA_UNAVAILABLE || report.state == SignalState.BLOCKED) {
                    val waitReason = report.blockers.firstOrNull() ?: report.reasons.firstOrNull() ?: "No valid confluence trigger"
                    _status.value = _status.value.copy(
                        lastAction = "Scanning: $signalStateStr ($waitReason)"
                    )
                    delay(20_000)
                    continue
                }

                val plan = report.plan
                if (plan == null) {
                    _status.value = _status.value.copy(lastAction = "WAIT: No trade plan generated")
                    delay(20_000)
                    continue
                }

                // 7. Strict 2R Rule Enforcement
                if (plan.primary.rr < 2.0) {
                    _status.value = _status.value.copy(
                        lastAction = "NO_VALID_TARGET: RR (${String.format("%.2f", plan.primary.rr)}) below minimum 2.0R"
                    )
                    delay(20_000)
                    continue
                }

                // 8. Execute Trade
                _status.value = _status.value.copy(
                    state = BotLifecycleState.EXECUTING,
                    lastAction = "Executing ${if (plan.isLong) "BUY" else "SELL"} on $symbol @ ${plan.entry} (RR ${String.format("%.2f", plan.primary.rr)})"
                )

                val tradeSig = TradeSignal(
                    id = UUID.randomUUID().toString(),
                    symbol = symbol,
                    timeframe = tf.label,
                    direction = if (plan.isLong) SignalDirection.LONG else SignalDirection.SHORT,
                    entryPrice = plan.entry,
                    stopLoss = plan.stopLoss,
                    takeProfit = plan.primary.price,
                    confidenceScore = report.confluenceScore,
                    rationale = report.reasons.joinToString(" | "),
                    timestamp = now,
                    invalidationLevel = plan.stopLoss,
                    entryReason = "Automated execution: $strategyId",
                    stopLossReason = "Structural invalidation",
                    takeProfitReason = plan.primary.label,
                    nearestHighYieldZone = "",
                    profitZones = emptyList(),
                    keyLevels = emptyList(),
                    strategyScores = emptyMap(),
                    signalState = report.state.name,
                    executable = true,
                    blockReasons = emptyList(),
                    reasons = report.reasons,
                    dataSource = quote.source,
                    dataHealth = health.name,
                    mtfSummary = report.mtf?.state?.name ?: "ALIGNED",
                    spreadText = "${quote.spread ?: 0.0}",
                    sessionLabel = "",
                    newsStatus = "CLEAR",
                    knowledge = emptyList(),
                    strategyVersion = report.strategyVersion,
                    analysisLoadMs = 0L,
                    analysisComputeMs = 0L,
                    analysisTotalMs = 0L,
                    cacheHit = false
                )

                try {
                    tradeUseCase.executeTrade(tradeSig)
                    _status.value = _status.value.copy(
                        state = BotLifecycleState.ACTIVE_SCANNING,
                        lastAction = "CONFIRMED: Executed ${tradeSig.direction} on $symbol @ ${plan.entry}",
                        lastExecutionRef = tradeSig.id
                    )
                    Log.i(TAG, "Bot execution successful for $symbol: ${tradeSig.id}")
                } catch (e: Exception) {
                    _status.value = _status.value.copy(
                        state = BotLifecycleState.ACTIVE_SCANNING,
                        lastError = "Execution rejected: ${e.message}",
                        lastAction = "Execution rejected: ${e.message?.take(80)}"
                    )
                    Log.w(TAG, "Bot execution rejected: ${e.message}")
                }

                // Wait before next scan cycle to prevent tight loops
                delay(30_000)

            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Autonomous loop error: ${e.message}", e)
                _status.value = _status.value.copy(
                    lastError = "Loop error: ${e.javaClass.simpleName} - ${e.message}",
                    lastAction = "Transient error, retrying scan in 15s"
                )
                delay(15_000)
            }
        }
    }
}
