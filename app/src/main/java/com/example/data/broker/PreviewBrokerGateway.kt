package com.example.data.broker

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.marketdata.MarketQuote
import com.example.domain.model.CanonicalTradePlan
import com.example.domain.model.Position
import com.example.domain.repository.AccountMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PreviewBrokerGateway : BrokerGateway {
    override val brokerType: BrokerType = BrokerType.DERIV
    override val displayName: String = "Preview only (no broker)"
    override val connectionStatus: StateFlow<BrokerConnectionStatus> =
        MutableStateFlow(BrokerConnectionStatus.DISCONNECTED)
    override val accountMetrics: StateFlow<AccountMetrics?> =
        MutableStateFlow(null)
    override val accountSnapshot: StateFlow<BrokerAccountSnapshot?> =
        MutableStateFlow(BrokerAccountSnapshot.unavailable("PREVIEW", BrokerConnectionStatus.DISCONNECTED, "PREVIEW_ONLY"))
    override val openPositions: StateFlow<List<Position>> =
        MutableStateFlow(emptyList())

    override suspend fun connect(): Result<Boolean> =
        Result.failure(IllegalStateException("PREVIEW_ONLY: no broker authentication is available"))
    override suspend fun disconnect() {}
    override suspend fun executeTrade(plan: CanonicalTradePlan): Result<String> =
        Result.failure(IllegalStateException("PREVIEW_ONLY: simulated orders are disabled"))
    override suspend fun closePosition(positionId: String): Result<Boolean> =
        Result.failure(IllegalStateException("PREVIEW_ONLY: simulated closes are disabled"))
    override suspend fun getBrokerQuote(symbol: String): Result<MarketQuote?> =
        Result.failure(IllegalStateException("PREVIEW_ONLY: no broker quote is available"))
    override suspend fun loadSymbolSpec(symbol: String): Result<InstrumentSpec?> = Result.success(null)
    override suspend fun calculateRequiredMargin(symbol: String, lots: Double): Result<Double> =
        Result.failure(IllegalStateException("PREVIEW_ONLY: broker margin is unavailable"))
}
