package com.example

import com.example.domain.model.Candle
import com.example.domain.patterns.CandlestickPatternEngine
import com.example.domain.patterns.CandlestickPatternType
import org.junit.Assert.*
import org.junit.Test

class CandlestickPatternEngineTest {
    private val engine = CandlestickPatternEngine()
    private fun c(i: Int, o: Double, h: Double, l: Double, close: Double, complete: Boolean = true) =
        Candle(timestamp = 1_700_000_000_000L + i * 60_000L, open = o, high = h, low = l, close = close, volume = 1.0, complete = complete)

    private fun priorDown() = listOf(c(0, 12.0, 12.2, 11.8, 11.5), c(1, 11.5, 11.7, 11.3, 11.0),
        c(2, 11.0, 11.2, 10.8, 10.5), c(3, 10.5, 10.7, 10.3, 10.0), c(4, 10.0, 10.2, 9.8, 9.5))
    private fun priorUp() = listOf(c(0, 9.0, 9.2, 8.8, 9.5), c(1, 9.5, 9.7, 9.3, 10.0),
        c(2, 10.0, 10.2, 9.8, 10.5), c(3, 10.5, 10.7, 10.3, 11.0), c(4, 11.0, 11.2, 10.8, 11.5))

    @Test fun soldiersAndCrowsHaveTheirOwnClassifications() {
        val soldiers = priorDown() + listOf(c(5, 9.6, 10.8, 9.5, 10.7), c(6, 10.2, 11.2, 10.1, 11.1), c(7, 10.8, 11.8, 10.7, 11.7))
        assertEquals(CandlestickPatternType.THREE_WHITE_SOLDIERS, engine.detectPattern(soldiers).type)
        val crows = priorUp() + listOf(c(5, 11.4, 11.5, 10.2, 10.3), c(6, 10.8, 10.9, 9.8, 9.9), c(7, 10.4, 10.5, 9.4, 9.5))
        assertEquals(CandlestickPatternType.THREE_BLACK_CROWS, engine.detectPattern(crows).type)
    }

    @Test fun tweezersPiercingAndDarkCloudDoNotMasqueradeAsSingleCandleTypes() {
        val top = priorUp() + listOf(c(5, 11.2, 12.0, 11.0, 11.8), c(6, 11.8, 12.0, 11.3, 11.5))
        assertEquals(CandlestickPatternType.TWEEZER_TOP, engine.detectPattern(top).type)
        val bottom = priorDown() + listOf(c(5, 9.7, 10.0, 9.0, 9.2), c(6, 9.2, 9.7, 9.0, 9.6))
        assertEquals(CandlestickPatternType.TWEEZER_BOTTOM, engine.detectPattern(bottom).type)
        val piercing = priorDown() + listOf(c(5, 9.6, 9.8, 9.0, 9.1), c(6, 8.9, 9.7, 8.8, 9.5))
        assertEquals(CandlestickPatternType.PIERCING_LINE, engine.detectPattern(piercing).type)
        val dark = priorUp() + listOf(c(5, 11.4, 12.0, 11.2, 11.9), c(6, 12.1, 12.2, 11.3, 11.5))
        assertEquals(CandlestickPatternType.DARK_CLOUD_COVER, engine.detectPattern(dark).type)
    }

    @Test fun dojiVariantsAndGeometryExposeRatios() {
        val data = priorUp() + c(5, 11.0, 12.0, 10.99, 11.01)
        val r = engine.detectPattern(data)
        assertEquals(CandlestickPatternType.GRAVESTONE_DOJI, r.type)
        assertTrue(r.upperWickRatio > 0.9)
        assertTrue(r.bodyRangeRatio < 0.1)
        assertTrue(r.confirmed)
        assertNotEquals("UNKNOWN", r.priorTrend)
        assertNotEquals("UNAVAILABLE", r.volatilityContext)
    }

    @Test fun malformedAndOutOfOrderCandlesFailClosed() {
        val invalid = priorDown() + c(5, 10.0, 9.0, 9.5, 9.8)
        val result = engine.detectPattern(invalid)
        assertFalse(result.dataValid)
        assertFalse(result.confirmed)
        assertEquals(CandlestickPatternType.NONE, result.type)
        val ordered = priorDown() + Candle(1_700_000_000_000L, 9.0, 9.5, 8.5, 9.2, 0.0)
        assertFalse(engine.detectPattern(ordered).dataValid)
    }

    @Test fun formingCandleIsNeverReportedAsConfirmed() {
        val result = engine.detectPattern(priorDown() + c(5, 9.6, 10.0, 9.4, 9.9, complete = false))
        assertTrue(result.forming)
        assertFalse(result.confirmed)
        assertEquals(CandlestickPatternType.NONE, result.type)
    }

    @Test fun insufficientDataReturnsNoPattern() {
        val result = engine.detectPattern(listOf(c(0, 1.0, 1.1, 0.9, 1.0)))
        assertEquals(CandlestickPatternType.NONE, result.type)
        assertFalse(result.confirmed)
    }
}
