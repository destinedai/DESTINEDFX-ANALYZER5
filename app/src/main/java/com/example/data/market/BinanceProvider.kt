package com.example.data.market

import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import okhttp3.OkHttpClient

/**
 * Retired compatibility adapter.
 *
 * Binance's crypto spot prices are not a substitute for a broker's FX, metal, index or
 * synthetic-instrument quotes. The analyzer no longer calls this class; keep these
 * fail-closed methods only so an old caller cannot silently reintroduce the legacy venue.
 */
@Deprecated("Use the configured, verified market-data repository.")
class BinanceProvider(@Suppress("UNUSED_PARAMETER") client: OkHttpClient = OkHttpClient()) {
    @Suppress("UNUSED_PARAMETER")
    suspend fun getLiveQuote(symbol: String): MarketTicker? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun getCandles(symbol: String, timeframe: String, limit: Int = 100): List<Candle> = emptyList()
}
