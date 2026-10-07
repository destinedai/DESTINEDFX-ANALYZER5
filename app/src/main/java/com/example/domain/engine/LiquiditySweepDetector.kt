package com.example.domain.engine

import com.example.domain.model.Candle
import com.example.domain.model.LiquidityZone

interface LiquiditySweepDetector {
    suspend fun detectSweeps(candles: List<Candle>): List<LiquidityZone>
}
