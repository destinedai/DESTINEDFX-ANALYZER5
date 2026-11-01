package com.example.domain.patterns

import com.example.domain.knowledge.ClassicalPriceAction
import com.example.domain.model.Candle
import kotlin.math.abs

/** Deterministic OHLC-only pattern recognition. Patterns are descriptive evidence, never trade authorization. */
class CandlestickPatternEngine {
    private data class Shape(val range: Double, val body: Double, val upper: Double, val lower: Double) {
        val bodyRatio get() = body / range
        val upperRatio get() = upper / range
        val lowerRatio get() = lower / range
    }

    fun detectPattern(candles: List<Candle>): CandlestickPatternResult {
        val fallback = candles.lastOrNull() ?: Candle(0L, 1.0, 1.0, 1.0, 1.0, 0.0)
        val invalidReason = validate(candles)
        if (invalidReason != null) return result(CandlestickPatternType.NONE, fallback, "NEUTRAL", 0,
            "Pattern unavailable: $invalidReason", candles, valid = false)
        if (candles.isEmpty()) return result(CandlestickPatternType.NONE, fallback, "NEUTRAL", 0,
            "Insufficient candle data.", candles)
        if (candles.dropLast(1).any { !it.complete }) return result(CandlestickPatternType.NONE, fallback, "NEUTRAL", 0,
            "Pattern unavailable: incomplete candles are present inside the historical series.", candles, valid = false)
        if (!candles.last().complete) return result(CandlestickPatternType.NONE, fallback, "NEUTRAL", 0,
            "Latest candle is still forming; no confirmed pattern is reported.", candles, forming = true)
        if (candles.size < 3) return result(CandlestickPatternType.NONE, fallback, "NEUTRAL", 0,
            "Insufficient closed candles for pattern recognition.", candles)

        val n = candles.size
        val c = candles[n - 1]
        val p = candles[n - 2]
        val p2 = candles[n - 3]
        val s = shape(c)
        val ps = shape(p)
        val p2s = shape(p2)
        val trend = trendBefore(candles, n - 3)
        val down = trend == "DOWN"
        val up = trend == "UP"
        val sameHigh = abs(c.high - p.high) <= maxOf(s.range, ps.range) * 0.08
        val sameLow = abs(c.low - p.low) <= maxOf(s.range, ps.range) * 0.08
        val typeAndBias: Pair<CandlestickPatternType, String> = when {
            // Three-candle formations have precedence over single-candle geometry.
            n >= 5 && threeSoldiers(candles.takeLast(3)) -> CandlestickPatternType.THREE_WHITE_SOLDIERS to "BULLISH"
            n >= 5 && threeCrows(candles.takeLast(3)) -> CandlestickPatternType.THREE_BLACK_CROWS to "BEARISH"
            n >= 5 && down && morningStar(p2, p, c, p2s, ps, s) -> CandlestickPatternType.MORNING_STAR to "BULLISH"
            n >= 5 && up && eveningStar(p2, p, c, p2s, ps, s) -> CandlestickPatternType.EVENING_STAR to "BEARISH"
            down && p.close < p.open && c.close > c.open && c.open < p.low && c.close > p.open + ps.body * 0.5 && c.close < p.open ->
                CandlestickPatternType.PIERCING_LINE to "BULLISH"
            up && p.close > p.open && c.close < c.open && c.open > p.high && c.close < p.open - ps.body * 0.5 && c.close > p.open ->
                CandlestickPatternType.DARK_CLOUD_COVER to "BEARISH"
            down && p.close < p.open && c.close > c.open && c.open >= p.close && c.close <= p.open && s.body < ps.body ->
                CandlestickPatternType.BULLISH_HARAMI to "BULLISH"
            up && p.close > p.open && c.close < c.open && c.open <= p.close && c.close >= p.open && s.body < ps.body ->
                CandlestickPatternType.BEARISH_HARAMI to "BEARISH"
            up && sameHigh && c.close < c.open && p.close > p.open -> CandlestickPatternType.TWEEZER_TOP to "BEARISH"
            down && sameLow && c.close > c.open && p.close < p.open -> CandlestickPatternType.TWEEZER_BOTTOM to "BULLISH"
            p.close < p.open && c.close > c.open && c.open <= p.close && c.close >= p.open && s.body > ps.body ->
                CandlestickPatternType.BULLISH_ENGULFING to "BULLISH"
            p.close > p.open && c.close < c.open && c.open >= p.close && c.close <= p.open && s.body > ps.body ->
                CandlestickPatternType.BEARISH_ENGULFING to "BEARISH"
            s.bodyRatio <= 0.10 && s.lowerRatio >= 0.65 && s.upperRatio <= 0.10 -> CandlestickPatternType.DRAGONFLY_DOJI to "NEUTRAL"
            s.bodyRatio <= 0.10 && s.upperRatio >= 0.65 && s.lowerRatio <= 0.10 -> CandlestickPatternType.GRAVESTONE_DOJI to "NEUTRAL"
            s.bodyRatio <= 0.10 -> CandlestickPatternType.DOJI to "NEUTRAL"
            s.lower >= 2.0 * s.body && s.upper <= 0.15 * s.range && down -> CandlestickPatternType.HAMMER to "BULLISH"
            s.lower >= 2.0 * s.body && s.upper <= 0.15 * s.range && up -> CandlestickPatternType.HANGING_MAN to "BEARISH"
            s.upper >= 2.0 * s.body && s.lower <= 0.15 * s.range && up -> CandlestickPatternType.SHOOTING_STAR to "BEARISH"
            s.upper >= 2.0 * s.body && s.lower <= 0.15 * s.range && down -> CandlestickPatternType.INVERTED_HAMMER to "BULLISH"
            s.bodyRatio >= 0.90 && s.upperRatio <= 0.05 && s.lowerRatio <= 0.05 -> CandlestickPatternType.MARUBOZU to if (c.close > c.open) "BULLISH" else "BEARISH"
            s.bodyRatio in 0.10..0.35 && s.upperRatio >= 0.25 && s.lowerRatio >= 0.25 -> CandlestickPatternType.SPINNING_TOP to "NEUTRAL"
            else -> CandlestickPatternType.NONE to "NEUTRAL"
        }
        val rationale = when (typeAndBias.first) {
            CandlestickPatternType.THREE_WHITE_SOLDIERS -> "Three rising bullish sessions with substantial bodies; indicates persistent buying, not guaranteed reversal."
            CandlestickPatternType.THREE_BLACK_CROWS -> "Three declining bearish sessions with substantial bodies; indicates persistent selling, not guaranteed reversal."
            CandlestickPatternType.TWEEZER_TOP -> "Two closed candles tested a similar high and closed with rejection; requires structural confirmation."
            CandlestickPatternType.TWEEZER_BOTTOM -> "Two closed candles tested a similar low and closed with rejection; requires structural confirmation."
            CandlestickPatternType.PIERCING_LINE -> "Bullish close penetrated beyond the midpoint of the prior bearish body without engulfing it."
            CandlestickPatternType.DARK_CLOUD_COVER -> "Bearish close penetrated beyond the midpoint of the prior bullish body without engulfing it."
            CandlestickPatternType.HAMMER, CandlestickPatternType.HANGING_MAN -> "Long lower shadow and compact upper body show lower-price rejection; interpretation depends on prior trend."
            CandlestickPatternType.SHOOTING_STAR, CandlestickPatternType.INVERTED_HAMMER -> "Long upper shadow shows rejection of higher prices; interpretation depends on prior trend."
            CandlestickPatternType.NONE -> "No qualifying classical pattern; candle direction alone is not a reversal signal."
            else -> "Recognized ${typeAndBias.first.displayName} geometry in closed OHLC candles; confirmation from structure and follow-through is required."
        }
        val strength = when (typeAndBias.first) {
            CandlestickPatternType.NONE -> 0
            CandlestickPatternType.DOJI, CandlestickPatternType.DRAGONFLY_DOJI,
            CandlestickPatternType.GRAVESTONE_DOJI, CandlestickPatternType.SPINNING_TOP -> 0
            else -> (50 + (s.bodyRatio * 25).toInt()).coerceIn(50, 75)
        }
        return result(typeAndBias.first, c, typeAndBias.second, strength, rationale, candles)
    }

