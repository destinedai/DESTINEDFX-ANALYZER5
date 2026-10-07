package com.example.data.market

import android.util.Log
import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class AlphaVantageProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
    private val apiKeyProvider: () -> String
) {
    companion object {
        private const val TAG = "AlphaVantageProvider"
        private const val BASE_URL = "https://www.alphavantage.co/query"
    }

    suspend fun getCandles(
        symbol: String,
        timeframe: String,
        outputSize: String = "compact"
    ): List<Candle> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) return@withContext emptyList()

        val info = SymbolRegistry.get(symbol)
        val isCrypto = BinanceSymbols.toBinance(symbol) != null || com.destinedfx.core.instrument.AssetClassifier.of(symbol) == com.destinedfx.core.marketdata.AssetClass.CRYPTO
        val isForex = !isCrypto && com.destinedfx.core.instrument.AssetClassifier.of(symbol) == com.destinedfx.core.marketdata.AssetClass.FOREX
        val function = if (isForex) {
            "FX_INTRADAY"
        } else if (isCrypto) {
            "CRYPTO_INTRADAY"
        } else {
            "TIME_SERIES_INTRADAY"
        }

        val interval = when (timeframe) {
            "M1", "1" -> "1min"
            "M5", "5" -> "5min"
            "M15", "15" -> "15min"
            "M30", "30" -> "30min"
            "H1", "60" -> "60min"
            else -> "15min"
        }

        val symbolParam = if (function == "FX_INTRADAY") "from_symbol=${symbol.take(3)}&to_symbol=${symbol.takeLast(3)}" 
                         else if (function == "CRYPTO_INTRADAY") "symbol=${symbol.replace("/", "")}&market=USD"
                         else "symbol=${symbol.replace("/", "")}"

        val url = "$BASE_URL?function=$function&$symbolParam&interval=$interval&outputsize=$outputSize&apikey=$apiKey"

        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                
                val timeSeriesKey = json.keys().asSequence().find { it.contains("Time Series") } ?: return@withContext emptyList()
                val timeSeries = json.optJSONObject(timeSeriesKey) ?: return@withContext emptyList()
                val list = mutableListOf<Candle>()
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

                val keys = timeSeries.keys()
                while (keys.hasNext()) {
                    val dtStr = keys.next()
                    val v = timeSeries.getJSONObject(dtStr)
                    val time = try {
                        LocalDateTime.parse(dtStr, formatter).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        Log.e(TAG, "LocalDateTime parse error: ${e.javaClass.simpleName}")
                        System.currentTimeMillis()
                    }

                    val open = v.optString("1. open").toDoubleOrNull() ?: 0.0
                    val high = v.optString("2. high").toDoubleOrNull() ?: 0.0
                    val low = v.optString("3. low").toDoubleOrNull() ?: 0.0
                    val close = v.optString("4. close").toDoubleOrNull() ?: 0.0
                    val volume = v.optDouble("5. volume", 0.0)

                    list.add(Candle(time, open, high, low, close, volume, true))
                }
                list.sortBy { it.timestamp }
                list
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AlphaVantage candles error for $symbol: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    suspend fun getLiveQuote(symbol: String): MarketTicker? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) return@withContext null

        val info = SymbolRegistry.get(symbol)
        val isCrypto = BinanceSymbols.toBinance(symbol) != null || com.destinedfx.core.instrument.AssetClassifier.of(symbol) == com.destinedfx.core.marketdata.AssetClass.CRYPTO
        val isFX = !isCrypto && com.destinedfx.core.instrument.AssetClassifier.of(symbol) == com.destinedfx.core.marketdata.AssetClass.FOREX
        val function = if (isFX) "CURRENCY_EXCHANGE_RATE" else "GLOBAL_QUOTE"
        
        val url = if (isFX) {
            "$BASE_URL?function=$function&from_currency=${symbol.take(3)}&to_currency=${symbol.takeLast(3)}&apikey=$apiKey"
        } else {
            "$BASE_URL?function=$function&symbol=${symbol.replace("/", "")}&apikey=$apiKey"
        }

        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                
                if (isFX) {
                    val rateObj = json.optJSONObject("Realtime Currency Exchange Rate") ?: return@withContext null
                    val rate = rateObj.optString("5. Exchange Rate").toDoubleOrNull() ?: 0.0
                    if (rate > 0.0) {
                        return@withContext MarketTicker(
                            symbol = symbol,
                            price = rate,
                            changePercent = 0.0,
                            high = 0.0,
                            low = 0.0,
                            spread = 0.0,
                            bias = "NEUTRAL",
                            confidence = 0,
                            lastUpdateMillis = System.currentTimeMillis(),
                            isLive = true,
                            source = "AlphaVantage (polled last price)"
                        )
                    }
                } else {
                    val quote = json.optJSONObject("Global Quote") ?: return@withContext null
                    val price = quote.optString("05. price").toDoubleOrNull() ?: 0.0
                    if (price > 0.0) {
                        return@withContext MarketTicker(
                            symbol = symbol,
                            price = price,
                            changePercent = quote.optString("10. change percent").replace("%", "").toDoubleOrNull() ?: 0.0,
                            high = quote.optString("03. high").toDoubleOrNull() ?: 0.0,
                            low = quote.optString("04. low").toDoubleOrNull() ?: 0.0,
                            spread = 0.0,
                            bias = "NEUTRAL",
                            confidence = 0,
                            lastUpdateMillis = System.currentTimeMillis(),
                            isLive = true,
                            source = "AlphaVantage (polled last price)"
                        )
                    }
                }
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AlphaVantage quote error for $symbol: ${e.javaClass.simpleName}")
            null
        }
    }
}
