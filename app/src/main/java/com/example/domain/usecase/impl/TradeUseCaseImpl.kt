package com.example.domain.usecase.impl

import com.destinedfx.core.analysis.*
import com.destinedfx.core.execution.DuplicateGuard
import com.destinedfx.core.instrument.AssetClassifier
import com.destinedfx.core.instrument.SpecRegistry
import com.destinedfx.core.journal.JournalEntry
import com.destinedfx.core.marketdata.*
import com.destinedfx.core.news.NewsFilter
import com.destinedfx.core.risk.AccountState
import com.destinedfx.core.risk.OpenRisk
import com.destinedfx.core.risk.RealizedPnl
import com.destinedfx.core.risk.RiskEngine
import com.destinedfx.core.risk.SizingResult
import com.destinedfx.core.session.SessionEngine
import com.destinedfx.core.trade.TradeDecision
import com.destinedfx.core.trade.TradeValidationPipeline
import com.destinedfx.core.trade.TradingConfig
import com.destinedfx.core.trade.ValidationContext
import com.example.data.broker.BrokerGateway
import com.example.data.trade.TradeEntity
import com.example.data.trade.TradeRepository
import com.example.domain.model.CanonicalTarget
import com.example.domain.model.CanonicalTradePlan
import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal
import com.example.domain.repository.MT5Repository
import com.example.domain.repository.MarketDataRepository
import com.example.domain.repository.NewsRepository
import com.example.domain.usecase.TradeUseCase
import kotlinx.coroutines.flow.first
import java.util.UUID
import com.destinedfx.core.marketdata.Candle as CoreCandle

class TradeBlockedException(val decision: TradeDecision) : Exception(decision.explanation)

/**
 * Execution gate. Everything runs through [TradeValidationPipeline]; there is no second validation path. A rejected attempt is journaled with its reasons.
 * BrokerGateway is the canonical source of truth for balance, equity, margin and open positions.
 */
