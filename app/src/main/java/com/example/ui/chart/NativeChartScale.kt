package com.example.ui.chart

import com.example.domain.model.Candle

/** Finite candle-only axis. Strategy levels and spot markers are overlays, never scale inputs. */
data class NativeChartScale(val minPrice: Double, val maxPrice: Double) {
    val range: Double get() = maxPrice - minPrice

    fun priceToFraction(price: Double): Double? {
        if (!price.isFinite() || price <= 0.0 || !range.isFinite() || range <= 0.0) return null
        return ((price - minPrice) / range).takeIf { it.isFinite() }
    }

    fun fractionToPrice(fraction: Double): Double? {
        if (!fraction.isFinite() || !range.isFinite() || range <= 0.0) return null
        return (minPrice + fraction * range).takeIf { it.isFinite() && it > 0.0 }
    }
}

/** Candle validity matches OHLC constraints; invalid provider rows do not affect the chart. */
fun isValidChartCandle(candle: Candle): Boolean =
    candle.timestamp > 0L &&
        listOf(candle.open, candle.high, candle.low, candle.close).all { it.isFinite() && it > 0.0 } &&
        candle.high >= maxOf(candle.open, candle.close, candle.low) &&
        candle.low <= minOf(candle.open, candle.close, candle.high)

fun validatedChartCandles(candles: List<Candle>): List<Candle> = candles.filter(::isValidChartCandle)

/** Return null when there is no valid history; padding handles constant and very narrow prices. */
fun buildNativeChartScale(candles: List<Candle>, symbol: String): NativeChartScale? {
    val valid = validatedChartCandles(candles)
    if (valid.isEmpty()) return null
    val low = valid.minOf { it.low }
    val high = valid.maxOf { it.high }
    if (!low.isFinite() || !high.isFinite() || low <= 0.0 || high < low) return null

    val normalized = symbol.uppercase()
    val minStep = when {
        normalized.contains("JPY") -> 0.001
        normalized.contains("XAU") || normalized.contains("XAG") || normalized.contains("XPT") || normalized.contains("XPD") -> 0.01
        normalized.contains("BTC") || normalized.contains("ETH") || normalized.contains("US30") || normalized.contains("NAS") || normalized.contains("SPX") -> 0.1
        else -> 0.00001
    }
    val rawRange = high - low
    val padding = maxOf(rawRange * 0.08, minStep * 4.0, high * 1e-7)
    val minPrice = (low - padding).coerceAtLeast(minStep)
    val maxPrice = high + padding
    return if (minPrice.isFinite() && maxPrice.isFinite() && maxPrice > minPrice) {
        NativeChartScale(minPrice, maxPrice)
    } else null
}

fun isValidSpotPrice(price: Double?): Boolean = price != null && price.isFinite() && price > 0.0

/** Levels become drawable only with symbol-matched actionable BUY/SELL output and valid geometry. */
data class NativeChartTradeLevels(val entry: Double, val stop: Double, val target: Double?)

fun validNativeChartTradeLevels(
    direction: String,
    analysisSymbol: String,
    chartSymbol: String,
    entry: Double,
    stop: Double,
    target: Double
): NativeChartTradeLevels? {
    if (!analysisSymbol.equals(chartSymbol, ignoreCase = true)) return null
    if (direction != "BUY" && direction != "SELL") return null
    if (!entry.isFinite() || !stop.isFinite() || entry <= 0.0 || stop <= 0.0 || entry == stop) return null
    if (direction == "BUY" && stop >= entry || direction == "SELL" && stop <= entry) return null
    val validTarget = target.takeIf {
        it.isFinite() && it > 0.0 && if (direction == "BUY") it > entry else it < entry
    }
    return NativeChartTradeLevels(entry, stop, validTarget)
}
