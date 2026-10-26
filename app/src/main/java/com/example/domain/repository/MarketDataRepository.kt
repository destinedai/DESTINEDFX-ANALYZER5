package com.example.domain.repository

import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import com.example.domain.model.Tick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface MarketDataRepository {
    suspend fun getCandles(symbol: String, timeframe: String, count: Int): List<Candle>
    suspend fun getLatestTick(symbol: String): Tick?
    fun isFresh(symbol: String, maxAgeMillis: Long = 15000): Boolean
    suspend fun refreshSymbol(symbol: String): MarketTicker?
    val tickerFlow: StateFlow<Map<String, MarketTicker>>
    val quoteDiagnostics: StateFlow<Map<String, String>>
    val candleFetchErrors: StateFlow<Map<String, String>>
    val selectedApiSource: MutableStateFlow<String>
    val candleSources: Map<String, String> get() = emptyMap()
    fun updateLiveQuoteFromFeed(symbol: String, price: Double) {}
}
