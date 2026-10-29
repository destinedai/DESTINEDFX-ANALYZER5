package com.example.domain.usecase.impl

import com.destinedfx.core.analysis.*
import com.destinedfx.core.instrument.AssetClassifier
import com.destinedfx.core.instrument.SpecRegistry
import com.destinedfx.core.marketdata.AssetClass
import com.destinedfx.core.marketdata.CandleSelector
import com.destinedfx.core.marketdata.CandleValidator
import com.destinedfx.core.marketdata.HealthConfig
import com.destinedfx.core.marketdata.MarketDataHealth
import com.destinedfx.core.marketdata.MarketDataHealthEvaluator
import com.destinedfx.core.marketdata.MarketQuote
import com.destinedfx.core.marketdata.Timeframe
import com.destinedfx.core.marketdata.VolumeType
import com.destinedfx.core.news.NewsFilter
import com.destinedfx.core.news.NewsStatus
import com.destinedfx.core.session.SessionEngine as CoreSession
import com.example.domain.model.MarketTicker
import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal
import com.example.domain.repository.MarketDataRepository
import com.example.domain.repository.NewsRepository
import com.example.domain.usecase.SignalGenerationUseCase
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.example.domain.model.Candle as LegacyCandle
import com.destinedfx.core.marketdata.Candle as CoreCandle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/** Lets the trade use case fetch the verified analysis behind a signal. */
interface SignalReportStore { fun reportFor(signalId: String): SignalReport? }

/**
 * Thin adapter: real quote -> health -> validated candles -> [SignalEngine] (the ONE scoring implementation) -> TradeSignal for the UI.
 * No scores, SLs or TPs are computed here.
 */
