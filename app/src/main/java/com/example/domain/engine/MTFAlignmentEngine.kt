package com.example.domain.engine

import com.example.domain.model.Candle

/**
 * Broker HTF-to-LTF alignment hierarchy:
 *   Monthly -> Weekly -> Daily -> 4H -> 1H -> 15M -> 5M
 *
 * HTF bias MUST override LTF entry decisions.
 * isAligned = true only when Weekly + Daily + H4 all agree.
 */
class MTFAlignmentEngine {

    enum class Timeframe(val minutes: Int) {
        MONTHLY(43200), WEEKLY(10080), DAILY(1440), H4(240), H1(60), M15(15), M5(5)
    }

    enum class Bias { BULLISH, BEARISH, NEUTRAL }

    data class MTFAnalysis(
        val timeframe: Timeframe,
        val bias: Bias,
        val lastSwingHigh: Double,
        val lastSwingLow: Double,
        val isInPullback: Boolean,
        val confidenceWeight: Double
    )

    data class MTFConsensus(
        val overallBias: Bias,
        val alignmentScore: Double,
        val analyses: Map<Timeframe, MTFAnalysis>,
        val isAligned: Boolean
    )

    fun analyze(data: Map<Timeframe, List<Candle>>): MTFConsensus {
        val analyses = data.filter { it.value.size >= 10 }
            .mapValues { (tf, c) -> analyzeTimeframe(tf, c) }
        return buildConsensus(analyses)
    }

    private fun analyzeTimeframe(tf: Timeframe, candles: List<Candle>): MTFAnalysis {
        val highs  = swingHighs(candles)
        val lows   = swingLows(candles)
        val rHigh  = highs.maxOrNull() ?: candles.maxOf { it.high }
        val rLow   = lows.minOrNull()  ?: candles.minOf { it.low  }
        val close  = candles.last().close
        val bias   = when {
            isHHHL(candles) -> Bias.BULLISH
            isLHLL(candles) -> Bias.BEARISH
            else            -> Bias.NEUTRAL
        }
        val mid       = (rHigh + rLow) / 2.0
        val pullback  = when(bias) {
            Bias.BULLISH -> close < mid
            Bias.BEARISH -> close > mid
            else         -> false
        }
        val weight = when(tf) {
            Timeframe.MONTHLY->1.00; Timeframe.WEEKLY->0.85; Timeframe.DAILY->0.70
            Timeframe.H4->0.50; Timeframe.H1->0.35; Timeframe.M15->0.20; Timeframe.M5->0.10
        }
        return MTFAnalysis(tf, bias, rHigh, rLow, pullback, weight)
    }

    private fun buildConsensus(analyses: Map<Timeframe, MTFAnalysis>): MTFConsensus {
        if (analyses.isEmpty()) return MTFConsensus(Bias.NEUTRAL, 0.0, emptyMap(), false)
        var wB = 0.0; var wBr = 0.0; var wT = 0.0
        analyses.values.forEach { a ->
            wT += a.confidenceWeight
            when(a.bias) { Bias.BULLISH->wB+=a.confidenceWeight; Bias.BEARISH->wBr+=a.confidenceWeight; else->{} }
        }
        val bs  = if (wT > 0) wB / wT else 0.0
        val brs = if (wT > 0) wBr / wT else 0.0
        val ob  = when { bs > brs + 0.15 -> Bias.BULLISH; brs > bs + 0.15 -> Bias.BEARISH; else -> Bias.NEUTRAL }
        val keyBiases = listOf(Timeframe.WEEKLY, Timeframe.DAILY, Timeframe.H4)
            .mapNotNull { analyses[it]?.bias }
        val aligned = keyBiases.size >= 2 && keyBiases.all { it == ob && it != Bias.NEUTRAL }
        return MTFConsensus(ob, maxOf(bs,brs)*100.0, analyses, aligned)
    }

    private fun swingHighs(c: List<Candle>, b: Int = 3): List<Double> {
        val out = mutableListOf<Double>()
        for (i in b until c.size - b) {
            val h = c[i].high
            if ((i-b until i).all{ c[it].high < h } && (i+1..i+b).all{ c[it].high < h }) out.add(h)
        }
        return out
    }

    private fun swingLows(c: List<Candle>, b: Int = 3): List<Double> {
        val out = mutableListOf<Double>()
        for (i in b until c.size - b) {
            val l = c[i].low
            if ((i-b until i).all{ c[it].low > l } && (i+1..i+b).all{ c[it].low > l }) out.add(l)
        }
        return out
    }

    private fun isHHHL(c: List<Candle>): Boolean {
        val h = swingHighs(c); val l = swingLows(c)
        return h.size>=2 && l.size>=2 && h.takeLast(2).let{it[1]>it[0]} && l.takeLast(2).let{it[1]>it[0]}
    }

    private fun isLHLL(c: List<Candle>): Boolean {
        val h = swingHighs(c); val l = swingLows(c)
        return h.size>=2 && l.size>=2 && h.takeLast(2).let{it[1]<it[0]} && l.takeLast(2).let{it[1]<it[0]}
    }
}
