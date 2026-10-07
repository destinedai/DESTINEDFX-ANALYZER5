package com.destinedfx.core.marketdata

import com.destinedfx.core.instrument.AssetClassifier
import org.json.JSONObject
import java.time.Instant

object BrokerQuoteParser {
    fun parse(json: JSONObject, symbol: String, nowMs: Long): Result<MarketQuote> {
        val bid = if (json.has("bid")) json.optDouble("bid").takeIf { it.isFinite() } else null
        val ask = if (json.has("ask")) json.optDouble("ask").takeIf { it.isFinite() } else null
        
        val timeObj = when {
            json.has("time") -> json.get("time")
            json.has("brokerTime") -> json.get("brokerTime")
            else -> null
        }
        
        val tsMs: Long? = when (timeObj) {
            is Number -> timeObj.toLong()
            is String -> {
                try {
                    Instant.parse(timeObj).toEpochMilli()
                } catch (e: Exception) {
                    timeObj.toLongOrNull()
                }
            }
            else -> null
        }
        
        if (tsMs == null) {
            return Result.failure(Exception("BROKER QUOTE TIME UNAVAILABLE"))
        }
        
        val rx = tsMs
        val cls = AssetClassifier.of(symbol)
        
        val quote = if (bid != null && ask != null) {
            MarketQuote.twoSided(symbol, cls, bid, ask, tsMs, rx, "Broker (MT5)")
        } else {
            val price = if (json.has("price")) json.optDouble("price").takeIf { it.isFinite() } else (if (json.has("last")) json.optDouble("last").takeIf { it.isFinite() } else null)
            price?.let { MarketQuote.lastOnly(symbol, cls, it, tsMs, rx, "Broker (MT5)") }
        }
        
        return if (quote != null) {
            Result.success(quote)
        } else {
            Result.failure(Exception("BROKER QUOTE INVALID"))
        }
    }
}
