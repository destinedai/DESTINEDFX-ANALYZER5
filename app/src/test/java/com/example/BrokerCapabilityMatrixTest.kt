package com.example

import com.example.data.broker.BrokerCapabilityMatrix
import com.example.data.broker.BrokerConnectionStatus
import com.example.data.broker.BrokerType
import com.example.data.broker.CapabilityStatus
import com.example.data.broker.PreviewBrokerGateway
import com.example.domain.model.CanonicalTradePlan
import com.example.domain.model.SignalDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerCapabilityMatrixTest {
    @Test
    fun previewGatewayCannotAuthenticateOrCreateSyntheticOrder() = runBlocking {
        val gateway = PreviewBrokerGateway()
        assertEquals(BrokerConnectionStatus.DISCONNECTED, gateway.connectionStatus.value)
        assertFalse(gateway.connect().isSuccess)
        assertFalse(gateway.executeTrade(testPlan()).isSuccess)
        assertFalse(gateway.closePosition("preview_order_1").isSuccess)
        assertEquals(null, gateway.accountMetrics.value)
    }

    @Test
    fun everyBrokerHasExplicitExecutionVerdicts() {
        assertEquals(setOf(BrokerType.DERIV, BrokerType.MT5, BrokerType.OANDA),
            BrokerCapabilityMatrix.profiles.map { it.broker }.toSet())
        BrokerCapabilityMatrix.profiles.forEach { profile ->
            assertTrue(profile.capability("demo_orders")?.evidence.orEmpty().isNotBlank())
            assertTrue(profile.capability("live_orders")?.status in
                setOf(CapabilityStatus.BLOCKED, CapabilityStatus.NOT_VERIFIED, CapabilityStatus.NOT_IMPLEMENTED))
        }
        assertEquals(CapabilityStatus.NOT_IMPLEMENTED,
            BrokerCapabilityMatrix.forBroker(BrokerType.OANDA).capability("demo_orders")?.status)
        assertEquals(CapabilityStatus.BLOCKED,
            BrokerCapabilityMatrix.forBroker(BrokerType.MT5).capability("live_orders")?.status)
    }

    private fun testPlan() = CanonicalTradePlan(
        symbol = "EUR/USD",
        broker = "PREVIEW",
        provider = "test",
        timeframe = "M15",
        strategy = "test",
        direction = SignalDirection.LONG,
        status = "CANDIDATE",
        entry = 1.1,
        bid = 1.0999,
        ask = 1.1001,
        spread = 0.0002,
        stopLoss = 1.0,
        takeProfit = 1.3,
        rr = 2.0,
        riskPercent = 1.0,
        riskAmount = 1.0,
        positionSize = 0.01,
        invalidation = 1.0,
        timestamp = 1L,
        marketDataTimestamp = 1L,
        dataHealth = "TEST",
        reason = "test",
        strategyVersion = "test",
        confluenceScore = 0,
    )
}
