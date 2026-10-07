package com.example.data.market

import android.util.Log
import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BinanceProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "BinanceProvider"
        private const val BASE_URL = "https://api.binance.com/api/v3"
    }

    suspend fun getLiveQuote(symbol: String): MarketTicker? = withContext(Dispatchers.IO) {
        val binanceSymbol = BinanceSymbols.toBinance(symbol) ?: return@withContext null
        val url = "$BASE_URL/ticker/24hr?symbol=$binanceSymbol"

        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                
                val price = json.optDouble("lastPrice", 0.0)
                val change = json.optDouble("priceChangePercent", 0.0)
                val high = json.optDouble("highPrice", 0.0)
                val low = json.optDouble("lowPrice", 0.0)

                val book = fetchBook(binanceSymbol)
                if (price > 0.0) {
                    MarketTicker(
                        symbol = symbol,
                        price = price,
                        changePercent = change,
                        high = high,
                        low = low,
                        spread = if (book != null) book.second - book.first else 0.0,
                        bid = book?.first, ask = book?.second, hasRealSpread = book != null, kind = if (book != null) "POLLED_BIDASK" else "POLLED_LAST",
                        bias = if (change > 0) "BULLISH" else "BEARISH",
                        confidence = 0,
                        lastUpdateMillis = System.currentTimeMillis(),
                        isLive = true,
                        source = "Binance Spot (USDT pairs)"
                    )
                } else null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Binance quote error for $symbol: ${e.javaClass.simpleName}")
            null
        }
    }

    suspend fun getCandles(symbol: String, timeframe: String, limit: Int = 100): List<Candle> = withContext(Dispatchers.IO) {
        val binanceSymbol = BinanceSymbols.toBinance(symbol) ?: return@withContext emptyList()
        val interval = when (timeframe) {
            "M1", "1" -> "1m"
            "M5", "5" -> "5m"
            "M15", "15" -> "15m"
            "M30", "30" -> "30m"
            "H1", "60" -> "1h"
            "H4", "240" -> "4h"
            "D1", "D" -> "1d"
            "W1", "W" -> "1w"
            "MN", "M" -> "1M"
            else -> "1h"
        }
        val url = "$BASE_URL/klines?symbol=$binanceSymbol&interval=$interval&limit=$limit"

        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val arr = JSONArray(body)
                val list = mutableListOf<Candle>()
                
                val now = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val k = arr.getJSONArray(i)
                    val closeTime = k.getLong(6)
                    list.add(Candle(
                        timestamp = k.getLong(0),
                        open = k.getString(1).toDouble(),
                        high = k.getString(2).toDouble(),
                        low = k.getString(3).toDouble(),
                        close = k.getString(4).toDouble(),
                        volume = k.getString(5).toDouble(),
                        complete = closeTime < now
                    ))
                }
                list
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Binance candles error for $symbol: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun fetchBook(binanceSymbol: String): Pair<Double, Double>? = try {
        client.newCall(Request.Builder().url("$BASE_URL/ticker/bookTicker?symbol=$binanceSymbol").build()).execute().use { r ->
            if (!r.isSuccessful) null else {
                val j = JSONObject(r.body?.string() ?: return null)
                val b = j.getString("bidPrice").toDouble(); val a = j.getString("askPrice").toDouble()
                if (b > 0 && a >= b) b to a else null
            }
        }
    } catch (e: java.io.IOException) { Log.w(TAG, "bookTicker failed: ${e.javaClass.simpleName}"); null }
      catch (e: org.json.JSONException) { Log.w(TAG, "bookTicker parse failed"); null }

}
