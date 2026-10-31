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
import okhttp3.RequestBody.Companion.toRequestBody
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
) {
    val isExecutionValid: Boolean
        get() = contractId > 0L &&
            DerivMultiplierRules.isSupportedUnderlying(symbol) &&
            contractType.uppercase() in setOf("MULTUP", "MULTDOWN") &&
            buyPrice.isFinite() && buyPrice > 0.0 && purchaseTime > 0L
}

/** Selects only an active Options account matching the user's explicit DEMO/REAL choice. */
internal fun selectDerivOptionsAccount(accounts: org.json.JSONArray, requestedMode: String): JSONObject? {
    val mode = requestedMode.trim().lowercase().takeIf { it == "real" || it == "demo" } ?: "demo"
    for (index in 0 until accounts.length()) {
        val account = accounts.optJSONObject(index) ?: continue
        if (account.optString("status").equals("active", ignoreCase = true) &&
            account.optString("account_type").equals(mode, ignoreCase = true)) return account
    }
    return null
}

/** Compatibility helper for callers that explicitly request a DEMO account. */
internal fun selectDerivDemoOptionsAccount(accounts: org.json.JSONArray): JSONObject? =
    selectDerivOptionsAccount(accounts, "demo")

/**
 * Real Broker Gateway for Deriv.
 * Performs Deriv DEMO/REAL authorization, balance queries, and open-contract monitoring.
 * REAL connection is separate from the process-memory live-trading arming decision.
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
        private const val REST_BASE = "https://api.derivws.com"
        private const val AUTHENTICATED_WS_PREFIX = "wss://api.derivws.com/trading/v1/options/ws/"
    }

    private val _connectionState = MutableStateFlow(DerivConnectionState.DISCONNECTED)
    val connectionState: StateFlow<DerivConnectionState> = _connectionState.asStateFlow()

    private val _accountMetrics = MutableStateFlow<AccountMetrics?>(null)
    val accountMetrics: StateFlow<AccountMetrics?> = _accountMetrics.asStateFlow()
    private val _accountMetricsUpdatedAtMs = MutableStateFlow(0L)
    val accountMetricsUpdatedAtMs: StateFlow<Long> = _accountMetricsUpdatedAtMs.asStateFlow()

    private val _accountInfo = MutableStateFlow<DerivAccountInfo?>(null)
    val accountInfo: StateFlow<DerivAccountInfo?> = _accountInfo.asStateFlow()

    private val _openPositions = MutableStateFlow<List<Position>>(emptyList())
    val openPositions: StateFlow<List<Position>> = _openPositions.asStateFlow()

    private val _activeContracts = MutableStateFlow<Map<Long, DerivContractInfo>>(emptyMap())
    val activeContracts: StateFlow<Map<Long, DerivContractInfo>> = _activeContracts.asStateFlow()

    private val _portfolioSnapshot = MutableStateFlow<DerivPortfolioSnapshot?>(null)
    val portfolioSnapshot: StateFlow<DerivPortfolioSnapshot?> = _portfolioSnapshot.asStateFlow()

    private val cachedQuotes = ConcurrentHashMap<String, MarketQuote>()
    @Volatile private var lastConnectionError: String? = null

    private var webSocket: WebSocket? = null
    private val reqIdCounter = AtomicInteger(100)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Connects directly to the current Deriv Options API without MetaAPI:
     * 1) Use the user's PAT to list their Options accounts over REST.
     * 2) Request a single-use account OTP.
     * 3) Open the exact authenticated WebSocket URL returned by Deriv.
     *
     * Deriv API tokens from the old v3 "authorize" flow are not interchangeable with current
     * Personal Access Tokens. If the token is legacy or missing the trade scope, the REST error
     * is surfaced so the user can create the correct token.
     */
    suspend fun connectAndAuthorize(apiToken: String? = null): Result<DerivAccountInfo> = withContext(Dispatchers.IO) {
        if (!secrets.isSecureStorageAvailable) {
            _connectionState.value = DerivConnectionState.AUTH_FAILED
            return@withContext Result.failure(Exception("Secure Android Keystore storage is unavailable; Deriv connection is blocked."))
        }
        val requestedMode = secrets.getDerivAccountMode().lowercase()
        val token = (apiToken ?: secrets.getDerivApiToken()).trim()
        if (token.isBlank()) {
            _connectionState.value = DerivConnectionState.AUTH_FAILED
            return@withContext Result.failure(Exception("Enter a current Deriv Personal Access Token with Trade scope."))
        }

        val appId = secrets.getDerivAppId().trim()
        if (appId.isBlank() || !appId.all { it.isDigit() } || appId == "1089") {
            _connectionState.value = DerivConnectionState.ERROR
            return@withContext Result.failure(
                Exception("Enter a numeric Deriv API App ID registered to your application. The legacy App ID 1089 is not accepted by this connector.")
            )
        }

        lastConnectionError = null
        _connectionState.value = DerivConnectionState.CONNECTING
        // Ensure a retry never leaves old request waiters attached to the previous socket.
        webSocket?.close(1000, "Replacing old Deriv session")
        webSocket = null
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()

        try {
            _connectionState.value = DerivConnectionState.AUTHENTICATING
            val accountsRequest = Request.Builder()
                .url("$REST_BASE/trading/v1/options/accounts")
                .header("Authorization", "Bearer $token")
                .header("Deriv-App-ID", appId)
                .header("Accept", "application/json")
                .get()
                .build()
            val accountJson = executeRestJson(accountsRequest)
            val accounts = accountJson.optJSONArray("data")
                ?: throw IllegalStateException("Deriv account-list response did not contain a data array.")

            val activeAccounts = (0 until accounts.length()).mapNotNull { accounts.optJSONObject(it) }
                .filter { it.optString("status").equals("active", ignoreCase = true) }
            val account = selectDerivOptionsAccount(accounts, requestedMode)
            if (account == null) {
                val available = activeAccounts.map { it.optString("account_type").ifBlank { "unknown" } }.distinct()
                throw IllegalStateException(
                    "No active ${requestedMode.uppercase()} Options account was returned for this token" +
                        (if (available.isEmpty()) "." else ". Available account types: " + available.joinToString() + ".") +
                        " Check that the token has the required account-read and trading permissions."
                )
            }

            val accountId = account.optString("account_id").trim()
            val currency = account.optString("currency").trim()
            val initialBalance = account.optDouble("balance", 0.0)
            if (accountId.isBlank()) throw IllegalStateException("Deriv returned an account with no account_id.")
            if (currency.isBlank() || !currency.matches(Regex("[A-Za-z0-9]{2,20}"))) {
                throw IllegalStateException("Deriv $requestedMode account response omitted a valid account currency.")
            }
            if (!initialBalance.isFinite() || initialBalance < 0.0) {
                throw IllegalStateException("Deriv $requestedMode account response contained an invalid balance.")
            }

            val otpRequest = Request.Builder()
                .url("$REST_BASE/trading/v1/options/accounts/${java.net.URLEncoder.encode(accountId, "UTF-8")}/otp")
                .header("Authorization", "Bearer $token")
                .header("Deriv-App-ID", appId)
                .header("Accept", "application/json")
                .post(ByteArray(0).toRequestBody())
                .build()
            val otpJson = executeRestJson(otpRequest)
            val authenticatedUrl = otpJson.optJSONObject("data")?.optString("url")?.trim().orEmpty()
            val expectedEndpoint = "/trading/v1/options/ws/$requestedMode"
            if (!authenticatedUrl.startsWith(AUTHENTICATED_WS_PREFIX) || !authenticatedUrl.substringBefore('?').endsWith(expectedEndpoint)) {
                throw IllegalStateException("Deriv OTP response did not select the requested ${requestedMode.uppercase()} authenticated endpoint.")
            }

            val wsDeferred = CompletableDeferred<Boolean>()
            val request = Request.Builder().url(authenticatedUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.i(TAG, "Deriv authenticated Options WebSocket connected; account type=$requestedMode")
                    wsDeferred.complete(true)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    val detail = buildString {
                        append(t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName)
                        if (response != null) append(" (HTTP ${response.code} ${response.message})")
                    }
                    val ownsCurrentSession = webSocket === ws
                    if (ownsCurrentSession) {
                        lastConnectionError = "Deriv authenticated WebSocket failed: $detail"
                        Log.e(TAG, lastConnectionError ?: "Deriv authenticated WebSocket failure")
                        _connectionState.value = DerivConnectionState.ERROR
                        pendingRequests.values.forEach { it.completeExceptionally(t) }
                    }
                    wsDeferred.complete(false)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    val ownsCurrentSession = webSocket === ws
                    if (ownsCurrentSession) {
                        if (code != 1000) {
                            lastConnectionError = "Deriv closed the authenticated WebSocket (code $code" +
                                (reason.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") + ")."
                        }
                        _connectionState.value = DerivConnectionState.DISCONNECTED
                    }
                    if (!wsDeferred.isCompleted) wsDeferred.complete(false)
                }
            })

            val connected = withTimeoutOrNull(12_000L) { wsDeferred.await() } ?: false
            if (!connected) {
                _connectionState.value = DerivConnectionState.ERROR
                return@withContext Result.failure(
                    Exception(lastConnectionError ?: "Deriv authenticated WebSocket did not connect before the OTP expired.")
                )
            }

            val info = DerivAccountInfo(
                loginId = accountId,
                currency = currency,
                balance = initialBalance,
                email = "",
                accountType = account.optString("account_type").lowercase(),
                demoVerified = account.optString("account_type").equals("demo", ignoreCase = true),
                mode = requestedMode,
                realExecutionAuthorized = requestedMode == "real" && secrets.isDerivRealExecutionEnabled()
            )
            if (!account.optString("account_type").equals(requestedMode, ignoreCase = true)) {
                throw IllegalStateException("Deriv returned an account-mode mismatch; requested $requestedMode but received ${account.optString("account_type") }.")
            }
            _accountInfo.value = info
            _accountMetrics.value = AccountMetrics(
                balance = initialBalance,
                equity = initialBalance,
                margin = 0.0,
                freeMargin = initialBalance,
                marginLevel = 0.0,
                currency = currency
            )
            _accountMetricsUpdatedAtMs.value = System.currentTimeMillis()
            _connectionState.value = DerivConnectionState.CONNECTED
            // The authenticated WebSocket already carries the account scope; no legacy
            // "authorize" message is sent after the OTP handshake.
            sendBalanceSubscription()
            fetchPortfolio()
            Result.success(info)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _connectionState.value = DerivConnectionState.ERROR
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            lastConnectionError = detail
            Log.e(TAG, "Deriv direct-API connection failed: $detail")
            Result.failure(Exception("Deriv connection failed: $detail"))
        }
    }

    private fun executeRestJson(request: Request): JSONObject =
        client.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                if (!response.isSuccessful) {
                    val errors = json.optJSONArray("errors")
                    val apiMessage = errors?.optJSONObject(0)?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: json.optString("message").takeIf { it.isNotBlank() }
                        ?: body.take(240).ifBlank { response.message }
                    throw IllegalStateException("HTTP ${response.code}: $apiMessage")
                }
                json
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
        val current = _accountInfo.value
        if (_connectionState.value != DerivConnectionState.CONNECTED || current == null) {
            _portfolioSnapshot.value = DerivPortfolioSnapshot(false, current?.loginId.orEmpty(), current?.mode.orEmpty(), System.currentTimeMillis(), error = "BROKER_DISCONNECTED")
            return@withContext emptyList()
        }
        val reqId = reqIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[reqId] = deferred

        val json = JSONObject().apply {
            put("portfolio", 1)
            put("req_id", reqId)
        }
        if (webSocket?.send(json.toString()) != true) {
            pendingRequests.remove(reqId)
            _portfolioSnapshot.value = DerivPortfolioSnapshot(false, current.loginId, current.mode.uppercase(), System.currentTimeMillis(), error = "PORTFOLIO_REQUEST_NOT_SENT")
            return@withContext emptyList()
        }

        try {
            val resp = withTimeout(8000) { deferred.await() }
            val portfolio = resp.optJSONObject("portfolio")
            val contracts = portfolio?.optJSONArray("contracts")
                ?: throw IllegalStateException("Deriv portfolio response omitted contracts")
            val list = mutableListOf<Position>()
            val snapshotContracts = mutableListOf<DerivContractInfo>()
            for (i in 0 until contracts.length()) {
                val c = contracts.getJSONObject(i)
                val contractId = c.optLong("contract_id", 0L)
                // Current Deriv portfolio responses use underlying_symbol; symbol is
                // retained only for compatibility with the documented legacy response.
                val symbol = c.optString("underlying_symbol").ifBlank {
                    c.optString("symbol").ifBlank { c.optString("underlying") }
                }.trim()
                val buyPrice = c.optDouble("buy_price", Double.NaN)
                val contractType = c.optString("contract_type").trim().uppercase()
                val currency = c.optString("currency").trim()
                val purchaseEpoch = c.optLong("purchase_time", 0L).takeIf { it > 0L }
                    ?: c.optLong("date_start", 0L).takeIf { it > 0L }
                if (contractId <= 0L || symbol.isBlank() ||
                    !DerivMultiplierRules.isSupportedUnderlying(symbol) ||
                    contractType !in setOf("MULTUP", "MULTDOWN") ||
                    currency.isBlank() || !currency.equals(current.currency, ignoreCase = true) ||
                    !buyPrice.isFinite() || buyPrice <= 0.0 || purchaseEpoch == null ||
                    purchaseEpoch > System.currentTimeMillis() / 1000L + 60L ||
                    snapshotContracts.any { it.contractId == contractId }) {
                    throw IllegalStateException("PORTFOLIO_INVALID_CONTRACT: every open contract must have a unique positive ID, supported underlying and type, explicit matching currency, positive finite purchase price, and valid broker timestamp")
                }
                val isLong = contractType == "MULTUP"
                val openTime = purchaseEpoch * 1000L

                subscribeOpenContract(contractId)
                // Portfolio provides open-position identity and buy price; live P/L is
                // delivered separately by proposal_open_contract and is not required
                // to calculate committed stake exposure.
                val profit = c.optDouble("profit", Double.NaN)
                snapshotContracts += DerivContractInfo(contractId, c.optLong("transaction_id", 0L), symbol, contractType, buyPrice, 0.0, profit, false, false, "open", purchaseEpoch, c.optLong("expiry_time", 0L).takeIf { it > 0L })

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
                        currentProfit = c.optDouble("profit", Double.NaN),
                        openedAt = openTime
                    )
                )
            }
            _openPositions.value = list
            _activeContracts.value = snapshotContracts.associateBy { it.contractId }
            _portfolioSnapshot.value = DerivPortfolioSnapshot(true, current.loginId, current.mode.uppercase(), System.currentTimeMillis(), snapshotContracts, currency = current.currency)
            list
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _portfolioSnapshot.value = DerivPortfolioSnapshot(false, current.loginId, current.mode.uppercase(), System.currentTimeMillis(), error = e.message?.take(240) ?: "PORTFOLIO_FETCH_FAILED")
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
            val quoteVal = tickObj.optDouble("quote", Double.NaN)
            val bidVal = tickObj.optDouble("bid", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
            val askVal = tickObj.optDouble("ask", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
            val epoch = tickObj.optLong("epoch", 0L)
            if (!quoteVal.isFinite() || quoteVal <= 0.0 || epoch <= 0L) {
                return@withContext Result.failure(Exception("DATA_UNAVAILABLE: Deriv returned an invalid quote timestamp or price for $symbol"))
            }
            val timeMs = epoch * 1000L
            val now = System.currentTimeMillis()
            val ageMs = now - timeMs
            if (ageMs < -2_000L || ageMs > 15_000L) {
                return@withContext Result.failure(Exception("DATA_STALE: Deriv broker quote for $symbol is not current (age: ${ageMs / 1000}s)"))
            }

            val validTwoSided = bidVal != null && askVal != null && askVal >= bidVal
            val cls = com.destinedfx.core.instrument.AssetClassifier.of(symbol)
            val marketQuote = MarketQuote(
                symbol = symbol,
                assetClass = cls,
                bid = if (validTwoSided) bidVal else null,
                ask = if (validTwoSided) askVal else null,
                last = quoteVal,
                timestampMs = timeMs,
                receivedAtMs = now,
                source = "Deriv Options WebSocket"
            )
            cachedQuotes[symbol] = marketQuote
            Result.success(marketQuote)
        } catch (e: Exception) {
            Result.failure(Exception("DATA_UNAVAILABLE: Timeout or error obtaining Deriv quote for $symbol: ${e.message}"))
        } finally {
            pendingRequests.remove(reqId)
        }
    }

    /**
     * Buy a Deriv multiplier contract through the authenticated Options WebSocket.
     * The limit_order values are cash-denominated P/L thresholds in account currency; they are
     * not the same as chart-price stops on an MT5/CFD position. Stake is the maximum risk amount.
     */
    /**
     * Product-specific purchase path. This is intentionally not part of BrokerGateway because
     * CanonicalTradePlan carries Forex/CFD semantics that cannot safely describe a multiplier.
     */
    private suspend fun isBrokerSupportedSymbol(symbol: String, product: DerivMultiplierProduct): Boolean {
        val reqId = reqIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[reqId] = deferred
        val payload = JSONObject().apply {
            put("active_symbols", "brief")
            put("contract_type", org.json.JSONArray().put(product.contractType))
            put("req_id", reqId)
        }
        if (webSocket?.send(payload.toString()) != true) { pendingRequests.remove(reqId); return false }
        return try {
            val response = withTimeout(5_000) { deferred.await() }
            val rows = response.optJSONArray("active_symbols") ?: return false
            (0 until rows.length()).any { rows.optJSONObject(it)?.optString("symbol") == symbol }
        } catch (_: Exception) { false } finally { pendingRequests.remove(reqId) }
    }

    suspend fun executeMultiplier(
        intent: DerivMultiplierIntent,
        onStage: suspend (requestId: Int?, proposalId: String?, stage: DerivSubmissionStage) -> Unit = { _, _, _ -> }
    ): Result<DerivBuyResult> = withContext(Dispatchers.IO) {
        if (_connectionState.value != DerivConnectionState.CONNECTED) {
            return@withContext Result.failure(Exception("Deriv broker is not connected. Please connect in the Bot tab."))
        }
        val account = _accountInfo.value
            ?: return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.VALIDATION_REJECTED, "ACCOUNT_UNVERIFIED: no verified Deriv account snapshot is available."))
        val mode = secrets.getDerivAccountMode().lowercase()
        if (account.loginId.isBlank() || account.mode != mode || account.accountType != mode) {
            return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.VALIDATION_REJECTED, "ACCOUNT_MODE_MISMATCH: selected account does not match the requested profile."))
        }
        if (mode == "real" && !secrets.isDerivRealExecutionEnabled()) {
            return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.VALIDATION_REJECTED, "REAL_EXECUTION_DISABLED: explicitly arm the exact verified REAL account before starting."))
        }
        val balance = _accountMetrics.value?.balance ?: account.balance
        val validation = DerivMultiplierRules.validate(intent, balance, account.currency)
        if (!validation.valid) return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.VALIDATION_REJECTED, validation.reason ?: "Multiplier intent rejected"))
        if (!isBrokerSupportedSymbol(intent.symbol, intent.product)) {
            return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.VALIDATION_REJECTED, "UNSUPPORTED_SYMBOL: broker did not validate ${intent.symbol} for ${intent.product.contractType}."))
        }

        val currentQuote = getQuote(intent.symbol)
        if (currentQuote.isFailure) {
            return@withContext Result.failure(currentQuote.exceptionOrNull() ?: Exception("DATA_UNAVAILABLE: current Deriv quote is required"))
        }
        if (currentQuote.getOrThrow().timestampMs <= 0L) {
            return@withContext Result.failure(Exception("DATA_STALE: Deriv quote did not contain a valid broker timestamp"))
        }

        // 1. Request a proposal using the current API schema and cash-based protective limits.
        val propReqId = reqIdCounter.getAndIncrement()
        val propDeferred = CompletableDeferred<JSONObject>()
        pendingRequests[propReqId] = propDeferred
        val propPayload = JSONObject().apply {
            put("proposal", 1)
            put("amount", intent.stake)
            put("basis", "stake")
            put("contract_type", intent.product.contractType)
            put("currency", intent.currency)
            put("duration_unit", "s")
            put("multiplier", intent.multiplier)
            put("underlying_symbol", intent.symbol)
            put("limit_order", JSONObject().apply {
                put("stop_loss", intent.stopLossAmount)
                put("take_profit", intent.takeProfitAmount)
            })
            put("subscribe", 1)
            put("req_id", propReqId)
        }
        onStage(propReqId, null, DerivSubmissionStage.PROPOSAL_SENT)
        if (webSocket?.send(propPayload.toString()) != true) {
            pendingRequests.remove(propReqId)
            return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.PROPOSAL_TIMEOUT, "PROPOSAL_NOT_SENT: Deriv WebSocket rejected the proposal request before it was sent.", propReqId, submissionStage = DerivSubmissionStage.VALIDATED))
        }

        val proposalId: String
        val askPrice: Double
        try {
            val propResp = withTimeout(10_000) { propDeferred.await() }
            val err = propResp.optJSONObject("error")
            if (err != null) {
                return@withContext Result.failure(
                    DerivExecutionFailure(DerivExecutionFailureKind.PROPOSAL_REJECTED, "PROPOSAL_REJECTED: ${err.optString("code")}: ${err.optString("message")}", propReqId, submissionStage = DerivSubmissionStage.PROPOSAL_SENT)
                )
            }
            val p = propResp.optJSONObject("proposal")
                ?: return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.PROPOSAL_REJECTED, "PROPOSAL_REJECTED: Deriv did not return a proposal object.", propReqId, submissionStage = DerivSubmissionStage.PROPOSAL_SENT))
            proposalId = p.optString("id").trim()
            askPrice = p.optDouble("ask_price", Double.NaN)
            if (proposalId.isBlank() || !askPrice.isFinite() || askPrice <= 0.0) {
                return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.PROPOSAL_REJECTED, "PROPOSAL_REJECTED: Deriv omitted a valid ID or ask_price; no order was sent.", propReqId, submissionStage = DerivSubmissionStage.PROPOSAL_CONFIRMED))
            }
            onStage(propReqId, proposalId, DerivSubmissionStage.PROPOSAL_CONFIRMED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val kind = if (error is kotlinx.coroutines.TimeoutCancellationException) DerivExecutionFailureKind.PROPOSAL_TIMEOUT else DerivExecutionFailureKind.PROPOSAL_REJECTED
            return@withContext Result.failure(DerivExecutionFailure(kind, "${kind.name}: ${error.message ?: error.javaClass.simpleName}", propReqId, submissionStage = DerivSubmissionStage.PROPOSAL_SENT))
        } finally {
            pendingRequests.remove(propReqId)
        }

        // 2. Buy only the proposal ID/price returned by Deriv.
        val buyReqId = reqIdCounter.getAndIncrement()
        val buyDeferred = CompletableDeferred<JSONObject>()
        pendingRequests[buyReqId] = buyDeferred
        val buyPayload = JSONObject().apply {
            put("buy", proposalId)
            put("price", askPrice)
            put("req_id", buyReqId)
        }
        onStage(buyReqId, proposalId, DerivSubmissionStage.PURCHASE_SENT)
        if (webSocket?.send(buyPayload.toString()) != true) {
            pendingRequests.remove(buyReqId)
            return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.PURCHASE_NOT_SENT, "PURCHASE_NOT_SENT: Deriv WebSocket rejected the buy request before transmission.", buyReqId, proposalId, DerivSubmissionStage.PROPOSAL_CONFIRMED))
        }

        try {
            val buyResp = withTimeout(10_000) { buyDeferred.await() }
            val buyErr = buyResp.optJSONObject("error")
            if (buyErr != null) {
                return@withContext Result.failure(
                    DerivExecutionFailure(DerivExecutionFailureKind.PURCHASE_REJECTED, "PURCHASE_REJECTED: ${buyErr.optString("code")}: ${buyErr.optString("message")}", buyReqId, proposalId, DerivSubmissionStage.PURCHASE_SENT)
                )
            }
            val buyData = buyResp.optJSONObject("buy")
                ?: return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.PURCHASE_REJECTED, "PURCHASE_REJECTED: Deriv returned no buy confirmation object.", buyReqId, proposalId, DerivSubmissionStage.PURCHASE_SENT))
            val contractId = buyData.optLong("contract_id", 0L)
            val txId = buyData.optLong("transaction_id", 0L)
            val boughtPrice = buyData.optDouble("buy_price", Double.NaN)
            if (contractId <= 0L || !boughtPrice.isFinite() || boughtPrice <= 0.0) {
                return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.PURCHASE_AMBIGUOUS, "PURCHASE_AMBIGUOUS: buy response lacked a valid contract ID and purchase price.", buyReqId, proposalId, DerivSubmissionStage.PURCHASE_SENT))
            }
            subscribeOpenContract(contractId)
            fetchPortfolio()
            Result.success(DerivBuyResult(contractId, txId, boughtPrice, proposalId, buyReqId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val kind = if (error is kotlinx.coroutines.TimeoutCancellationException) DerivExecutionFailureKind.PURCHASE_AMBIGUOUS else DerivExecutionFailureKind.PURCHASE_AMBIGUOUS
            Result.failure(DerivExecutionFailure(kind, "${kind.name}: ${error.message ?: error.javaClass.simpleName}; do not retry blindly.", buyReqId, proposalId, DerivSubmissionStage.PURCHASE_SENT))
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

    suspend fun lookupHistoricalContract(contractId: Long, sinceMs: Long): DerivContractInfo? = withContext(Dispatchers.IO) {
        if (_connectionState.value != DerivConnectionState.CONNECTED || contractId <= 0L) return@withContext null
        val reqId = reqIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[reqId] = deferred
        val payload = JSONObject().apply {
            put("profit_table", 1)
            put("description", 1)
            put("date_from", (sinceMs / 1000L).coerceAtLeast(0L))
            put("date_to", System.currentTimeMillis() / 1000L)
            put("limit", 500)
            put("req_id", reqId)
        }
        if (webSocket?.send(payload.toString()) != true) { pendingRequests.remove(reqId); return@withContext null }
        try {
            val response = withTimeout(8_000) { deferred.await() }
            val rows = response.optJSONObject("profit_table")?.optJSONArray("transactions") ?: return@withContext null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optLong("contract_id", 0L) != contractId) continue
                // Profit-table transactions are closed-contract evidence. Never fill
                // absent settlement fields with the buy price or a computed placeholder.
                val transactionId = row.optLong("transaction_id", 0L)
                val buy = row.optDouble("buy_price", Double.NaN)
                val sell = row.optDouble("sell_price", Double.NaN)
                val profit = row.optDouble("profit", Double.NaN)
                val purchaseEpoch = row.optLong("purchase_time", 0L)
                val sellEpoch = row.optLong("sell_time", 0L)
                val symbol = row.optString("underlying_symbol").ifBlank { row.optString("symbol") }.trim()
                val contractType = row.optString("contract_type").trim().uppercase()
                if (transactionId <= 0L || !buy.isFinite() || buy <= 0.0 ||
                    !sell.isFinite() || sell < 0.0 || !profit.isFinite() ||
                    purchaseEpoch <= 0L || sellEpoch <= 0L || sellEpoch < purchaseEpoch ||
                    !DerivMultiplierRules.isSupportedUnderlying(symbol) ||
                    contractType !in setOf("MULTUP", "MULTDOWN")) {
                    return@withContext null
                }
                return@withContext DerivContractInfo(
                    contractId, transactionId, symbol, contractType, buy, sell, profit,
                    true, false, row.optString("status").ifBlank { "settled" },
                    purchaseEpoch * 1000L, null
                )
            }
            null
        } catch (_: Exception) { null } finally { pendingRequests.remove(reqId) }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = DerivConnectionState.DISCONNECTED
        _accountMetrics.value = null
        _accountMetricsUpdatedAtMs.value = 0L
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
                    val balance = balObj.optDouble("balance", Double.NaN)
                    val currency = balObj.optString("currency").trim()
                    val selectedCurrency = _accountInfo.value?.currency.orEmpty()
                    if (!balance.isFinite() || balance <= 0.0 || currency.isBlank() ||
                        !currency.equals(selectedCurrency, ignoreCase = true)) {
                        _accountMetricsUpdatedAtMs.value = 0L
                        return
                    }
                    _accountMetrics.value = _accountMetrics.value?.copy(
                        balance = balance,
                        equity = balance,
                        freeMargin = balance,
                        currency = currency
                    ) ?: AccountMetrics(balance, balance, 0.0, balance, 0.0, currency)
                    _accountMetricsUpdatedAtMs.value = System.currentTimeMillis()
                }
                "proposal_open_contract" -> {
                    val poc = json.optJSONObject("proposal_open_contract") ?: return
                    val contractId = poc.optLong("contract_id", 0L)
                    if (contractId <= 0L) return
                    val underlying = poc.optString("underlying_symbol").ifBlank {
                        poc.optString("underlying").ifBlank { poc.optString("symbol") }
                    }.trim()
                    val buyPrice = poc.optDouble("buy_price", Double.NaN)
                    val bidPrice = poc.optDouble("bid_price", Double.NaN)
                    val profit = poc.optDouble("profit", Double.NaN)
                    val isSold = poc.optInt("is_sold", 0) == 1
                    val isValidToSell = poc.optInt("is_valid_to_sell", 0) == 1
                    val status = poc.optString("status").ifBlank { if (isSold) "sold" else "open" }
                    val purchaseEpoch = poc.optLong("date_start", 0L).takeIf { it > 0L }
                        ?: poc.optLong("purchase_time", 0L).takeIf { it > 0L }
                    if (underlying.isBlank() || !DerivMultiplierRules.isSupportedUnderlying(underlying) ||
                        !buyPrice.isFinite() || buyPrice <= 0.0 || purchaseEpoch == null ||
                        poc.optString("currency").isBlank() ||
                        poc.optString("contract_type").trim().uppercase() !in setOf("MULTUP", "MULTDOWN")) {
                        _portfolioSnapshot.value = DerivPortfolioSnapshot(
                            false, _accountInfo.value?.loginId.orEmpty(), _accountInfo.value?.mode.orEmpty(),
                            System.currentTimeMillis(), error = "OPEN_CONTRACT_STREAM_INVALID"
                        )
                        return
                    }
                    val purchaseTime = purchaseEpoch * 1000L
                    val expiryTime = poc.optLong("date_expiry", 0L).takeIf { it > 0 }?.times(1000L)
                    val contractType = poc.optString("contract_type").trim().uppercase()
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
                            .ifBlank { tickObj.optString("underlying_symbol") }
                        val quoteVal = tickObj.optDouble("quote", Double.NaN)
                        val bidVal = tickObj.optDouble("bid", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                        val askVal = tickObj.optDouble("ask", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                        val epoch = tickObj.optLong("epoch", 0L)
                        if (sym.isBlank() || !quoteVal.isFinite() || quoteVal <= 0.0 || epoch <= 0L) {
                            Log.w(TAG, "Ignoring invalid Deriv tick without a valid symbol, price, and epoch")
                            return
                        }
                        val timeMs = epoch * 1000L
                        val validTwoSided = bidVal != null && askVal != null && askVal >= bidVal
                        val cls = com.destinedfx.core.instrument.AssetClassifier.of(sym)
                        val mq = MarketQuote(
                            symbol = sym,
                            assetClass = cls,
                            bid = if (validTwoSided) bidVal else null,
                            ask = if (validTwoSided) askVal else null,
                            last = quoteVal,
                            timestampMs = timeMs,
                            receivedAtMs = System.currentTimeMillis(),
                            source = "Deriv Options WebSocket"
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
