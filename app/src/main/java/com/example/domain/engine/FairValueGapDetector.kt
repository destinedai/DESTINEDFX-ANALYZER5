package com.example.domain.engine

import com.example.domain.model.Candle
import com.example.domain.model.FairValueGap

interface FairValueGapDetector {
    suspend fun detectFVGs(candles: List<Candle>): List<FairValueGap>
}
