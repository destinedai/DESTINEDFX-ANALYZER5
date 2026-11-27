package com.example

import com.example.data.trade.VerifiedBrokerCandle
import com.example.data.trade.VerifiedBrokerQuote
import com.example.ui.MT5AnalyzerMath
import org.junit.Assert.*
import org.junit.Test

class MT5AnalyzerMathTest {
    private val baseTime = 1_800_000_000_000L
    private val quote = VerifiedBrokerQuote("EURUSD.a", 1.1600, 1.1602, 5, baseTime, 0)

    private fun bars(timeframe: String, step: Double, start: Double = 1.10): List<VerifiedBrokerCandle> =
        (0 until 60).map { i ->
            val wave = if (i == 59) 0.0040 else when (i % 6) {
                0 -> 0.0000
                1 -> 0.0020
                2 -> 0.0030
                3 -> 0.0010
                4 -> -0.0020
                else -> -0.0040
            }
            val close = start + i * step + wave
            val spacing = if (timeframe == "H1") 3_600_000L else 900_000L
            VerifiedBrokerCandle(
                "EURUSD.a", timeframe, baseTime + i * spacing,
                close - 0.0002, close + 0.0004, close - 0.0004, close, 100.0
            )
        }

    @Test fun missingMT5QuoteNeverCreatesDirectionalSignalOrTradeLevels() {
        val result = MT5AnalyzerMath.analyze(null, bars("M15", 0.001) + bars("H1", 0.001), "M15")
        assertEquals("NEUTRAL", result.trend)
        assertNull(result.stopLoss)
        assertNull(result.takeProfit)
    }

    @Test fun insufficientMT5BarsRemainNeutral() {
        val result = MT5AnalyzerMath.analyze(
            quote, bars("M15", 0.001).take(10) + bars("H1", 0.001), "M15"
        )
        assertEquals("NEUTRAL", result.trend)
        assertNull(result.stopLoss)
        assertNull(result.takeProfit)
        assertNotNull(result.support)
        assertNotNull(result.resistance)
    }

    @Test fun verifiedMT5QuoteAndClosedCandlesCalculateStructureAndLevels() {
        val allBars = bars("M15", 0.001) + bars("H1", 0.001)
        val result = MT5AnalyzerMath.analyze(quote, allBars, "M15")
        assertEquals("UPTREND", result.higherTimeframe.substringAfter(" "))
        assertEquals("UPTREND", result.lowerTimeframe.substringAfter(" "))
        assertEquals("HH + HL", result.structure)
        assertEquals("TRENDING", result.marketState)
        assertEquals("BUY", result.trend)
        assertTrue(result.confidence in 50..92)
        assertNotNull(result.swingHigh)
        assertNotNull(result.swingLow)
        assertNotNull(result.support)
        assertNotNull(result.resistance)
        assertTrue(result.support!! <= result.resistance!!)
        assertTrue(result.stopLoss!! < quote.bid)
        assertTrue(result.takeProfit!! > quote.bid)
    }

    @Test fun conflictingHigherAndLowerTimeframesRemainNeutral() {
        val allBars = bars("M15", 0.001) + bars("H1", -0.001, start = 1.22)
        val result = MT5AnalyzerMath.analyze(quote, allBars, "M15")
        assertEquals("DOWNTREND", result.higherTimeframe.substringAfter(" "))
        assertEquals("NEUTRAL", result.trend)
        assertEquals("TRANSITION", result.marketState)
        assertNull(result.stopLoss)
        assertNull(result.takeProfit)
    }

    @Test fun rangingMarketIsReportedInsteadOfInventingDirection() {
        val flat = bars("M15", 0.0) + bars("H1", 0.0)
        val result = MT5AnalyzerMath.analyze(quote, flat, "M15")
        assertEquals("RANGING", result.marketState)
        assertEquals("NEUTRAL", result.trend)
    }
}
