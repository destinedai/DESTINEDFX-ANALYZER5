package com.example.data.deriv

import android.util.Log
import com.destinedfx.core.marketdata.AssetClass
import com.destinedfx.core.marketdata.Candle as CoreCandle
import com.destinedfx.core.marketdata.MarketQuote
import com.destinedfx.core.marketdata.VolumeType
import com.example.domain.model.Candle
import com.example.domain.model.Tick
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Canonical active instrument returned by Deriv's public active_symbols endpoint.
 * Display labels are kept separately from broker symbols to avoid guessed market codes.
 */
data class DerivActiveSymbol(
    val symbol: String,
    val displayName: String,
    val market: String = "",
    val symbolType: String = ""
)

/**
 * Real-time WebSocket Gateway for Deriv market data.
 * Uses the public active_symbols, ticks, and ticks_history endpoints for market data.
 */
class DerivMarketDataGateway(
    private val appId: String = "1089",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "DerivMDGateway"
        private const val WS_URL_BASE = "wss://ws.binaryws.com/websockets/v3?app_id="
    }

    private val _connectionState = MutableStateFlow(DerivConnectionState.DISCONNECTED)
    val connectionState: StateFlow<DerivConnectionState> = _connectionState.asStateFlow()

    private val _latestQuotes = MutableStateFlow<Map<String, MarketQuote>>(emptyMap())
    val latestQuotes: StateFlow<Map<String, MarketQuote>> = _latestQuotes.asStateFlow()

    private var webSocket: WebSocket? = null
    private val reqIdCounter = AtomicInteger(1)
    private val activeSubscriptions = ConcurrentHashMap<String, String>() // symbol -> subscriptionId
    private val requestedSubscriptions = ConcurrentHashMap.newKeySet<String>()
    private val tickListeners = ConcurrentHashMap<String, (Tick) -> Unit>()
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val lastCandleErrors = ConcurrentHashMap<String, String>()
    private val activeSymbolsMutex = Mutex()
    @Volatile private var cachedActiveSymbols: List<DerivActiveSymbol> = emptyList()
    @Volatile private var activeSymbolsFetchedAtMs: Long = 0L
    @Volatile private var lastConnectionError: String? = null
    @Volatile private var manuallyDisconnected = false
    private val reconnectAttempts = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getLastCandleError(symbol: String): String? = lastCandleErrors[symbol] ?: lastConnectionError

    fun connect() {
        if (_connectionState.value == DerivConnectionState.CONNECTING || _connectionState.value == DerivConnectionState.CONNECTED) {
            return
        }

        manuallyDisconnected = false
        lastConnectionError = null
        _connectionState.value = DerivConnectionState.CONNECTING
        val request = Request.Builder()
            .url("$WS_URL_BASE$appId")
            .build()

        try {
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Deriv WebSocket connected.")
                    reconnectAttempts.set(0)
                    _connectionState.value = DerivConnectionState.CONNECTED
                    startHeartbeat()
                    // Re-subscribe to any active symbols
                    tickListeners.keys.forEach { sym ->
                        sendSubscribeTick(sym)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    lastConnectionError = t.message?.takeIf { it.isNotBlank() }
                        ?: "Network connection to Deriv WebSocket failed"
                    Log.e(TAG, "Deriv WebSocket failure: " + (lastConnectionError ?: "unknown"))
                    _connectionState.value = DerivConnectionState.ERROR
                    cleanup()
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Deriv WebSocket closed: $code / $reason")
                    if (code != 1000 && reason.isNotBlank()) lastConnectionError = "WebSocket closed: " + code + " / " + reason
                    _connectionState.value = DerivConnectionState.DISCONNECTED
                    cleanup()
                    if (code != 1000) scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect Deriv WebSocket: ${e.message}")
            _connectionState.value = DerivConnectionState.ERROR
        }
    }

    fun disconnect() {
        manuallyDisconnected = true
        webSocket?.close(1000, "Client disconnect")
        cleanup()
        _connectionState.value = DerivConnectionState.DISCONNECTED
    }

    private fun cleanup() {
        webSocket = null
        activeSubscriptions.clear()
        requestedSubscriptions.clear()
        pendingRequests.values.forEach {
            it.cancel()
        }
        pendingRequests.clear()
    }

    private fun scheduleReconnect() {
        if (manuallyDisconnected) return
        val attempt = reconnectAttempts.getAndIncrement().coerceAtMost(5)
        val waitMs = (1_000L shl attempt).coerceAtMost(30_000L)
        scope.launch {
            delay(waitMs)
            if (!manuallyDisconnected &&
                _connectionState.value != DerivConnectionState.CONNECTED &&
                _connectionState.value != DerivConnectionState.CONNECTING
            ) {
                Log.i(TAG, "Retrying Deriv market-data connection after " + waitMs + "ms")
                connect()
            }
        }
    }

    private suspend fun ensureConnected(timeoutMs: Long): Boolean {
        if (_connectionState.value == DerivConnectionState.CONNECTED) return true
        connect()
        return withTimeoutOrNull(timeoutMs) {
            while (_connectionState.value == DerivConnectionState.CONNECTING) delay(100L)
            _connectionState.value == DerivConnectionState.CONNECTED
        } ?: false
    }

    /**
     * Discover broker-supported symbols using Deriv's public, unauthenticated active_symbols endpoint.
     * The result is cached for five minutes; callers must use each returned broker code for quotes/candles.
     */
    suspend fun fetchActiveSymbols(forceRefresh: Boolean = false): List<DerivActiveSymbol> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            cachedActiveSymbols.takeIf {
                !forceRefresh && it.isNotEmpty() && now - activeSymbolsFetchedAtMs < 5 * 60_000L
            }?.let { return@withContext it }

            activeSymbolsMutex.withLock {
                val recheckNow = System.currentTimeMillis()
                cachedActiveSymbols.takeIf {
                    !forceRefresh && it.isNotEmpty() && recheckNow - activeSymbolsFetchedAtMs < 5 * 60_000L
                }?.let { return@withLock it }

                if (!ensureConnected(16_000L)) {
                    Log.w(TAG, "active_symbols unavailable: WebSocket state=" + _connectionState.value.name)
                    return@withLock emptyList()
                }

                val reqId = reqIdCounter.getAndIncrement()
                val deferred = CompletableDeferred<JSONObject>()
                pendingRequests[reqId] = deferred
                try {
                    val requestJson = JSONObject().apply {
                        put("active_symbols", "brief")
                        // Match Deriv's documented public market-data workflow.
                        put("product_type", "basic")
                        put("req_id", reqId)
                    }
                    if (webSocket?.send(requestJson.toString()) != true) {
                        lastConnectionError = "WebSocket rejected the active_symbols request before it was sent"
                        return@withLock emptyList()
                    }

                    val response = withTimeoutOrNull(12_000L) { deferred.await() }
                    if (response == null) {
                        lastConnectionError = "Timed out waiting for Deriv active_symbols response"
                        return@withLock emptyList()
                    }
                    response.optJSONObject("error")?.let { apiError ->
                        lastConnectionError = apiError.optString("message").ifBlank { apiError.toString() }
                        Log.w(TAG, "active_symbols rejected: " + (lastConnectionError ?: "unknown"))
                        return@withLock emptyList()
                    }

                    val array = response.optJSONArray("active_symbols") ?: return@withLock emptyList()
                    val parsed = ArrayList<DerivActiveSymbol>(array.length())
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val brokerSymbol = item.optString("symbol")
                            .ifBlank { item.optString("underlying_symbol") }.trim()
                        if (brokerSymbol.isBlank()) continue
                        val displayName = item.optString("display_name")
                            .ifBlank { item.optString("underlying_symbol_name") }
                            .ifBlank { brokerSymbol }.trim()
                        parsed += DerivActiveSymbol(
                            symbol = brokerSymbol,
                            displayName = displayName,
                            market = item.optString("market"),
                            symbolType = item.optString("underlying_symbol_type")
                                .ifBlank { item.optString("symbol_type") }
                        )
                    }

                    if (parsed.isNotEmpty()) {
                        cachedActiveSymbols = parsed
                        activeSymbolsFetchedAtMs = System.currentTimeMillis()
                        Log.i(TAG, "Discovered " + parsed.size + " active Deriv symbols")
                    }
                    parsed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lastConnectionError = "active_symbols failed: " +
                        (error.message ?: error.javaClass.simpleName)
                    Log.w(TAG, lastConnectionError ?: "active_symbols failed")
                    emptyList()
                } finally {
                    pendingRequests.remove(reqId)
                }
            }
        }

    private fun startHeartbeat() {
        scope.launch {
            while (isActive && _connectionState.value == DerivConnectionState.CONNECTED) {
                delay(30_000)
                try {
                    val pingJson = JSONObject().apply {
                        put("ping", 1)
                        put("req_id", reqIdCounter.getAndIncrement())
                    }
                    webSocket?.send(pingJson.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat error: ${e.message}")
                }
            }
        }
    }

    fun subscribeTicks(symbol: String, onTick: (Tick) -> Unit) {
        tickListeners[symbol] = onTick
        if (_connectionState.value == DerivConnectionState.CONNECTED) {
            sendSubscribeTick(symbol)
        } else {
            connect()
        }
    }

    fun unsubscribeTicks(symbol: String) {
        tickListeners.remove(symbol)
        requestedSubscriptions.remove(symbol)
        val subId = activeSubscriptions.remove(symbol)
        if (subId != null && webSocket != null) {
            val forgetJson = JSONObject().apply {
                put("forget", subId)
                put("req_id", reqIdCounter.getAndIncrement())
            }
            webSocket?.send(forgetJson.toString())
        }
    }

    private fun sendSubscribeTick(symbol: String) {
        // A selected-symbol refresh must not create duplicate WebSocket tick subscriptions.
        if (!requestedSubscriptions.add(symbol)) return
        val reqId = reqIdCounter.getAndIncrement()
        val json = JSONObject().apply {
            put("ticks", symbol)
            put("subscribe", 1)
            put("req_id", reqId)
        }
        if (webSocket?.send(json.toString()) != true) requestedSubscriptions.remove(symbol)
    }

    suspend fun fetchCandles(symbol: String, granularitySeconds: Int, count: Int = 300): List<Candle> = withContext(Dispatchers.IO) {
        lastCandleErrors.remove(symbol)
        val reqId = reqIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[reqId] = deferred

        if (_connectionState.value != DerivConnectionState.CONNECTED) {
            connect()
            // Wait for the actual websocket handshake before sending a history request.
            val connected = withTimeoutOrNull(16_000L) {
                while (_connectionState.value == DerivConnectionState.CONNECTING) {
                    delay(100)
                }
                _connectionState.value == DerivConnectionState.CONNECTED
            } ?: false
            if (!connected) {
                lastCandleErrors[symbol] = lastConnectionError
                    ?: "Deriv WebSocket connection failed (state=" + _connectionState.value.name + ")"
                pendingRequests.remove(reqId)
                Log.w(TAG, "Candle request skipped: Deriv WebSocket did not connect for " + symbol)
                return@withContext emptyList<Candle>()
            }
        }

        val json = JSONObject().apply {
            put("ticks_history", symbol)
            put("end", "latest")
            put("count", count)
            put("style", "candles")
            put("granularity", granularitySeconds)
            put("req_id", reqId)
        }

        val sent = webSocket?.send(json.toString()) ?: false
        if (!sent) {
            lastCandleErrors[symbol] = "WebSocket rejected the candle request before it was sent"
            pendingRequests.remove(reqId)
            return@withContext emptyList<Candle>()
        }

        try {
            val response = withTimeout(10_000) { deferred.await() }
            val apiError = response.optJSONObject("error")
            if (apiError != null) {
                lastCandleErrors[symbol] = apiError.optString("message").ifBlank { apiError.toString() }
                Log.w(TAG, "Deriv candle request rejected for " + symbol + ": " + apiError.toString())
                return@withContext emptyList<Candle>()
            }
            val candlesArr = response.optJSONArray("candles")
            if (candlesArr == null) {
                lastCandleErrors[symbol] = "History response did not include OHLC candles (msg_type=" + response.optString("msg_type") + ")"
                return@withContext emptyList<Candle>()
            }
            val result = mutableListOf<Candle>()
            for (i in 0 until candlesArr.length()) {
                val c = candlesArr.getJSONObject(i)
                val epochSec = c.optLong("epoch", 0L)
                val open = c.optDouble("open", 0.0)
                val high = c.optDouble("high", 0.0)
                val low = c.optDouble("low", 0.0)
                val close = c.optDouble("close", 0.0)
                val volume = c.optDouble("volume", 0.0)
                if (epochSec > 0 && open > 0 && high > 0 && low > 0 && close > 0) {
                    result.add(
                        Candle(
                            timestamp = epochSec * 1000L,
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            volume = volume,
                            complete = true
                        )
                    )
                }
            }
            result.sortBy { it.timestamp }
            if (result.isEmpty()) {
                lastCandleErrors[symbol] = "Deriv returned no valid OHLC rows for granularity " + granularitySeconds
            } else {
                lastCandleErrors.remove(symbol)
            }
            result
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                // A child response can be cancelled because the socket failed while the app's
                // scan itself is still active. Surface that as a feed failure, not as cancellation
                // of the whole analysis coroutine.
                lastCandleErrors[symbol] = if (e is TimeoutCancellationException) {
                    "Timed out waiting for Deriv historical candles"
                } else {
                    lastConnectionError?.let { "Deriv WebSocket network error: " + it }
                        ?: "Deriv WebSocket interrupted the historical candle request"
                }
                return@withContext emptyList<Candle>()
            }
            // Respect real lifecycle/parent cancellation.
            throw e
        } catch (e: Exception) {
            lastCandleErrors[symbol] = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Failed to fetch Deriv candles for " + symbol + ": " + (e.message ?: e.javaClass.simpleName))
            emptyList()
        } finally {
            pendingRequests.remove(reqId)
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val msgType = json.optString("msg_type")
            val reqId = json.optInt("req_id", -1)

            if (reqId != -1 && pendingRequests.containsKey(reqId)) {
                pendingRequests[reqId]?.complete(json)
            }

            when (msgType) {
                "tick" -> {
                    val tickObj = json.optJSONObject("tick") ?: return
                    val symbol = tickObj.optString("symbol")
                    val quote = tickObj.optDouble("quote", 0.0)
                    // Do not synthesize bid/ask from the last price. A quote without both sides
                    // is useful for display, but cannot pass the bot's real-spread execution gate.
                    val bid = if (tickObj.has("bid")) {
                        tickObj.optDouble("bid", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                    } else null
                    val ask = if (tickObj.has("ask")) {
                        tickObj.optDouble("ask", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                    } else null
                    val epoch = tickObj.optLong("epoch", 0L)
                    if (symbol.isBlank() || !quote.isFinite() || quote <= 0.0 || epoch <= 0L) return
                    val validTwoSidedQuote = bid != null && ask != null && ask >= bid
                    val subId = json.optJSONObject("subscription")?.optString("id")
                    if (subId != null && symbol.isNotBlank()) {
                        activeSubscriptions[symbol] = subId
                    }

                    val timeMs = epoch * 1000L
                    val tick = Tick(timestamp = timeMs, price = quote, size = 1.0)
                    val cls = com.destinedfx.core.instrument.AssetClassifier.of(symbol)
                    val marketQuote = MarketQuote(
                        symbol = symbol,
                        assetClass = cls,
                        bid = if (validTwoSidedQuote) bid else null,
                        ask = if (validTwoSidedQuote) ask else null,
                        last = quote,
                        timestampMs = timeMs,
                        receivedAtMs = System.currentTimeMillis(),
                        source = "Deriv WebSocket"
                    )

                    val updatedMap = _latestQuotes.value.toMutableMap()
                    updatedMap[symbol] = marketQuote
                    _latestQuotes.value = updatedMap

                    tickListeners[symbol]?.invoke(tick)
                }
                "error" -> {
                    val errorObj = json.optJSONObject("error")
                    val code = errorObj?.optString("code") ?: "UNKNOWN"
                    val msg = errorObj?.optString("message") ?: "Unknown Deriv error"
                    Log.e(TAG, "Deriv API Error: $code - $msg")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Deriv message parse error: ${e.message}", e)
        }
    }
}
