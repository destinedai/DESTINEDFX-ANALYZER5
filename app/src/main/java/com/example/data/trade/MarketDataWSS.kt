package com.example.data.trade

import com.example.domain.model.MarketTicker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Retired compatibility surface.
 *
 * This old class subscribed to one exchange's crypto book and was not a broker-neutral
 * market-data transport. Keeping the type prevents accidental source compatibility breaks,
 * but it never opens a socket or publishes a quote.
 */
@Deprecated("Use MarketDataRepositoryImpl and its provider-verified market data.")
class MarketDataWSS(@Suppress("UNUSED_PARAMETER") private val symbols: List<String> = listOf("BTC/USD", "ETH/USD")) {
    private val _tickers = MutableStateFlow<Map<String, MarketTicker>>(emptyMap())
    val tickers: StateFlow<Map<String, MarketTicker>> = _tickers

    private val _status = MutableStateFlow("DISABLED • no approved data source configured")
    val status: StateFlow<String> = _status

    fun connectAndListen() {
        _tickers.value = emptyMap()
        _status.value = "DISABLED • use the verified market-data repository"
    }

    fun disconnect() {
        _tickers.value = emptyMap()
        _status.value = "DISABLED"
    }
}
