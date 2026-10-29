package com.example.ui

import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalPresentationSafetyTest {
    private fun signal(
        direction: SignalDirection = SignalDirection.LONG,
        score: Int = 90,
        entry: Double = 1.1,
        executable: Boolean = true
    ) = TradeSignal(
        id = "test-signal",
        symbol = "EUR/USD",
        timeframe = "H1",
        direction = direction,
        entryPrice = entry,
        stopLoss = 1.09,
        takeProfit = 1.12,
        confidenceScore = score,
        rationale = "test",
        timestamp = 1_800_000L,
        executable = executable
    )

    @Test
    fun waitNeverBecomesSellOrDirectionalBiasInHistory() {
        assertEquals("WAIT", signalHistoryLabel(SignalDirection.WAIT))
        assertEquals("NEUTRAL", signalBiasLabel(SignalDirection.WAIT))
        assertEquals("BUY", signalHistoryLabel(SignalDirection.LONG))
        assertEquals("SELL", signalHistoryLabel(SignalDirection.SHORT))
    }

    @Test
    fun selectedDisplayNameMapsToEngineStrategyId() {
        assertEquals("ict-silver-bullet", resolveSelectedStrategyId("ICT Silver Bullet"))
        assertEquals("mean-reversion", resolveSelectedStrategyId("Statistical Mean Reversion"))
        assertEquals("smc-confluence-1.0.0", resolveSelectedStrategyId("SMC Order Blocks"))
    }

    @Test
    fun alertsRequireHighConfidenceExecutableDirectionalSignal() {
        assertTrue(shouldSendHighConfidenceAlert(signal()))
        assertFalse(shouldSendHighConfidenceAlert(signal(executable = false)))
        assertFalse(shouldSendHighConfidenceAlert(signal(direction = SignalDirection.WAIT)))
        assertFalse(shouldSendHighConfidenceAlert(signal(score = 84)))
        assertFalse(shouldSendHighConfidenceAlert(signal(entry = Double.NaN)))
        assertFalse(shouldSendHighConfidenceAlert(signal(entry = 0.0)))
    }
}
