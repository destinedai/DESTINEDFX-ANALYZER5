package com.example.domain.engine.impl

import com.example.domain.engine.LiquiditySweepDetector
import com.example.domain.model.Candle
import com.example.domain.model.LiquidityType
import com.example.domain.model.LiquidityZone

data class SweepCalibrationParams(
    val pivotLeftBars: Int = 5,
    val pivotRightBars: Int = 5,
    val maxPiercePercent: Double = 0.5, // e.g., max 0.5% penetration (prevents misclassifying breakouts as sweeps)
    val minWickRejectionRatio: Double = 0.4, // The wick responsible for the sweep must be at least 40% of the candle's total body
    val requireOppositeClose: Boolean = true // BSL sweeps must close completely bearish, SSL sweeps must close bullish
)

class StandardLiquidityDetector(
    private val params: SweepCalibrationParams = SweepCalibrationParams()
) : LiquiditySweepDetector {

    private data class LiquidityPool(val priceLevel: Double, val index: Int, val type: LiquidityType)

    override suspend fun detectSweeps(candles: List<Candle>): List<LiquidityZone> {
        val sweeps = mutableListOf<LiquidityZone>()
        if (candles.size < params.pivotLeftBars + params.pivotRightBars + 1) return sweeps

        val bslPools = mutableListOf<LiquidityPool>()
        val sslPools = mutableListOf<LiquidityPool>()

        // 1. Identify valid Swing Pivots to map liquidity pools
        for (i in params.pivotLeftBars until candles.size - params.pivotRightBars) {
            if (isPivotHigh(candles, i)) {
                bslPools.add(LiquidityPool(candles[i].high, i, LiquidityType.BSL))
            }
            if (isPivotLow(candles, i)) {
                sslPools.add(LiquidityPool(candles[i].low, i, LiquidityType.SSL))
            }
        }

        // 2. Scan forward to detect sweeps at mapped liquidity levels
        for (candleIdx in 0 until candles.size) {
            val candle = candles[candleIdx]
            val candleRange = candle.high - candle.low

            // Process BSL (Buy-Side Liquidity) Sweeps
            val bslIterator = bslPools.iterator()
            while (bslIterator.hasNext()) {
                val pool = bslIterator.next()
                if (candleIdx <= pool.index + params.pivotRightBars) continue // Cannot sweep its own construction window

                if (candle.high >= pool.priceLevel) {
                    val pierceSize = candle.high - pool.priceLevel
                    val piercePercent = (pierceSize / pool.priceLevel) * 100.0
                    val isClosedBelow = candle.close < pool.priceLevel
                    val isBearish = candle.close <= candle.open
                    val upperWick = candle.high - maxOf(candle.open, candle.close)
                    val wickRatio = if (candleRange > 0) upperWick / candleRange else 0.0

                    val isValidSweep = isClosedBelow &&
                            piercePercent <= params.maxPiercePercent &&
                            wickRatio >= params.minWickRejectionRatio &&
                            (!params.requireOppositeClose || isBearish)

                    if (!isClosedBelow) {
                        // Structural Breakout (Liquidity logic invalidated)
                        bslIterator.remove()
                    } else if (isValidSweep) {
                        // Valid Broker Sweep Detected
                        sweeps.add(LiquidityZone(LiquidityType.BSL, pool.priceLevel, true, candle.timestamp))
                        bslIterator.remove()
                    }
                }
            }

            // Process SSL (Sell-Side Liquidity) Sweeps
            val sslIterator = sslPools.iterator()
            while (sslIterator.hasNext()) {
                val pool = sslIterator.next()
                if (candleIdx <= pool.index + params.pivotRightBars) continue

                if (candle.low <= pool.priceLevel) {
                    val pierceSize = pool.priceLevel - candle.low
                    val piercePercent = (pierceSize / pool.priceLevel) * 100.0
                    val isClosedAbove = candle.close > pool.priceLevel
                    val isBullish = candle.close >= candle.open
                    val lowerWick = minOf(candle.open, candle.close) - candle.low
                    val wickRatio = if (candleRange > 0) lowerWick / candleRange else 0.0

                    val isValidSweep = isClosedAbove &&
                            piercePercent <= params.maxPiercePercent &&
                            wickRatio >= params.minWickRejectionRatio &&
                            (!params.requireOppositeClose || isBullish)

                    if (!isClosedAbove) {
                        // Structural Breakdown
                        sslIterator.remove()
                    } else if (isValidSweep) {
                        // Valid Broker Sweep Detected
                        sweeps.add(LiquidityZone(LiquidityType.SSL, pool.priceLevel, true, candle.timestamp))
                        sslIterator.remove()
                    }
                }
            }
        }
        return sweeps
    }

    private fun isPivotHigh(candles: List<Candle>, index: Int): Boolean {
        val high = candles[index].high
        for (i in index - params.pivotLeftBars..index + params.pivotRightBars) {
            if (i != index && candles[i].high >= high) return false
        }
        return true
    }

    private fun isPivotLow(candles: List<Candle>, index: Int): Boolean {
        val low = candles[index].low
        for (i in index - params.pivotLeftBars..index + params.pivotRightBars) {
            if (i != index && candles[i].low <= low) return false
        }
        return true
    }
}
