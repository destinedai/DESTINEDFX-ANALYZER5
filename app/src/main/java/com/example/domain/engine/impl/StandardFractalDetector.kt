package com.example.domain.engine.impl

import com.example.domain.engine.FractalDetector
import com.example.domain.model.Candle

data class FractalCalibrationParams(
    val leftBars: Int = 2,
    val rightBars: Int = 2,
    val filterSymmetric: Boolean = false // if true, both sides must be cleanly ascending/descending
)

class StandardFractalDetector(
    private val params: FractalCalibrationParams = FractalCalibrationParams()
) : FractalDetector {
    
    override suspend fun detectFractals(candles: List<Candle>): Map<Int, String> {
        val fractals = mutableMapOf<Int, String>()
        val totalBars = params.leftBars + params.rightBars + 1
        
        if (candles.size < totalBars) return fractals

        for (i in params.leftBars until candles.size - params.rightBars) {
            val center = candles[i]
            var isHigh = true
            var isLow = true

            // Check Left Side
            for (j in 1..params.leftBars) {
                if (candles[i - j].high >= center.high) isHigh = false
                if (candles[i - j].low <= center.low) isLow = false
                
                if (params.filterSymmetric && j < params.leftBars) {
                    if (candles[i - j].high < candles[i - j - 1].high) isHigh = false
                    if (candles[i - j].low > candles[i - j - 1].low) isLow = false
                }
            }

            // Check Right Side
            for (j in 1..params.rightBars) {
                if (candles[i + j].high >= center.high) isHigh = false
                if (candles[i + j].low <= center.low) isLow = false
                
                if (params.filterSymmetric && j < params.rightBars) {
                    if (candles[i + j].high < candles[i + j + 1].high) isHigh = false
                    if (candles[i + j].low > candles[i + j + 1].low) isLow = false
                }
            }

            if (isHigh && !isLow) fractals[i] = "HIGH"
            if (isLow && !isHigh) fractals[i] = "LOW"
        }
        return fractals
    }
}
