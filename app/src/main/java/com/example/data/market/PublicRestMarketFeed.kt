package com.example.data.market

import android.util.Log
import com.example.domain.model.MarketTicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PublicRestMarketFeed {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun mapToTvTicker(symbol: String): String? {
        val raw = symbol.trim().uppercase()
        if (raw.startsWith("FOREXCOM:") || raw.startsWith("CAPITALCOM:") || raw.startsWith("TVC:") || raw.startsWith("BINANCE:") || raw.startsWith("NASDAQ:") || raw.startsWith("BITSTAMP:") || raw.startsWith("COINBASE:") || raw.startsWith("NYMEX:")) {
            return raw
        }
        val clean = raw.replace(" ", "").replace("-", "")
        return when (clean) {
            "EUR/USD", "EURUSD" -> "FOREXCOM:EURUSD"
            "GBP/USD", "GBPUSD" -> "FOREXCOM:GBPUSD"
            "USD/JPY", "USDJPY" -> "FOREXCOM:USDJPY"
            "USD/CHF", "USDCHF" -> "FOREXCOM:USDCHF"
            "AUD/USD", "AUDUSD" -> "FOREXCOM:AUDUSD"
            "USD/CAD", "USDCAD" -> "FOREXCOM:USDCAD"
            "NZD/USD", "NZDUSD" -> "FOREXCOM:NZDUSD"
            "EUR/GBP", "EURGBP" -> "FOREXCOM:EURGBP"
            "EUR/JPY", "EURJPY" -> "FOREXCOM:EURJPY"
            "GBP/JPY", "GBPJPY" -> "FOREXCOM:GBPJPY"
            "AUD/JPY", "AUDJPY" -> "FOREXCOM:AUDJPY"
            "EUR/AUD", "EURAUD" -> "FOREXCOM:EURAUD"
            "EUR/CAD", "EURCAD" -> "FOREXCOM:EURCAD"
            "EUR/CHF", "EURCHF" -> "FOREXCOM:EURCHF"
            "GBP/CHF", "GBPCHF" -> "FOREXCOM:GBPCHF"
            "CAD/JPY", "CADJPY" -> "FOREXCOM:CADJPY"
            "CHF/JPY", "CHFJPY" -> "FOREXCOM:CHFJPY"
            "USD/TRY", "USDTRY" -> "FOREXCOM:USDTRY"
            "USD/ZAR", "USDZAR" -> "FOREXCOM:USDZAR"
            "USD/MXN", "USDMXN" -> "FOREXCOM:USDMXN"
            "USD/SGD", "USDSGD" -> "FOREXCOM:USDSGD"
            "EUR/TRY", "EURTRY" -> "FOREXCOM:EURTRY"
            "GBP/NZD", "GBPNZD" -> "FOREXCOM:GBPNZD"
            "GBP/AUD", "GBPAUD" -> "FOREXCOM:GBPAUD"
            "GBP/CAD", "GBPCAD" -> "FOREXCOM:GBPCAD"
            "XAU/USD", "XAUUSD", "GOLD" -> "TVC:GOLD"
            "XAG/USD", "XAGUSD", "SILVER" -> "TVC:SILVER"
            "XPT/USD", "XPTUSD" -> "FOREXCOM:XPTUSD"
            "XPD/USD", "XPDUSD" -> "FOREXCOM:XPDUSD"
            "BTC/USD", "BTCUSD", "BTCUSDT" -> "BINANCE:BTCUSDT"
            "ETH/USD", "ETHUSD", "ETHUSDT" -> "BINANCE:ETHUSDT"
            "SOL/USD", "SOLUSD", "SOLUSDT" -> "BINANCE:SOLUSDT"
            "XRP/USD", "XRPUSD", "XRPUSDT" -> "BINANCE:XRPUSDT"
            "BNB/USD", "BNBUSD", "BNBUSDT" -> "BINANCE:BNBUSDT"
            "USOIL", "WTI" -> "TVC:USOIL"
            "UKOIL", "BRENT" -> "TVC:UKOIL"
            "NATGAS", "NATURAL GAS" -> "NYMEX:NG1!"
            "FTSE100", "UK100" -> "CAPITALCOM:UK100"
            "SPX500", "US500", "SPX" -> "CAPITALCOM:US500"
            "NAS100", "US100", "NDX" -> "CAPITALCOM:US100"
            "DJ30", "US30", "DJI" -> "CAPITALCOM:US30"
            "GER30", "GER40", "DAX", "DAX40" -> "CAPITALCOM:DE40"
            else -> if (clean.contains("/")) {
                val simple = clean.replace("/", "")
                "FOREXCOM:$simple"
            } else if (clean.length in 6..7) {
                "FOREXCOM:$clean"
            } else null
        }
    }

    suspend fun fetchAllLiveQuotes(symbols: List<String>): Map<String, MarketTicker> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val symbolToTv = symbols.mapNotNull { sym ->
            val tv = mapToTvTicker(sym)
            if (tv != null) sym to tv else null
        }.toMap()

        if (symbolToTv.isEmpty()) return@withContext emptyMap()

        val tvToSymbols = mutableMapOf<String, MutableList<String>>()
        symbolToTv.forEach { (sym, tv) ->
            tvToSymbols.getOrPut(tv) { mutableListOf() }.add(sym)
        }

        val tvTickers = tvToSymbols.keys.toList()
        try {
            val jsonBody = JSONObject().apply {
                put("symbols", JSONObject().apply {
                    val arr = org.json.JSONArray()
                    tvTickers.forEach { arr.put(it) }
                    put("tickers", arr)
                })
                put("columns", org.json.JSONArray().apply {
                    put("close")
                    put("change")
                    put("high")
                    put("low")
                    put("bid")
                    put("ask")
                    put("volume")
                })
            }.toString()

            val req = Request.Builder()
                .url("https://scanner.tradingview.com/global/scan")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        val root = JSONObject(bodyStr)
                        val data = root.optJSONArray("data")
                        val results = mutableMapOf<String, MarketTicker>()
                        if (data != null) {
                            for (i in 0 until data.length()) {
                                val item = data.optJSONObject(i) ?: continue
                                val s = item.optString("s") ?: continue
                                val d = item.optJSONArray("d") ?: continue
                                if (d.length() >= 6) {
                                    val close = d.optDouble(0, 0.0)
                                    val change = d.optDouble(1, 0.0)
                                    val high = d.optDouble(2, close)
                                    val low = d.optDouble(3, close)
                                    val bid = d.optDouble(4, close)
                                    val ask = d.optDouble(5, close)
                                    val spread = if (ask > bid && bid > 0) ask - bid else close * 0.0001

                                    if (close > 0.0) {
                                        val canonicalList = tvToSymbols[s] ?: emptyList()
                                        for (sym in canonicalList) {
                                            results[sym] = MarketTicker(
                                                symbol = sym,
                                                price = close,
                                                changePercent = change,
                                                high = if (high > 0) high else close,
                                                low = if (low > 0) low else close,
                                                spread = spread,
                                                bias = if (change >= 0) "BULLISH" else "BEARISH",
                                                confidence = 90,
                                                lastUpdateMillis = now,
                                                isLive = true,
                                                source = "TradingView Live API",
                                                bid = if (bid > 0) bid else close,
                                                ask = if (ask > 0) ask else close,
                                                hasRealSpread = true,
                                                kind = "STREAM"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        return@withContext results
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("PublicRestFeed", "TradingView scanner fetch failed: ${e.message}")
        }
        return@withContext emptyMap()
    }

    suspend fun getLiveQuote(symbol: String): MarketTicker? {
        val quotes = fetchAllLiveQuotes(listOf(symbol))
        return quotes[symbol]
    }
}
