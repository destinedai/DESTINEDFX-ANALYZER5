package com.example.domain.usecase

import com.example.domain.model.TradeSignal
import com.example.domain.usecase.impl.SignalGenerationUseCaseImpl

interface SignalGenerationUseCase {
    suspend fun generateSignal(symbol: String, timeframe: String, strategyId: String = "smc-confluence-1.0.0"): TradeSignal
    fun getTimingHistory(symbol: String): List<SignalGenerationUseCaseImpl.TimingRecord> = emptyList()
    fun getLastTiming(symbol: String): SignalGenerationUseCaseImpl.TimingRecord? = getTimingHistory(symbol).lastOrNull()
}
