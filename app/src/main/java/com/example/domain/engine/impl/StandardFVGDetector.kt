package com.example.domain.engine.impl

import com.example.domain.engine.FairValueGapDetector
import com.example.domain.model.Candle
import com.example.domain.model.FVGType
import com.example.domain.model.FairValueGap
import com.example.domain.model.Strength
import kotlin.math.abs

data class FVGCalibrationParams(
    val minGapThresholdRatio: Double = 0.1, // Gap must be at least 10% of the displacement candle's range
    val checkMitigation: Boolean = true,
    val requireBodyEncroachment: Boolean = true // The middle candle must have strong momentum
)

class StandardFVGDetector(
    private val params: FVGCalibrationParams = FVGCalibrationParams()
) : FairValueGapDetector {

    private data class TrackedFVG(
        var fvg: FairValueGap,
        val originCandleIndex: Int
    )

    override suspend fun detectFVGs(candles: List<Candle>): List<FairValueGap> {
        val trackedFvgs = mutableListOf<TrackedFVG>()
        if (candles.size < 3) return emptyList()

        for (i in 0 until candles.size - 2) {
            val c1 = candles[i]
            val c2 = candles[i + 1] // Displacement candle
            val c3 = candles[i + 2]

            val c2Range = c2.high - c2.low
            val c2BodyRatio = abs(c2.close - c2.open) / if (c2Range > 0) c2Range else 1.0

            // Skip if the displacement isn't strong enough and body rule is enabled
            if (params.requireBodyEncroachment && c2BodyRatio < 0.6) continue

            // Bullish FVG
            if (c1.high < c3.low) {
                val gap = c3.low - c1.high
                if (gap >= c2Range * params.minGapThresholdRatio) {
                    val strength = if (gap > c2Range * 0.5) Strength.STRONG else Strength.MEDIUM
                    trackedFvgs.add(TrackedFVG(FairValueGap(FVGType.BULLISH, c3.low, c1.high, strength, false), i + 2))
                }
            }
            
            // Bearish FVG
            if (c1.low > c3.high) {
                val gap = c1.low - c3.high
                if (gap >= c2Range * params.minGapThresholdRatio) {
                    val strength = if (gap > c2Range * 0.5) Strength.STRONG else Strength.MEDIUM
                    trackedFvgs.add(TrackedFVG(FairValueGap(FVGType.BEARISH, c1.low, c3.high, strength, false), i + 2))
                }
            }
            
            // Process Mitigations for existing FVGs (if enabled)
            if (params.checkMitigation) {
                val currentCandle = candles[i + 2]
                val iterator = trackedFvgs.iterator()
                while (iterator.hasNext()) {
                    val tracked = iterator.next()
                    if (tracked.originCandleIndex >= i + 2) continue
                    
                    if (tracked.fvg.type == FVGType.BULLISH && currentCandle.low <= tracked.fvg.bottomPrice) {
                        tracked.fvg = tracked.fvg.copy(mitigated = true)
                    } else if (tracked.fvg.type == FVGType.BEARISH && currentCandle.high >= tracked.fvg.topPrice) {
                        tracked.fvg = tracked.fvg.copy(mitigated = true)
                    }
                }
            }
        }
        
        return trackedFvgs.map { it.fvg }
    }
}