    private fun validate(candles: List<Candle>): String? {
        var previousTime: Long? = null
        for ((i, c) in candles.withIndex()) {
            val values = listOf(c.open, c.high, c.low, c.close, c.volume)
            if (values.any { !it.isFinite() }) return "non-finite OHLCV at index $i"
            if (c.open < 0 || c.high < 0 || c.low < 0 || c.close < 0 || c.volume < 0) return "negative price or volume at index $i"
            if (c.high < c.low || c.high < maxOf(c.open, c.close) || c.low > minOf(c.open, c.close)) return "impossible OHLC relationship at index $i"
            if (previousTime != null && c.timestamp <= previousTime) return "duplicate or out-of-order timestamps"
            previousTime = c.timestamp
        }
        return null
    }

    private fun shape(c: Candle): Shape {
        val range = c.high - c.low
        if (range <= 0.0) return Shape(0.0, 0.0, 0.0, 0.0)
        return Shape(range, abs(c.close - c.open), c.high - maxOf(c.open, c.close), minOf(c.open, c.close) - c.low)
    }

    private fun trendBefore(candles: List<Candle>, endExclusive: Int): String {
        val end = endExclusive.coerceIn(0, candles.size)
        val sample = candles.take(end).takeLast(5)
        if (sample.size < 3) return "UNKNOWN"
        val delta = sample.last().close - sample.first().close
        val scale = sample.map { it.high - it.low }.average().coerceAtLeast(1e-12)
        return when { delta > scale * 0.5 -> "UP"; delta < -scale * 0.5 -> "DOWN"; else -> "RANGE" }
    }

