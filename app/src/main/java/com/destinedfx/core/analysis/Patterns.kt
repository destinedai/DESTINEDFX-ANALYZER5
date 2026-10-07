package com.destinedfx.core.analysis

import com.destinedfx.core.marketdata.Candle
import kotlin.math.abs

enum class PatternState { FORMING, CONFIRMED_BREAKOUT, FAILED }
enum class PatternBias { BULLISH, BEARISH, NEUTRAL }

data class ChartPattern(val name: String, val bias: PatternBias, val state: PatternState, val startIndex: Int, val endIndex: Int,
                        val keyLevel: Double, val measuredTarget: Double?, val quality: Int, val knowledgeKey: String)

/**
 * Rule-based chart pattern recognition on confirmed swings. Detects: double top/bottom, head & shoulders (and inverse),
 * ascending/descending/symmetrical triangle, rising/falling wedge, rectangle/range, ascending/descending channel,
 * bull/bear flag. NOT implemented (reported as such, never guessed): cup & handle, harmonics, Elliott waves.
 */
object PatternEngine {
    private fun slope(pts: List<Pair<Int, Double>>): Double {
        val n = pts.size; val mx = pts.sumOf { it.first.toDouble() } / n; val my = pts.sumOf { it.second } / n
        val den = pts.sumOf { (it.first - mx) * (it.first - mx) }
        return if (den == 0.0) 0.0 else pts.sumOf { (it.first - mx) * (it.second - my) } / den
    }

