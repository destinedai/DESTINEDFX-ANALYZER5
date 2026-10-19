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
    override val displayName: String = "Deriv Demo (Preview)"
    override val connectionStatus: StateFlow<BrokerConnectionStatus> =
        MutableStateFlow(BrokerConnectionStatus.DISCONNECTED)
    override val accountMetrics: StateFlow<AccountMetrics?> =
        MutableStateFlow(AccountMetrics(balance = 10.0, equity = 10.0, margin = 0.0, freeMargin = 10.0, marginLevel = 0.0, currency = "USD"))
    override val openPositions: StateFlow<List<Position>> =
        MutableStateFlow(emptyList())

    override suspend fun connect(): Result<Boolean> = Result.success(true)
    override suspend fun disconnect() {}
    override suspend fun executeTrade(plan: CanonicalTradePlan): Result<String> = Result.success("preview_order_1")
    override suspend fun closePosition(positionId: String): Result<Boolean> = Result.success(true)
    override suspend fun getBrokerQuote(symbol: String): Result<MarketQuote?> = Result.success(null)
    override suspend fun loadSymbolSpec(symbol: String): Result<InstrumentSpec?> = Result.success(null)
    override suspend fun calculateRequiredMargin(symbol: String, lots: Double): Result<Double> = Result.success(0.0)
}
