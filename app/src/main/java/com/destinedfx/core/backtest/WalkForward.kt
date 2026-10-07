package com.destinedfx.core.backtest

import com.destinedfx.core.marketdata.Candle

enum class Verdict { NO_EVIDENCE_OF_EDGE, INSUFFICIENT_SAMPLE, OUT_OF_SAMPLE_POSITIVE }

data class WalkForwardConfig(val trainFrac: Double = 0.5, val validationFrac: Double = 0.25, val minOosTrades: Int = 30)
data class WalkForwardResult(val bestParams: Map<String, Double>?, val train: BacktestResult?, val validation: BacktestResult?, val outOfSample: BacktestResult?,
                             val verdict: Verdict, val explanation: String)

object WalkForward {
    /**
     * Chronological split: TRAIN picks parameters, VALIDATION picks among the top candidates, OUT-OF-SAMPLE is run exactly once
     * with the chosen parameters and is the only result that may be quoted as evidence. In-sample results never count.
     */
    fun run(candles: List<Candle>, grid: List<Map<String, Double>>, factory: (Map<String, Double>) -> BacktestStrategy, cfg: BacktestConfig,
            wf: WalkForwardConfig, dataSource: String, nowMs: Long): WalkForwardResult {
        require(wf.trainFrac + wf.validationFrac < 1.0)
        val a = (candles.size * wf.trainFrac).toInt(); val b = (candles.size * (wf.trainFrac + wf.validationFrac)).toInt()
        val train = candles.subList(0, a); val valid = candles.subList(a, b); val oos = candles.subList(b, candles.size)
        if (grid.isEmpty() || oos.size < 100) return WalkForwardResult(null, null, null, null, Verdict.INSUFFICIENT_SAMPLE, "Not enough data or empty parameter grid")
        fun score(r: BacktestResult) = r.stats.expectancyR ?: Double.NEGATIVE_INFINITY
        val trained = grid.map { it to Backtester.run(train, factory(it), cfg, dataSource, nowMs) }.filter { it.second.stats.trades >= 10 }.sortedByDescending { score(it.second) }.take(3)
        if (trained.isEmpty()) return WalkForwardResult(null, null, null, null, Verdict.INSUFFICIENT_SAMPLE, "No parameter set produced >= 10 training trades")
        val validated = trained.map { (p, _) -> p to Backtester.run(valid, factory(p), cfg, dataSource, nowMs) }.maxByOrNull { score(it.second) }!!
        val best = validated.first
        val trRes = trained.first { it.first == best }.second
        val oosRes = Backtester.run(oos, factory(best), cfg, dataSource, nowMs)
        val s = oosRes.stats
        val v = when { s.trades < wf.minOosTrades -> Verdict.INSUFFICIENT_SAMPLE
            (s.expectancyR ?: 0.0) > 0 && (s.profitFactor ?: 0.0) > 1.0 -> Verdict.OUT_OF_SAMPLE_POSITIVE else -> Verdict.NO_EVIDENCE_OF_EDGE }
        val msg = when (v) { Verdict.INSUFFICIENT_SAMPLE -> "Only ${s.trades} out-of-sample trades (need ${wf.minOosTrades}). No profitability claim can be made."
            Verdict.NO_EVIDENCE_OF_EDGE -> "Out-of-sample expectancy/profit factor not positive. Do not trade this configuration."
            Verdict.OUT_OF_SAMPLE_POSITIVE -> "Positive out-of-sample result on ${s.trades} trades. Still historical, not a guarantee." }
        return WalkForwardResult(best, trRes, validated.second, oosRes, v, msg)
    }
}
