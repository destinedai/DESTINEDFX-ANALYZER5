package com.destinedfx.core.analysis

import com.destinedfx.core.marketdata.Candle
import com.destinedfx.core.marketdata.Timeframe
import kotlin.math.min

enum class FillState { UNFILLED, PARTIALLY_FILLED, FULLY_FILLED }
enum class ZoneState { FRESH, TESTED, MITIGATED, INVALIDATED }

/** Gap size and mitigation are measured, not scored. No fake "FVG score". */
data class FairValueGap(val dir: Dir, val top: Double, val bottom: Double, val sizeAtr: Double, val timeframe: Timeframe?,
                        val createdIndex: Int, val createdMs: Long, val mitigationPct: Double, val fill: FillState)

data class OrderBlock(val dir: Dir, val high: Double, val low: Double, val timeframe: Timeframe?, val createdIndex: Int, val createdMs: Long,
                      val state: ZoneState, val ageBars: Int, val displacementAtr: Double, val displacementConfirmed: Boolean,
                      val hasFvg: Boolean, val relativeVolume: Double?, val strength: Int) {
    val mid: Double get() = (high + low) / 2.0
    val fresh: Boolean get() = state == ZoneState.FRESH
}

data class ZoneConfig(val minFvgAtr: Double = 0.2, val obMaxAgeBars: Int = 120, val obMinDisplacementAtr: Double = 1.2, val obLookbackBeforeBreak: Int = 12)

object ZoneEngine {
    fun fvgs(c: List<Candle>, atrSeries: List<Double?>, tf: Timeframe?, cfg: ZoneConfig = ZoneConfig()): List<FairValueGap> {
        val n = c.size
        if (n < 3) return emptyList()
        val minLow = DoubleArray(n); val maxHigh = DoubleArray(n)
        minLow[n - 1] = c[n - 1].low; maxHigh[n - 1] = c[n - 1].high
        for (i in n - 2 downTo 0) {
            minLow[i] = minOf(c[i].low, minLow[i + 1])
            maxHigh[i] = maxOf(c[i].high, maxHigh[i + 1])
        }
        val out = mutableListOf<FairValueGap>()
        for (i in 2 until n) {
            val atr = atrSeries[i] ?: continue
            val bull = c[i].low > c[i - 2].high; val bear = c[i].high < c[i - 2].low
            if (!bull && !bear) continue
            val top = if (bull) c[i].low else c[i - 2].low
            val bottom = if (bull) c[i - 2].high else c[i].high
            val size = top - bottom
            if (size < cfg.minFvgAtr * atr) continue
            val pen = if (bull) (top - minLow[i]) / size else (maxHigh[i] - bottom) / size
            val pct = pen.coerceIn(0.0, 1.0) * 100.0
            out += FairValueGap(if (bull) Dir.BULL else Dir.BEAR, top, bottom, size / atr, tf, i, c[i - 1].timeMs, pct,
                when { pct <= 0.0 -> FillState.UNFILLED; pct >= 100.0 -> FillState.FULLY_FILLED; else -> FillState.PARTIALLY_FILLED })
        }
        return out
    }

    /**
     * OB = last opposite-colour candle before the displacement leg that produced a close-confirmed BOS/CHOCH.
     * Rejected: no displacement leg, no structure break, too old, or already invalidated.
     * strength (0-100) = 50*min(displacementATR/3,1) + 30*(FVG left behind) + 20*min(relVol/2,1) [volume term 0 if no volume].
     */
    fun orderBlocks(c: List<Candle>, a: StructureAnalysis, fvgList: List<FairValueGap>, tf: Timeframe?, cfg: ZoneConfig = ZoneConfig()): List<OrderBlock> {
        val atr = a.atr ?: return emptyList(); val out = mutableListOf<OrderBlock>()
        for (ev in a.events) {
            val from = maxOf(0, ev.breakIndex - cfg.obLookbackBeforeBreak)
            if (ev.breakIndex - from < 2) continue
            val origin = if (ev.dir == Dir.BULL) (from until ev.breakIndex).minByOrNull { c[it].low }!! else (from until ev.breakIndex).maxByOrNull { c[it].high }!!
            var obIdx = -1
            for (k in origin downTo maxOf(0, origin - 3)) if ((ev.dir == Dir.BULL && c[k].isBearish) || (ev.dir == Dir.BEAR && c[k].isBullish)) { obIdx = k; break }
            if (obIdx < 0) continue
            val leg = (obIdx + 1..ev.breakIndex).maxOfOrNull { c[it].body } ?: 0.0
            val dispAtr = leg / atr
            if (dispAtr < cfg.obMinDisplacementAtr) continue                      // weak: reject
            val ob = c[obIdx]; val hi = ob.high; val lo = ob.low
            val age = c.size - 1 - obIdx
            if (age > cfg.obMaxAgeBars) continue
            var state = ZoneState.FRESH
            for (j in ev.breakIndex + 1 until c.size) {
                val cj = c[j]
                val beyond = if (ev.dir == Dir.BULL) cj.close < lo else cj.close > hi
                if (beyond) { state = ZoneState.INVALIDATED; break }
                val mid = (hi + lo) / 2
                val closedThroughMid = if (ev.dir == Dir.BULL) cj.close < mid else cj.close > mid
                val touched = if (ev.dir == Dir.BULL) cj.low <= hi else cj.high >= lo
                if (closedThroughMid) state = ZoneState.MITIGATED else if (touched && state == ZoneState.FRESH) state = ZoneState.TESTED
            }
            if (state == ZoneState.INVALIDATED) continue
            val hasFvg = fvgList.any { it.dir == ev.dir && it.createdIndex in (obIdx + 1)..(ev.breakIndex + 1) }
            val rv = Indicators.relativeVolume(c, minOf(ev.breakIndex, c.size - 1))
            val strength = (50 * min(dispAtr / 3, 1.0) + (if (hasFvg) 30 else 0) + 20 * min((rv ?: 0.0) / 2, 1.0)).toInt()
            out += OrderBlock(ev.dir, hi, lo, tf, obIdx, ob.timeMs, state, age, dispAtr, true, hasFvg, rv, strength)
        }
        return out.distinctBy { it.createdIndex to it.dir }
    }
}
