package com.example.data.market

import android.util.Log
import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class OandaProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val apiKeyProvider: () -> String,
    private val accountIdProvider: () -> String,
    private val isPracticeModeProvider: () -> Boolean
) {
    companion object {
        private const val TAG = "OandaProvider"
        const val PRACTICE_URL = "https://api-fxpractice.oanda.com/v3"
        const val LIVE_URL = "https://api-fxtrade.oanda.com/v3"
    }

    private fun getBaseUrl() = if (isPracticeModeProvider()) PRACTICE_URL else LIVE_URL

    suspend fun getCandles(
        symbol: String,
        timeframe: String,
        count: Int = 150
    ): List<Candle> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            Log.w(TAG, "OANDA API key is blank")
            return@withContext emptyList()
        }

        val info = SymbolRegistry.get(symbol)
        if (info.oandaInstrument.isBlank()) return@withContext emptyList()
        val granularity = SymbolRegistry.mapOandaGranularity(timeframe)
        val url = "${getBaseUrl()}/instruments/${info.oandaInstrument}/candles?price=M&granularity=$granularity&count=$count&dailyAlignment=17&alignmentTimezone=America/New_York"

        try {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "OANDA candles failed for $symbol: HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val arr = json.optJSONArray("candles") ?: return@withContext emptyList()
                val list = mutableListOf<Candle>()

                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val timeStr = c.optString("time")
                    val complete = c.optBoolean("complete", true)
                    val mid = c.optJSONObject("mid") ?: continue

                    val timestamp = try {
                        Instant.parse(timeStr).toEpochMilli()
                    } catch (e: Exception) {
                        Log.e(TAG, "Instant parse error: ${e.javaClass.simpleName}")
                        System.currentTimeMillis()
                    }

                    val open = mid.optString("o").toDoubleOrNull() ?: 0.0
                    val high = mid.optString("h").toDoubleOrNull() ?: 0.0
                    val low = mid.optString("l").toDoubleOrNull() ?: 0.0
                    val close = mid.optString("c").toDoubleOrNull() ?: 0.0
                    val volume = c.optDouble("volume", 0.0)

                    list.add(Candle(timestamp, open, high, low, close, volume, complete))
                }
                list.sortBy { it.timestamp }
                list
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OANDA error for $symbol: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    suspend fun getLiveQuote(symbol: String): MarketTicker? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        val accountId = accountIdProvider().trim()
        if (apiKey.isBlank() || accountId.isBlank()) return@withContext null

        val info = SymbolRegistry.get(symbol)
        if (info.oandaInstrument.isBlank()) return@withContext null
        val url = "${getBaseUrl()}/accounts/$accountId/pricing?instruments=${info.oandaInstrument}"

        try {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val prices = json.optJSONArray("prices") ?: return@withContext null
                if (prices.length() == 0) return@withContext null

                val p = prices.getJSONObject(0)
                val bids = p.optJSONArray("bids")
                val asks = p.optJSONArray("asks")
                val bid = bids?.optJSONObject(0)?.optDouble("price", 0.0) ?: 0.0
                val ask = asks?.optJSONObject(0)?.optDouble("price", 0.0) ?: 0.0

                if (bid > 0.0 && ask > 0.0) {
                    val mid = (bid + ask) / 2.0
                    val spread = ask - bid
                    MarketTicker(
                        symbol = symbol,
                        price = mid,
                        changePercent = 0.0,
                        high = 0.0,
                        low = 0.0,
                        spread = spread,
                        bias = "NEUTRAL",
                        confidence = 0,
                        lastUpdateMillis = System.currentTimeMillis(),
                        isLive = true,
                        source = "OANDA v20 pricing (REST snapshot)",
                        bid = bid, ask = ask, hasRealSpread = true, kind = "POLLED_BIDASK"
                    )
                } else null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "OANDA pricing error for $symbol: ${e.javaClass.simpleName}")
            null
        }
    }
}
