package com.example.data.deriv

import android.util.Log
import com.destinedfx.core.marketdata.MarketQuote
import com.example.domain.model.*
import com.example.domain.repository.AccountMetrics
import com.example.security.SecretsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class DerivContractInfo(
    val contractId: Long,
    val transactionId: Long,
    val symbol: String,
    val contractType: String,
    val buyPrice: Double,
    val bidPrice: Double,
    val profit: Double,
    val isSold: Boolean,
    val isValidToSell: Boolean,
    val status: String,
    val purchaseTime: Long,
    val expiryTime: Long?
)

/**
 * Real Broker Gateway for Deriv.
 * Performs real authorization, balance queries, order execution (proposals + contracts),
 * and open position monitoring through Deriv WebSocket.
 */
class DerivBrokerGateway(
    private val secrets: SecretsManager,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "DerivBrokerGW"
        private const val WS_URL_BASE = "wss://ws.derivws.com/websockets/v3?app_id="
    }

    private val _connectionState = MutableStateFlow(DerivConnectionState.DISCONNECTED)
    val connectionState: StateFlow<DerivConnectionState> = _connectionState.asStateFlow()

    private val _accountMetrics = MutableStateFlow<AccountMetrics?>(null)
    val accountMetrics: StateFlow<AccountMetrics?> = _accountMetrics.asStateFlow()

    private val _accountInfo = MutableStateFlow<DerivAccountInfo?>(null)
    val accountInfo: StateFlow<DerivAccountInfo?> = _accountInfo.asStateFlow()

    private val _openPositions = MutableStateFlow<List<Position>>(emptyList())
    val openPositions: StateFlow<List<Position>> = _openPositions.asStateFlow()

    private val _activeContracts = MutableStateFlow<Map<Long, DerivContractInfo>>(emptyMap())
    val activeContracts: StateFlow<Map<Long, DerivContractInfo>> = _activeContracts.asStateFlow()

    private val cachedQuotes = ConcurrentHashMap<String, MarketQuote>()

    private var webSocket: WebSocket? = null
    private val reqIdCounter = AtomicInteger(100)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun connectAndAuthorize(apiToken: String? = null): Result<DerivAccountInfo> = withContext(Dispatchers.IO) {
        val token = (apiToken ?: secrets.getDerivApiToken()).trim()
        if (token.isBlank()) {
            _connectionState.value = DerivConnectionState.AUTH_FAILED
            return@withContext Result.failure(Exception("Deriv API token is not configured. Please set it in Settings."))
        }

        val appId = secrets.getDerivAppId().ifBlank { "1089" }
        _connectionState.value = DerivConnectionState.CONNECTING

        val wsDeferred = CompletableDeferred<Boolean>()
        val request = Request.Builder().url("$WS_URL_BASE$appId").build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Deriv Broker WebSocket connected.")
                wsDeferred.complete(true)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Deriv Broker WS failure: ${t.message}")
                _connectionState.value = DerivConnectionState.ERROR
                wsDeferred.complete(false)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Deriv Broker WS closed: $code")
                _connectionState.value = DerivConnectionState.DISCONNECTED
            }
        })

        val connected = withTimeoutOrNull(8000) { wsDeferred.await() } ?: false
        if (!connected) {
            _connectionState.value = DerivConnectionState.ERROR
            return@withContext Result.failure(Exception("Failed to establish WebSocket connection to Deriv."))
        }

        // Send authorize request
        _connectionState.value = DerivConnectionState.AUTHENTICATING
        val authReqId = reqIdCounter.getAndIncrement()
        val authDeferred = CompletableDeferred<JSONObject>()
        pendingRequests[authReqId] = authDeferred

        val authPayload = JSONObject().apply {
            put("authorize", token)
            put("req_id", authReqId)
        }
        webSocket?.send(authPayload.toString())

        try {
            val authResp = withTimeout(10_000) { authDeferred.await() }
            val error = authResp.optJSONObject("error")
            if (error != null) {
                val code = error.optString("code")
                val msg = error.optString("message")
                _connectionState.value = DerivConnectionState.AUTH_FAILED
                return@withContext Result.failure(Exception("Deriv Auth Failed ($code): $msg"))
            }

            val authData = authResp.optJSONObject("authorize") ?: return@withContext Result.failure(Exception("Empty authorize payload from Deriv"))
            val balance = authData.optDouble("balance", 0.0)
            val currency = authData.optString("currency", "USD")
            val loginId = authData.optString("loginid", "")
            val email = authData.optString("email", "")

            val info = DerivAccountInfo(loginId, currency, balance, email)
            _accountInfo.value = info
            _accountMetrics.value = AccountMetrics(
                balance = balance,
                equity = balance,
                margin = 0.0,
                freeMargin = balance,
                marginLevel = 0.0,
                currency = currency
            )
            _connectionState.value = DerivConnectionState.CONNECTED

            // Subscribe to balance updates
            sendBalanceSubscription()
            // Fetch open positions
            fetchPortfolio()

            Result.success(info)
        } catch (e: Exception) {
            _connectionState.value = DerivConnectionState.ERROR
            Result.failure(Exception("Authorization timeout or error: ${e.message}"))
        } finally {
            pendingRequests.remove(authReqId)
        }
    }

    private fun sendBalanceSubscription() {
        val reqId = reqIdCounter.getAndIncrement()
        val json = JSONObject().apply {
            put("balance", 1)
            put("subscribe", 1)
            put("req_id", reqId)
        }
        webSocket?.send(json.toString())
    }

    suspend fun fetchPortfolio(): List<Position> = withContext(Dispatchers.IO) {
        if (_connectionState.value != DerivConnectionState.CONNECTED) return@withContext emptyList()
        val reqId = reqIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[reqId] = deferred

        val json = JSONObject().apply {
            put("portfolio", 1)
            put("req_id", reqId)
        }
        webSocket?.send(json.toString())

        try {
            val resp = withTimeout(8000) { deferred.await() }
            val contracts = resp.optJSONObject("portfolio")?.optJSONArray("contracts") ?: return@withContext emptyList()
            val list = mutableListOf<Position>()
            for (i in 0 until contracts.length()) {
                val c = contracts.getJSONObject(i)
                val contractId = c.optLong("contract_id", 0L)
                val symbol = c.optString("symbol").ifBlank {
                    c.optString("underlying_symbol").ifBlank {
                        c.optString("underlying")
                    }
                }
                val buyPrice = c.optDouble("buy_price", 0.0)
                val contractType = c.optString("contract_type")
                val isLong = contractType.contains("CALL") || contractType.contains("UP")
                val openTime = (c.optLong("purchase_time", 0L).takeIf { it > 0 }
                    ?: c.optLong("date_start", System.currentTimeMillis() / 1000)) * 1000L

                if (contractId > 0) {
                    subscribeOpenContract(contractId)
                }

                list.add(
                    Position(
                        id = contractId.toString(),
                        signalId = null,
                        symbol = symbol,
                        direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT,
                        entryPrice = buyPrice,
                        stopLoss = 0.0,
                        takeProfit = 0.0,
                        lotSize = 1.0,
                        status = PositionStatus.OPEN,
                        currentProfit = c.optDouble("profit", 0.0),
                        openedAt = openTime
                    )
                )
            }
            _openPositions.value = list
            list
        } catch (e: Exception) {
            emptyList()
        } finally {
            pendingRequests.remove(reqId)
        }
    }

    fun subscribeOpenContract(contractId: Long) {
        if (contractId <= 0L) return
        val reqId = reqIdCounter.getAndIncrement()
        val json = JSONObject().apply {
            put("proposal_open_contract", 1)
            put("contract_id", contractId)
            put("subscribe", 1)
            put("req_id", reqId)
        }
        webSocket?.send(json.toString())
    }

    suspend fun getQuote(symbol: String): Result<MarketQuote> = withContext(Dispatchers.IO) {
        if (_connectionState.value != DerivConnectionState.CONNECTED) {
            return@withContext Result.failure(Exception("BROKER_DISCONNECTED: Deriv broker is not connected."))
        }

        cachedQuotes[symbol]?.let { cached ->
            if (System.currentTimeMillis() - cached.receivedAtMs <= 10_000L) {
                return@withContext Result.success(cached)
            }
        }

        val reqId = reqIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[reqId] = deferred

        val json = JSONObject().apply {
            put("ticks", symbol)
            put("req_id", reqId)
        }
        val sent = webSocket?.send(json.toString()) ?: false
        if (!sent) {
            pendingRequests.remove(reqId)
            return@withContext Result.failure(Exception("BROKER_DISCONNECTED: Failed to send tick request to Deriv"))
        }

        try {
            val resp = withTimeout(5000) { deferred.await() }
            val err = resp.optJSONObject("error")
            if (err != null) {
                return@withContext Result.failure(Exception("DATA_UNAVAILABLE: Deriv quote rejected for $symbol: ${err.optString("message")}"))
            }
            val tickObj = resp.optJSONObject("tick")
                ?: return@withContext Result.failure(Exception("DATA_UNAVAILABLE: Missing tick object in Deriv quote for $symbol"))
            val quoteVal = tickObj.optDouble("quote", 0.0)
            val bidVal = tickObj.optDouble("bid", quoteVal)
            val askVal = tickObj.optDouble("ask", quoteVal)
            val epoch = tickObj.optLong("epoch", System.currentTimeMillis() / 1000)
            val timeMs = epoch * 1000L

            val now = System.currentTimeMillis()
            if (now - timeMs > 15_000L) {
                return@withContext Result.failure(Exception("DATA_STALE: Deriv broker quote for $symbol is older than 15s (age: ${(now - timeMs) / 1000}s)"))
            }

            val cls = com.destinedfx.core.instrument.AssetClassifier.of(symbol)
            val marketQuote = MarketQuote(
                symbol = symbol,
                assetClass = cls,
                bid = bidVal,
                ask = askVal,
                last = quoteVal,
                timestampMs = timeMs,
                receivedAtMs = now,
                source = "Deriv WebSocket"
            )
            cachedQuotes[symbol] = marketQuote
            Result.success(marketQuote)
        } catch (e: Exception) {
            Result.failure(Exception("DATA_UNAVAILABLE: Timeout or error obtaining Deriv quote for $symbol: ${e.message}"))
        } finally {
            pendingRequests.remove(reqId)
        }
    }

    suspend fun executeOrder(symbol: String, stake: Double, isBuy: Boolean): Result<DerivBuyResult> = withContext(Dispatchers.IO) {
        if (_connectionState.value != DerivConnectionState.CONNECTED) {
            return@withContext Result.failure(Exception("Deriv broker is not connected. Please connect in Settings."))
        }

        // 1. Request price proposal
        val propReqId = reqIdCounter.getAndIncrement()
        val propDeferred = CompletableDeferred<JSONObject>()
        pendingRequests[propReqId] = propDeferred

        val propType = if (isBuy) "MULTUP" else "MULTDOWN"
        val propPayload = JSONObject().apply {
            put("proposal", 1)
            put("amount", stake)
            put("basis", "stake")
            put("contract_type", propType)
            put("currency", _accountInfo.value?.currency ?: "USD")
            put("symbol", symbol)
            put("req_id", propReqId)
        }
        webSocket?.send(propPayload.toString())

        val proposalId: String
        val askPrice: Double
        try {
            val propResp = withTimeout(10_000) { propDeferred.await() }
            val err = propResp.optJSONObject("error")
            if (err != null) {
                return@withContext Result.failure(Exception("Deriv Proposal Rejected: ${err.optString("message")}"))
            }
            val p = propResp.optJSONObject("proposal") ?: return@withContext Result.failure(Exception("No proposal returned"))
            proposalId = p.optString("id")
            askPrice = p.optDouble("ask_price", stake)
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Proposal timeout: ${e.message}"))
        } finally {
            pendingRequests.remove(propReqId)
        }

        // 2. Buy contract using proposal id
        val buyReqId = reqIdCounter.getAndIncrement()
        val buyDeferred = CompletableDeferred<JSONObject>()
        pendingRequests[buyReqId] = buyDeferred

        val buyPayload = JSONObject().apply {
            put("buy", proposalId)
            put("price", askPrice)
            put("req_id", buyReqId)
        }
        webSocket?.send(buyPayload.toString())

        try {
            val buyResp = withTimeout(10_000) { buyDeferred.await() }
            val buyErr = buyResp.optJSONObject("error")
            if (buyErr != null) {
                return@withContext Result.failure(Exception("Deriv Order Execution Rejected: ${buyErr.optString("message")}"))
            }
            val buyData = buyResp.optJSONObject("buy") ?: return@withContext Result.failure(Exception("Invalid buy response"))
            val contractId = buyData.optLong("contract_id", 0L)
            val txId = buyData.optLong("transaction_id", 0L)
            val boughtPrice = buyData.optDouble("buy_price", askPrice)

            if (contractId > 0) {
                subscribeOpenContract(contractId)
            }

            fetchPortfolio()
            Result.success(DerivBuyResult(contractId, txId, boughtPrice))
        } catch (e: Exception) {
            Result.failure(Exception("Order buy timeout: ${e.message}"))
        } finally {
            pendingRequests.remove(buyReqId)
        }
    }

    suspend fun sellContract(contractId: Long): Result<DerivSellResult> = withContext(Dispatchers.IO) {
        if (_connectionState.value != DerivConnectionState.CONNECTED) {
            return@withContext Result.failure(Exception("Deriv broker is not connected."))
        }
        val sellReqId = reqIdCounter.getAndIncrement()
        val sellDeferred = CompletableDeferred<JSONObject>()
        pendingRequests[sellReqId] = sellDeferred

        val sellPayload = JSONObject().apply {
            put("sell", contractId)
            put("price", 0) // sell at market price
            put("req_id", sellReqId)
        }
        webSocket?.send(sellPayload.toString())

        try {
            val sellResp = withTimeout(10_000) { sellDeferred.await() }
            val err = sellResp.optJSONObject("error")
            if (err != null) {
                val code = err.optString("code")
                val msg = err.optString("message")
                return@withContext Result.failure(Exception("CLOSE_FAILED: Deriv contract sell rejected ($code): $msg"))
            }
            val sellData = sellResp.optJSONObject("sell")
                ?: return@withContext Result.failure(Exception("CLOSE_FAILED: Invalid sell response from Deriv"))
            val soldFor = sellData.optDouble("sold_for", 0.0)
            val txId = sellData.optLong("transaction_id", 0L)
            val cId = sellData.optLong("contract_id", contractId)

            _activeContracts.update { it - contractId }
            _openPositions.update { list -> list.filterNot { it.id == contractId.toString() } }

            // Refresh portfolio to ensure closed state is persisted and visible
            fetchPortfolio()
            Result.success(DerivSellResult(cId, txId, soldFor))
        } catch (e: Exception) {
            Result.failure(Exception("CLOSE_FAILED: Deriv sell error: ${e.message}"))
        } finally {
            pendingRequests.remove(sellReqId)
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = DerivConnectionState.DISCONNECTED
        _accountMetrics.value = null
        _accountInfo.value = null
        _openPositions.value = emptyList()
        _activeContracts.value = emptyMap()
        cachedQuotes.clear()
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
                "balance" -> {
                    val balObj = json.optJSONObject("balance") ?: return
                    val balance = balObj.optDouble("balance", 0.0)
                    val currency = balObj.optString("currency", "USD")
                    _accountMetrics.value = _accountMetrics.value?.copy(
                        balance = balance,
                        equity = balance,
                        freeMargin = balance
                    ) ?: AccountMetrics(balance, balance, 0.0, balance, 0.0, currency)
                }
                "proposal_open_contract" -> {
                    val poc = json.optJSONObject("proposal_open_contract") ?: return
                    val contractId = poc.optLong("contract_id", 0L)
                    if (contractId <= 0L) return
                    val underlying = poc.optString("underlying").ifBlank {
                        poc.optString("underlying_symbol").ifBlank { poc.optString("symbol") }
                    }
                    val buyPrice = poc.optDouble("buy_price", 0.0)
                    val bidPrice = poc.optDouble("bid_price", buyPrice)
                    val profit = poc.optDouble("profit", 0.0)
                    val isSold = poc.optInt("is_sold", 0) == 1
                    val isValidToSell = poc.optInt("is_valid_to_sell", 0) == 1
                    val status = poc.optString("status", if (isSold) "sold" else "open")
                    val purchaseTime = poc.optLong("date_start", System.currentTimeMillis() / 1000) * 1000L
                    val expiryTime = poc.optLong("date_expiry", 0L).takeIf { it > 0 }?.times(1000L)
                    val contractType = poc.optString("contract_type")
                    val txId = poc.optJSONObject("transaction_ids")?.optLong("buy", 0L)
                        ?: poc.optLong("transaction_id", 0L)

                    val info = DerivContractInfo(
                        contractId = contractId,
                        transactionId = txId,
                        symbol = underlying,
                        contractType = contractType,
                        buyPrice = buyPrice,
                        bidPrice = bidPrice,
                        profit = profit,
                        isSold = isSold,
                        isValidToSell = isValidToSell,
                        status = status,
                        purchaseTime = purchaseTime,
                        expiryTime = expiryTime
                    )
                    _activeContracts.update { it + (contractId to info) }

                    if (isSold || status in setOf("sold", "won", "lost", "expired")) {
                        _openPositions.update { list -> list.filterNot { it.id == contractId.toString() } }
                    } else {
                        _openPositions.update { list ->
                            val isLong = contractType.contains("CALL") || contractType.contains("UP")
                            val existing = list.find { it.id == contractId.toString() }
                            if (existing != null) {
                                list.map {
                                    if (it.id == contractId.toString()) it.copy(currentProfit = profit, status = PositionStatus.OPEN)
                                    else it
                                }
                            } else {
                                list + Position(
                                    id = contractId.toString(),
                                    signalId = null,
                                    symbol = underlying,
                                    direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT,
                                    entryPrice = buyPrice,
                                    stopLoss = 0.0,
                                    takeProfit = 0.0,
                                    lotSize = 1.0,
                                    status = PositionStatus.OPEN,
                                    currentProfit = profit,
                                    openedAt = purchaseTime
                                )
                            }
                        }
                    }
                }
                "tick" -> {
                    val tickObj = json.optJSONObject("tick")
                    if (tickObj != null) {
                        val sym = tickObj.optString("symbol")
                        val quoteVal = tickObj.optDouble("quote", 0.0)
                        val bidVal = tickObj.optDouble("bid", quoteVal)
                        val askVal = tickObj.optDouble("ask", quoteVal)
                        val epoch = tickObj.optLong("epoch", System.currentTimeMillis() / 1000)
                        val timeMs = epoch * 1000L
                        val cls = com.destinedfx.core.instrument.AssetClassifier.of(sym)
                        val mq = MarketQuote(
                            symbol = sym,
                            assetClass = cls,
                            bid = bidVal,
                            ask = askVal,
                            last = quoteVal,
                            timestampMs = timeMs,
                            receivedAtMs = System.currentTimeMillis(),
                            source = "Deriv WebSocket"
                        )
                        cachedQuotes[sym] = mq
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Deriv Broker message parsing error: ${e.message}")
        }
    }
}
