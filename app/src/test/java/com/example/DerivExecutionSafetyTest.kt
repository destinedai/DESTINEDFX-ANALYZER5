package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.broker.DerivBrokerGatewayAdapter
import com.example.data.deriv.DerivBrokerGateway
import com.example.domain.model.CanonicalTradePlan
import com.example.domain.model.SignalDirection
import com.example.security.SecretsManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DerivExecutionSafetyTest {
    @Test
    fun forexCfdCanonicalPlanCannotBeSubmittedAsMultiplierOrder() = runBlocking {
        val secrets = SecretsManager(ApplicationProvider.getApplicationContext())
        val adapter = DerivBrokerGatewayAdapter(DerivBrokerGateway(secrets))
        val plan = CanonicalTradePlan(
            symbol = "EURUSD",
            broker = "Deriv WebSocket",
            provider = "unit-test",
            timeframe = "H1",
            strategy = "smc-confluence-1.0.0",
            direction = SignalDirection.LONG,
            status = "BUY",
            entry = 1.1,
            bid = 1.0999,
            ask = 1.1001,
            spread = 0.0002,
            stopLoss = 1.09,
            takeProfit = 1.12,
            rr = 2.0,
            riskPercent = 1.0,
            riskAmount = 1.0,
            positionSize = 0.01,
            invalidation = 1.09,
            timestamp = 1L,
            marketDataTimestamp = 1L,
            dataHealth = "LIVE",
            reason = "unit-test only",
            strategyVersion = "smc-confluence-1.0.0",
            confluenceScore = 80
        )

        val result = adapter.executeTrade(plan)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("No purchase was sent"))
    }
}
