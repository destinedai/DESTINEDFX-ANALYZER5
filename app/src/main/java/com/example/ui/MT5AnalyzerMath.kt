package com.example.ui

import com.example.data.trade.VerifiedBrokerCandle
import com.example.data.trade.VerifiedBrokerQuote
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class MT5AnalyzerInsight(
    val trend: String = "NEUTRAL",
    val confidence: Int = 0,
    val reason: String = "Waiting for MT5 candle data.",
    val support: Double? = null,
    val resistance: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val higherTimeframe: String = "UNKNOWN",
    val lowerTimeframe: String = "UNKNOWN",
    val structure: String = "UNCONFIRMED",
    val marketState: String = "WAITING",
    val swingHigh: Double? = null,
    val swingLow: Double? = null
)

private data class MT5Structure(
    val direction: String,
    val structure: String,
    val marketState: String,
    val swingHigh: Double?,
    val swingLow: Double?,
    val atr: Double?
)

object MT5AnalyzerMath {
    private val timeframes = listOf("M1", "M5", "M15", "M30", "H1", "H4", "D1", "W1")

    fun analyze(
        quote: VerifiedBrokerQuote?,
        candles: List<VerifiedBrokerCandle>,
        selectedTimeframe: String = candles.firstOrNull()?.timeframe ?: "M15"
    ): MT5AnalyzerInsight {
        if (quote == null || quote.source != "MT5_TERMINAL") {
            return MT5AnalyzerInsight(reason = "Waiting for MT5 quote.")
        }
        val price = quote.bid
        if (!price.isFinite() || price <= 0.0) {
            return MT5AnalyzerInsight(reason = "Waiting for valid MT5 quote.")
        }

        val timeframe = selectedTimeframe.uppercase(Locale.ROOT)
            .takeIf { it in timeframes } ?: "M15"
        val symbolCandles = validCandles(candles, quote.symbol)
        val lowerBars = symbolCandles.filter { it.timeframe.equals(timeframe, true) }
        val higherTf = nextHigherTimeframe(timeframe)
        val higherBars = symbolCandles.filter { it.timeframe.equals(higherTf, true) }
        val lower = structure(lowerBars)
        val higher = structure(higherBars)

        val recent = lowerBars.takeLast(20)
        val support = recent.minOfOrNull { it.low }
        val resistance = recent.maxOfOrNull { it.high }
        val higherLabel = "$higherTf ${higher.direction}"
        val lowerLabel = "$timeframe ${lower.direction}"
        val marketState = when {
            lower.marketState == "RANGING" || higher.marketState == "RANGING" -> "RANGING"
            lower.marketState == "COOLING" || higher.marketState == "COOLING" -> "COOLING"
            higher.direction == "UNKNOWN" -> "WAITING FOR HTF"
            lower.direction != higher.direction -> "TRANSITION"
            lower.direction in setOf("UPTREND", "DOWNTREND") -> "TRENDING"
            else -> "TRANSITION"
        }

        if (lowerBars.size < 21) {
            return MT5AnalyzerInsight(
                reason = "Waiting for enough closed MT5 candles.",
                support = support,
                resistance = resistance,
                higherTimeframe = higherLabel,
                lowerTimeframe = lowerLabel,
                structure = lower.structure,
                marketState = marketState,
                swingHigh = lower.swingHigh,
                swingLow = lower.swingLow
            )
        }
        if (higherBars.size < 21 || higher.direction == "UNKNOWN") {
            return MT5AnalyzerInsight(
                reason = "Waiting for higher-timeframe MT5 confirmation.",
                support = support,
                resistance = resistance,
                higherTimeframe = higherLabel,
                lowerTimeframe = lowerLabel,
                structure = lower.structure,
                marketState = "WAITING FOR HTF",
                swingHigh = lower.swingHigh,
                swingLow = lower.swingLow
            )
        }

        val sma20 = recent.map { it.close }.average()
        val rsi = rsi14(lowerBars)
            ?: return MT5AnalyzerInsight(reason = "Waiting for valid MT5 RSI data.",
                support = support, resistance = resistance, higherTimeframe = higherLabel,
                lowerTimeframe = lowerLabel, structure = lower.structure, marketState = marketState,
                swingHigh = lower.swingHigh, swingLow = lower.swingLow)
        val atr = atr14(lowerBars)
            ?: return MT5AnalyzerInsight(reason = "Waiting for valid MT5 volatility data.",
                support = support, resistance = resistance, higherTimeframe = higherLabel,
                lowerTimeframe = lowerLabel, structure = lower.structure, marketState = marketState,
                swingHigh = lower.swingHigh, swingLow = lower.swingLow)

        val last = lowerBars.last()
        val previous = lowerBars[lowerBars.lastIndex - 1]
        val momentum = when {
            price > sma20 && rsi >= 53.0 && last.close >= previous.close -> "BUY"
            price < sma20 && rsi <= 47.0 && last.close <= previous.close -> "SELL"
            else -> "NEUTRAL"
        }
        val trend = when {
            momentum == "BUY" && higher.direction == "UPTREND" &&
                lower.direction == "UPTREND" && marketState == "TRENDING" -> "BUY"
            momentum == "SELL" && higher.direction == "DOWNTREND" &&
                lower.direction == "DOWNTREND" && marketState == "TRENDING" -> "SELL"
            else -> "NEUTRAL"
        }

        val distance = (abs(price - sma20) / atr).coerceIn(0.0, 2.0)
        val rsiPower = (abs(rsi - 50.0) / 25.0).coerceIn(0.0, 1.0)
        val candlePower = (abs(last.close - previous.close) / atr).coerceIn(0.0, 1.0)
        val confidence = if (trend == "NEUTRAL") 0 else
            (55.0 + distance * 9.0 + rsiPower * 13.0 + candlePower * 7.0).roundToInt().coerceIn(50, 92)
        val ma = String.format(Locale.US, "%.5f", sma20)
        val reason = when (trend) {
            "BUY" -> "Price above MA20 · HTF uptrend · higher highs and higher lows"
            "SELL" -> "Price below MA20 · HTF downtrend · lower highs and lower lows"
            else -> when (marketState) {
                "RANGING" -> "No confirmed direction · market ranging · wait for structure break"
                "COOLING" -> "No confirmed direction · momentum cooling · wait for expansion"
                "TRANSITION" -> "No confirmed direction · higher/lower timeframe conflict"
                "WAITING FOR HTF" -> "No confirmed direction · waiting for higher-timeframe candles"
                else -> "No confirmed direction · MA20 $ma · RSI ${rsi.roundToInt()}"
            }
        }

        val stop: Double?
        val target: Double?
        when (trend) {
            "BUY" -> {
                val structuralStop = lower.swingLow ?: support
                stop = minOf(structuralStop ?: (price - atr * 1.5), price - atr * 1.5)
                    .takeIf { it.isFinite() && it > 0.0 && it < price }
                val risk = stop?.let { price - it }
                target = risk?.let { price + it * 2.0 }?.takeIf { it.isFinite() }
            }
            "SELL" -> {
                val structuralStop = lower.swingHigh ?: resistance
                stop = maxOf(structuralStop ?: (price + atr * 1.5), price + atr * 1.5)
                    .takeIf { it.isFinite() && it > price }
                val risk = stop?.let { it - price }
                target = risk?.let { price - it * 2.0 }?.takeIf { it.isFinite() && it > 0.0 }
            }
            else -> {
                stop = null
                target = null
            }
        }
        return MT5AnalyzerInsight(
            trend = trend,
            confidence = confidence,
            reason = reason,
            support = support,
            resistance = resistance,
            stopLoss = stop,
            takeProfit = target,
            higherTimeframe = higherLabel,
            lowerTimeframe = lowerLabel,
            structure = lower.structure,
            marketState = marketState,
            swingHigh = lower.swingHigh,
            swingLow = lower.swingLow
        )
    }

