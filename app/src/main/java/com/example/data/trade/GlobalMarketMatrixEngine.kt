package com.example.data.trade

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GlobalMarketMatrixEngine(private val uiListener: MatrixUiListener) {
    interface MatrixUiListener {
        fun onEngineOnline()
        fun onLiveAssetTelemetry(symbol: String, assetType: String, price: Double, optimalLots: Double, cashRisk: Double, advice: String)
        fun onEngineFault(error: String)
    }

    fun configureRiskParameters(investment: Double, risk: Double, rawSlDistance: Double, rawTpDistance: Double) {}

    fun startGlobalStream() {
        uiListener.onEngineFault("DATA UNAVAILABLE: No authorized provider configured.")
    }

    fun haltEngine() {}
}
