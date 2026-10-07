package com.example.domain.engine

import com.example.domain.model.Candle
import com.example.domain.model.MarketAnalysisResult

interface MarketStructureAnalyzer {
    suspend fun analyzeStructure(candles: List<Candle>): MarketAnalysisResult
}
