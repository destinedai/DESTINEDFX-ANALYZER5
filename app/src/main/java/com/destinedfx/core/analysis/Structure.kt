package com.destinedfx.core.analysis

import com.destinedfx.core.marketdata.Candle
import com.destinedfx.core.marketdata.Timeframe
import kotlin.math.abs

enum class Dir { BULL, BEAR }
enum class Trend { BULLISH, BEARISH, RANGE, UNKNOWN }
enum class SwingType { HIGH, LOW }
enum class SwingLabel { HH, HL, LH, LL, NONE }
enum class EventType { BOS, CHOCH }
enum class Confirmation { CONFIRMED_BY_CLOSE }

data class StructureConfig(
    val swingLeft: Int = 3, val swingRight: Int = 3,       // configurable swing sensitivity
    val minSwingProminenceAtr: Double = 0.5,                // swings smaller than this many ATR are noise
    val displacementBodyAtr: Double = 1.2, val displacementBodyRatio: Double = 0.6,
    val consolidationLookback: Int = 20, val consolidationMaxRangeAtr: Double = 4.0, val atrPeriod: Int = 14
)

data class Swing(val index: Int, val timeMs: Long, val price: Double, val type: SwingType, val strengthAtr: Double,
                 val confirmedAtIndex: Int, val label: SwingLabel = SwingLabel.NONE)

data class StructureEvent(val type: EventType, val dir: Dir, val level: Double, val breakIndex: Int, val timeMs: Long,
                          val timeframe: Timeframe?, val strengthAtr: Double, val displacement: Boolean,
                          val confirmation: Confirmation, val brokenSwingIndex: Int)

data class StructureAnalysis(
    val swings: List<Swing>, val events: List<StructureEvent>, val trend: Trend,
    val atr: Double?, val lastDisplacementIndex: Int?, val consolidation: Boolean
) {
    fun lastSwing(t: SwingType, beforeOrAt: Int = Int.MAX_VALUE) = swings.lastOrNull { it.type == t && it.confirmedAtIndex <= beforeOrAt }
    fun recentEvent(bars: Int, lastIndex: Int, dir: Dir? = null) =
        events.lastOrNull { lastIndex - it.breakIndex <= bars && (dir == null || it.dir == dir) }
}

object StructureEngine {
    fun isDisplacement(c: Candle, atr: Double?, cfg: StructureConfig) =
        atr != null && c.range > 0 && c.body >= cfg.displacementBodyAtr * atr && c.body / c.range >= cfg.displacementBodyRatio

    /** Swing = N-left / N-right extreme, kept only if its prominence vs the surrounding opposite extreme is >= minSwingProminenceAtr*ATR. */
    fun swings(c: List<Candle>, cfg: StructureConfig, atrSeries: List<Double?>): List<Swing> {
        val raw = mutableListOf<Swing>()
        val l = cfg.swingLeft; val r = cfg.swingRight
        for (i in l until c.size - r) {
            val atr = atrSeries[minOf(i + r, c.size - 1)] ?: continue
            val isHigh = (1..l).all { c[i - it].high < c[i].high } && (1..r).all { c[i + it].high <= c[i].high }
            val isLow = (1..l).all { c[i - it].low > c[i].low } && (1..r).all { c[i + it].low >= c[i].low }
            if (isHigh) {
                val ref = ((i - l)..(i + r)).minOf { c[it].low }
                val prom = (c[i].high - ref) / atr
                if (prom >= cfg.minSwingProminenceAtr) raw += Swing(i, c[i].timeMs, c[i].high, SwingType.HIGH, prom, i + r)
            }
            if (isLow) {
                val ref = ((i - l)..(i + r)).maxOf { c[it].high }
                val prom = (ref - c[i].low) / atr
                if (prom >= cfg.minSwingProminenceAtr) raw += Swing(i, c[i].timeMs, c[i].low, SwingType.LOW, prom, i + r)
            }
        }
        // Enforce alternation: of consecutive same-type swings keep the more extreme one.
        val alt = mutableListOf<Swing>()
        for (s in raw.sortedBy { it.index }) {
            val last = alt.lastOrNull()
            if (last != null && last.type == s.type) {
                val better = if (s.type == SwingType.HIGH) s.price > last.price else s.price < last.price
                if (better) alt[alt.lastIndex] = s
            } else alt += s
        }
        // HH / HL / LH / LL labels
        var pH: Swing? = null; var pL: Swing? = null
        return alt.map { s ->
            val lab = if (s.type == SwingType.HIGH) { val p = pH; pH = s; if (p == null) SwingLabel.NONE else if (s.price > p.price) SwingLabel.HH else SwingLabel.LH }
            else { val p = pL; pL = s; if (p == null) SwingLabel.NONE else if (s.price > p.price) SwingLabel.HL else SwingLabel.LL }
            s.copy(label = lab)
        }
    }

