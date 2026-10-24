package com.example

import com.example.domain.model.Candle
import com.example.ui.chart.buildNativeChartScale
import com.example.ui.chart.isValidChartCandle
import com.example.ui.chart.isValidSpotPrice
import com.example.ui.chart.validNativeChartTradeLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChartScaleTest {
    private fun c(t: Long, o: Double, h: Double, l: Double, close: Double) = Candle(t, o, h, l, close, 1.0)

    @Test
    fun eurUsdCandlesProduceUsefulPositiveRangeIndependentOfZeroDefaultAnalysis() {
        val candles = listOf(c(1, 1.0840, 1.0850, 1.0830, 1.0845), c(2, 1.0845, 1.0860, 1.0840, 1.0855))
        // Default DynamicAnalysisInfo levels are all zero; the scale helper takes candles only.
        val scale = buildNativeChartScale(candles, "EUR/USD")!!
        assertTrue(scale.minPrice > 1.0)
        assertTrue(scale.minPrice < 1.0830)
        assertTrue(scale.maxPrice > 1.0860)
        assertTrue(scale.range < 0.01)
        assertEquals(0.5, scale.priceToFraction((scale.minPrice + scale.maxPrice) / 2.0)!!, 1e-10)
    }

    @Test
    fun invalidRowsAndNonFiniteOhlcCannotDistortAxis() {
        val candles = listOf(
            c(1, 1.10, 1.11, 1.09, 1.10),
            c(2, 0.0, 0.0, 0.0, 0.0),
            c(3, 1.10, 1.08, 1.09, 1.10),
            c(4, Double.NaN, 1.2, 1.0, 1.1),
            c(5, 1.1, Double.POSITIVE_INFINITY, 1.0, 1.1),
            c(6, 1.1, 1.2, 1.0, Double.NEGATIVE_INFINITY)
        )
        assertEquals(1, candles.count(::isValidChartCandle))
        val scale = buildNativeChartScale(candles, "EUR/USD")!!
        assertTrue(scale.minPrice > 1.0 && scale.maxPrice < 1.2)
    }

    @Test
    fun narrowAndConstantPricesGetFiniteNonzeroDrawableRange() {
        for (candles in listOf(
            listOf(c(1, 1.10000, 1.10001, 1.09999, 1.10000)),
            listOf(c(1, 1.10000, 1.10000, 1.10000, 1.10000))
        )) {
            val scale = buildNativeChartScale(candles, "EUR/USD")!!
            assertTrue(scale.minPrice.isFinite() && scale.maxPrice.isFinite())
            assertTrue(scale.minPrice > 0.0 && scale.range > 0.0)
            val fraction = scale.priceToFraction(1.1)!!
            assertTrue(fraction.isFinite() && fraction in 0.0..1.0)
            assertEquals(1.1, scale.fractionToPrice(fraction)!!, 1e-12)
        }
    }

    @Test
    fun emptyHistoryHasNoAxisAndDoesNotNeedSpot() {
        assertNull(buildNativeChartScale(emptyList(), "EUR/USD"))
        assertFalse(isValidSpotPrice(null))
        assertFalse(isValidSpotPrice(0.0))
        assertFalse(isValidSpotPrice(Double.NaN))
        assertFalse(isValidSpotPrice(Double.POSITIVE_INFINITY))
        assertTrue(isValidSpotPrice(1.0845))
    }

    @Test
    fun validTradePlanIsAnOverlayButDoesNotAlterCandleOnlyScale() {
        val candles = listOf(c(1, 1.10, 1.11, 1.09, 1.10))
        val base = buildNativeChartScale(candles, "EUR/USD")!!
        val plan = validNativeChartTradeLevels("BUY", "EUR/USD", "EUR/USD", 1.105, 1.095, 1.125)
        assertNotNull(plan)
        assertEquals(base, buildNativeChartScale(candles, "EUR/USD"))
        assertNull(validNativeChartTradeLevels("WAIT", "EUR/USD", "EUR/USD", 1.105, 1.095, 1.125))
        assertNull(validNativeChartTradeLevels("SELL", "EUR/USD", "EUR/USD", 1.105, 1.095, 1.125))
        assertNull(validNativeChartTradeLevels("BUY", "GBP/USD", "EUR/USD", 1.105, 1.095, 1.125))
        assertNull(validNativeChartTradeLevels("BUY", "EUR/USD", "EUR/USD", Double.NaN, 1.095, 1.125))
    }
}
