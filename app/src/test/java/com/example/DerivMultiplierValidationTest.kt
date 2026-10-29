package com.example

import com.example.data.deriv.DerivAccountInfo
import com.example.data.deriv.DerivMultiplierIntent
import com.example.data.deriv.DerivMultiplierProduct
import com.example.data.deriv.DerivMultiplierRules
import com.example.domain.model.SignalDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivMultiplierValidationTest {
    private fun intent(
        symbol: String = "1HZ100V",
        direction: SignalDirection = SignalDirection.LONG,
        product: DerivMultiplierProduct = DerivMultiplierProduct.MULTUP,
        stake: Double = 1.0,
        currency: String = "USD",
        signalTimestampMs: Long = System.currentTimeMillis()
    ) = DerivMultiplierIntent(
        signalId = "signal-1",
        symbol = symbol,
        strategyId = "smc-confluence",
        strategyVersion = "1.0.0",
        direction = direction,
        product = product,
        stake = stake,
        currency = currency,
        multiplier = 10,
        stopLossAmount = stake,
        takeProfitAmount = stake * 2.0,
        signalTimestampMs = signalTimestampMs
    )

    @Test fun validSyntheticMultiplierIntentPasses() {
        assertTrue(DerivMultiplierRules.validate(intent(), 100.0, "USD").valid)
    }

    @Test fun forexMappingIsRejected() {
        assertFalse(DerivMultiplierRules.validate(intent(symbol = "frxEURUSD"), 100.0, "USD").valid)
    }

    @Test fun wrongProductDirectionIsRejected() {
        assertFalse(DerivMultiplierRules.validate(intent(direction = SignalDirection.SHORT), 100.0, "USD").valid)
    }

    @Test fun stakeAboveOnePercentIsRejected() {
        assertFalse(DerivMultiplierRules.validate(intent(stake = 1.01), 100.0, "USD").valid)
    }

    @Test fun currencyAndStaleSignalAreRejected() {
        assertFalse(DerivMultiplierRules.validate(intent(currency = "EUR"), 100.0, "USD").valid)
        assertFalse(DerivMultiplierRules.validate(intent(signalTimestampMs = System.currentTimeMillis() - 31_000), 100.0, "USD").valid)
    }

    @Test fun AndroidBotAcceptsOnlyExplicitlyVerifiedDemoAccount() {
        val demo = DerivAccountInfo(
            loginId = "VRTC12345",
            currency = "USD",
            balance = 100.0,
            email = "",
            accountType = "demo",
            demoVerified = true,
            mode = "demo",
            realExecutionAuthorized = false
        )
        assertTrue(DerivMultiplierRules.isVerifiedDemoAccount(demo))
        assertFalse(DerivMultiplierRules.isVerifiedDemoAccount(demo.copy(demoVerified = false)))
        assertFalse(DerivMultiplierRules.isVerifiedDemoAccount(
            demo.copy(accountType = "real", mode = "real", realExecutionAuthorized = true)
        ))
    }

    @Test fun safeStakeFloorsToCentsAndNeverExceedsOnePercentCap() {
        val balance = 123.45
        val stake = DerivMultiplierRules.safeStakeForBalance(balance)
        assertEquals(1.23, stake, 0.0)
        assertTrue(stake <= balance * DerivMultiplierRules.MAX_RISK_PERCENT / 100.0)
        assertEquals(0.0, DerivMultiplierRules.safeStakeForBalance(Double.NaN), 0.0)
        assertEquals(0.0, DerivMultiplierRules.safeStakeForBalance(-10.0), 0.0)
    }

}