    fun analyze(c: List<Candle>, tf: Timeframe? = null, cfg: StructureConfig = StructureConfig()): StructureAnalysis {
        if (c.size < cfg.atrPeriod + cfg.swingLeft + cfg.swingRight + 2)
            return StructureAnalysis(emptyList(), emptyList(), Trend.UNKNOWN, null, null, false)
        val atrS = Indicators.atr(c, cfg.atrPeriod)
        val sw = swings(c, cfg, atrS)
        val events = mutableListOf<StructureEvent>()
        var trend = Trend.UNKNOWN
        var usedHigh = -1; var usedLow = -1
        var lastDisp: Int? = null
        for (i in c.indices) {
            val atr = atrS[i]
            if (isDisplacement(c[i], atr, cfg)) lastDisp = i
            // Only swings already confirmed (right-side bars elapsed) by bar i can be broken.
            val h = sw.lastOrNull { it.type == SwingType.HIGH && it.confirmedAtIndex < i && it.index != usedHigh }
            val lo = sw.lastOrNull { it.type == SwingType.LOW && it.confirmedAtIndex < i && it.index != usedLow }
            if (h != null && c[i].close > h.price) {      // close-based break: a wick alone is not structure
                val type = if (trend == Trend.BEARISH) EventType.CHOCH else EventType.BOS
                events += StructureEvent(type, Dir.BULL, h.price, i, c[i].timeMs, tf, if (atr != null) (c[i].close - h.price) / atr else 0.0,
                    isDisplacement(c[i], atr, cfg), Confirmation.CONFIRMED_BY_CLOSE, h.index)
                trend = Trend.BULLISH; usedHigh = h.index
            } else if (lo != null && c[i].close < lo.price) {
                val type = if (trend == Trend.BULLISH) EventType.CHOCH else EventType.BOS
                events += StructureEvent(type, Dir.BEAR, lo.price, i, c[i].timeMs, tf, if (atr != null) (lo.price - c[i].close) / atr else 0.0,
                    isDisplacement(c[i], atr, cfg), Confirmation.CONFIRMED_BY_CLOSE, lo.index)
                trend = Trend.BEARISH; usedLow = lo.index
            }
        }
        // Fallback when no break has happened yet: use swing labels (needs two of each).
        if (trend == Trend.UNKNOWN) {
            val hs = sw.filter { it.type == SwingType.HIGH }.takeLast(2); val ls = sw.filter { it.type == SwingType.LOW }.takeLast(2)
            if (hs.size == 2 && ls.size == 2) trend = when {
                hs[1].price > hs[0].price && ls[1].price > ls[0].price -> Trend.BULLISH
                hs[1].price < hs[0].price && ls[1].price < ls[0].price -> Trend.BEARISH
                else -> Trend.RANGE }
        }
        val n = cfg.consolidationLookback
        val lastAtr = atrS.last()
        val consolidation = lastAtr != null && c.size > n && events.none { c.size - 1 - it.breakIndex < n } &&
            (c.takeLast(n).maxOf { it.high } - c.takeLast(n).minOf { it.low }) / lastAtr < cfg.consolidationMaxRangeAtr
        return StructureAnalysis(sw, events, if (consolidation) Trend.RANGE else trend, lastAtr, lastDisp, consolidation)
    }
}
