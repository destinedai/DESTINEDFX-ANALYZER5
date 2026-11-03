package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.destinedfx.core.marketdata.Candle
import com.example.domain.usecase.impl.ChaquopyPythonAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator proof that the APK can start its bundled interpreter, import quantitative.py,
 * validate the supplied closed-candle snapshot, and return a non-executable typed result.
 */
@RunWith(AndroidJUnit4::class)
class OnDevicePythonAnalyzerInstrumentedTest {
    @Test
    fun packagedPythonAnalyzesClosedCandleSnapshot() = runBlocking {
        val now = System.currentTimeMillis()
        val interval = 15 * 60_000L
        val candles = (0 until 40).map { index ->
            val close = 2000.0 + index * 0.1
            Candle(
                timeMs = now - (40 - index) * interval - 60_000L,
                open = close - 0.05,
                high = close + 0.2,
                low = close - 0.2,
                close = close,
                volume = 10.0,
                complete = true
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = ChaquopyPythonAnalyzer(context).analyze(
            symbol = "XAU/USD", timeframe = "M15", strategyId = "smc-confluence-1.0.0",
            source = "instrumentation-fixture", nowMs = now, intervalMs = interval,
            allowSessionGaps = true, candles = candles
        )
        assertEquals("XAU/USD", result.symbol)
        assertEquals("M15", result.timeframe)
        assertEquals("smc-confluence-1.0.0", result.strategyId)
        assertEquals(candles.last().timeMs, result.candleTimestampMs)
        assertEquals("instrumentation-fixture", result.source)
        assertTrue(result.state == "WAITING" || result.state == "CANDIDATE")
        assertFalse(result.executable)
    }
}
