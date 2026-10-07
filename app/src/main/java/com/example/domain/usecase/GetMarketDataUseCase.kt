package com.example.domain.usecase

import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import kotlinx.coroutines.flow.Flow

interface GetMarketDataUseCase {
    fun getLiveTickers(): Flow<Map<String, MarketTicker>>
    fun getCandleHistory(symbol: String): Flow<List<Candle>>
    suspend fun refreshData()
}
