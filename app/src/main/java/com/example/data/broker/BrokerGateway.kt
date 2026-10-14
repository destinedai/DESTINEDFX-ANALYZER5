package com.example.data.broker

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.marketdata.MarketQuote
import com.example.domain.model.CanonicalTradePlan
import com.example.domain.model.Position
import com.example.domain.repository.AccountMetrics
import kotlinx.coroutines.flow.StateFlow

enum class BrokerType {
    DERIV,
    MT5,
    OANDA
}

enum class BrokerConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    DEGRADED,
    AUTH_FAILED,
    ERROR,
    RECONNECTING
}

interface BrokerGateway {
    val brokerType: BrokerType
    val displayName: String
    val connectionStatus: StateFlow<BrokerConnectionStatus>
    val accountMetrics: StateFlow<AccountMetrics?>
    val openPositions: StateFlow<List<Position>>

    suspend fun connect(): Result<Boolean>
    suspend fun disconnect()
    suspend fun executeTrade(plan: CanonicalTradePlan): Result<String>
    suspend fun closePosition(positionId: String): Result<Boolean>
    suspend fun getBrokerQuote(symbol: String): Result<MarketQuote?>
    suspend fun loadSymbolSpec(symbol: String): Result<InstrumentSpec?>
    suspend fun calculateRequiredMargin(symbol: String, lots: Double): Result<Double>
    fun isConnected(): Boolean = connectionStatus.value == BrokerConnectionStatus.CONNECTED
}
