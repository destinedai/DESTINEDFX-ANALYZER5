package com.destinedfx.core.marketdata

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** CRYPTO ONLY. No mapping exists for EUR/USD, XAU/USD...: Binance EURUSDT is a crypto-venue pair, never broker EUR/USD. */
object BinanceSymbols {
    private val map = mapOf("BTC/USD" to "BTCUSDT", "ETH/USD" to "ETHUSDT", "BNB/USD" to "BNBUSDT", "SOL/USD" to "SOLUSDT", "XRP/USD" to "XRPUSDT")
    fun toBinance(symbol: String): String? = map[symbol.uppercase().trim()]
    fun fromBinance(b: String): String? = map.entries.firstOrNull { it.value == b.uppercase() }?.key
    const val SOURCE_LABEL = "Binance Spot (USDT pairs)"
}

object BinanceStreamParser {
    /** Combined stream: wss://stream.binance.com:9443/stream?streams=btcusdt@bookTicker/ethusdt@bookTicker */
    fun combinedUrl(binanceSymbols: Collection<String>, host: String = "wss://stream.binance.com:9443"): String {
        require(binanceSymbols.isNotEmpty()) { "No symbols to subscribe" }
        return "$host/stream?streams=" + binanceSymbols.joinToString("/") { it.lowercase() + "@bookTicker" }
    }
    /** Parses {"stream":"...","data":{u,s,b,B,a,A}}. Null = malformed/invalid; callers must count these. */
    fun parseBookTicker(text: String, rxMs: Long): MarketQuote? = try {
        val root = JSONObject(text); val d = root.optJSONObject("data") ?: root
        if (!d.has("b") || !d.has("a") || !d.has("s")) null else {
            val sym = BinanceSymbols.fromBinance(d.getString("s"))
            if (sym == null) null else MarketQuote.twoSided(sym, AssetClass.CRYPTO, d.getString("b").toDouble(),
                d.getString("a").toDouble(), rxMs, rxMs, BinanceSymbols.SOURCE_LABEL, if (d.has("u")) d.getLong("u") else null)
        }
    } catch (e: org.json.JSONException) { null } catch (e: NumberFormatException) { null }
}

sealed interface StreamEvent {
    data class Quote(val quote: MarketQuote) : StreamEvent
    data class State(val health: MarketDataHealth, val detail: String) : StreamEvent
}

/** Streaming feed + backoff reconnect. After every reconnect [snapshotRecovery] re-pulls a REST snapshot. */
class BinanceBookTickerStream(
    private val symbols: List<String>, private val onEvent: (StreamEvent) -> Unit,
    private val scheduler: (delayMs: Long, block: () -> Unit) -> Unit,
    private val snapshotRecovery: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: ExponentialBackoff = ExponentialBackoff(),
    private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = true
    @Volatile private var parseErrors = 0

    fun start() {
        stopped = false
        val bs = symbols.mapNotNull { BinanceSymbols.toBinance(it) }
        if (bs.isEmpty()) { onEvent(StreamEvent.State(MarketDataHealth.SOURCE_ERROR, "No Binance-supported symbols")); return }
        connect(BinanceStreamParser.combinedUrl(bs))
    }
    fun stop() { stopped = true; socket?.close(1000, "client stop"); socket = null; onEvent(StreamEvent.State(MarketDataHealth.DISCONNECTED, "stopped")) }

    private fun connect(url: String) {
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { backoff.reset(); snapshotRecovery() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val q = BinanceStreamParser.parseBookTicker(text, clock())
                if (q != null) onEvent(StreamEvent.Quote(q))
                else if (++parseErrors % 20 == 1) onEvent(StreamEvent.State(MarketDataHealth.INVALID, "Unparseable message ($parseErrors total)"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry(url, "closed $code")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = retry(url, t.javaClass.simpleName)
        })
    }
    private fun retry(url: String, why: String) {
        if (stopped) return
        onEvent(StreamEvent.State(MarketDataHealth.DISCONNECTED, "reconnecting after $why"))
        scheduler(backoff.nextDelayMs()) { if (!stopped) connect(url) }
    }
}
