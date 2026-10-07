package com.destinedfx.core.marketdata

import kotlin.math.abs

/** Asset classes are separate so a crypto feed can never silently stand in for forex. */
enum class AssetClass { FOREX, METALS, INDICES, CRYPTO, COMMODITIES }
enum class MarketDataHealth { LIVE, DELAYED, STALE, DISCONNECTED, GAP_DETECTED, INVALID, SOURCE_ERROR }
enum class VolumeType { REAL, TICK, NONE }

enum class Timeframe(val label: String, val minutes: Int) {
    M1("M1", 1), M5("M5", 5), M15("M15", 15), M30("M30", 30),
    H1("H1", 60), H4("H4", 240), D1("D1", 1440), W1("W1", 10080);
    val millis: Long get() = minutes * 60_000L
    companion object {
        fun parse(s: String): Timeframe? = when (s.trim().uppercase()) {
            "M1", "1" -> M1; "M5", "5" -> M5; "M15", "15" -> M15; "M30", "30" -> M30
            "H1", "60" -> H1; "H4", "240" -> H4; "D1", "D", "1D" -> D1; "W1", "W", "1W" -> W1
            else -> null
        }
    }
}

/**
 * Normalised quote. bid/ask are null when the source only supplies a last/mid price (most free REST
 * APIs). In that case spread is null and trading is blocked by the spread check - nothing is invented.
 */
data class MarketQuote(
    val symbol: String, val assetClass: AssetClass,
    val bid: Double?, val ask: Double?, val last: Double?,
    val timestampMs: Long, val receivedAtMs: Long, val source: String,
    val sequence: Long? = null, val volumeType: VolumeType = VolumeType.NONE
) {
    val mid: Double? get() = if (bid != null && ask != null) (bid + ask) / 2.0 else last
    /** The ONE spread formula: ask - bid. Null when bid/ask are not both known. */
    val spread: Double? get() = if (bid != null && ask != null) ask - bid else null
    fun ageMs(nowMs: Long): Long = (nowMs - receivedAtMs).coerceAtLeast(0)
    val isStructurallyValid: Boolean get() {
        val m = mid ?: return false
        if (!m.isFinite() || m <= 0.0) return false
        if (bid != null && ask != null) return bid.isFinite() && ask.isFinite() && bid > 0.0 && ask >= bid
        return true
    }
    companion object {
        fun twoSided(symbol: String, cls: AssetClass, bid: Double, ask: Double, tsMs: Long, rxMs: Long, source: String, seq: Long? = null) =
            MarketQuote(symbol, cls, bid, ask, null, tsMs, rxMs, source, seq).takeIf { it.isStructurallyValid }
        fun lastOnly(symbol: String, cls: AssetClass, last: Double, tsMs: Long, rxMs: Long, source: String) =
            MarketQuote(symbol, cls, null, null, last, tsMs, rxMs, source).takeIf { it.isStructurallyValid }
    }
}

data class Candle(
    val timeMs: Long, val open: Double, val high: Double, val low: Double, val close: Double,
    val volume: Double = 0.0, val volumeType: VolumeType = VolumeType.NONE,
    val spread: Double? = null, val complete: Boolean = true
) {
    val body: Double get() = abs(close - open)
    val range: Double get() = high - low
    val isBullish: Boolean get() = close > open
    val isBearish: Boolean get() = close < open
    val isValid: Boolean get() = listOf(open, high, low, close).all { it.isFinite() && it > 0.0 } &&
        high >= maxOf(open, close) && low <= minOf(open, close) && volume >= 0.0
}

data class CandleSeriesCheck(val ok: Boolean, val problems: List<String>, val cleanClosed: List<Candle>)

object CandleValidator {
    /** Reports problems; never repairs or interpolates. Weekend gaps are tolerated only for non-24/7 markets. */
    fun validate(candles: List<Candle>, tf: Timeframe, minCount: Int, tolerateWeekendGaps: Boolean): CandleSeriesCheck {
        val problems = mutableListOf<String>()
        val closed = candles.filter { it.complete }
        if (closed.size < minCount) problems += "Only ${closed.size} closed candles, need $minCount"
        if (closed.any { !it.isValid }) problems += "Invalid OHLC values present"
        for (i in 1 until closed.size) {
            val dt = closed[i].timeMs - closed[i - 1].timeMs
            if (dt <= 0) { problems += "Non-increasing timestamps at index $i"; break }
            val missing = dt / tf.millis - 1
            if (missing >= 1) {
                val weekend = tolerateWeekendGaps && tf.minutes < 10080 && crossesWeekend(closed[i - 1].timeMs, closed[i].timeMs)
                if (!weekend) { problems += "$missing missing candle(s) before index $i"; break }
            }
        }
        return CandleSeriesCheck(problems.isEmpty(), problems, closed)
    }
    /** True if the gap contains a Saturday or Sunday (UTC) and is at most 72h: normal for FX/metals/indices. */
    private fun crossesWeekend(a: Long, b: Long): Boolean {
        if (b - a > 72 * 3_600_000L) return false
        var t = a
        while (t <= b) {
            val d = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneOffset.UTC).dayOfWeek.value
            if (d >= 6) return true
            t += 3_600_000L
        }
        return false
    }
}
