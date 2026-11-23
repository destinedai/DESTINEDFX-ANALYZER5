package com.example

import com.example.data.market.isTimestampFresh
import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal
import com.example.ui.mapTradeSignalToUiModel
import org.junit.Assert.*
import org.junit.Test

class TradingViewAndMtfPureUnitTest {
    @Test
    fun isTimestampFresh_RejectsFutureStaleAndInvalidTimestamps() {
        assertTrue(isTimestampFresh(timestampMs = 9_950L, nowMs = 10_000L, maxAgeMillis = 100L))
        assertFalse("At the max-age boundary the tick is stale",
            isTimestampFresh(timestampMs = 9_900L, nowMs = 10_000L, maxAgeMillis = 100L))
        assertFalse("A future-dated quote must not count as fresh",
            isTimestampFresh(timestampMs = 10_001L, nowMs = 10_000L, maxAgeMillis = 100L))
        assertFalse(isTimestampFresh(timestampMs = 1L, nowMs = 10_000L, maxAgeMillis = 100L))
        assertFalse(isTimestampFresh(timestampMs = 0L, nowMs = 10_000L, maxAgeMillis = 100L))
        assertFalse(isTimestampFresh(timestampMs = 9_950L, nowMs = 10_000L, maxAgeMillis = 0L))
        assertFalse("Negative timestamps are invalid", isTimestampFresh(timestampMs = -1L, nowMs = 10_000L, maxAgeMillis = 100L))
        // The API accepts Long epoch milliseconds; NaN converts to zero and is rejected as invalid.
        assertFalse("NaN-converted timestamps are invalid", isTimestampFresh(timestampMs = Double.NaN.toLong(), nowMs = 10_000L, maxAgeMillis = 100L))
        assertFalse("Future timestamps beyond five seconds are invalid", isTimestampFresh(timestampMs = 105_001L, nowMs = 100_000L, maxAgeMillis = 60_000L))
        assertFalse("A timestamp exactly sixty seconds old is stale", isTimestampFresh(timestampMs = 40_000L, nowMs = 100_000L, maxAgeMillis = 60_000L))
        assertFalse("A timestamp older than sixty seconds is stale", isTimestampFresh(timestampMs = 39_999L, nowMs = 100_000L, maxAgeMillis = 60_000L))
    }

    @Test
    fun testDynamicAnalysisInfo_ExposesMtfBiasesCorrectly() {
        val signal = TradeSignal(
            id = "test-signal-1",
            symbol = "EUR/USD",
            timeframe = "M15",
            direction = SignalDirection.LONG,
            entryPrice = 1.0850,
            stopLoss = 1.0840,
            takeProfit = 1.0875,
            confidenceScore = 78,
            rationale = "MTF: 2/3 aligned",
            timestamp = System.currentTimeMillis(),
            mtfSummary = "2/3 aligned",
            mtfState = "PARTIAL_BULLISH",
            mtfPerTimeframe = mapOf(
                "H1" to "BULLISH",
                "H4" to "BULLISH",
                "D1" to "BEARISH",
                "W1" to "BULLISH"
            ),
            signalState = "BUY",
            executable = true
        )

        val uiModel = mapTradeSignalToUiModel(signal)
        assertEquals("BULLISH", uiModel.getTfBias("H1"))
        assertEquals("BULLISH", uiModel.getTfBias("H4"))
        assertEquals("BEARISH", uiModel.getTfBias("D1"))
        assertEquals("BULLISH", uiModel.getTfBias("W1"))
        assertEquals("N/A", uiModel.getTfBias("M1"))
        assertEquals("2/3 aligned", uiModel.mtfSummary)
        assertEquals("PARTIAL_BULLISH", uiModel.mtfStatus)
    }
}
