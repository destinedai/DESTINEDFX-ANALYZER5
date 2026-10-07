package com.example.data.trade

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GlobalMarketMatrixEngine(private val uiListener: MatrixUiListener) {
    private val TAG = "GLOBAL_MATRIX_ENGINE"
    private val MULTI_ASSET_WS_URL = "wss://ws.twelvedata.com/v1/quotes/price?apikey="

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    // Local Memory Configuration Parameters set by user inputs
    private var userInvestment: Double = 0.0
    private var riskPercent: Double = 1.0
    private var targetStopLossDistance: Double = 0.0 // Raw distance or pips user wants to risk
    private var targetTakeProfitDistance: Double = 0.0

    interface MatrixUiListener {
        fun onEngineOnline()
        fun onLiveAssetTelemetry(symbol: String, assetType: String, price: Double, optimalLots: Double, cashRisk: Double, advice: String)
        fun onEngineFault(error: String)
    }

    // Set or update risk preferences instantly based on user input fields
    fun configureRiskParameters(investment: Double, risk: Double, rawSlDistance: Double, rawTpDistance: Double) {
        this.userInvestment = investment
        this.riskPercent = risk
        this.targetStopLossDistance = rawSlDistance
        this.targetTakeProfitDistance = rawTpDistance
    }

    fun startGlobalStream(apiKey: String) {
        if (apiKey.isBlank()) { uiListener.onEngineFault("DATA UNAVAILABLE: Twelve Data API key not configured"); return }
        val finalUrl = "$MULTI_ASSET_WS_URL$apiKey"
        val request = Request.Builder().url(finalUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to Global Feed. Routing subscriptions...")
                
                try {
                    val subscribeCmd = JSONObject().apply {
                        put("action", "subscribe")
                        val params = JSONObject().apply {
                            put("symbols", "EUR/USD,XAU/USD,XAG/USD,CL")
                        }
                        put("params", params)
                    }
                    webSocket.send(subscribeCmd.toString())
                    uiListener.onEngineOnline()
                } catch (e: Exception) {
                    uiListener.onEngineFault("Subscription payload syntax error: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseAndAnalyzeTick(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                uiListener.onEngineFault(t.message ?: "TwelveData socket connection failure")
            }
        })
    }

    private fun parseAndAnalyzeTick(jsonPayload: String) {
        try {
            val tick = JSONObject(jsonPayload)
            if (!tick.has("symbol") || !tick.has("price")) return

            val symbol = tick.getString("symbol")
            val currentPrice = tick.getDouble("price")

            // 1. Asset Type Classification
            val assetType = when {
                symbol.contains("XAU") || symbol.contains("GOLD") -> "PRECIOUS_METAL_GOLD"
                symbol.contains("XAG") || symbol.contains("SILVER") -> "PRECIOUS_METAL_SILVER"
                symbol.equals("CL") || symbol.contains("CRUDE") || symbol.contains("OIL") -> "ENERGY_COMMODITY"
                com.example.data.market.BinanceSymbols.toBinance(symbol) != null || com.destinedfx.core.instrument.AssetClassifier.of(symbol) == com.destinedfx.core.marketdata.AssetClass.CRYPTO -> "CRYPTO"
                else -> "FOREX"
            }

            // Position sizing is NOT computed here: it needs the broker symbol specification and lives only in RiskEngine.
            uiListener.onLiveAssetTelemetry(symbol, assetType, currentPrice, 0.0, 0.0,
                "Sizing unavailable here: use the Risk screen (requires broker tick size/value and volume limits).")

        } catch (e: org.json.JSONException) {
            Log.e(TAG, "Malformed tick ignored")
        }
    }

    fun haltEngine() {
        webSocket?.close(1000, "Operator manually closed connections")
        webSocket = null
    }
}
