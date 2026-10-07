package com.destinedfx.core.marketdata

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * True two-sided FOREX/METALS/INDICES(CFD) prices via OANDA v20 pricing stream. This is the fix for
 * "app prices differ from the live market": free REST APIs return a single delayed mid, OANDA streams real bid/ask.
 */
object OandaInstruments {
    fun toOanda(symbol: String): String? = when (symbol.uppercase().trim()) {
        "XAU/USD" -> "XAU_USD"; "XAG/USD" -> "XAG_USD"
        "SPX500" -> "SPX500_USD"; "NAS100" -> "NAS100_USD"; "DJ30" -> "US30_USD"
        else -> symbol.uppercase().takeIf { it.matches(Regex("[A-Z]{3}/[A-Z]{3}")) }?.replace("/", "_")
    }
    fun fromOanda(i: String): String = when (i) { "SPX500_USD" -> "SPX500"; "NAS100_USD" -> "NAS100"; "US30_USD" -> "DJ30"; else -> i.replace("_", "/") }
    fun assetClass(symbol: String) = when { symbol.startsWith("XAU") || symbol.startsWith("XAG") -> AssetClass.METALS
        symbol in setOf("SPX500", "NAS100", "DJ30") -> AssetClass.INDICES; else -> AssetClass.FOREX }
}

object OandaStreamParser {
    /** One JSON line from the stream. HEARTBEAT lines return null (they are not quotes but prove liveness). */
    fun parsePriceLine(line: String, rxMs: Long): MarketQuote? = try {
        val o = JSONObject(line)
        if (o.optString("type") != "PRICE") null else {
            val bid = o.getJSONArray("bids").getJSONObject(0).getString("price").toDouble()
            val ask = o.getJSONArray("asks").getJSONObject(0).getString("price").toDouble()
            val sym = OandaInstruments.fromOanda(o.getString("instrument"))
            val ts = runCatching { java.time.Instant.parse(o.getString("time")).toEpochMilli() }.getOrDefault(rxMs)
            if (o.optBoolean("tradeable", true).not()) null
            else MarketQuote.twoSided(sym, OandaInstruments.assetClass(sym), bid, ask, ts, rxMs, "OANDA v20 stream")
        }
    } catch (e: org.json.JSONException) { null } catch (e: NumberFormatException) { null }
    fun isHeartbeat(line: String) = runCatching { JSONObject(line).optString("type") == "HEARTBEAT" }.getOrDefault(false)
}

/** Blocking read loop - run it on Dispatchers.IO inside a retry loop driven by [ExponentialBackoff]. */
class OandaPricingStream(private val accountId: String, private val tokenProvider: () -> String, private val practice: Boolean,
                         private val clock: () -> Long = System::currentTimeMillis,
                         private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()) {
    fun streamUrl(instruments: List<String>): String {
        val host = if (practice) "stream-fxpractice.oanda.com" else "stream-fxtrade.oanda.com"
        return "https://$host/v3/accounts/$accountId/pricing/stream?instruments=" + instruments.joinToString("%2C")
    }
    /** Returns normally on clean close; throws IOException on failure (caller backs off and reconnects). */
    fun run(symbols: List<String>, onQuote: (MarketQuote) -> Unit, onHeartbeat: () -> Unit) {
        val inst = symbols.mapNotNull { OandaInstruments.toOanda(it) }
        require(inst.isNotEmpty()) { "No OANDA-supported symbols" }
        val req = Request.Builder().url(streamUrl(inst)).header("Authorization", "Bearer ${tokenProvider()}").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("OANDA stream HTTP ${resp.code}")
            val src = resp.body?.source() ?: throw java.io.IOException("Empty stream body")
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (OandaStreamParser.isHeartbeat(line)) { onHeartbeat(); continue }
                OandaStreamParser.parsePriceLine(line, clock())?.let(onQuote)
            }
        }
    }
}
