package com.example

import com.destinedfx.core.risk.OpenRisk
import com.destinedfx.core.risk.PortfolioLimits
import com.destinedfx.core.risk.PortfolioRiskEngine
import com.destinedfx.core.risk.RealizedPnl
import com.example.domain.risk.TradingSafetyLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioRiskLimitsTest {
    @Test
    fun defaultDailyLossLimitMatchesTheSharedSafetyLimit() {
        assertEquals(
            TradingSafetyLimits.MAX_DAILY_LOSS_PERCENT,
            PortfolioLimits().maxDailyLossPct,
            0.0
        )
    }

    @Test
    fun defaultPortfolioLimitsBlockAtTwoPercentDailyLoss() {
        val now = 1_790_000_000_000L
        val limits = PortfolioLimits()
        val candidate = OpenRisk("EURUSD", true, 0.5, "EUR", "USD")

        val belowLimit = PortfolioRiskEngine.evaluate(
            startBalance = 1_000.0,
            closed = listOf(RealizedPnl(now, -19.99)),
            floatingPnl = 0.0,
            open = emptyList(),
            candidate = candidate,
            nowMs = now,
            l = limits
        )
        val atLimit = PortfolioRiskEngine.evaluate(
            startBalance = 1_000.0,
            closed = listOf(RealizedPnl(now, -20.0)),
            floatingPnl = 0.0,
            open = emptyList(),
            candidate = candidate,
            nowMs = now,
            l = limits
        )

        assertTrue(belowLimit.allowed)
        assertFalse(atLimit.allowed)
        assertEquals("DAILY_LOSS_LIMIT_REACHED", atLimit.code)
    }
}
