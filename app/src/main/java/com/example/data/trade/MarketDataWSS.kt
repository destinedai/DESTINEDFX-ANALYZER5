package com.example.data.trade

import android.os.Handler
import android.os.Looper
import com.destinedfx.core.marketdata.BinanceBookTickerStream
import com.destinedfx.core.marketdata.StreamEvent
import com.example.domain.model.MarketTicker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Crypto-only live stream (Binance combined bookTicker). No seeded prices: the map stays EMPTY until a real tick arrives,
 * and the UI must show "WAITING FOR LIVE DATA". Forex symbols are intentionally not subscribed (EURUSDT != broker EUR/USD).
 */
class MarketDataWSS(private val symbols: List<String> = listOf("BTC/USD", "ETH/USD")) {
    private val _tickers = MutableStateFlow<Map<String, MarketTicker>>(emptyMap())
    val tickers: StateFlow<Map<String, MarketTicker>> = _tickers
    private val _status = MutableStateFlow("WAITING FOR LIVE DATA")
    val status: StateFlow<String> = _status
    private val handler = Handler(Looper.getMainLooper())
    private val stream = BinanceBookTickerStream(symbols, ::onEvent, scheduler = { d, b -> handler.postDelayed(b, d) })

    private fun onEvent(e: StreamEvent) = when (e) {
        is StreamEvent.Quote -> { val q = e.quote
            _tickers.value = _tickers.value + (q.symbol to MarketTicker(q.symbol, q.mid ?: 0.0, 0.0, 0.0, 0.0, q.spread ?: 0.0, "N/A", 0, q.receivedAtMs, true, q.source, q.bid, q.ask, true, "STREAM"))
            _status.value = "LIVE" }
        is StreamEvent.State -> _status.value = e.health.name
    }
    fun connectAndListen() = stream.start()
    fun disconnect() = stream.stop()
}
