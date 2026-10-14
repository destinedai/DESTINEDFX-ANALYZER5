package com.example.domain.usecase.impl

import com.destinedfx.core.analysis.*
import com.destinedfx.core.backtest.*
import com.destinedfx.core.instrument.AssetClassifier
import com.destinedfx.core.instrument.SpecRegistry
import com.destinedfx.core.marketdata.AssetClass
import com.destinedfx.core.marketdata.Timeframe
import com.destinedfx.core.marketdata.VolumeType
import com.example.domain.model.BacktestResult as UiBacktestResult
import com.example.domain.repository.MarketDataRepository
import com.example.domain.usecase.BacktestUseCase
import com.destinedfx.core.marketdata.Candle as CoreCandle

/**
 * Real backtest: next-bar-open fills, spread/commission/slippage/swap, walk-forward (train/validation/out-of-sample).
 * Only the OUT-OF-SAMPLE figures are returned as the result. Needs a broker spec and a real starting balance; otherwise it says so.
 * The strategy is single-timeframe (the MTF requirement is reduced to the traded timeframe because historical HTF alignment is not replayed).
 */
class BacktestUseCaseImpl(
    private val market: MarketDataRepository,
    private val startingBalance: suspend () -> Double? = { null },
    private val clock: () -> Long = System::currentTimeMillis
) : BacktestUseCase {

    var symbol: String = "EUR/USD"; var timeframe: Timeframe = Timeframe.H1
    var commissionPerLot: Double = 0.0; var slippage: Double = 0.0; var fixedSpread: Double? = null

    override suspend fun runBacktest(strategy: String): UiBacktestResult {
        val bal = startingBalance() ?: return unavailable("Starting balance unavailable (connect broker or set it)")
        val spec = SpecRegistry.get(symbol) ?: return unavailable("Broker symbol specification unavailable for $symbol")
        val cls = AssetClassifier.of(symbol)
        val raw = market.getCandles(symbol, timeframe.label, 2000).filter { it.complete }
        if (raw.size < 600) return unavailable("Only ${raw.size} candles available; need >= 600 for train/validation/out-of-sample")
        val kind = if (raw.sumOf { it.volume } <= 0.0) VolumeType.NONE else if (cls == AssetClass.CRYPTO) VolumeType.REAL else VolumeType.TICK
        val candles = raw.map { CoreCandle(it.timestamp, it.open, it.high, it.low, it.close, it.volume, kind) }
        val cfg = BacktestConfig(startingBalance = bal, spec = spec, fixedSpread = fixedSpread, commissionPerLotRoundTurn = commissionPerLot, slippage = slippage)
        val grid = listOf(2.0, 2.5, 3.0).flatMap { rr -> listOf(2.0, 3.0).map { l -> mapOf("minRR" to rr, "swingLeft" to l) } }
        val factory = { p: Map<String, Double> -> BacktestStrategy { hist ->
            val sc = SignalConfig(structure = StructureConfig(swingLeft = p.getValue("swingLeft").toInt(), swingRight = p.getValue("swingLeft").toInt()),
                mtf = MtfConfig(required = listOf(timeframe)), minRR = p.getValue("minRR"))
            val q = com.destinedfx.core.marketdata.MarketQuote.lastOnly(symbol, cls, hist.last().close, hist.last().timeMs, hist.last().timeMs, "backtest")
            val rep = SignalEngine.analyse(AnalysisInput(symbol, timeframe, hist.takeLast(250), emptyMap(), q, spec, hist.last().timeMs, strategyId = strategy), sc)
            val pl = rep.plan
            if (pl != null && rep.state in setOf(SignalState.STRONG_BUY, SignalState.BUY, SignalState.STRONG_SELL, SignalState.SELL))
                Order(if (pl.isLong) Dir.BULL else Dir.BEAR, pl.stopLoss, pl.primary.price) else null } }
        val wf = WalkForward.run(candles, grid, factory, cfg, WalkForwardConfig(), "broker/market feed via MarketDataRepository", clock())
        val oos = wf.outOfSample ?: return unavailable(wf.explanation)
        val s = oos.stats
        return UiBacktestResult(historicalReplayId = "${oos.strategyVersion}@${oos.timestampMs}", totalTrades = s.trades, winRatePercentage = (s.winRate ?: 0.0) * 100,
            maxDrawdownPercentage = s.maxDrawdownPct, profitFactor = s.profitFactor ?: 0.0, sharpeRatio = s.sharpe ?: 0.0, expectancy = s.expectancyMoney ?: 0.0, netProfit = s.netProfit,
            verdict = wf.verdict.name, verdictText = wf.explanation, startingBalance = bal, strategyVersion = oos.strategyVersion, outOfSample = true)
    }
    private fun unavailable(why: String) = UiBacktestResult("none", 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, verdict = "INSUFFICIENT_SAMPLE", verdictText = "BACKTEST UNAVAILABLE: $why")
}
