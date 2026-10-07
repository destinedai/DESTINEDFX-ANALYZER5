package com.destinedfx.core.backtest

import com.destinedfx.core.analysis.Dir
import com.destinedfx.core.analysis.Versions
import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.marketdata.Candle
import com.destinedfx.core.risk.AccountState
import com.destinedfx.core.risk.RiskEngine
import com.destinedfx.core.risk.SizingResult
import kotlin.math.abs
import kotlin.math.sqrt

data class BacktestConfig(
    val startingBalance: Double,                         // no default: caller must choose
    val riskPercent: Double = 1.0, val spec: InstrumentSpec,
    val fixedSpread: Double? = null,                     // used only when candles carry no spread
    val commissionPerLotRoundTurn: Double = 0.0, val slippage: Double = 0.0,   // slippage in price units, applied against the trader
    val swapPerLotPerDayLong: Double = 0.0, val swapPerLotPerDayShort: Double = 0.0,
    val riskFreeRateAnnual: Double = 0.0, val periodsPerYear: Double = 252.0, // periods of the equity series (daily=252)
    val maxHoldBars: Int = 200
)
data class Order(val dir: Dir, val stopLoss: Double, val takeProfit: Double)
/** Strategy sees only candles up to and including the signal bar (no look-ahead); the order fills at the NEXT bar's open. */
fun interface BacktestStrategy { fun onBar(history: List<Candle>): Order? }

data class BtTrade(val dir: Dir, val entryIdx: Int, val exitIdx: Int, val entry: Double, val exit: Double, val lots: Double, val pnl: Double,
                   val rMultiple: Double, val mae: Double, val mfe: Double, val reason: String, val costs: Double)
data class BacktestStats(
    val trades: Int, val wins: Int, val losses: Int, val winRate: Double?, val lossRate: Double?, val profitFactor: Double?, val expectancyMoney: Double?, val expectancyR: Double?,
    val netProfit: Double, val maxDrawdownPct: Double, val sharpe: Double?, val sortino: Double?, val calmar: Double?, val recoveryFactor: Double?,
    val avgMae: Double?, val avgMfe: Double?, val maxConsecWins: Int, val maxConsecLosses: Int, val totalCosts: Double)
data class BacktestResult(val stats: BacktestStats, val trades: List<BtTrade>, val equityCurve: List<Double>, val startingBalance: Double,
                          val strategyVersion: String, val rulesetVersion: String, val dataSource: String, val period: Pair<Long, Long>, val timestampMs: Long)

object Backtester {
    fun run(candles: List<Candle>, strat: BacktestStrategy, cfg: BacktestConfig, dataSource: String, nowMs: Long, warmup: Int = 60): BacktestResult {
        require(cfg.startingBalance > 0) { "startingBalance must be set > 0" }
        var bal = cfg.startingBalance; val eq = mutableListOf(bal); val trades = mutableListOf<BtTrade>()
        var i = warmup
        while (i < candles.size - 1) {
            val order = strat.onBar(candles.subList(0, i + 1)); if (order == null) { i++; continue }
            val e = candles[i + 1]; val sp = e.spread ?: cfg.fixedSpread ?: 0.0
            val long = order.dir == Dir.BULL
            val entry = if (long) e.open + sp / 2 + cfg.slippage else e.open - sp / 2 - cfg.slippage     // pay the spread + slippage
            val valid = if (long) order.stopLoss < entry && order.takeProfit > entry else order.stopLoss > entry && order.takeProfit < entry
            if (!valid) { i++; continue }
            val sized = RiskEngine.positionSize(AccountState(bal, bal, null, null, "ACC"), cfg.riskPercent, entry, order.stopLoss, cfg.spec, cfg.commissionPerLotRoundTurn)
            if (sized !is SizingResult.Sized) { i++; continue }
            var exitPx = Double.NaN; var exitIdx = -1; var reason = ""; var mae = 0.0; var mfe = 0.0
            var j = i + 1
            while (j < candles.size && j - i <= cfg.maxHoldBars) {
                val b = candles[j]; val bsp = b.spread ?: cfg.fixedSpread ?: 0.0
                // Bids are what a long exits against; asks for a short exit. Candle series are treated as BID prices.
                val lo = if (long) b.low else b.low + bsp; val hi = if (long) b.high else b.high + bsp
                mae = maxOf(mae, if (long) entry - lo else hi - entry); mfe = maxOf(mfe, if (long) hi - entry else entry - lo)
                val slHit = if (long) lo <= order.stopLoss else hi >= order.stopLoss
                val tpHit = if (long) hi >= order.takeProfit else lo <= order.takeProfit
                if (slHit) { exitPx = order.stopLoss - (if (long) cfg.slippage else -cfg.slippage); exitIdx = j; reason = if (tpHit) "SL (same-bar tie: SL assumed first)" else "SL"; break }
                if (tpHit) { exitPx = order.takeProfit; exitIdx = j; reason = "TP"; break }
                j++
            }
            if (exitIdx < 0) { exitIdx = minOf(j, candles.size - 1); exitPx = candles[exitIdx].close; reason = "TIME" }
            val ticks = (if (long) exitPx - entry else entry - exitPx) / cfg.spec.tickSize
            val days = (candles[exitIdx].timeMs - candles[i + 1].timeMs) / 86_400_000.0
            val swap = sized.lots * days * (if (long) cfg.swapPerLotPerDayLong else cfg.swapPerLotPerDayShort)
            val comm = sized.lots * cfg.commissionPerLotRoundTurn
            val pnl = ticks * cfg.spec.tickValue * sized.lots - comm + swap
            bal += pnl; eq += bal
            trades += BtTrade(order.dir, i + 1, exitIdx, entry, exitPx, sized.lots, pnl, pnl / sized.moneyRiskTarget, mae, mfe, reason, comm - swap)
            i = exitIdx + 1
        }
        return BacktestResult(stats(trades, eq, cfg), trades, eq, cfg.startingBalance, Versions.STRATEGY, Versions.RULESET, dataSource,
            (candles.firstOrNull()?.timeMs ?: 0L) to (candles.lastOrNull()?.timeMs ?: 0L), nowMs)
    }