    fun detect(c: List<Candle>, a: StructureAnalysis): List<ChartPattern> {
        val atr = a.atr ?: return emptyList(); val out = mutableListOf<ChartPattern>()
        val hs = a.swings.filter { it.type == SwingType.HIGH }; val ls = a.swings.filter { it.type == SwingType.LOW }
        val last = c.last().close; val lastIdx = c.size - 1

        // Double top / bottom
        if (hs.size >= 2) { val (p1, p2) = hs.takeLast(2)
            val valley = ls.filter { it.index in p1.index..p2.index }.minByOrNull { it.price }
            if (valley != null && abs(p1.price - p2.price) <= 0.5 * atr && p2.index - p1.index >= 5 && (minOf(p1.price, p2.price) - valley.price) >= atr) {
                val conf = last < valley.price
                out += ChartPattern("Double Top", PatternBias.BEARISH, if (conf) PatternState.CONFIRMED_BREAKOUT else PatternState.FORMING, p1.index, lastIdx,
                    valley.price, valley.price - (maxOf(p1.price, p2.price) - valley.price), 60, "DOUBLE_TOP") } }
        if (ls.size >= 2) { val (t1, t2) = ls.takeLast(2)
            val peak = hs.filter { it.index in t1.index..t2.index }.maxByOrNull { it.price }
            if (peak != null && abs(t1.price - t2.price) <= 0.5 * atr && t2.index - t1.index >= 5 && (peak.price - maxOf(t1.price, t2.price)) >= atr) {
                val conf = last > peak.price
                out += ChartPattern("Double Bottom", PatternBias.BULLISH, if (conf) PatternState.CONFIRMED_BREAKOUT else PatternState.FORMING, t1.index, lastIdx,
                    peak.price, peak.price + (peak.price - minOf(t1.price, t2.price)), 60, "DOUBLE_BOTTOM") } }

        // Head & shoulders / inverse
        if (hs.size >= 3) { val (l, h, r) = hs.takeLast(3)
            val t1 = ls.filter { it.index in l.index..h.index }.minByOrNull { it.price }; val t2 = ls.filter { it.index in h.index..r.index }.minByOrNull { it.price }
            if (t1 != null && t2 != null && h.price - maxOf(l.price, r.price) >= 0.5 * atr && abs(l.price - r.price) <= 0.15 * (h.price - minOf(t1.price, t2.price))) {
                val neck = (t1.price + t2.price) / 2; val conf = last < neck
                out += ChartPattern("Head and Shoulders", PatternBias.BEARISH, if (conf) PatternState.CONFIRMED_BREAKOUT else PatternState.FORMING, l.index, lastIdx, neck, neck - (h.price - neck), 70, "HEAD_SHOULDERS") } }
        if (ls.size >= 3) { val (l, h, r) = ls.takeLast(3)
            val p1 = hs.filter { it.index in l.index..h.index }.maxByOrNull { it.price }; val p2 = hs.filter { it.index in h.index..r.index }.maxByOrNull { it.price }
            if (p1 != null && p2 != null && minOf(l.price, r.price) - h.price >= 0.5 * atr && abs(l.price - r.price) <= 0.15 * (maxOf(p1.price, p2.price) - h.price)) {
                val neck = (p1.price + p2.price) / 2; val conf = last > neck
                out += ChartPattern("Inverse Head and Shoulders", PatternBias.BULLISH, if (conf) PatternState.CONFIRMED_BREAKOUT else PatternState.FORMING, l.index, lastIdx, neck, neck + (neck - h.price), 70, "INV_HEAD_SHOULDERS") } }

        // Trendline-pair patterns on the last 3+ swings of each type
        val rh = hs.takeLast(4); val rl = ls.takeLast(4)
        if (rh.size >= 3 && rl.size >= 3) {
            val sH = slope(rh.map { it.index to it.price }) / atr; val sL = slope(rl.map { it.index to it.price }) / atr
            val flat = 0.03
            val start = minOf(rh.first().index, rl.first().index)
            val hiLine = { i: Int -> rh.last().price + slope(rh.map { it.index to it.price }) * (i - rh.last().index) }
            val loLine = { i: Int -> rl.last().price + slope(rl.map { it.index to it.price }) * (i - rl.last().index) }
            val gap0 = hiLine(start) - loLine(start); val gap1 = hiLine(lastIdx) - loLine(lastIdx)
            val converging = gap0 > 0 && gap1 > 0 && gap1 < gap0 * 0.8
            val bUp = last > hiLine(lastIdx); val bDn = last < loLine(lastIdx)
            fun add(n: String, b: PatternBias, key: String, up: Boolean?, lvl: Double) {
                val conf = (up == true && bUp) || (up == false && bDn) || (up == null && (bUp || bDn))
                out += ChartPattern(n, b, if (conf) PatternState.CONFIRMED_BREAKOUT else PatternState.FORMING, start, lastIdx, lvl, null, 50, key) }
            when {
                abs(sH) < flat && sL > flat && converging -> add("Ascending Triangle", PatternBias.BULLISH, "ASC_TRIANGLE", true, hiLine(lastIdx))
                sH < -flat && abs(sL) < flat && converging -> add("Descending Triangle", PatternBias.BEARISH, "DESC_TRIANGLE", false, loLine(lastIdx))
                sH < -flat && sL > flat && converging -> add("Symmetrical Triangle", PatternBias.NEUTRAL, "SYM_TRIANGLE", null, (hiLine(lastIdx) + loLine(lastIdx)) / 2)
                sH > flat && sL > flat && converging -> add("Rising Wedge", PatternBias.BEARISH, "RISING_WEDGE", false, loLine(lastIdx))
                sH < -flat && sL < -flat && converging -> add("Falling Wedge", PatternBias.BULLISH, "FALLING_WEDGE", true, hiLine(lastIdx))
                abs(sH) < flat && abs(sL) < flat && gap1 > 0 -> add("Rectangle / Range", PatternBias.NEUTRAL, "RANGE", null, (hiLine(lastIdx) + loLine(lastIdx)) / 2)
                sH > flat && sL > flat && abs(sH - sL) < 0.04 -> add("Ascending Channel", PatternBias.BULLISH, "CHANNEL_UP", null, loLine(lastIdx))
                sH < -flat && sL < -flat && abs(sH - sL) < 0.04 -> add("Descending Channel", PatternBias.BEARISH, "CHANNEL_DOWN", null, hiLine(lastIdx))
            }
        }

        // Flags: strong impulse followed by a shallow counter-drift
        if (c.size > 30) {
            val flagLen = 8; val impStart = c.size - 1 - flagLen - 8; val impEnd = c.size - 1 - flagLen
            val imp = c[impEnd].close - c[impStart].close
            val flagRange = c.takeLast(flagLen).let { it.maxOf { x -> x.high } - it.minOf { x -> x.low } }
            val drift = c.last().close - c[c.size - 1 - flagLen].close
            if (abs(imp) >= 3 * atr && flagRange < 0.5 * abs(imp) && drift * imp < 0 && abs(drift) < 0.5 * abs(imp)) {
                val bull = imp > 0; val lvl = if (bull) c.takeLast(flagLen).maxOf { it.high } else c.takeLast(flagLen).minOf { it.low }
                val conf = if (bull) last > lvl else last < lvl
                out += ChartPattern(if (bull) "Bull Flag" else "Bear Flag", if (bull) PatternBias.BULLISH else PatternBias.BEARISH,
                    if (conf) PatternState.CONFIRMED_BREAKOUT else PatternState.FORMING, impStart, lastIdx, lvl, last + imp, 55, if (bull) "BULL_FLAG" else "BEAR_FLAG")
            }
        }
        return out
    }
}
