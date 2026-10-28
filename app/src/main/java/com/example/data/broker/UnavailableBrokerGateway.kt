package com.example.data.broker

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.marketdata.MarketQuote
import com.example.domain.model.CanonicalTradePlan
import com.example.domain.model.Position
import com.example.domain.repository.AccountMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit fail-closed adapter for broker integrations not implemented by this build. */
class UnavailableBrokerGateway(
    override val brokerType: BrokerType,
    override val displayName: String
) : BrokerGateway {
    private val unavailable = IllegalStateException("$displayName is not implemented in this app build; no connection or order was attempted.")
    private val status = MutableStateFlow(BrokerConnectionStatus.ERROR)
    private val metrics = MutableStateFlow<AccountMetrics?>(null)
    private val positions = MutableStateFlow<List<Position>>(emptyList())

    override val connectionStatus: StateFlow<BrokerConnectionStatus> = status.asStateFlow()
    override val accountMetrics: StateFlow<AccountMetrics?> = metrics.asStateFlow()
    override val openPositions: StateFlow<List<Position>> = positions.asStateFlow()

    override suspend fun connect(): Result<Boolean> = Result.failure(unavailable)
    override suspend fun disconnect() = Unit
    override suspend fun executeTrade(plan: CanonicalTradePlan): Result<String> = Result.failure(unavailable)
    override suspend fun closePosition(positionId: String): Result<Boolean> = Result.failure(unavailable)
    override suspend fun getBrokerQuote(symbol: String): Result<MarketQuote?> = Result.failure(unavailable)
    override suspend fun loadSymbolSpec(symbol: String): Result<InstrumentSpec?> = Result.failure(unavailable)
    override suspend fun calculateRequiredMargin(symbol: String, lots: Double): Result<Double> = Result.failure(unavailable)
}