class TradeUseCaseImpl(
    private val market: MarketDataRepository,
    private val brokerProvider: () -> BrokerGateway,
    private val journal: TradeRepository,
    private val news: NewsRepository,
    private val reports: SignalReportStore,
    private val config: suspend () -> TradingConfig = { TradingConfig() },
    private val guard: DuplicateGuard = DuplicateGuard(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = 45_000L
) : TradeUseCase {

    // Backward-compatible secondary constructor for MT5-only invocations
    constructor(
        market: MarketDataRepository,
        mt5: MT5Repository,
        journal: TradeRepository,
        news: NewsRepository,
        reports: SignalReportStore,
        config: suspend () -> TradingConfig = { TradingConfig() },
        guard: DuplicateGuard = DuplicateGuard(),
        clock: () -> Long = System::currentTimeMillis,
        pollIntervalMs: Long = 45_000L
    ) : this(
        market = market,
        brokerProvider = {
            if (mt5 is com.example.data.mt5.MT5GatewayRepository) {
                com.example.data.broker.MT5BrokerGatewayAdapter(mt5)
            } else {
                throw UnsupportedOperationException("Unsupported MT5 repository instance")
            }
        },
        journal = journal,
        news = news,
        reports = reports,
        config = config,
        guard = guard,
        clock = clock,
        pollIntervalMs = pollIntervalMs
    )

    override suspend fun executeTrade(signal: TradeSignal) {
        val now = clock(); val cfg = config(); val symbol = signal.symbol
        val broker = brokerProvider()
        val ticker = market.tickerFlow.value[symbol]
        val quote = ticker?.let { if (it.bid != null && it.ask != null && it.hasRealSpread) MarketQuote.twoSided(symbol, AssetClassifier.of(symbol), it.bid, it.ask, it.lastUpdateMillis, it.lastUpdateMillis, it.source)
            else MarketQuote.lastOnly(symbol, AssetClassifier.of(symbol), it.price, it.lastUpdateMillis, it.lastUpdateMillis, it.source) }
        val health = MarketDataHealthEvaluator.evaluate(quote, now, ticker != null && ticker.kind != "DAILY_REFERENCE",
            cfg = if (ticker?.kind == "STREAM") HealthConfig() else HealthConfig.polled(pollIntervalMs))

        val stored = reports.reportFor(signal.id); val manual = stored == null
        val isLong = signal.direction == SignalDirection.LONG
        val tf = Timeframe.parse(signal.timeframe) ?: Timeframe.H1
        val report = stored ?: SignalEngine.manualReport(symbol, tf, now, quote?.source)
        val plan: TradePlan? = if (!manual) null else RiskEngine.rewardToRisk(signal.entryPrice, signal.stopLoss, signal.takeProfit, isLong)?.let {
            val t = Target("TP1", signal.takeProfit, it, "manual"); TradePlan(isLong, signal.entryPrice, signal.stopLoss, 0.0, listOf(t), t, "user-specified") }

        val metrics = broker.accountMetrics.value
        val account = metrics?.let { AccountState(it.balance, it.equity, it.freeMargin, null, "ACC") }
        val spec = SpecRegistry.get(symbol) ?: broker.loadSymbolSpec(symbol).getOrNull()
        val effPlan = plan ?: report.plan
        val prelim = if (effPlan != null && spec != null && account != null) RiskEngine.positionSize(account, cfg.riskPercent, effPlan.entry, effPlan.stopLoss, spec, cfg.commissionPerLot) as? SizingResult.Sized else null
        val reqMargin = prelim?.let { broker.calculateRequiredMargin(symbol, it.lots).getOrNull() }
        val positions = broker.openPositions.value
        val open = positions.map { p -> val sp = SpecRegistry.get(p.symbol)
            val risk = if (sp != null && account != null && account.balance > 0) kotlin.math.abs(p.entryPrice - p.stopLoss) / sp.tickSize * sp.tickValue * p.lotSize / account.balance * 100 else cfg.riskPercent
            OpenRisk(p.symbol, p.direction == SignalDirection.LONG, risk, sp?.baseCurrency ?: p.symbol.take(3), sp?.profitCurrency ?: p.symbol.takeLast(3)) }
        val closed = journal.allTrades.first().filter { it.closeTimestamp != null }.map { RealizedPnl(it.closeTimestamp ?: 0L, it.plAmount) }
        val atr = report.structure?.atr ?: market.getCandles(symbol, "H1", 60).filter { it.complete }
            .map { CoreCandle(it.timestamp, it.open, it.high, it.low, it.close) }.takeIf { it.size > 20 }?.let { Indicators.lastAtr(it) }
        val key = guard.key(signal.id, symbol, isLong)
        val brokerQuote = broker.getBrokerQuote(symbol).getOrNull()

        val decision = TradeValidationPipeline.validate(ValidationContext(
            report, health, quote, NewsFilter.evaluate(news.calendar(), AssetClassifier.newsCurrencies(symbol), now), SessionEngine.state(now), spec, account,
            broker.isConnected(), reqMargin, account?.balance ?: 0.0, closed, (metrics?.equity ?: 0.0) - (metrics?.balance ?: 0.0), open, now, cfg,
            duplicateOrder = guard.isDuplicate(key), analysisGatesRequired = !manual, planOverride = plan, atr = atr, brokerQuote = brokerQuote))

        if (!decision.approved || effPlan == null) {
            record(signal, 0.0, "BLOCKED", decision.explanation, report, now)
            throw TradeBlockedException(decision)
        }

        // Recalculate from broker quote if available
        val bq = brokerQuote ?: quote
        val executionPrice = if (isLong) bq?.ask ?: bq?.mid else bq?.bid ?: bq?.mid
        val brokerPlan = if (executionPrice != null && executionPrice > 0.0 && spec != null) {
            val p = effPlan
            val diff = executionPrice - p.entry
            val newSl = spec.roundToTick(p.stopLoss + diff)
            val targets = p.targets.map { it.copy(price = spec.roundToTick(it.price + diff)) }
            p.copy(entry = executionPrice, stopLoss = newSl, targets = targets, primary = targets.first())
        } else effPlan

        if (brokerPlan != null && spec != null) {
            val failedChecks = com.destinedfx.core.trade.PostBrokerChecks.evaluate(
                plan = brokerPlan,
                spec = spec,
                minRR = cfg.minRR,
                quote = quote,
                brokerQuote = brokerQuote,
                atr = atr,
                nowMs = now
            )
            if (failedChecks.isNotEmpty()) {
                val firstFailed = failedChecks.first()
                record(signal, 0.0, "BLOCKED", "BLOCKED: ${firstFailed.detail}", report, now)
                throw TradeBlockedException(decision.copy(approved = false, checks = decision.checks + failedChecks))
            }
        }

        val finalSizing = if (brokerPlan != null && spec != null && account != null) {
            RiskEngine.positionSize(account, cfg.riskPercent, brokerPlan.entry, brokerPlan.stopLoss, spec, cfg.commissionPerLot) as? SizingResult.Sized
        } else decision.sizing

        if (finalSizing == null || brokerPlan == null) {
            record(signal, 0.0, "BLOCKED", "Sizing failure after broker recalculation", report, now)
            throw IllegalStateException("Sizing failure after broker recalculation")
        }

        if (!guard.tryClaim(key)) { record(signal, 0.0, "BLOCKED", "Duplicate order", report, now); throw IllegalStateException("Duplicate order blocked") }

        // Execute via unified BrokerGateway using CanonicalTradePlan
        val canonicalPlan = CanonicalTradePlan(
            symbol = signal.symbol,
            broker = broker.displayName,
            provider = quote?.source ?: "NATIVE",
            timeframe = signal.timeframe,
            strategy = report.strategyVersion,
            direction = signal.direction,
            status = report.state.name,
            entry = brokerPlan.entry,
            bid = bq?.bid ?: brokerPlan.entry,
            ask = bq?.ask ?: brokerPlan.entry,
            spread = quote?.spread ?: 0.0,
            stopLoss = brokerPlan.stopLoss,
            takeProfit = brokerPlan.primary.price,
            rr = brokerPlan.primary.rr,
            riskPercent = cfg.riskPercent,
            riskAmount = finalSizing.moneyRiskActual,
            positionSize = finalSizing.lots,
            invalidation = brokerPlan.stopLoss,
            timestamp = now,
            marketDataTimestamp = quote?.timestampMs ?: now,
            dataHealth = health.name,
            reason = report.reasons.firstOrNull() ?: "Verified technical setup",
            strategyVersion = report.strategyVersion,
            confluenceScore = report.confluenceScore,
            targets = brokerPlan.targets.map { CanonicalTarget(it.label, it.price, it.rr, it.source) },
            newsStatus = "CLEAR"
        )

        val result = broker.executeTrade(canonicalPlan)
        val orderRef = result.getOrNull()
        record(signal, finalSizing.lots, if (result.isSuccess) "OPEN" else "FAILED", result.exceptionOrNull()?.message ?: "broker=${broker.displayName} orderRef=$orderRef", report, now, orderRef)
        if (result.isFailure) { guard.release(key); throw Exception(result.exceptionOrNull()?.message ?: "Execution failed") }
    }

    private suspend fun record(s: TradeSignal, lots: Double, status: String, note: String, r: SignalReport, now: Long, ticket: String? = null) {
        journal.insertTrade(TradeEntity(ticketId = ticket, symbol = s.symbol, positionType = if (s.direction == SignalDirection.LONG) "BUY" else "SELL", entryPrice = s.entryPrice,
            stopLoss = s.stopLoss, takeProfit = s.takeProfit, rationale = "[$status] ${note.take(400)} | score=${r.confluenceScore}/100 mtf=${r.mtf?.state} strategy=${r.strategyVersion} src=${r.dataSource}",
            status = status, pipsProfit = 0, plAmount = 0.0, lotSize = lots, setupType = r.state.name, timestamp = now))
    }
}
