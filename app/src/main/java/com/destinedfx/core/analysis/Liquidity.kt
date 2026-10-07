package com.destinedfx.core.analysis

import com.destinedfx.core.marketdata.Candle
import kotlin.math.abs

enum class LiquiditySide { BUY_SIDE, SELL_SIDE }   // buy-side = resting above highs, sell-side = resting below lows
enum class LevelKind { EQUAL_HIGHS, EQUAL_LOWS, PREV_DAY_HIGH, PREV_DAY_LOW, PREV_WEEK_HIGH, PREV_WEEK_LOW, SESSION_HIGH, SESSION_LOW, SWING_HIGH, SWING_LOW }
data class LiquidityLevel(val price: Double, val kind: LevelKind, val side: LiquiditySide, val touches: Int = 1, val higherTimeframe: Boolean = false, val swept: Boolean = false)

/** A sweep = wick beyond a level + close back inside. It is only CONFIRMED if a structure shift in the opposite direction follows. */
data class LiquiditySweep(val level: LiquidityLevel, val index: Int, val timeMs: Long, val wickAtr: Double, val confirmed: Boolean, val impliedDir: Dir)

data class LiquidityConfig(val equalToleranceAtr: Double = 0.10, val minWickBeyondAtr: Double = 0.05,
                           val confirmWithinBars: Int = 10, val lookbackBars: Int = 150)

object LiquidityEngine {
    fun equalLevels(a: StructureAnalysis, cfg: LiquidityConfig): List<LiquidityLevel> {
        val atr = a.atr ?: return emptyList(); val tol = atr * cfg.equalToleranceAtr; val out = mutableListOf<LiquidityLevel>()
        for (type in SwingType.values()) {
            val s = a.swings.filter { it.type == type }.takeLast(12)
            val used = BooleanArray(s.size)
            for (i in s.indices) { if (used[i]) continue
                val grp = s.indices.filter { j -> !used[j] && abs(s[j].price - s[i].price) <= tol && (j == i || abs(s[j].index - s[i].index) >= 3) }
                if (grp.size >= 2) { grp.forEach { used[it] = true }
                    val p = if (type == SwingType.HIGH) grp.maxOf { s[it].price } else grp.minOf { s[it].price }
                    out += LiquidityLevel(p, if (type == SwingType.HIGH) LevelKind.EQUAL_HIGHS else LevelKind.EQUAL_LOWS,
                        if (type == SwingType.HIGH) LiquiditySide.BUY_SIDE else LiquiditySide.SELL_SIDE, grp.size) }
            }
        }
        return out
    }

    /** Previous completed D1/W1 candle extremes. Pass the CLOSED higher-timeframe series. */
    fun previousPeriodLevels(d1: List<Candle>?, w1: List<Candle>?): List<LiquidityLevel> = buildList {
        d1?.lastOrNull()?.let { add(LiquidityLevel(it.high, LevelKind.PREV_DAY_HIGH, LiquiditySide.BUY_SIDE, higherTimeframe = true)); add(LiquidityLevel(it.low, LevelKind.PREV_DAY_LOW, LiquiditySide.SELL_SIDE, higherTimeframe = true)) }
        w1?.lastOrNull()?.let { add(LiquidityLevel(it.high, LevelKind.PREV_WEEK_HIGH, LiquiditySide.BUY_SIDE, higherTimeframe = true)); add(LiquidityLevel(it.low, LevelKind.PREV_WEEK_LOW, LiquiditySide.SELL_SIDE, higherTimeframe = true)) }
    }

    /** Session high/low from candles whose time falls in [fromMs,toMs). */
    fun sessionLevels(c: List<Candle>, fromMs: Long, toMs: Long): List<LiquidityLevel> {
        val s = c.filter { it.timeMs in fromMs until toMs }
        if (s.isEmpty()) return emptyList()
        return listOf(LiquidityLevel(s.maxOf { it.high }, LevelKind.SESSION_HIGH, LiquiditySide.BUY_SIDE), LiquidityLevel(s.minOf { it.low }, LevelKind.SESSION_LOW, LiquiditySide.SELL_SIDE))
    }

    fun swingLevels(a: StructureAnalysis, c: List<Candle>): List<LiquidityLevel> {
        val n = c.size
        if (n == 0) return emptyList()
        val minLow = DoubleArray(n); val maxHigh = DoubleArray(n)
        minLow[n - 1] = c[n - 1].low; maxHigh[n - 1] = c[n - 1].high
        for (i in n - 2 downTo 0) {
            minLow[i] = minOf(c[i].low, minLow[i + 1])
            maxHigh[i] = maxOf(c[i].high, maxHigh[i + 1])
        }
        return a.swings.map { s ->
            val taken = if (s.index + 1 < n) {
                if (s.type == SwingType.HIGH) maxHigh[s.index + 1] > s.price else minLow[s.index + 1] < s.price
            } else false
            LiquidityLevel(s.price, if (s.type == SwingType.HIGH) LevelKind.SWING_HIGH else LevelKind.SWING_LOW,
                if (s.type == SwingType.HIGH) LiquiditySide.BUY_SIDE else LiquiditySide.SELL_SIDE, swept = taken)
        }
    }

    fun allLevels(a: StructureAnalysis, c: List<Candle>, d1: List<Candle>?, w1: List<Candle>?, cfg: LiquidityConfig = LiquidityConfig()): List<LiquidityLevel> =
        (equalLevels(a, cfg) + previousPeriodLevels(d1, w1) + swingLevels(a, c).filter { !it.swept }).distinctBy { Triple(it.kind, (it.price * 1e6).toLong(), it.side) }

    fun sweeps(c: List<Candle>, a: StructureAnalysis, levels: List<LiquidityLevel>, cfg: LiquidityConfig = LiquidityConfig()): List<LiquiditySweep> {
        val atr = a.atr ?: return emptyList(); val out = mutableListOf<LiquiditySweep>()
        val start = maxOf(1, c.size - cfg.lookbackBars)
        for (i in start until c.size) for (lv in levels) {
            if (lv.side == LiquiditySide.BUY_SIDE) {
                val wick = c[i].high - lv.price
                if (wick >= atr * cfg.minWickBeyondAtr && c[i].close < lv.price && c[i - 1].high <= lv.price) {
                    val conf = a.events.any { it.dir == Dir.BEAR && it.breakIndex in i..(i + cfg.confirmWithinBars) }
                    out += LiquiditySweep(lv, i, c[i].timeMs, wick / atr, conf, Dir.BEAR)
                }
            } else {
                val wick = lv.price - c[i].low
                if (wick >= atr * cfg.minWickBeyondAtr && c[i].close > lv.price && c[i - 1].low >= lv.price) {
                    val conf = a.events.any { it.dir == Dir.BULL && it.breakIndex in i..(i + cfg.confirmWithinBars) }
                    out += LiquiditySweep(lv, i, c[i].timeMs, wick / atr, conf, Dir.BULL)
                }
            }
        }
        return out.sortedBy { it.index }
    }
}