    private fun validCandles(candles: List<VerifiedBrokerCandle>, symbol: String): List<VerifiedBrokerCandle> =
        candles.asSequence().filter {
            it.source == "MT5_TERMINAL" && it.complete && it.symbol.equals(symbol, true) &&
                it.timeframe.uppercase(Locale.ROOT) in timeframes && it.timestampMs > 0L &&
                it.open.isFinite() && it.high.isFinite() && it.low.isFinite() && it.close.isFinite() &&
                it.open > 0.0 && it.low > 0.0 &&
                it.high >= maxOf(it.open, it.close, it.low) &&
                it.low <= minOf(it.open, it.close)
        }.distinctBy { "${it.timeframe}:${it.timestampMs}" }
            .sortedBy { it.timestampMs }.toList()

    private fun nextHigherTimeframe(timeframe: String): String = when (timeframe) {
        "M1" -> "M5"
        "M5" -> "M15"
        "M15" -> "H1"
        "M30" -> "H4"
        "H1" -> "H4"
        "H4" -> "D1"
        "D1" -> "W1"
        else -> "W1"
    }

    private fun structure(bars: List<VerifiedBrokerCandle>): MT5Structure {
        if (bars.size < 21) return MT5Structure("UNKNOWN", "UNCONFIRMED", "WAITING", null, null, null)
        val atr = atr14(bars)
        val recent = bars.takeLast(20)
        val range = recent.maxOf { it.high } - recent.minOf { it.low }
        val netMove = abs(recent.last().close - recent.first().open)
        val recentAtr = averageTrueRange(bars.takeLast(7))
        val previousAtr = averageTrueRange(bars.dropLast(7).takeLast(14))
        val cooling = recentAtr != null && previousAtr != null &&
            recentAtr < previousAtr * 0.70 && netMove < previousAtr * 2.0
        val ranging = atr != null && range < atr * 5.0 && netMove < atr * 2.5

        val swingHighs = mutableListOf<Double>()
        val swingLows = mutableListOf<Double>()
        for (i in 2 until bars.size - 2) {
            val current = bars[i]
            val neighbors = (i - 2..i + 2).filter { it != i }
            if (neighbors.all { current.high > bars[it].high }) swingHighs += current.high
            if (neighbors.all { current.low < bars[it].low }) swingLows += current.low
        }
        val lastHighs = swingHighs.takeLast(2)
        val lastLows = swingLows.takeLast(2)
        val higherHigh = lastHighs.size == 2 && lastHighs[1] > lastHighs[0]
        val lowerHigh = lastHighs.size == 2 && lastHighs[1] < lastHighs[0]
        val higherLow = lastLows.size == 2 && lastLows[1] > lastLows[0]
        val lowerLow = lastLows.size == 2 && lastLows[1] < lastLows[0]

        val direction = when {
            ranging -> "RANGING"
            cooling -> "COOLING"
            higherHigh && higherLow -> "UPTREND"
            lowerHigh && lowerLow -> "DOWNTREND"
            else -> "MIXED"
        }
        val detail = when {
            higherHigh && higherLow -> "HH + HL"
            lowerHigh && lowerLow -> "LH + LL"
            higherHigh && lowerLow -> "HH + LL"
            lowerHigh && higherLow -> "LH + HL"
            ranging -> "RANGE"
            cooling -> "COOLING"
            else -> "MIXED SWINGS"
        }
        val recentSwingHigh = swingHighs.lastOrNull()
        val recentSwingLow = swingLows.lastOrNull()
        val marketState = when {
            ranging -> "RANGING"
            cooling -> "COOLING"
            else -> direction
        }
        return MT5Structure(direction, detail, marketState, recentSwingHigh, recentSwingLow, atr)
    }

    private fun averageTrueRange(bars: List<VerifiedBrokerCandle>): Double? {
        if (bars.size < 3) return null
        val ranges = (1 until bars.size).map { i ->
            val current = bars[i]
            val previousClose = bars[i - 1].close
            maxOf(
                current.high - current.low,
                abs(current.high - previousClose),
                abs(current.low - previousClose)
            )
        }
        return ranges.average().takeIf { it.isFinite() && it > 0.0 }
    }

    private fun rsi14(bars: List<VerifiedBrokerCandle>): Double? {
        if (bars.size < 15) return null
        val changes = bars.takeLast(15).zipWithNext { a, b -> b.close - a.close }
        val gain = changes.sumOf { it.coerceAtLeast(0.0) } / 14.0
        val loss = changes.sumOf { (-it).coerceAtLeast(0.0) } / 14.0
        if (!gain.isFinite() || !loss.isFinite()) return null
        if (loss <= 0.0) return 100.0
        val rs = gain / loss
        return (100.0 - 100.0 / (1.0 + rs)).takeIf { it.isFinite() }
    }

    private fun atr14(bars: List<VerifiedBrokerCandle>): Double? {
        if (bars.size < 15) return null
        return averageTrueRange(bars.takeLast(15))
    }
}
