package com.example.data.trade

import android.util.Log

/**
 * Retired compatibility surface.
 *
 * This legacy engine connected to a hard-coded single-instrument exchange ticker and did
 * not authenticate an MT5 account or provide broker contract metadata. It must never be
 * mistaken for the DestinedFX analyzer or a broker terminal.
 */
@Deprecated("Use provider-verified market data and the managed MT5 terminal bridge.")
class MT5MasterCoreEngine(private val mainListener: CoreDataEngineListener) {
    private val tag = "MASTER_CORE_ENGINE"

    interface CoreDataEngineListener {
        fun onCoreEngineReady()
        fun onRealTimeMetricsUpdate(symbol: String, currentPrice: Double, algorithmIndicatorValue: Double, tradeSignal: String)
        fun onEngineFailure(errorLog: String)
    }

    fun igniteEngine() {
        Log.w(tag, "Legacy hard-coded market socket is disabled; no broker or analyzer data was connected.")
    }

    fun terminateEnginePipeline() = Unit
}
