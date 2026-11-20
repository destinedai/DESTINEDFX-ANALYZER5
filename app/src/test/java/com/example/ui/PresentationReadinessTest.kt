package com.example.ui

import com.example.domain.model.MarketTicker
import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationReadinessTest {
    private fun liveTicker(nowMs: Long) = MarketTicker(
        symbol = "EUR/USD",
        price = 1.0850,
        changePercent = 0.1,
        high = 1.09,
        low = 1.08,
        spread = 0.0001,
        bias = "BULLISH",
        confidence = 90,
        lastUpdateMillis = nowMs,
        isLive = true,
        source = "fixture-provider",
        bid = 1.0849,
        ask = 1.0850,
        hasRealSpread = true,
        kind = "STREAM"
    )

    private fun executableSignal(nowMs: Long) = TradeSignal(
        id = "fixture-signal",
        symbol = "EUR/USD",
        timeframe = "M15",
        direction = SignalDirection.LONG,
        entryPrice = 1.0850,
        stopLoss = 1.0800,
        takeProfit = 1.0950,
        confidenceScore = 90,
        rationale = "Fixture only",
        timestamp = nowMs,
        signalState = "BUY",
        executable = true,
        dataSource = "fixture-provider",
        dataHealth = "LIVE"
    )

    @Test
    fun executableSignalRequiresFreshLiveQuoteAndFreshCandles() {
        val now = 1_800_000_000_000L
        val result = deriveMarketReadiness(
            ticker = liveTicker(now),
            candleSource = "fixture-provider",
            candlesAvailable = true,
            chartDisplayed = true,
            signal = executableSignal(now),
            candlesFresh = true,
            nowMs = now
        )

        assertTrue(result.quoteLive)
        assertTrue(result.candlesConfirmed)
        assertTrue(result.analysisCompleted)
        assertTrue(result.tradingEligible)
        assertEquals("Trading checks passed", result.eligibilityLabel)
    }

    @Test
    fun staleQuoteCannotLeaveRetainedSignalMarkedTradeEligible() {
        val now = 1_800_000_000_000L
        val result = deriveMarketReadiness(
            ticker = liveTicker(now - 15_001L),
            candleSource = "fixture-provider",
            candlesAvailable = true,
            chartDisplayed = true,
            signal = executableSignal(now - 1_000L),
            candlesFresh = true,
            nowMs = now
        )

        assertFalse(result.quoteLive)
        assertFalse(result.analysisCompleted)
        assertFalse(result.tradingEligible)
        assertEquals("Not eligible to trade", result.eligibilityLabel)
        assertEquals("Prior analysis — waiting for fresh verified data", result.analysisLabel)
    }

    @Test
    fun staleCandlesCannotLeaveRetainedSignalMarkedTradeEligible() {
        val now = 1_800_000_000_000L
        val result = deriveMarketReadiness(
            ticker = liveTicker(now),
            candleSource = "fixture-provider",
            candlesAvailable = true,
            chartDisplayed = true,
            signal = executableSignal(now),
            candlesFresh = false,
            nowMs = now
        )

        assertTrue(result.quoteLive)
        assertFalse(result.candlesConfirmed)
        assertFalse(result.analysisCompleted)
        assertFalse(result.tradingEligible)
        assertEquals("Not eligible to trade", result.eligibilityLabel)
    }

    @Test
    fun retainedExecutableSignalExpiresEvenWhenCurrentMarketFeedsAreFresh() {
        val now = 1_800_000_000_000L
        val result = deriveMarketReadiness(
            ticker = liveTicker(now),
            candleSource = "fixture-provider",
            candlesAvailable = true,
            chartDisplayed = true,
            signal = executableSignal(now - 15_001L),
            candlesFresh = true,
            nowMs = now
        )

        assertTrue(result.quoteLive)
        assertTrue(result.candlesConfirmed)
        assertFalse(result.analysisCompleted)
        assertFalse(result.tradingEligible)
        assertEquals("Prior analysis — rescan to refresh", result.analysisLabel)
        assertEquals("Not eligible to trade", result.eligibilityLabel)
    }

    @Test
    fun delayedOrNonExecutableSignalNeverPassesTradingReadiness() {
        val now = 1_800_000_000_000L
        val result = deriveMarketReadiness(
            ticker = liveTicker(now),
            candleSource = "fixture-provider",
            candlesAvailable = true,
            chartDisplayed = true,
            signal = executableSignal(now).copy(executable = false),
            candlesFresh = true,
            nowMs = now
        )

        assertTrue(result.quoteLive)
        assertTrue(result.candlesConfirmed)
        assertTrue(result.analysisCompleted)
        assertFalse(result.tradingEligible)
        assertEquals("Not eligible to trade", result.eligibilityLabel)
    }
    @Test
    fun timestampedCandleSnapshotMustBeFreshForReadiness() {
        val now = 1_800_000_000_000L
        val fresh = deriveMarketReadiness(
            ticker = liveTicker(now),
            candleSource = "fixture-provider",
            candlesAvailable = true,
            chartDisplayed = true,
            signal = executableSignal(now),
            nowMs = now,
            candleRetrievedAtMs = now - 1_000L,
            latestClosedCandleAtMs = now - 60_000L,
            timeframe = "M1"
        )
        assertTrue(fresh.candlesConfirmed)
        assertTrue(fresh.analysisCompleted)
        assertTrue(fresh.tradingEligible)

        val staleSnapshot = deriveMarketReadiness(
            ticker = liveTicker(now),
            candleSource = "fixture-provider",
            candlesAvailable = true,
            chartDisplayed = true,
            signal = executableSignal(now),
            nowMs = now,
            candleRetrievedAtMs = now - 30_001L,
            latestClosedCandleAtMs = now - 60_000L,
            timeframe = "M1"
        )
        assertFalse(staleSnapshot.candlesConfirmed)
        assertFalse(staleSnapshot.analysisCompleted)
        assertFalse(staleSnapshot.tradingEligible)
    }

}