    private fun threeSoldiers(cs: List<Candle>): Boolean = cs.size == 3 && cs.all {
        it.close > it.open && shape(it).bodyRatio >= 0.55
    } && cs[1].close > cs[0].close && cs[2].close > cs[1].close &&
        cs[1].open in cs[0].open..cs[0].close && cs[2].open in cs[1].open..cs[1].close

    private fun threeCrows(cs: List<Candle>): Boolean = cs.size == 3 && cs.all {
        it.close < it.open && shape(it).bodyRatio >= 0.55
    } && cs[1].close < cs[0].close && cs[2].close < cs[1].close &&
        cs[1].open in cs[0].close..cs[0].open && cs[2].open in cs[1].close..cs[1].open

    private fun morningStar(a: Candle, b: Candle, c: Candle, as_: Shape, bs: Shape, cs: Shape) =
        a.close < a.open && as_.bodyRatio >= 0.5 && bs.body < as_.body * 0.35 &&
            c.close > c.open && c.close >= (a.open + a.close) / 2.0 && cs.bodyRatio >= 0.4

    private fun eveningStar(a: Candle, b: Candle, c: Candle, as_: Shape, bs: Shape, cs: Shape) =
        a.close > a.open && as_.bodyRatio >= 0.5 && bs.body < as_.body * 0.35 &&
            c.close < c.open && c.close <= (a.open + a.close) / 2.0 && cs.bodyRatio >= 0.4

    private fun result(type: CandlestickPatternType, candle: Candle, bias: String, score: Int, text: String,
                       candles: List<Candle>, valid: Boolean = true, forming: Boolean = false): CandlestickPatternResult {
        val sh = shape(candle)
        val trend = trendBefore(candles, (candles.size - 3).coerceAtLeast(0))
        val recent = candles.takeLast(21).dropLast(if (forming) 1 else 0)
        val loc = if (recent.isEmpty()) "UNAVAILABLE" else {
            val lo = recent.minOf { it.low }; val hi = recent.maxOf { it.high }
            when { hi <= lo -> "UNAVAILABLE"; candle.close >= hi - (hi - lo) * 0.1 -> "NEAR_RECENT_HIGH";
                candle.close <= lo + (hi - lo) * 0.1 -> "NEAR_RECENT_LOW"; else -> "MID_RANGE" }
        }
        val avgRange = recent.map { it.high - it.low }.average()
        val vol = if (!avgRange.isFinite() || avgRange <= 0.0) "UNAVAILABLE" else
            if (sh.range > avgRange * 1.5) "EXPANSION" else if (sh.range < avgRange * 0.65) "CONTRACTION" else "TYPICAL"
        val previous = candles.dropLast(1).lastOrNull()?.let { prev ->
            when { candle.open > prev.close -> "GAP_UP"; candle.open < prev.close -> "GAP_DOWN";
                candle.close > candle.open -> "BULLISH_BODY"; candle.close < candle.open -> "BEARISH_BODY"; else -> "DOJI_BODY" }
        } ?: "UNAVAILABLE"
        val liquidity = if (recent.size < 2) "UNAVAILABLE" else {
            val prior = recent.dropLast(1); when {
                candle.low < prior.minOf { it.low } && candle.close > candle.low + sh.range * 0.5 -> "LOW_SWEEP_AND_RECLAIM_OBSERVED"
                candle.high > prior.maxOf { it.high } && candle.close < candle.high - sh.range * 0.5 -> "HIGH_SWEEP_AND_REJECTION_OBSERVED"
                else -> "NO_SWEEP_OBSERVED"
            }
        }
        return CandlestickPatternResult(type, candle, bias, score, text, ClassicalPriceAction.getFraming(type),
            sh.bodyRatio.takeIf { it.isFinite() } ?: 0.0, sh.upperRatio.takeIf { it.isFinite() } ?: 0.0,
            sh.lowerRatio.takeIf { it.isFinite() } ?: 0.0, trend, vol, previous, loc, liquidity,
            confirmed = valid && candle.complete && type != CandlestickPatternType.NONE,
            forming = forming, dataValid = valid)
    }
}
