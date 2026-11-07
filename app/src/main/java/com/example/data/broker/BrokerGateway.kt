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
    /** Account values with explicit provenance and freshness evidence. */
    val accountSnapshot: StateFlow<BrokerAccountSnapshot?>
    val openPositions: StateFlow<List<Position>>

    suspend fun connect(): Result<Boolean>
    suspend fun disconnect()
    suspend fun executeTrade(plan: CanonicalTradePlan): Result<String>
    /** Adapters must derive this decision from the shared ModeArmingPolicy and fresh account evidence. */
    fun tradeModeDecision(): ModeDecision =
        ModeDecision(false, "BLOCKED", "MODE_ARMING_POLICY_UNAVAILABLE")

    suspend fun executeTradeWhenArmed(plan: CanonicalTradePlan): Result<String> =
        executeWithModeArmingDecision(tradeModeDecision()) { executeTrade(plan) }

    suspend fun closePosition(positionId: String): Result<Boolean>
    suspend fun getBrokerQuote(symbol: String): Result<MarketQuote?>
    suspend fun loadSymbolSpec(symbol: String): Result<InstrumentSpec?>
    suspend fun calculateRequiredMargin(symbol: String, lots: Double): Result<Double>
    fun isConnected(): Boolean = connectionStatus.value == BrokerConnectionStatus.CONNECTED
}

internal suspend fun executeWithModeArmingDecision(
    decision: ModeDecision,
    submit: suspend () -> Result<String>
): Result<String> = if (decision.allowed) {
    submit()
} else {
    Result.failure(IllegalStateException("MODE_ARMING_BLOCKED: ${decision.reason}"))
}
