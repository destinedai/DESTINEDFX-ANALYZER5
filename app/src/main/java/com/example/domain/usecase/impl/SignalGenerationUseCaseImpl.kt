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
            val now = clock()
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
                    val source = market.candleSources[symbol] ?: "Historical candles"
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
            if (newsVerdict.status == NewsStatus.UNKNOWN) blockers += newsVerdict.detail
            if (health != MarketDataHealth.LIVE) blockers += "Market data is " + health.name + " (historical/delayed): analysis only, not executable"
            if (spread == null) blockers += "Spread unknown (source provides no bid/ask): not executable"
            val tradeable = state in setOf(SignalState.STRONG_BUY, SignalState.BUY, SignalState.STRONG_SELL, SignalState.SELL)
            val final = report.copy(state = state, blockers = blockers)
            val id = UUID.randomUUID().toString(); reports[id] = final
            if (reports.size > 200) reports.keys.take(100).forEach { reports.remove(it) }
            val plan = final.plan
            val mtfPerTfMap = final.mtf?.perTf?.mapKeys { it.key.label }?.mapValues { it.value.name } ?: emptyMap()
            TradeSignal(
                id = id, symbol = symbol, timeframe = timeframe,
                direction = if (!tradeable && state != SignalState.WEAK_BUY && state != SignalState.WEAK_SELL) SignalDirection.WAIT else if (plan?.isLong == true) SignalDirection.LONG else SignalDirection.SHORT,
                entryPrice = plan?.entry ?: (quote?.mid ?: 0.0), stopLoss = plan?.stopLoss ?: 0.0, takeProfit = plan?.primary?.price ?: 0.0,
                confidenceScore = final.confluenceScore, rationale = "MTF: ${final.mtf?.summary ?: "n/a"}", timestamp = now, invalidationLevel = plan?.stopLoss ?: 0.0,
                entryReason = final.reasons.take(6).joinToString("; "), stopLossReason = plan?.slReason ?: "N/A",
                takeProfitReason = plan?.targets?.joinToString { "${it.label} ${it.price} (RR ${"%.2f".format(it.rr)}, ${it.source})" } ?: "N/A",
                nearestHighYieldZone = plan?.primary?.let { "${it.label} @ ${it.price} (1:${"%.2f".format(it.rr)})" } ?: "No active trade setup",
                profitZones = plan?.targets?.map { com.example.domain.model.ProfitZone(it.label, it.price, it.rr, spec?.priceToPips(kotlin.math.abs(it.price - plan.entry)) ?: 0.0, "", it.source) } ?: emptyList(),
                keyLevels = final.levels.filter { !it.swept }.take(12).map { "${it.kind}: ${it.price}" },
                strategyScores = final.components.filter { it.isAvailable }.associate { it.name to (it.earned / it.available * 100).toInt() },
                signalState = state.name, executable = tradeable && health == MarketDataHealth.LIVE && spread != null && blockers.isEmpty(),
                blockReasons = blockers, reasons = final.reasons, dataSource = quote?.source ?: "", dataHealth = health.name, mtfSummary = final.mtf?.summary ?: "",
                mtfState = final.mtf?.state?.name ?: "DATA_UNAVAILABLE",
                mtfPerTimeframe = mtfPerTfMap,
                spreadText = spread?.let { s -> spec?.priceToPips(s)?.let { "%.1f pips".format(it) } ?: "%.5f".format(s) } ?: "UNKNOWN",
                sessionLabel = sess.label, newsStatus = newsVerdict.status.name, knowledge = final.knowledge, strategyVersion = final.strategyVersion,
                analysisLoadMs = loadMs, analysisComputeMs = computeMs, analysisTotalMs = totalMs, cacheHit = cacheHit)
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
