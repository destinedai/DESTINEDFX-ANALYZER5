package com.example.domain.usecase.impl

import com.example.domain.repository.MarketDataRepository
import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import com.example.domain.usecase.GetMarketDataUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GetMarketDataUseCaseImpl(
    private val repo: MarketDataRepository
) : GetMarketDataUseCase {
    override fun getLiveTickers(): Flow<Map<String, MarketTicker>> = repo.tickerFlow
    override fun getCandleHistory(symbol: String): Flow<List<Candle>> =
        repo.tickerFlow.map { repo.getCandles(symbol, "H1", 200) }
    override suspend fun refreshData() = Unit
}
