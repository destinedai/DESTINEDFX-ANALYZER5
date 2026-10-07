package com.destinedfx.core.analysis

import com.destinedfx.core.marketdata.Candle
import com.destinedfx.core.marketdata.VolumeType
import kotlin.math.abs

/** Single authoritative indicator implementations. Series are aligned to the input: null = not enough data yet. */
object Indicators {
    fun trueRange(c: List<Candle>, i: Int): Double =
        if (i == 0) c[0].range else maxOf(c[i].range, abs(c[i].high - c[i - 1].close), abs(c[i].low - c[i - 1].close))

    /** Wilder ATR. */
    fun atr(c: List<Candle>, period: Int = 14): List<Double?> {
        val out = MutableList<Double?>(c.size) { null }
        if (c.size <= period) return out
        var a = (1..period).sumOf { trueRange(c, it) } / period
        out[period] = a
        for (i in period + 1 until c.size) { a = (a * (period - 1) + trueRange(c, i)) / period; out[i] = a }
        return out
    }
    fun lastAtr(c: List<Candle>, period: Int = 14): Double? = atr(c, period).lastOrNull()

    /** Wilder RSI. Returns 100 when there are no losses and 50 when flat. */
    fun rsi(c: List<Candle>, period: Int = 14): List<Double?> {
        val out = MutableList<Double?>(c.size) { null }
        if (c.size <= period) return out
        var gain = 0.0; var loss = 0.0
        for (i in 1..period) { val d = c[i].close - c[i - 1].close; if (d > 0) gain += d else loss -= d }
        gain /= period; loss /= period
        fun rs(g: Double, l: Double) = if (g == 0.0 && l == 0.0) 50.0 else if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
        out[period] = rs(gain, loss)
        for (i in period + 1 until c.size) {
            val d = c[i].close - c[i - 1].close
            gain = (gain * (period - 1) + maxOf(d, 0.0)) / period
            loss = (loss * (period - 1) + maxOf(-d, 0.0)) / period
            out[i] = rs(gain, loss)
        }
        return out
    }

    fun sma(v: List<Double>, period: Int): List<Double?> =
        v.indices.map { i -> if (i + 1 < period) null else v.subList(i + 1 - period, i + 1).average() }

    fun ema(v: List<Double>, period: Int): List<Double?> {
        val out = MutableList<Double?>(v.size) { null }
        if (v.size < period) return out
        var e = v.take(period).average(); out[period - 1] = e
        val k = 2.0 / (period + 1)
        for (i in period until v.size) { e = v[i] * k + e * (1 - k); out[i] = e }
        return out
    }

    fun obv(c: List<Candle>): List<Double> {
        val out = ArrayList<Double>(c.size); var o = 0.0
        for (i in c.indices) { if (i > 0) o += when { c[i].close > c[i - 1].close -> c[i].volume; c[i].close < c[i - 1].close -> -c[i].volume; else -> 0.0 }; out += o }
        return out
    }

    /** volume / SMA(volume, period) at index i, excluding bar i from the average. Null when no usable volume. */
    fun relativeVolume(c: List<Candle>, i: Int, period: Int = 20): Double? {
        if (i < period || c[i].volumeType == VolumeType.NONE) return null
        val avg = c.subList(i - period, i).map { it.volume }.average()
        return if (avg > 0) c[i].volume / avg else null
    }
    fun volumeSpike(c: List<Candle>, i: Int, threshold: Double = 1.5) = (relativeVolume(c, i) ?: 0.0) >= threshold

    /** Real vs tick volume. Forex tick volume is broker-specific activity, NOT centralised exchange volume. */
    fun volumeKind(c: List<Candle>): VolumeType = c.lastOrNull()?.volumeType ?: VolumeType.NONE
}
