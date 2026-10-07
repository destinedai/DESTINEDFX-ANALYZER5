package com.example.domain.usecase

import com.example.domain.model.TradeSignal
import com.example.domain.model.BacktestResult

interface TradeUseCase {
    suspend fun executeTrade(signal: TradeSignal)
}

interface BacktestUseCase {
    suspend fun runBacktest(strategy: String): BacktestResult
}
