package com.example.data.broker

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.marketdata.MarketQuote
import com.example.data.mt5.MT5GatewayRepository
import com.example.domain.model.CanonicalTradePlan
import com.example.domain.model.Position
import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal
import com.example.domain.repository.AccountMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class MT5BrokerGatewayAdapter(
    private val mt5Repo: MT5GatewayRepository
) : BrokerGateway {

    override val brokerType: BrokerType = BrokerType.MT5
    override val displayName: String = "MetaTrader 5 (MetaAPI)"

    private val _connectionStatus = MutableStateFlow(BrokerConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<BrokerConnectionStatus> = _connectionStatus.asStateFlow()

    private val _accountMetrics = MutableStateFlow<AccountMetrics?>(null)
    override val accountMetrics: StateFlow<AccountMetrics?> = _accountMetrics.asStateFlow()

    private val _openPositions = MutableStateFlow<List<Position>>(emptyList())
    override val openPositions: StateFlow<List<Position>> = _openPositions.asStateFlow()

    override suspend fun connect(): Result<Boolean> {
        _connectionStatus.value = BrokerConnectionStatus.CONNECTING
        val res = mt5Repo.getAccountMetrics()
        return if (res.isSuccess) {
            _accountMetrics.value = res.getOrThrow()
            _connectionStatus.value = BrokerConnectionStatus.CONNECTED
            refreshPositions()
            Result.success(true)
        } else {
            _connectionStatus.value = BrokerConnectionStatus.AUTH_FAILED
            Result.failure(res.exceptionOrNull() ?: Exception("MT5 connect failed"))
        }
    }

    override suspend fun disconnect() {
        _connectionStatus.value = BrokerConnectionStatus.DISCONNECTED
        _accountMetrics.value = null
        _openPositions.value = emptyList()
    }

    override suspend fun executeTrade(plan: CanonicalTradePlan): Result<String> {
        val sig = TradeSignal(
            id = UUID.randomUUID().toString(),
            symbol = plan.symbol,
            timeframe = plan.timeframe,
            direction = plan.direction,
            entryPrice = plan.entry,
            stopLoss = plan.stopLoss,
            takeProfit = plan.takeProfit,
            confidenceScore = plan.confluenceScore,
            rationale = plan.reason,
            timestamp = plan.timestamp,
            invalidationLevel = plan.invalidation,
            entryReason = plan.reason,
            stopLossReason = "SL",
            takeProfitReason = "TP",
            nearestHighYieldZone = "",
            profitZones = emptyList(),
            keyLevels = emptyList(),
            strategyScores = emptyMap(),
            signalState = plan.status,
            executable = true,
            blockReasons = emptyList(),
            reasons = listOf(plan.reason),
            dataSource = plan.provider,
            dataHealth = plan.dataHealth,
            mtfSummary = "",
            spreadText = "${plan.spread}",
            sessionLabel = "",
            newsStatus = "CLEAR",
            knowledge = emptyList(),
            strategyVersion = plan.strategyVersion,
            analysisLoadMs = 0L,
            analysisComputeMs = 0L,
            analysisTotalMs = 0L,
            cacheHit = false
        )
        return mt5Repo.executeTrade(sig, plan.positionSize, UUID.randomUUID().toString())
    }

    override suspend fun closePosition(positionId: String): Result<Boolean> {
        return mt5Repo.closeTrade(positionId)
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

    private suspend fun refreshPositions() {
        val posRes = mt5Repo.getOpenPositions()
        if (posRes.isSuccess) {
            _openPositions.value = posRes.getOrThrow()
        }
    }
}
