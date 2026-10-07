package com.destinedfx.core.marketdata

import kotlin.math.abs

object CandleSelector {
    fun closedOnly(candles: List<Candle>): List<Candle> {
        if (candles.isEmpty()) return emptyList()
        val diffs = candles.zipWithNext { a, b -> abs(b.timeMs - a.timeMs) }
        val inferredInterval = if (diffs.isNotEmpty()) {
            diffs.groupBy { it }.maxByOrNull { it.value.size }?.key ?: 60_000L
        } else {
            60_000L
        }
        val now = System.currentTimeMillis()
        val currentCandleOpen = (now / inferredInterval) * inferredInterval
        return candles.filter { it.complete && it.timeMs < currentCandleOpen }
    }
}