class SignalGenerationUseCaseImpl(
    private val market: MarketDataRepository,
    private val news: NewsRepository,
    private val cfg: SignalConfig = SignalConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = 45_000L
) : SignalGenerationUseCase, SignalReportStore {

    data class TimingRecord(val loadMs: Long, val computeMs: Long, val totalMs: Long, val cacheHit: Boolean, val timestamp: Long)
    private val timingHistory = ConcurrentHashMap<String, MutableList<TimingRecord>>()
    override fun getTimingHistory(symbol: String): List<TimingRecord> = timingHistory[symbol]?.toList() ?: emptyList()

    private val reports = ConcurrentHashMap<String, SignalReport>()
    override fun reportFor(signalId: String): SignalReport? = reports[signalId]

    private val failedFetches = ConcurrentHashMap<String, Long>() // key: "symbol-tf", value: last failed timestamp

    override suspend fun generateSignal(symbol: String, timeframe: String, strategyId: String): TradeSignal =
        generateSignal(
            symbol = symbol,
            timeframe = timeframe,
            strategyId = strategyId,
            selectedMtfTimeframes = setOf("M5", "M15", "M30", "H1", "H4", "D1", "W1")
        )

    suspend fun generateSignal(
        symbol: String,
        timeframe: String,
        strategyId: String,
        selectedMtfTimeframes: Set<String>
    ): TradeSignal {
        val startTotal = clock()
        return try {
            var now = clock()
            val stratDef = com.destinedfx.core.strategy.StrategyRegistry.allStrategies.find { it.id == strategyId }
            if (stratDef != null && !stratDef.isImplemented) {
                return unavailable(symbol, timeframe, "Strategy '${stratDef.name}' is not implemented", "NOT_IMPLEMENTED")
            }

            val tf = Timeframe.parse(timeframe) ?: return unavailable(symbol, timeframe, "Unsupported timeframe '$timeframe'", "INVALID")
            var ticker = market.tickerFlow.value[symbol]
            // Do not reuse an old ticker forever just because a stale entry remains in the flow.
            // Refresh when absent or >10 seconds old; validation below still blocks stale data.
            if (ticker == null || now - ticker.lastUpdateMillis > 10_000L) {
                val refreshed = market.refreshSymbol(symbol)
                if (refreshed != null) ticker = refreshed
            }
            // A daily reference rate is not a market quote. Look for real timestamped OHLC instead.
            if (ticker?.kind == "DAILY_REFERENCE") ticker = null

            val cls = AssetClassifier.of(symbol)
            var quote: MarketQuote? = ticker?.let { toQuote(it) }
            if (quote == null) {
                val referenceCandle = market.getCandles(symbol, timeframe, 2)
                    .lastOrNull { it.close.isFinite() && it.close > 0.0 && it.timestamp > 0L }
                if (referenceCandle != null) {
                    val source = market.candleSources[com.example.data.market.candleSourceKey(symbol, timeframe)]
                        ?: "Historical candles"
                    // This timestamp is historical by construction. It can support analysis only.
                    quote = MarketQuote.lastOnly(
                        symbol = symbol,
                        cls = cls,
                        last = referenceCandle.close,
                        tsMs = referenceCandle.timestamp,
                        rxMs = referenceCandle.timestamp,
                        source = source + " • historical reference (analysis only)"
                    )
                }
            }
            if (quote == null) {
                return unavailable(symbol, timeframe, "NO QUOTE OR VALID HISTORICAL CANDLE for " + symbol + " " + timeframe, "DISCONNECTED")
            }
            // refreshSymbol() may return a quote captured after the scan began. Anchor health,
            // strategy analysis, news and session checks to the current clock after refresh so
            // a valid freshly-received quote is not falsely treated as future-dated.
            now = clock()
            val hc = if (ticker?.kind == "STREAM") HealthConfig() else HealthConfig.polled(pollIntervalMs)
            val health = if (ticker == null) MarketDataHealth.STALE
                else MarketDataHealthEvaluator.evaluate(quote, now, connected = true, cfg = hc)
            // STALE is accepted for a historical research readout, but is never executable below.
            if (health !in setOf(MarketDataHealth.LIVE, MarketDataHealth.DELAYED, MarketDataHealth.STALE)) {
                return unavailable(symbol, timeframe, "Market data " + health.name, health.name)
            }
            val startLoad = clock()
            suspend fun load(t: Timeframe, n: Int): List<CoreCandle> {
                val cacheKey = "$symbol-${t.label}"
                val lastFailed = failedFetches[cacheKey] ?: 0L
                if (now - lastFailed < 2_000L) {
                    return emptyList()
                }

                val raw = market.getCandles(symbol, t.label, n)
                if (raw.isEmpty()) {
                    failedFetches[cacheKey] = clock()
                    return emptyList()
                } else {
                    failedFetches.remove(cacheKey)
                }
                
                val kind = if (raw.sumOf { it.volume } <= 0.0) VolumeType.NONE else if (cls == AssetClass.CRYPTO) VolumeType.REAL else VolumeType.TICK
                val coreCandles = raw.map { CoreCandle(it.timestamp, it.open, it.high, it.low, it.close, it.volume, kind, null, it.complete) }
                return CandleSelector.closedOnly(coreCandles)
            }
            val main = load(tf, 250)
            val chk = CandleValidator.validate(main, tf, 60, tolerateWeekendGaps = cls != AssetClass.CRYPTO)
            if (!chk.ok) return unavailable(symbol, timeframe, "DATA ERROR: " + chk.problems.firstOrNull(), "GAP_DETECTED")
            
            val displayTargets = selectedMtfTimeframes
                .mapNotNull { Timeframe.parse(it) }
                .distinct()
                .filter { it != tf }
            // Defensive fallback; UI ensures an MTF context other than the primary timeframe is selected.
            val effectiveMtfTargets = displayTargets.ifEmpty {
                listOf(Timeframe.H4, Timeframe.D1, Timeframe.W1).filter { it != tf }.take(1)
            }
            val targets = effectiveMtfTargets.distinct()
            val htf = coroutineScope {
                targets.map { t ->
                    async {
                        try {
                            withTimeout(30_000) {
                                t to load(t, if (t == Timeframe.W1) 80 else 150)
                            }
                        } catch (e: TimeoutCancellationException) {
                            failedFetches["$symbol-${t.label}"] = clock()
                            t to emptyList<CoreCandle>()
                        }
                    }
                }.awaitAll().toMap()
            }
            val endLoad = clock()
            val loadMs = endLoad - startLoad

            val spec = SpecRegistry.get(symbol)
            val prevComputations = SignalEngine.structureComputations.get()
            val startCompute = clock()
            val dynamicCfg = cfg.copy(mtf = MtfConfig(required = effectiveMtfTargets))
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { 
                SignalEngine.analyse(AnalysisInput(symbol, tf, chk.cleanClosed, htf, quote, spec, now, strategyId = strategyId), dynamicCfg)
            }
            val endCompute = clock()
            val computeMs = endCompute - startCompute
            val newComputations = SignalEngine.structureComputations.get()
            val cacheHit = (newComputations == prevComputations)
            val totalMs = loadMs + computeMs

            val record = TimingRecord(loadMs, computeMs, totalMs, cacheHit, now)
            val history = timingHistory.computeIfAbsent(symbol) { java.util.Collections.synchronizedList(mutableListOf()) }
            synchronized(history) {
                history.add(record)
                if (history.size > 20) history.removeAt(0)
            }

            val newsVerdict = NewsFilter.evaluate(news.calendar(), AssetClassifier.newsCurrencies(symbol), now)
            val sess = CoreSession.state(now)

            val blockers = report.blockers.toMutableList()
            var state = report.state
            if (!report.mtfComplete) {
                blockers += "MTF DATA INCOMPLETE"
                // Do not change state to BLOCKED here; let it stay WEAK_BUY etc. for display,
                // but the blocker ensures executable = false.
            }
            val spread = quote?.spread; val atr = report.structure?.atr
            if (spread != null && atr != null && spread > 0.15 * atr) { blockers += "SPREAD TOO WIDE: %.5f > 15%% of ATR".format(spread); state = SignalState.BLOCKED }
            if (newsVerdict.status == NewsStatus.HIGH_IMPACT_WINDOW) { blockers += newsVerdict.detail; state = SignalState.BLOCKED }
            if (newsVerdict.status == NewsStatus.UNKNOWN) { blockers += newsVerdict.detail; state = SignalState.BLOCKED }
            if (health != MarketDataHealth.LIVE) blockers += "Market data is " + health.name + " (historical/delayed): analysis only, not executable"
            if (spread == null) blockers += "Spread unknown (source provides no bid/ask): not executable"
            // Only a plan produced by SignalEngine may be used. Never synthesize entry/SL/TP
            // values to turn a WAIT or an incomplete analysis into an apparent trade setup.
            val candidatePlan = report.plan
            var finalPlan = candidatePlan.takeIf { isValidExecutionPlan(it) }
            if (candidatePlan != null && finalPlan == null) {
                blockers += "INVALID TRADE PLAN: entry, stop, target, or actual reward/risk failed validation"
            }

            var finalState = state
            val buyStates = setOf(SignalState.STRONG_BUY, SignalState.BUY, SignalState.WEAK_BUY)
            val sellStates = setOf(SignalState.STRONG_SELL, SignalState.SELL, SignalState.WEAK_SELL)
            if (finalState in buyStates || finalState in sellStates) {
                when {
                    finalPlan == null -> {
                        if (candidatePlan == null) blockers += "NO VALID TRADE PLAN: analysis remains non-actionable"
                        finalState = SignalState.WAIT
                    }
                    finalState in buyStates && !finalPlan.isLong -> {
                        blockers += "TRADE PLAN DIRECTION MISMATCH: bullish state has a short plan"
                        finalPlan = null
                        finalState = SignalState.WAIT
                    }
                    finalState in sellStates && finalPlan.isLong -> {
                        blockers += "TRADE PLAN DIRECTION MISMATCH: bearish state has a long plan"
                        finalPlan = null
                        finalState = SignalState.WAIT
                    }
                }
            }

            val finalReport = report.copy(state = finalState, plan = finalPlan)
            val closedCandleTimestamp = chk.cleanClosed.lastOrNull()?.timeMs
                ?: return unavailable(symbol, timeframe, "NO CLOSED CANDLE available for stable signal identity", "DATA_UNAVAILABLE")
            // One stable signal identity per symbol/timeframe/strategy/closed candle. Repeated scans
            // of the same candle must not bypass execution duplicate guards.
            val id = stableAnalysisSignalId(symbol, tf.label, strategyId, closedCandleTimestamp)
            reports[id] = finalReport
            if (reports.size > 200) reports.keys.take(100).forEach { reports.remove(it) }
            val mtfPerTfMap = finalReport.mtf?.perTf?.mapKeys { it.key.label }?.mapValues { it.value.name } ?: emptyMap()
            val signalDirection = signalDirectionFor(finalState, finalPlan)

            TradeSignal(
                id = id, symbol = symbol, timeframe = timeframe,
                direction = signalDirection,
                entryPrice = finalPlan?.entry ?: 0.0,
                stopLoss = finalPlan?.stopLoss ?: 0.0,
                takeProfit = finalPlan?.primary?.price ?: 0.0,
                confidenceScore = finalReport.confluenceScore.coerceIn(0, 100),
                rationale = "Current Market Structure Analysis: ${finalReport.mtf?.summary ?: "No MTF summary available"}",
                timestamp = now,
                invalidationLevel = finalPlan?.stopLoss ?: 0.0,
                entryReason = finalReport.reasons.take(6).joinToString("; ").ifBlank { "No qualified entry reason was produced." },
                stopLossReason = finalPlan?.slReason ?: "N/A",
                takeProfitReason = finalPlan?.targets?.joinToString { "${it.label} ${it.price} (RR ${"%.2f".format(it.rr)}, ${it.source})" } ?: "N/A",
                nearestHighYieldZone = finalPlan?.primary?.let { "${it.label} @ ${it.price} (1:${"%.2f".format(it.rr)})" } ?: "No qualified target",
                profitZones = finalPlan?.targets?.map { com.example.domain.model.ProfitZone(it.label, it.price, it.rr, spec?.priceToPips(kotlin.math.abs(it.price - (finalPlan?.entry ?: 0.0))) ?: 0.0, "", it.source) } ?: emptyList(),
                keyLevels = finalReport.levels.filter { !it.swept }.take(12).map { "${it.kind}: ${it.price}" },
                strategyScores = finalReport.components.filter { it.isAvailable }.associate { it.name to (it.earned / it.available * 100).toInt().coerceIn(0, 100) },
                signalState = finalState.name,
                executable = health == MarketDataHealth.LIVE && spread != null && blockers.isEmpty() && signalDirection != SignalDirection.WAIT && finalPlan != null,
                blockReasons = blockers,
                reasons = finalReport.reasons,
                dataSource = quote?.source ?: "",
                dataHealth = health.name,
                mtfSummary = finalReport.mtf?.summary ?: "",
                mtfState = finalReport.mtf?.state?.name ?: "DATA_UNAVAILABLE",
                mtfPerTimeframe = mtfPerTfMap,
                spreadText = spread?.let { s -> spec?.priceToPips(s)?.let { "%.1f pips".format(it) } ?: "%.5f".format(s) } ?: "UNKNOWN",
                sessionLabel = sess.label,
                newsStatus = newsVerdict.status.name,
                knowledge = finalReport.knowledge,
                strategyVersion = finalReport.strategyVersion,
                analysisLoadMs = loadMs,
                analysisComputeMs = computeMs,
                analysisTotalMs = totalMs,
                cacheHit = cacheHit
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            unavailable(symbol, timeframe, "ANALYSIS UNAVAILABLE: ${e.javaClass.simpleName}", "SOURCE_ERROR")
        }
    }

    private fun toQuote(t: MarketTicker): MarketQuote? {
        val cls = AssetClassifier.of(t.symbol)
        return if (t.bid != null && t.ask != null && t.hasRealSpread) MarketQuote.twoSided(t.symbol, cls, t.bid, t.ask, t.lastUpdateMillis, t.lastUpdateMillis, t.source)
        else MarketQuote.lastOnly(t.symbol, cls, t.price, t.lastUpdateMillis, t.lastUpdateMillis, t.source)
    }

    private fun unavailable(symbol: String, tf: String, why: String, health: String) = TradeSignal(
        id = UUID.randomUUID().toString(), symbol = symbol, timeframe = tf, direction = SignalDirection.WAIT, entryPrice = 0.0, stopLoss = 0.0, takeProfit = 0.0,
        confidenceScore = 0, rationale = why, timestamp = clock(), signalState = SignalState.DATA_UNAVAILABLE.name, blockReasons = listOf(why), reasons = listOf(why), dataHealth = health)
}


/** Stable per-closed-candle identity keeps repeated scans from re-entering the same setup. */
internal fun stableAnalysisSignalId(symbol: String, timeframe: String, strategyId: String, closedCandleTimestamp: Long): String {
    val identity = listOf(
        symbol.trim().uppercase(java.util.Locale.ROOT),
        timeframe.trim().uppercase(java.util.Locale.ROOT),
        strategyId.trim(),
        closedCandleTimestamp.toString()
    ).joinToString("|")
    return UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
}

/** Validate the actual plan prices and reward/risk, not only the engine's reported RR field. */
internal fun isValidExecutionPlan(plan: com.destinedfx.core.analysis.TradePlan?): Boolean {
    if (plan == null) return false
    val entry = plan.entry
    val stop = plan.stopLoss
    val target = plan.primary.price
    if (!entry.isFinite() || !stop.isFinite() || !target.isFinite() || entry <= 0.0 || stop <= 0.0 || target <= 0.0) return false
    val riskDistance = if (plan.isLong) entry - stop else stop - entry
    val rewardDistance = if (plan.isLong) target - entry else entry - target
    if (!riskDistance.isFinite() || !rewardDistance.isFinite() || riskDistance <= 0.0 || rewardDistance <= 0.0) return false
    val actualRiskReward = rewardDistance / riskDistance
    return actualRiskReward.isFinite() && actualRiskReward >= 2.0
}

/** WAIT/BLOCKED states and plans that disagree on direction can never become a buy or sell. */
internal fun signalDirectionFor(
    state: SignalState,
    plan: com.destinedfx.core.analysis.TradePlan?
): SignalDirection {
    if (!isValidExecutionPlan(plan)) return SignalDirection.WAIT
    val validPlan = plan!!
    return when (state) {
        SignalState.STRONG_BUY, SignalState.BUY, SignalState.WEAK_BUY ->
            if (validPlan.isLong) SignalDirection.LONG else SignalDirection.WAIT
        SignalState.STRONG_SELL, SignalState.SELL, SignalState.WEAK_SELL ->
            if (!validPlan.isLong) SignalDirection.SHORT else SignalDirection.WAIT
        else -> SignalDirection.WAIT
    }
}

