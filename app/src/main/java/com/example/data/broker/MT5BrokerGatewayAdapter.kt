package com.example.data.broker

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.marketdata.MarketQuote
import com.example.data.mt5.MT5GatewayRepository
import com.example.domain.model.CanonicalTradePlan
import com.example.domain.model.Position
import com.example.domain.repository.AccountMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MT5BrokerGatewayAdapter(
    private val mt5Repo: MT5GatewayRepository
) : BrokerGateway {

    override val brokerType: BrokerType = BrokerType.MT5
    override val displayName: String = "MetaTrader 5 (managed terminal)"

    private val _connectionStatus = MutableStateFlow(BrokerConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<BrokerConnectionStatus> = _connectionStatus.asStateFlow()

    private val _accountMetrics = MutableStateFlow<AccountMetrics?>(null)
    override val accountMetrics: StateFlow<AccountMetrics?> = _accountMetrics.asStateFlow()
    private val _accountSnapshot = MutableStateFlow<BrokerAccountSnapshot?>(null)
    override val accountSnapshot: StateFlow<BrokerAccountSnapshot?> = _accountSnapshot.asStateFlow()

    private val _openPositions = MutableStateFlow<List<Position>>(emptyList())
    override val openPositions: StateFlow<List<Position>> = _openPositions.asStateFlow()

    override suspend fun connect(): Result<Boolean> {
        _connectionStatus.value = BrokerConnectionStatus.ERROR
        _accountMetrics.value = null
        _accountSnapshot.value = BrokerAccountSnapshot.unavailable("MT5", BrokerConnectionStatus.ERROR, "DIRECT_CONNECTION_DISABLED")
        _openPositions.value = emptyList()
        return Result.failure(IllegalStateException("Direct MT5 connection is disabled; authenticate through the configured managed terminal service."))
    }

    override suspend fun disconnect() {
        mt5Repo.disconnectSession()
        _connectionStatus.value = BrokerConnectionStatus.DISCONNECTED
        _accountMetrics.value = null
        _accountSnapshot.value = BrokerAccountSnapshot.unavailable("MT5", BrokerConnectionStatus.DISCONNECTED)
        _openPositions.value = emptyList()
    }

    override suspend fun executeTrade(plan: CanonicalTradePlan): Result<String> {
        return Result.failure(IllegalStateException("Direct MT5 orders are disabled; orders must be issued by the authenticated managed EA."))
    }

    override suspend fun closePosition(positionId: String): Result<Boolean> {
        return Result.failure(IllegalStateException("Direct MT5 position control is disabled in this app; manage existing positions on the broker terminal."))
    }

    override suspend fun getBrokerQuote(symbol: String): Result<MarketQuote?> {
        return mt5Repo.getBrokerQuote(symbol)
    }

    override suspend fun loadSymbolSpec(symbol: String): Result<InstrumentSpec?> {
        return mt5Repo.loadSymbolSpec(symbol)
    }

    override suspend fun calculateRequiredMargin(symbol: String, lots: Double): Result<Double> {
        return mt5Repo.calculateRequiredMargin(symbol, lots)
    }

    fun setAccountBalance(balance: Double) {
        mt5Repo.setAccountBalance(balance)
    }

    suspend fun refreshPositionsWithLiveQuotes(priceMap: Map<String, Double>) {
        val openList = mt5Repo.getOpenPositions().getOrNull() ?: emptyList()
        for (pos in openList) {
            val cmp = priceMap[pos.symbol] ?: continue
            mt5Repo.updatePositionProfit(pos.id, cmp)
        }
        val metricsRes = mt5Repo.getAccountMetrics()
        if (metricsRes.isSuccess) {
            val metrics = metricsRes.getOrThrow()
            _accountMetrics.value = metrics
            _accountSnapshot.value = if (_connectionStatus.value == BrokerConnectionStatus.CONNECTED) {
                BrokerAccountSnapshot(
                    providerId = "MT5",
                    origin = DataOrigin.BROKER_API,
                    status = _connectionStatus.value,
                    retrievedAtMs = System.currentTimeMillis(),
                    environment = null,
                    currency = metrics.currency,
                    balance = metrics.balance,
                    equity = metrics.equity,
                    margin = metrics.margin,
                    freeMargin = metrics.freeMargin,
                    validity = DataValidity.FRESH
                )
            } else {
                BrokerAccountSnapshot.unavailable("MT5", _connectionStatus.value, "SESSION_NOT_CONNECTED")
            }
        }
        refreshPositions()
    }

    private suspend fun refreshPositions() {
        val posRes = mt5Repo.getOpenPositions()
        if (posRes.isSuccess) {
            _openPositions.value = posRes.getOrThrow()
        }
    }
}
