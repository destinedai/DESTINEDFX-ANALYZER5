package com.example.data.trade

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Independent, read-only Analyzer feed client.
 *
 * It intentionally does not construct MT5RemoteBridgeClient, read broker profiles, or send an
 * app pairing token. The phone reads short-lived market data from the configured host origin;
 * a separate terminal-side Analyzer EA publishes quotes and closed candles through a host-local route.
 */
class MT5AnalyzerMarketFeedClient(
    serviceOrigin: String,
    private val cloudflareAccessClientId: String = "",
    private val cloudflareAccessClientSecret: String = ""
) {
    data class Snapshot(
        val connected: Boolean,
        val heartbeatFresh: Boolean,
        val heartbeatAgeSeconds: Long?,
        val symbols: List<String>,
        val brokerQuotes: List<VerifiedBrokerQuote>,
        val brokerCandles: List<VerifiedBrokerCandle>,
        val activeChartSymbol: String,
        val activeChartTimeframe: String,
        val errorMessage: String
    )

    private val origin: String = validateOrigin(serviceOrigin)
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun state(): Snapshot = withContext(Dispatchers.IO) {
        val body = execute("GET", "/v1/analyzer/market-state", null)
        val nowMs = System.currentTimeMillis()
        val quotes = parseVerifiedBrokerQuotes(body.optJSONArray("broker_quotes"), nowMs).orEmpty()
        val candles = parseVerifiedBrokerCandles(body.optJSONArray("broker_candles"), nowMs).orEmpty()
        val symbols = parseSymbols(body.optJSONArray("symbols"))
        val heartbeatFresh = body.optBoolean("heartbeat_fresh", false)
        val connected = body.optBoolean("connected", false) && heartbeatFresh
        Snapshot(
            connected = connected,
            heartbeatFresh = heartbeatFresh,
            heartbeatAgeSeconds = if (body.has("heartbeat_age_seconds") && !body.isNull("heartbeat_age_seconds"))
                body.optLong("heartbeat_age_seconds").coerceAtLeast(0L) else null,
            symbols = symbols,
            brokerQuotes = if (connected) quotes else emptyList(),
            brokerCandles = if (connected) candles else emptyList(),
            activeChartSymbol = body.optString("selected_symbol").trim(),
            activeChartTimeframe = body.optString("selected_timeframe", "M15")
                .uppercase(Locale.ROOT).takeIf { it in SUPPORTED_TIMEFRAMES } ?: "M15",
            errorMessage = body.optString("error_message").takeIf { it.isNotBlank() }
                ?: if (connected) "" else "MT5 not connected"
        )
    }

    suspend fun select(symbol: String, timeframe: String) = withContext(Dispatchers.IO) {
        val cleanSymbol = symbol.trim()
        val cleanTimeframe = timeframe.trim().uppercase(Locale.ROOT)
        require(isValidMarketSymbol(cleanSymbol)) { "Select a valid MT5 Market Watch symbol." }
        require(cleanTimeframe in SUPPORTED_TIMEFRAMES) { "Select a supported MT5 timeframe." }
        execute(
            "POST",
            "/v1/analyzer/market-selection",
            JSONObject().put("symbol", cleanSymbol).put("timeframe", cleanTimeframe)
        )
    }

    private fun execute(method: String, path: String, payload: JSONObject?): JSONObject {
        val builder = Request.Builder()
            .url(origin + path)
            .header("Accept", "application/json")
        if (cloudflareAccessClientId.isNotBlank() && cloudflareAccessClientSecret.isNotBlank()) {
            builder.header("CF-Access-Client-Id", cloudflareAccessClientId)
                .header("CF-Access-Client-Secret", cloudflareAccessClientSecret)
        }
        if (payload != null) {
            builder.header("Content-Type", "application/json")
                .method(method, payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.method(method, null)
        }
        return http.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optString("detail") }.getOrNull()
                throw IOException(detail?.takeIf { it.isNotBlank() }
                    ?: "Analyzer MT5 feed request failed (HTTP ${response.code}).")
            }
            runCatching { JSONObject(raw) }
                .getOrElse { throw IOException("Analyzer MT5 feed returned invalid data.") }
        }
    }

    private fun parseSymbols(rows: JSONArray?): List<String> {
        if (rows == null) return emptyList()
        val out = LinkedHashMap<String, String>()
        for (index in 0 until minOf(rows.length(), MAX_SYMBOLS)) {
            val value = rows.optString(index).trim()
            if (isValidMarketSymbol(value)) {
                out.putIfAbsent(value.uppercase(Locale.ROOT), value)
            }
        }
        return out.values.sortedBy { it.uppercase(Locale.ROOT) }
    }

    private fun isValidMarketSymbol(value: String): Boolean =
        value.isNotBlank() && value == value.trim() &&
            value.matches(Regex("[A-Za-z0-9._# -]{1,32}"))

    private fun validateOrigin(raw: String): String {
        val uri = runCatching { URI(raw.trim().trimEnd('/')).normalize() }.getOrNull()
            ?: throw IllegalArgumentException("Analyzer MT5 host URL is not configured.")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        val loopback = host in setOf("localhost", "127.0.0.1", "::1")
        require(host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.path.orEmpty().isBlank() && (scheme == "https" || (scheme == "http" && loopback))) {
            "Analyzer MT5 host URL must be a verified HTTPS origin."
        }
        return uri.toASCIIString().trimEnd('/')
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SUPPORTED_TIMEFRAMES = setOf("M1", "M5", "M15", "M30", "H1", "H4", "D1", "W1")
        private const val MAX_SYMBOLS = 128
    }
}
