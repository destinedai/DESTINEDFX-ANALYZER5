package com.example.data.trade

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MT5MasterCoreEngine(private val mainListener: CoreDataEngineListener) {
    private val TAG = "MASTER_CORE_ENGINE"
    private val RAW_FEED_STREAM_URL = "wss://stream.binance.com:9443/ws/btcusdt@ticker"

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indestructible streaming socket life
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()
        
    private var activeSocket: WebSocket? = null
    
    // In-memory array to process indicator analysis locally without a database
    private val rollingPriceHistory = java.util.ArrayList<Double>()
    private val ANALYSIS_PERIOD = 14 // Hardcore 14-period analysis matrix

    interface CoreDataEngineListener {
        fun onCoreEngineReady()
        fun onRealTimeMetricsUpdate(symbol: String, currentPrice: Double, algorithmIndicatorValue: Double, tradeSignal: String)
        fun onEngineFailure(errorLog: String)
    }

    fun igniteEngine() {
        val connectionPayload = Request.Builder().url(RAW_FEED_STREAM_URL).build()

        activeSocket = client.newWebSocket(connectionPayload, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Raw Data Protocol Established. No fetcher middleware active.")
                mainListener.onCoreEngineReady()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                crunchLiveMarketPayload(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Pipeline collapsed: ${t.javaClass.simpleName}")
                mainListener.onEngineFailure(t.message ?: "Unknown socket failure")
            }
        })
    }

    @Synchronized
    private fun crunchLiveMarketPayload(rawJsonString: String) {
        try {
            val dataCluster = JSONObject(rawJsonString)
            
            // Extracting raw telemetry fields directly from the data stream
            val instrument = dataCluster.optString("s") // Symbol identifier
            val currentPrice = dataCluster.optDouble("c", 0.0) // Current closing price

            if (currentPrice == 0.0) return

            // Push price data point into local rolling register memory
            rollingPriceHistory.add(currentPrice)
            if (rollingPriceHistory.size > ANALYSIS_PERIOD) {
                rollingPriceHistory.removeAt(0) // Prune oldest entry to keep collection tight
            }

            // Fire the localized analysis matrix
            val calculatedMovingAverage = calculateLocalizedMovingAverage()
            val generatedSignal = interpretTechnicalState(currentPrice, calculatedMovingAverage)

            // Export pure telemetry data packets back to UI Context instantly
            mainListener.onRealTimeMetricsUpdate(instrument, currentPrice, calculatedMovingAverage, generatedSignal)

        } catch (e: org.json.JSONException) {
            Log.e(TAG, "Malformed tick payload ignored")
        }
    }

    // Mathematical Indicator Logic executed directly on the mobile CPU
    private fun calculateLocalizedMovingAverage(): Double {
        if (rollingPriceHistory.size < ANALYSIS_PERIOD) {
            return 0.0 // Wait until register memory array fills up completely
        }
        
        var mathematicalSum = 0.0
        for (priceNode in rollingPriceHistory) {
            mathematicalSum += priceNode
        }
        return mathematicalSum / rollingPriceHistory.size
    }

    // Local decision matrix engine
    private fun interpretTechnicalState(spotPrice: Double, indicatorValue: Double): String {
        if (indicatorValue == 0.0) return "CALIBRATING (need 14 ticks)"
        
        // Strict trend parsing algorithms
        return if (spotPrice > indicatorValue + (indicatorValue * 0.0005)) {
            "ABOVE 14-TICK AVERAGE (information only, not a signal)"
        } else if (spotPrice < indicatorValue - (indicatorValue * 0.0005)) {
            "BELOW 14-TICK AVERAGE (information only, not a signal)"
        } else {
            "NEAR 14-TICK AVERAGE"
        }
    }

    fun terminateEnginePipeline() {
        activeSocket?.close(1000, "System operator manual override")
        activeSocket = null
    }
}