    fun maxDrawdownPct(eq: List<Double>): Double { var peak = eq.firstOrNull() ?: return 0.0; var dd = 0.0
        for (v in eq) { peak = maxOf(peak, v); dd = maxOf(dd, (peak - v) / peak * 100) }; return dd }

    /** Sharpe = (mean(period return) - rf per period) / stdev(period return) * sqrt(periodsPerYear). Sample stdev (n-1). Null if < 3 points or zero variance. */
    fun sharpe(returns: List<Double>, rfAnnual: Double, periodsPerYear: Double): Double? {
        if (returns.size < 3) return null
        val rf = rfAnnual / periodsPerYear; val m = returns.average(); val sd = sqrt(returns.sumOf { (it - m) * (it - m) } / (returns.size - 1))
        return if (sd == 0.0) null else (m - rf) / sd * sqrt(periodsPerYear)
    }
    /** Sortino uses downside deviation below the rf (target) rate. */
    fun sortino(returns: List<Double>, rfAnnual: Double, periodsPerYear: Double): Double? {
        if (returns.size < 3) return null
        val rf = rfAnnual / periodsPerYear; val dd = sqrt(returns.sumOf { val d = minOf(0.0, it - rf); d * d } / returns.size)
        return if (dd == 0.0) null else (returns.average() - rf) / dd * sqrt(periodsPerYear)
    }

    fun stats(t: List<BtTrade>, eq: List<Double>, cfg: BacktestConfig): BacktestStats {
        val n = t.size; val w = t.filter { it.pnl > 0 }; val l = t.filter { it.pnl <= 0 }
        val gp = w.sumOf { it.pnl }; val gl = abs(l.sumOf { it.pnl }); val net = t.sumOf { it.pnl }
        val ret = eq.zipWithNext { a, b -> if (a > 0) b / a - 1 else 0.0 }     // per-trade-close equity returns
        val tradesPerYear = cfg.periodsPerYear
        val mdd = maxDrawdownPct(eq)
        val years = if (n > 0) n / tradesPerYear else 0.0
        val cagr = if (years > 0 && eq.first() > 0 && eq.last() > 0) Math.pow(eq.last() / eq.first(), 1 / years) - 1 else null
        var cw = 0; var cl = 0; var mw = 0; var ml = 0
        for (x in t) if (x.pnl > 0) { cw++; cl = 0; mw = maxOf(mw, cw) } else { cl++; cw = 0; ml = maxOf(ml, cl) }
        val maxDdMoney = run { var p = eq.firstOrNull() ?: 0.0; var d = 0.0; for (v in eq) { p = maxOf(p, v); d = maxOf(d, p - v) }; d }
        return BacktestStats(n, w.size, l.size, if (n > 0) w.size.toDouble() / n else null, if (n > 0) l.size.toDouble() / n else null,
            if (gl > 0) gp / gl else null, if (n > 0) net / n else null, if (n > 0) t.map { it.rMultiple }.average() else null, net, mdd,
            sharpe(ret, cfg.riskFreeRateAnnual, cfg.periodsPerYear), sortino(ret, cfg.riskFreeRateAnnual, cfg.periodsPerYear),
            if (cagr != null && mdd > 0) cagr * 100 / mdd else null, if (maxDdMoney > 0) net / maxDdMoney else null,
            if (n > 0) t.map { it.mae }.average() else null, if (n > 0) t.map { it.mfe }.average() else null, mw, ml, t.sumOf { it.costs })
    }
}
