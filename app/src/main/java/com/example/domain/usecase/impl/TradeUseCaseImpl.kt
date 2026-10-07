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
import com.example.data.trade.TradeEntity
import com.example.data.trade.TradeRepository
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
 * Broker is the source of truth for balance, equity, margin and open positions.
 */
class TradeUseCaseImpl(
    private val market: MarketDataRepository, private val mt5: MT5Repository, private val journal: TradeRepository,
    private val news: NewsRepository, private val reports: SignalReportStore,
    private val config: suspend () -> TradingConfig = { TradingConfig() }, private val guard: DuplicateGuard = DuplicateGuard(),
    private val clock: () -> Long = System::currentTimeMillis, private val pollIntervalMs: Long = 45_000L
) : TradeUseCase {

    override suspend fun executeTrade(signal: TradeSignal) {
        val now = clock(); val cfg = config(); val symbol = signal.symbol
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

        val metrics = mt5.getAccountMetrics().getOrNull()
        val account = metrics?.let { AccountState(it.balance, it.equity, it.freeMargin, null, "ACC") }
        val spec = SpecRegistry.get(symbol) ?: mt5.loadSymbolSpec(symbol).getOrNull()
        val effPlan = plan ?: report.plan
        val prelim = if (effPlan != null && spec != null && account != null) RiskEngine.positionSize(account, cfg.riskPercent, effPlan.entry, effPlan.stopLoss, spec, cfg.commissionPerLot) as? SizingResult.Sized else null
        val reqMargin = prelim?.let { mt5.calculateRequiredMargin(symbol, it.lots).getOrNull() }
        val positions = mt5.getOpenPositions().getOrNull()
        val open = positions?.map { p -> val sp = SpecRegistry.get(p.symbol)
            val risk = if (sp != null && account != null && account.balance > 0) kotlin.math.abs(p.entryPrice - p.stopLoss) / sp.tickSize * sp.tickValue * p.lotSize / account.balance * 100 else cfg.riskPercent
            OpenRisk(p.symbol, p.direction == SignalDirection.LONG, risk, sp?.baseCurrency ?: p.symbol.take(3), sp?.profitCurrency ?: p.symbol.takeLast(3)) } ?: emptyList()
        val closed = journal.allTrades.first().filter { it.closeTimestamp != null }.map { RealizedPnl(it.closeTimestamp ?: 0L, it.plAmount) }
        val atr = report.structure?.atr ?: market.getCandles(symbol, "H1", 60).filter { it.complete }
            .map { CoreCandle(it.timestamp, it.open, it.high, it.low, it.close) }.takeIf { it.size > 20 }?.let { Indicators.lastAtr(it) }
        val key = guard.key(signal.id, symbol, isLong)
        val brokerQuote = mt5.getBrokerQuote(symbol).getOrNull()

        val decision = TradeValidationPipeline.validate(ValidationContext(
            report, health, quote, NewsFilter.evaluate(news.calendar(), AssetClassifier.newsCurrencies(symbol), now), SessionEngine.state(now), spec, account,
            mt5.isConnected() && positions != null, reqMargin, account?.balance ?: 0.0, closed, (metrics?.equity ?: 0.0) - (metrics?.balance ?: 0.0), open, now, cfg,
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
        val requestId = "REQ-" + UUID.randomUUID()
        val result = mt5.executeTrade(signal.copy(entryPrice = brokerPlan.entry, stopLoss = brokerPlan.stopLoss, takeProfit = brokerPlan.primary.price), finalSizing.lots, requestId)
        record(signal, finalSizing.lots, if (result.isSuccess) "OPEN" else "FAILED", result.exceptionOrNull()?.message ?: "requestId=$requestId order=${result.getOrNull()}", report, now, result.getOrNull())
        if (result.isFailure) { guard.release(key); throw Exception(result.exceptionOrNull()?.message ?: "Execution failed") }
    }

    private suspend fun record(s: TradeSignal, lots: Double, status: String, note: String, r: SignalReport, now: Long, ticket: String? = null) {
        journal.insertTrade(TradeEntity(ticketId = ticket, symbol = s.symbol, positionType = if (s.direction == SignalDirection.LONG) "BUY" else "SELL", entryPrice = s.entryPrice,
            stopLoss = s.stopLoss, takeProfit = s.takeProfit, rationale = "[$status] ${note.take(400)} | score=${r.confluenceScore}/100 mtf=${r.mtf?.state} strategy=${r.strategyVersion} src=${r.dataSource}",
            status = status, pipsProfit = 0, plAmount = 0.0, lotSize = lots, setupType = r.state.name, timestamp = now))
    }
}
