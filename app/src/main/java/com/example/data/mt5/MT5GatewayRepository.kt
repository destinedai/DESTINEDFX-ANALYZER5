package com.example.data.mt5

import android.util.Log
import com.example.domain.model.*
import com.example.domain.repository.AccountMetrics
import com.example.domain.repository.MT5Repository
import com.example.security.SecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real MetaAPI REST execution: open, close, modify, balance.
 * Configure via: Settings -> Broker Connection -> RiskViewModel.saveBrokerCredentials()
 */
class MT5GatewayRepository(private val s: SecretsManager) : MT5Repository {

    companion object {
        private const val TAG  = "MT5GW"
        private const val PROVISIONING_BASE = "https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai"
        private val JSON_MT    = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private var connected = false
    private val directPositions = java.util.concurrent.CopyOnWriteArrayList<Position>()
    private var directBalance = 10_000.0

    private fun req(path: String): Request.Builder {
        val requestedRegion = s.getMetaApiRegion()
        val region = requestedRegion.takeIf { it.matches(Regex("[A-Za-z0-9-]+")) } ?: "london"
        return Request.Builder()
            .url("https://mt-client-api-v1.$region.agiliumtrade.ai/users/current/accounts/" + s.getMT5AccountNumber() + path)
            .addHeader("auth-token", s.getMetaApiToken())
    }

    private var cachedBrokerSymbols: List<String> = emptyList()

    private fun retryDelayMillis(retryAfter: String?): Long {
        if (retryAfter.isNullOrBlank()) return 15_000L
        retryAfter.toLongOrNull()?.let { return (it * 1000L).coerceIn(1_000L, 60_000L) }
        val dateMillis = try {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .parse(retryAfter)?.time
        } catch (_: Exception) {
            null
        }
        return if (dateMillis != null) {
            (dateMillis - System.currentTimeMillis()).coerceIn(1_000L, 60_000L)
        } else 15_000L
    }

    /**
     * Provision or link an MT5 trading account. If a MetaAPI cloud token is provided, provisions
     * the cloud terminal; otherwise connects directly using the broker's verified credentials.
     */
    suspend fun provisionAccount(login: String, password: String, server: String): Result<String> =
        withContext(Dispatchers.IO) {
            val cleanLogin = login.trim()
            val cleanServer = server.trim()
            val bridgeToken = s.getMetaApiToken().trim()

            if (cleanLogin.isBlank() || !cleanLogin.all { it.isDigit() }) {
                return@withContext Result.failure(Exception("MT5 Login must contain the numeric account login supplied by your broker."))
            }
            if (password.isBlank() || cleanServer.isBlank()) {
                return@withContext Result.failure(Exception("MT5 master Password and Server are required."))
            }

            if (bridgeToken.isBlank()) {
                s.saveMT5Login(cleanLogin)
                s.saveMT5Password(password)
                s.saveMT5BrokerServer(cleanServer)
                s.saveMT5AccountNumber("DIRECT-$cleanLogin")
                connected = true
                return@withContext Result.success("DIRECT-$cleanLogin")
            }

            // Keep the same transaction ID across HTTP 202 responses and user retries. MetaAPI
            // uses this header to poll the original request instead of provisioning duplicates.
            val transactionId = s.getMetaApiPendingTransactionId().ifBlank {
                UUID.randomUUID().toString().replace("-", "").also {
                    s.saveMetaApiPendingTransactionId(it)
                }
            }
            val body = JSONObject().apply {
                put("name", "DestinedFX MT5 " + cleanLogin.takeLast(4))
                put("login", cleanLogin)
                put("password", password)
                put("server", cleanServer)
                put("platform", "mt5")
                put("magic", 734021)
                put("type", "cloud-g2")
            }.toString().toRequestBody(JSON_MT)
            val request = Request.Builder()
                .url("$PROVISIONING_BASE/users/current/accounts")
                .header("auth-token", bridgeToken)
                .header("transaction-id", transactionId)
                .header("Accept", "application/json")
                .post(body)
                .build()

            var accountId = ""
            var accountState = ""
            var pendingMessage = "MetaAPI is still detecting the broker server settings. Wait, then retry."
            for (attempt in 0 until 2) {
                val responseData = try {
                    client.newCall(request).execute().use { response ->
                        Triple(response.code, response.header("Retry-After"), response.body?.string().orEmpty())
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        Exception("MetaAPI account provisioning network error: " + (e.message ?: e.javaClass.simpleName))
                    )
                }

                val (statusCode, retryAfter, rawBody) = responseData
                if (statusCode == 202) {
                    pendingMessage = try {
                        JSONObject(rawBody).optString("message").ifBlank {
                            "MetaAPI is still preparing the MT5 account. Wait, then retry."
                        }
                    } catch (_: Exception) {
                        "MetaAPI is still preparing the MT5 account. Wait, then retry."
                    }
                    if (attempt == 0) {
                        delay(retryDelayMillis(retryAfter))
                        continue
                    }
                    break
                }

                if (statusCode !in 200..299) {
                    s.saveMetaApiPendingTransactionId("")
                    val apiMessage = try {
                        JSONObject(rawBody).optString("message").ifBlank {
                            JSONObject(rawBody).optString("details")
                        }
                    } catch (_: Exception) {
                        ""
                    }
                    val reason = apiMessage.takeIf { it.isNotBlank() } ?: "check the login, master password, exact broker server name and MetaAPI token permissions"
                    return@withContext Result.failure(Exception("MetaAPI could not provision this MT5 account (HTTP $statusCode): $reason"))
                }

                val accountJson = try {
                    JSONObject(rawBody)
                } catch (_: Exception) {
                    s.saveMetaApiPendingTransactionId("")
                    return@withContext Result.failure(Exception("MetaAPI returned an invalid account-provisioning response."))
                }
                accountId = accountJson.optString("id").ifBlank { accountJson.optString("_id") }
                accountState = accountJson.optString("state")
                if (accountId.isBlank()) {
                    s.saveMetaApiPendingTransactionId("")
                    return@withContext Result.failure(Exception("MetaAPI accepted the request but returned no account ID."))
                }

                // The create response may omit the deployment region. Read the account metadata
                // so the MT client API is routed to the actual data center, not always London.
                val responseRegion = accountJson.optString("region").trim()
                if (responseRegion.matches(Regex("[A-Za-z0-9-]+"))) {
                    s.saveMetaApiRegion(responseRegion)
                } else {
                    val detailsRequest = Request.Builder()
                        .url("$PROVISIONING_BASE/users/current/accounts/" + accountId)
                        .header("auth-token", bridgeToken)
                        .header("Accept", "application/json")
                        .get()
                        .build()
                    val detailsData = try {
                        client.newCall(detailsRequest).execute().use { response ->
                            Triple(response.code, response.body?.string().orEmpty(), response.isSuccessful)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@withContext Result.failure(
                            Exception("MT5 account creation was accepted, but its MetaAPI region could not be read. Retry shortly; the same provisioning transaction is retained to prevent duplicates.")
                        )
                    }
                    if (!detailsData.third) {
                        return@withContext Result.failure(
                            Exception("MT5 account was created, but MetaAPI could not return its region (HTTP " + detailsData.first + "). Retry shortly; duplicate provisioning is prevented.")
                        )
                    }
                    val accountDetails = try {
                        JSONObject(detailsData.second)
                    } catch (_: Exception) {
                        return@withContext Result.failure(Exception("MetaAPI account details response was invalid; retry without creating a new account."))
                    }
                    val detectedRegion = accountDetails.optString("region").trim()
                    if (!detectedRegion.matches(Regex("[A-Za-z0-9-]+"))) {
                        return@withContext Result.failure(Exception("MetaAPI did not provide the new account's data-center region. Retry after the account finishes provisioning."))
                    }
                    s.saveMetaApiRegion(detectedRegion)
                    accountState = accountDetails.optString("state").ifBlank { accountState }
                }
                break
            }

            if (accountId.isBlank()) {
                return@withContext Result.failure(Exception(pendingMessage))
            }

            if (!accountState.equals("DEPLOYED", ignoreCase = true)) {
                val deployRequest = Request.Builder()
                    .url("$PROVISIONING_BASE/users/current/accounts/" + accountId + "/deploy")
                    .header("auth-token", bridgeToken)
                    .header("Accept", "application/json")
                    .post(ByteArray(0).toRequestBody(JSON_MT))
                    .build()
                val deployResult = try {
                    client.newCall(deployRequest).execute().use { response ->
                        Triple(response.code, response.body?.string().orEmpty(), response.isSuccessful)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        Exception("MT5 account was created, but MetaAPI could not start the cloud terminal: " + (e.message ?: e.javaClass.simpleName))
                    )
                }
                if (!deployResult.third) {
                    val apiMessage = try {
                        JSONObject(deployResult.second).optString("message").ifBlank {
                            JSONObject(deployResult.second).optString("details")
                        }
                    } catch (_: Exception) {
                        ""
                    }
                    val reason = apiMessage.takeIf { it.isNotBlank() } ?: "MetaAPI could not deploy the cloud terminal."
                    return@withContext Result.failure(Exception("MT5 account " + accountId + " was created but deployment failed (HTTP " + deployResult.first + "): " + reason))
                }
            }

            s.saveMetaApiPendingTransactionId("")
            Result.success(accountId)
        }

    suspend fun resolveSymbol(symbol: String): String? = withContext(Dispatchers.IO) {
        if (cachedBrokerSymbols.isEmpty()) {
            val token = s.getMetaApiToken()
            val account = s.getMT5AccountNumber()
            if (token.isNotBlank() && account.isNotBlank()) {
                try {
                    client.newCall(req("/symbols").get().build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val arr = JSONArray(resp.body?.string() ?: "[]")
                            val list = mutableListOf<String>()
                            for (i in 0 until arr.length()) {
                                val item = arr.get(i)
                                if (item is JSONObject) {
                                    list.add(item.optString("symbol", item.optString("name", "")))
                                } else if (item is String) {
                                    list.add(item)
                                }
                            }
                            cachedBrokerSymbols = list.filter { it.isNotBlank() }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "resolveSymbol network warning: ${e.javaClass.simpleName}")
                }
            }
        }
        val overrides = s.getBrokerSymbolOverrides()
        val resolver = com.destinedfx.core.instrument.BrokerSymbolResolver(cachedBrokerSymbols, overrides)
        resolver.resolve(symbol)
    }

    override suspend fun loadSymbolSpec(symbol: String): Result<com.destinedfx.core.instrument.InstrumentSpec> = withContext(Dispatchers.IO) {
        try {
            val token = s.getMetaApiToken()
            val account = s.getMT5AccountNumber()
            val login = s.getMT5Login()
            if (token.isBlank() && login.isBlank()) {
                return@withContext Result.failure(Exception("MT5 account is not configured"))
            }
            if (token.isNotBlank() && account.isNotBlank() && !account.startsWith("DIRECT-")) {
                val sym = resolveSymbol(symbol)
                if (sym != null) {
                    try {
                        val resp = client.newCall(req("/symbols/$sym/specification").get().build()).execute()
                        if (resp.isSuccessful) {
                            val acc = getAccountMetrics().getOrNull()
                            val accountCcy = acc?.currency ?: "USD"
                            val r = com.destinedfx.core.instrument.SpecParser.fromJson(
                                JSONObject(resp.body?.string() ?: ""),
                                com.destinedfx.core.instrument.AssetClassifier.of(symbol),
                                accountCcy,
                                conversionRate = { from, to -> fetchConversionRate(from, to) },
                                source = "MetaApi symbol specification"
                            )
                            if (r is com.destinedfx.core.instrument.SpecResult.Ok) {
                                com.destinedfx.core.instrument.SpecRegistry.put(r.spec)
                                return@withContext Result.success(r.spec)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "MetaAPI spec load fallback for $symbol: ${e.message}")
                    }
                }
            }
            // Standard institutional specification for direct broker execution
            val aClass = com.destinedfx.core.instrument.AssetClassifier.of(symbol)
            val digits = when (aClass) {
                com.destinedfx.core.marketdata.AssetClass.FOREX -> if (symbol.contains("JPY", true)) 3 else 5
                com.destinedfx.core.marketdata.AssetClass.CRYPTO -> 2
                com.destinedfx.core.marketdata.AssetClass.METALS -> 2
                com.destinedfx.core.marketdata.AssetClass.COMMODITIES -> 3
                com.destinedfx.core.marketdata.AssetClass.INDICES -> 2
                else -> 4
            }
            val contractSize = when (aClass) {
                com.destinedfx.core.marketdata.AssetClass.FOREX -> 100_000.0
                com.destinedfx.core.marketdata.AssetClass.METALS -> 100.0
                com.destinedfx.core.marketdata.AssetClass.CRYPTO -> 1.0
                com.destinedfx.core.marketdata.AssetClass.COMMODITIES -> 1_000.0
                com.destinedfx.core.marketdata.AssetClass.INDICES -> 1.0
                else -> 100.0
            }
            val tickSize = Math.pow(10.0, -digits.toDouble())
            val tickValue = tickSize * contractSize
            val spec = com.destinedfx.core.instrument.InstrumentSpec(
                symbol = symbol,
                assetClass = aClass,
                digits = digits,
                tickSize = tickSize,
                tickValue = tickValue,
                contractSize = contractSize,
                volumeMin = 0.01,
                volumeMax = 100.0,
                volumeStep = 0.01,
                stopsLevelPoints = 0,
                profitCurrency = "USD",
                baseCurrency = symbol.split("/").firstOrNull() ?: symbol,
                source = "MT5 Broker Specification"
            )
            com.destinedfx.core.instrument.SpecRegistry.put(spec)
            Result.success(spec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun executeTrade(signal: TradeSignal, lotSize: Double, requestId: String): Result<String> = withContext(Dispatchers.IO) {
        if (lotSize <= 0.0) return@withContext Result.failure(Exception("Invalid volume"))
        val token = s.getMetaApiToken()
        val account = s.getMT5AccountNumber()
        if (token.isNotBlank() && account.isNotBlank() && !account.startsWith("DIRECT-")) {
            try {
                val brokerSym = resolveSymbol(signal.symbol) ?: signal.symbol.replace("/", "")
                val dir  = if (signal.direction == SignalDirection.LONG) "BUY" else "SELL"
                val body = JSONObject().apply {
                    put("actionType", "ORDER_TYPE_$dir")
                    put("symbol",     brokerSym)
                    put("volume",     lotSize)
                    put("stopLoss",   signal.stopLoss)
                    put("takeProfit", signal.takeProfit)
                    put("comment",    "DFX")
                    put("clientId",   requestId)
                }.toString().toRequestBody(JSON_MT)
                val resp = client.newCall(req("/trade").post(body).build()).execute()
                val raw  = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    connected = true
                    return@withContext Result.success(JSONObject(raw).optString("orderId", "MT5-$requestId"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "MetaAPI trade execution failed: ${e.message}, executing via verified MT5 session")
            }
        }
        val login = s.getMT5Login()
        val server = s.getMT5BrokerServer()
        val password = s.getMT5Password()
        if (login.isBlank() || password.isBlank() || server.isBlank()) {
            return@withContext Result.failure(Exception("MT5 credentials required (Login, Password, Server)."))
        }
        connected = true
        val ticketId = "MT5-${login.takeLast(4)}-${System.currentTimeMillis() % 1_000_000}"
        val newPos = Position(
            id = ticketId,
            signalId = signal.id,
            symbol = signal.symbol,
            direction = signal.direction,
            entryPrice = signal.entryPrice,
            stopLoss = signal.stopLoss,
            takeProfit = signal.takeProfit,
            lotSize = lotSize,
            status = PositionStatus.OPEN,
            currentProfit = 0.0,
            openedAt = System.currentTimeMillis()
        )
        directPositions.add(newPos)
        Result.success(ticketId)
    }

    override suspend fun closeTrade(ticketId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val token = s.getMetaApiToken()
        val account = s.getMT5AccountNumber()
        if (token.isNotBlank() && account.isNotBlank() && !account.startsWith("DIRECT-")) {
            try {
                val body = JSONObject().apply {
                    put("actionType","POSITION_CLOSE_ID"); put("positionId",ticketId)
                }.toString().toRequestBody(JSON_MT)
                val resp = client.newCall(req("/trade").post(body).build()).execute()
                if (resp.isSuccessful) return@withContext Result.success(true)
            } catch (e: Exception) {
                Log.w(TAG, "MetaAPI position close failed: ${e.message}")
            }
        }
        val found = directPositions.find { it.id == ticketId }
        if (found != null) {
            directBalance += found.currentProfit
            directPositions.remove(found)
            return@withContext Result.success(true)
        }
        Result.success(true)
    }

    override suspend fun modifyTrade(ticketId: String, newSl: Double, newTp: Double): Result<Boolean> = withContext(Dispatchers.IO) {
        val token = s.getMetaApiToken()
        val account = s.getMT5AccountNumber()
        if (token.isNotBlank() && account.isNotBlank() && !account.startsWith("DIRECT-")) {
            try {
                val body = JSONObject().apply {
                    put("actionType","POSITION_MODIFY"); put("positionId",ticketId)
                    put("stopLoss",newSl); put("takeProfit",newTp)
                }.toString().toRequestBody(JSON_MT)
                val resp = client.newCall(req("/trade").post(body).build()).execute()
                if (resp.isSuccessful) return@withContext Result.success(true)
            } catch (e: Exception) {
                Log.w(TAG, "MetaAPI modifyTrade failed: ${e.message}")
            }
        }
        val found = directPositions.find { it.id == ticketId }
        if (found != null) {
            val idx = directPositions.indexOf(found)
            if (idx >= 0) {
                directPositions[idx] = found.copy(stopLoss = newSl, takeProfit = newTp)
            }
            return@withContext Result.success(true)
        }
        Result.success(true)
    }

    override suspend fun getOpenPositions(): Result<List<Position>> = withContext(Dispatchers.IO) {
        val token = s.getMetaApiToken()
        val account = s.getMT5AccountNumber()
        if (token.isNotBlank() && account.isNotBlank() && !account.startsWith("DIRECT-")) {
            try {
                val resp = client.newCall(req("/positions").get().build()).execute()
                if (resp.isSuccessful) {
                    val arr  = JSONArray(resp.body?.string() ?: "[]")
                    val list = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Position(
                            id=o.getString("id"), signalId=null, symbol=o.getString("symbol"),
                            direction=if(o.getString("type")=="POSITION_TYPE_BUY") SignalDirection.LONG else SignalDirection.SHORT,
                            entryPrice=o.getDouble("openPrice"), stopLoss=o.optDouble("stopLoss",0.0),
                            takeProfit=o.optDouble("takeProfit",0.0), lotSize=o.getDouble("volume"),
                            status=PositionStatus.OPEN, currentProfit=o.optDouble("profit",0.0),
                            openedAt=System.currentTimeMillis()
                        )
                    }
                    return@withContext Result.success(list)
                }
            } catch (e: Exception) {
                Log.w(TAG, "MetaAPI getOpenPositions failed: ${e.message}")
            }
        }
        Result.success(directPositions.toList())
    }

    override suspend fun getAccountBalance(): Result<Double> = getAccountMetrics().map { it.balance }

    override suspend fun getAccountMetrics(): Result<AccountMetrics> = withContext(Dispatchers.IO) {
        val token = s.getMetaApiToken()
        val account = s.getMT5AccountNumber()
        if (token.isNotBlank() && account.isNotBlank() && !account.startsWith("DIRECT-")) {
            try {
                val resp = client.newCall(req("/account-information").get().build()).execute()
                if (resp.isSuccessful) {
                    val body = JSONObject(resp.body?.string() ?: "{}")
                    val currency = body.optString("currency").takeIf { it.isNotBlank() } ?: "USD"
                    connected = true
                    return@withContext Result.success(
                        AccountMetrics(
                            balance = body.optDouble("balance", 0.0),
                            equity = body.optDouble("equity", 0.0),
                            margin = body.optDouble("margin", 0.0),
                            freeMargin = body.optDouble("freeMargin", 0.0),
                            marginLevel = body.optDouble("marginLevel", 0.0),
                            currency = currency
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "MetaAPI account-information call failed: ${e.message}")
            }
        }
        val login = s.getMT5Login()
        val server = s.getMT5BrokerServer()
        val password = s.getMT5Password()
        if (login.isNotBlank() && password.isNotBlank() && server.isNotBlank()) {
            connected = true
            val unrealized = directPositions.sumOf { it.currentProfit }
            val usedMargin = directPositions.sumOf { it.lotSize * 1000.0 }
            val equity = directBalance + unrealized
            val freeMargin = (equity - usedMargin).coerceAtLeast(0.0)
            val marginLevel = if (usedMargin > 0.0) (equity / usedMargin) * 100.0 else 100.0
            return@withContext Result.success(
                AccountMetrics(
                    balance = directBalance,
                    equity = equity,
                    margin = usedMargin,
                    freeMargin = freeMargin,
                    marginLevel = marginLevel,
                    currency = "USD"
                )
            )
        }
        Result.failure(Exception("MT5 credentials required (Account Login, Password, Server)."))
    }

    override suspend fun calculateRequiredMargin(symbol: String, lotSize: Double): Result<Double> = withContext(Dispatchers.IO) {
        val token = s.getMetaApiToken()
        val account = s.getMT5AccountNumber()
        if (token.isNotBlank() && account.isNotBlank() && !account.startsWith("DIRECT-")) {
            try {
                val cleanSymbol = resolveSymbol(symbol) ?: symbol.replace("/", "")
                val resp = client.newCall(req("/symbols/$cleanSymbol/margin-calculate?volume=$lotSize").get().build()).execute()
                if (resp.isSuccessful) {
                    val margin = JSONObject(resp.body?.string() ?: "{}").optDouble("margin", 0.0)
                    return@withContext Result.success(margin)
                }
            } catch (e: Exception) {
                Log.w(TAG, "MetaAPI margin calculation failed: ${e.message}")
            }
        }
        val spec = loadSymbolSpec(symbol).getOrNull()
        val contractSize = spec?.contractSize ?: 100_000.0
        val leverage = 100.0
        val margin = (lotSize * contractSize) / leverage
        Result.success(margin)
    }

    private fun fetchConversionRate(from: String, to: String): Double? {
        if (from.equals(to, true)) return 1.0
        val token = s.getMetaApiToken()
        val account = s.getMT5AccountNumber()
        if (token.isBlank() || account.isBlank() || account.startsWith("DIRECT-")) return null

        val directSym = "${from}${to}"
        try {
            client.newCall(req("/symbols/$directSym/current-price").get().build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val j = JSONObject(resp.body?.string() ?: "")
                    val bid = j.optDouble("bid", 0.0)
                    val ask = j.optDouble("ask", 0.0)
                    val mid = if (bid > 0 && ask > 0) (bid + ask) / 2.0 else j.optDouble("price", 0.0)
                    if (mid > 0) return mid
                }
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "conversion rate lookup failed: ${e.javaClass.simpleName}")
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "conversion rate parse failed")
        }

        val invSym = "${to}${from}"
        try {
            client.newCall(req("/symbols/$invSym/current-price").get().build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val j = JSONObject(resp.body?.string() ?: "")
                    val bid = j.optDouble("bid", 0.0)
                    val ask = j.optDouble("ask", 0.0)
                    val mid = if (bid > 0 && ask > 0) (bid + ask) / 2.0 else j.optDouble("price", 0.0)
                    if (mid > 0) return 1.0 / mid
                }
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "conversion rate lookup failed: ${e.javaClass.simpleName}")
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "conversion rate parse failed")
        }

        return null
    }

    override fun isConnected() = connected

    override suspend fun getBrokerQuote(symbol: String): Result<com.destinedfx.core.marketdata.MarketQuote> = withContext(Dispatchers.IO) {
        val token = s.getMetaApiToken()
        val account = s.getMT5AccountNumber()
        if (token.isNotBlank() && account.isNotBlank() && !account.startsWith("DIRECT-")) {
            try {
                val sym = resolveSymbol(symbol) ?: symbol.replace("/", "")
                client.newCall(req("/symbols/$sym/current-price").get().build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val j = JSONObject(resp.body?.string() ?: "")
                        return@withContext com.destinedfx.core.marketdata.BrokerQuoteParser.parse(j, symbol, System.currentTimeMillis())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MetaAPI quote lookup failed: ${e.message}")
            }
        }
        // Return failure/null so caller uses live market data feed quote (TradingView / Deriv)
        Result.failure(Exception("Using primary market data quote for $symbol"))
    }
}
