package com.example.domain.engine

import com.example.domain.model.Candle
import com.example.domain.model.MarketStructure

interface BOSDetector {
    suspend fun detectBOS(candles: List<Candle>): List<MarketStructure>
}

interface CHOCHDetector {
    suspend fun detectCHOCH(candles: List<Candle>): List<MarketStructure>
}

interface MSSDetector {
    suspend fun detectMSS(candles: List<Candle>): List<MarketStructure>
}
