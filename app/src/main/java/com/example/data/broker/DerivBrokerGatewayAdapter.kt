package com.example.data.broker

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.instrument.SpecRegistry
import com.destinedfx.core.marketdata.AssetClass
import com.destinedfx.core.marketdata.MarketQuote
import com.example.data.deriv.DerivBrokerGateway
import com.example.data.deriv.DerivConnectionState
import com.example.domain.model.CanonicalTradePlan
import com.example.domain.model.Position
import com.example.domain.repository.AccountMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DerivBrokerGatewayAdapter(
    private val derivGateway: DerivBrokerGateway
) : BrokerGateway {

    override val brokerType: BrokerType = BrokerType.DERIV
    override val displayName: String = "Deriv WebSocket"

    private val _connectionStatus = MutableStateFlow(BrokerConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<BrokerConnectionStatus> = _connectionStatus.asStateFlow()

    override val accountMetrics: StateFlow<AccountMetrics?> = derivGateway.accountMetrics
    override val openPositions: StateFlow<List<Position>> = derivGateway.openPositions

    init {
        CoroutineScope(Dispatchers.IO).launch {
            derivGateway.connectionState.collect { s ->
                _connectionStatus.value = when (s) {
                    DerivConnectionState.CONNECTED -> BrokerConnectionStatus.CONNECTED
                    DerivConnectionState.CONNECTING -> BrokerConnectionStatus.CONNECTING
                    DerivConnectionState.AUTHENTICATING -> BrokerConnectionStatus.AUTHENTICATING
                    DerivConnectionState.AUTH_FAILED -> BrokerConnectionStatus.AUTH_FAILED
                    DerivConnectionState.ERROR -> BrokerConnectionStatus.ERROR
                    DerivConnectionState.RECONNECTING -> BrokerConnectionStatus.RECONNECTING
                    DerivConnectionState.DISCONNECTED -> BrokerConnectionStatus.DISCONNECTED
                }
            }
        }
    }

    override suspend fun connect(): Result<Boolean> {
        val res = derivGateway.connectAndAuthorize()
        return if (res.isSuccess) Result.success(true) else Result.failure(res.exceptionOrNull() ?: Exception("Deriv connection failed"))
    }

    override suspend fun disconnect() {
        derivGateway.disconnect()
    }

    override suspend fun executeTrade(plan: CanonicalTradePlan): Result<String> {
        @Suppress("UNUSED_VARIABLE")
        val unusedPlan = plan
        // CanonicalTradePlan contains Forex/CFD lots, chart-price SL/TP, and a 2R metric.
        // Those values are not a validated Deriv Multiplier contract specification. Keep the
        // generic trade pipeline fail-closed until a product-specific signal/risk mapping exists.
        return Result.failure(Exception(
            "UNSUPPORTED_PRODUCT_MAPPING: Forex/CFD trade plans cannot be submitted as Deriv Multipliers. No purchase was sent."
        ))

    }

    override suspend fun closePosition(positionId: String): Result<Boolean> {
        val contractId = positionId.toLongOrNull()
            ?: return Result.failure(Exception("CLOSE_FAILED: Invalid Deriv contract ID: $positionId"))
        val res = derivGateway.sellContract(contractId)
        return if (res.isSuccess) {
            Result.success(true)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("CLOSE_FAILED: Position close unconfirmed by broker"))
        }
    }

    override suspend fun getBrokerQuote(symbol: String): Result<MarketQuote?> {
        if (!isConnected()) {
            return Result.failure(Exception("BROKER_DISCONNECTED: Deriv broker is not connected."))
        }
        val derivSymbol = DerivInstrumentResolver.resolve(symbol)
            ?: return Result.failure(Exception("UNSUPPORTED_INSTRUMENT: Symbol '$symbol' has no mapping on Deriv"))
        val res = derivGateway.getQuote(derivSymbol)
        return if (res.isSuccess) {
            Result.success(res.getOrThrow())
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("DATA_UNAVAILABLE: Deriv quote failed for $symbol"))
        }
    }

    override suspend fun loadSymbolSpec(symbol: String): Result<InstrumentSpec?> {
        val spec = SpecRegistry.get(symbol) ?: run {
            val isSynthetic = symbol.startsWith("R_") || symbol.startsWith("1HZ") || symbol.startsWith("BOOM") || symbol.startsWith("CRASH")
            if (isSynthetic) {
                InstrumentSpec(
                    symbol = symbol,
                    assetClass = AssetClass.INDICES,
                    digits = 2,
                    tickSize = 0.01,
                    tickValue = 0.01,
                    contractSize = 1.0,
                    volumeMin = 0.01,
                    volumeMax = 100.0,
                    volumeStep = 0.01,
                    stopsLevelPoints = 0,
                    profitCurrency = "USD",
                    baseCurrency = "USD",
                    source = "Deriv"
                )
            } else null
        }
        return Result.success(spec)
    }

    override suspend fun calculateRequiredMargin(symbol: String, lots: Double): Result<Double> {
        // For Deriv contracts, required margin is simply the stake/lot budget
        return Result.success(lots)
    }
}
