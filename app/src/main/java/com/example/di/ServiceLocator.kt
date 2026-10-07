package com.example.di

import com.example.domain.repository.MarketDataRepository
import com.example.domain.usecase.GetMarketDataUseCase
import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class MarketDataUseCaseImpl(
    private val repository: MarketDataRepository
) : GetMarketDataUseCase {
    private val tickers = MutableStateFlow<Map<String, MarketTicker>>(emptyMap())
    
    override fun getLiveTickers(): Flow<Map<String, MarketTicker>> = repository.tickerFlow
    
    override fun getCandleHistory(symbol: String): Flow<List<Candle>> {
        return MutableStateFlow(emptyList())
    }
    
    override suspend fun refreshData() {
    }
}

object ServiceLocator {
    val mt5Bridge = com.example.data.trade.MT5Bridge()
}
