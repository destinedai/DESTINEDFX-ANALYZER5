package com.example.domain.engine

import com.example.domain.model.Candle

interface FractalDetector {
    suspend fun detectFractals(candles: List<Candle>): Map<Int, String> // Index to Type ("HIGH", "LOW")
}
