package com.example.data.mt5

import android.util.Log
import com.example.domain.model.*
import com.example.domain.repository.AccountMetrics
import com.example.domain.repository.MT5Repository
import com.example.security.SecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        private const val BASE = "https://mt-client-api-v1.london.agiliumtrade.ai"
        private val JSON_MT    = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private var connected = false

    private fun req(path: String) = Request.Builder()
        .url("$BASE/users/current/accounts/${s.getMT5AccountNumber()}$path")
        .addHeader("auth-token", s.getMetaApiToken())

    private var cachedBrokerSymbols: List<String> = emptyList()

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
            if (token.isBlank() || account.isBlank()) {
                return@withContext Result.failure(Exception("MetaAPI credentials not configured"))
            }
            val sym = resolveSymbol(symbol) ?: return@withContext Result.failure(Exception("BROKER SYMBOL NOT FOUND for $symbol: set it in Settings"))
            client.newCall(req("/symbols/$sym/specification").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("SPEC UNAVAILABLE: HTTP ${resp.code}"))
                val acc = getAccountMetrics().getOrNull()
                val accountCcy = acc?.currency ?: return@withContext Result.failure(Exception("SPEC UNAVAILABLE: account currency unavailable"))
                if (accountCcy.isBlank()) return@withContext Result.failure(Exception("SPEC UNAVAILABLE: account currency unavailable"))
                val r = com.destinedfx.core.instrument.SpecParser.fromJson(
                    JSONObject(resp.body?.string() ?: ""),
                    com.destinedfx.core.instrument.AssetClassifier.of(symbol),
                    accountCcy,
                    conversionRate = { from, to -> fetchConversionRate(from, to) },
                    source = "MetaApi symbol specification"
                )
                when (r) {
                    is com.destinedfx.core.instrument.SpecResult.Ok -> {
                        com.destinedfx.core.instrument.SpecRegistry.put(r.spec)
                        Result.success(r.spec)
                    }
                    is com.destinedfx.core.instrument.SpecResult.Unavailable -> Result.failure(Exception("SPEC UNAVAILABLE: ${r.reason}"))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun executeTrade(signal: TradeSignal, lotSize: Double, requestId: String): Result<String> = withContext(Dispatchers.IO) {
        if (lotSize <= 0.0) return@withContext Result.failure(Exception("Invalid volume"))
        val token = s.getMetaApiToken()
        if (token.isBlank()) return@withContext Result.failure(
            Exception("MetaAPI token not configured. Go to Settings > Broker Connection."))
        return@withContext try {
            val brokerSym = resolveSymbol(signal.symbol) ?: return@withContext Result.failure(Exception("BROKER SYMBOL NOT FOUND for ${signal.symbol}: set it in Settings"))
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
                Result.success(JSONObject(raw).optString("orderId","UNKNOWN"))
            } else {
                Log.e(TAG,"Execute failed HTTP ${resp.code}")
                Result.failure(Exception("MT5 rejected: HTTP ${resp.code}"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun closeTrade(ticketId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("actionType","POSITION_CLOSE_ID"); put("positionId",ticketId)
            }.toString().toRequestBody(JSON_MT)
            Result.success(client.newCall(req("/trade").post(body).build()).execute().isSuccessful)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun modifyTrade(ticketId: String, newSl: Double, newTp: Double): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("actionType","POSITION_MODIFY"); put("positionId",ticketId)
                put("stopLoss",newSl); put("takeProfit",newTp)
            }.toString().toRequestBody(JSON_MT)
            Result.success(client.newCall(req("/trade").post(body).build()).execute().isSuccessful)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun getOpenPositions(): Result<List<Position>> = withContext(Dispatchers.IO) {
        try {
            val resp = client.newCall(req("/positions").get().build()).execute()
            if (!resp.isSuccessful) return@withContext Result.success(emptyList())
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
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun getAccountBalance(): Result<Double> = getAccountMetrics().map { it.balance }

    override suspend fun getAccountMetrics(): Result<AccountMetrics> = withContext(Dispatchers.IO) {
        try {
            val token = s.getMetaApiToken()
            val account = s.getMT5AccountNumber()
            if (token.isBlank() || account.isBlank()) {
                return@withContext Result.failure(Exception("MetaAPI credentials not set"))
            }
            val resp = client.newCall(req("/account-information").get().build()).execute()
            if (!resp.isSuccessful) {
                Result.failure(Exception("HTTP ${resp.code}"))
            } else {
                val body = JSONObject(resp.body?.string() ?: "{}")
                val currency = body.optString("currency").takeIf { it.isNotBlank() }
                    ?: return@withContext Result.failure(Exception("ACCOUNT CURRENCY UNAVAILABLE"))
                connected = true
                Result.success(
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
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun calculateRequiredMargin(symbol: String, lotSize: Double): Result<Double> = withContext(Dispatchers.IO) {
        try {
            val cleanSymbol = resolveSymbol(symbol) ?: return@withContext Result.failure(Exception("BROKER SYMBOL NOT FOUND for $symbol: set it in Settings"))
            val resp = client.newCall(req("/symbols/$cleanSymbol/margin-calculate?volume=$lotSize").get().build()).execute()
            if (resp.isSuccessful) {
                val margin = JSONObject(resp.body?.string() ?: "{}").optDouble("margin", 0.0)
                Result.success(margin)
            } else {
                Result.failure(Exception("Margin calculation failed: HTTP ${resp.code}"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun fetchConversionRate(from: String, to: String): Double? {
        if (from.equals(to, true)) return 1.0
        val token = s.getMetaApiToken()
        val account = s.getMT5AccountNumber()
        if (token.isBlank() || account.isBlank()) return null

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
        try {
            val token = s.getMetaApiToken()
            val account = s.getMT5AccountNumber()
            if (token.isBlank() || account.isBlank()) {
                return@withContext Result.failure(Exception("MetaAPI not configured"))
            }
            val sym = resolveSymbol(symbol) ?: return@withContext Result.failure(Exception("BROKER SYMBOL NOT FOUND for $symbol: set it in Settings"))
            client.newCall(req("/symbols/$sym/current-price").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("Broker quote unavailable: HTTP ${resp.code}"))
                val j = JSONObject(resp.body?.string() ?: "")
                com.destinedfx.core.marketdata.BrokerQuoteParser.parse(j, symbol, System.currentTimeMillis())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
