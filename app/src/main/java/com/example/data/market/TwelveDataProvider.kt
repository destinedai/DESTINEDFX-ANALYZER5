package com.example.data.market

import android.util.Log
import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class TwelveDataProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val apiKeyProvider: () -> String
) {
    companion object {
        private const val TAG = "TwelveDataProvider"
        private const val BASE_URL = "https://api.twelvedata.com"
    }

    suspend fun getCandles(
        symbol: String,
        timeframe: String,
        outputSize: Int = 100
    ): List<Candle> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            Log.w(TAG, "Twelve Data API key is blank")
            return@withContext emptyList()
        }

        val info = SymbolRegistry.get(symbol)
        if (info.twelveDataSymbol.isBlank()) return@withContext emptyList()
        val interval = SymbolRegistry.mapTwelveDataInterval(timeframe)
        val encodedSymbol = URLEncoder.encode(info.twelveDataSymbol, "UTF-8")
        val url = "$BASE_URL/time_series?symbol=$encodedSymbol&interval=$interval&outputsize=$outputSize&timezone=UTC&apikey=$apiKey"

        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Twelve Data candles failed for $symbol: HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)

                if (json.optString("status") == "error") {
                    Log.w(TAG, "Twelve Data error: ${json.optString("message")}")
                    return@withContext emptyList()
                }

                val values = json.optJSONArray("values") ?: return@withContext emptyList()
                val list = mutableListOf<Candle>()
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

                for (i in 0 until values.length()) {
                    val v = values.getJSONObject(i)
                    val dtStr = v.optString("datetime")
                    val time = try {
                        LocalDateTime.parse(dtStr, formatter).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        Log.e(TAG, "DateTime parse error: ${e.javaClass.simpleName}")
                        try {
                            LocalDate.parse(dtStr).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
                        } catch (e2: Exception) {
                            Log.e(TAG, "Date parse fallback error: ${e2.javaClass.simpleName}")
                            System.currentTimeMillis()
                        }
                    }

                    val open = v.optString("open").toDoubleOrNull() ?: 0.0
                    val high = v.optString("high").toDoubleOrNull() ?: 0.0
                    val low = v.optString("low").toDoubleOrNull() ?: 0.0
                    val close = v.optString("close").toDoubleOrNull() ?: 0.0
                    val volume = v.optDouble("volume", 0.0)

                    list.add(Candle(time, open, high, low, close, volume, true))
                }
                // Twelve Data returns newest-first; sort strictly oldest -> newest
                list.sortBy { it.timestamp }
                list
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Twelve Data error for $symbol: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    suspend fun getLiveQuote(symbol: String): MarketTicker? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) return@withContext null

        val info = SymbolRegistry.get(symbol)
        if (info.twelveDataSymbol.isBlank()) return@withContext null
        val encodedSymbol = URLEncoder.encode(info.twelveDataSymbol, "UTF-8")
        val url = "$BASE_URL/price?symbol=$encodedSymbol&apikey=$apiKey"

        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val price = json.optDouble("price", 0.0)
                if (price > 0.0) {
                    MarketTicker(
                        symbol = symbol,
                        price = price,
                        changePercent = 0.0,
                        high = 0.0,
                        low = 0.0,
                        spread = 0.0,
                        bias = "NEUTRAL",
                        confidence = 0,
                        lastUpdateMillis = System.currentTimeMillis(),
                        isLive = true,
                        source = "TwelveData (polled last price)"
                    )
                } else null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Twelve Data price error for $symbol: ${e.javaClass.simpleName}")
            null
        }
    }
}
