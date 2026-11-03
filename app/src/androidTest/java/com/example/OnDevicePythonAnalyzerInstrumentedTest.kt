package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.destinedfx.core.marketdata.Candle
import com.destinedfx.core.marketdata.Timeframe
import com.example.domain.usecase.impl.ChaquopyPythonAnalyzer
import com.example.domain.usecase.impl.PythonInputUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device/emulator proof that the installed APK starts the bundled interpreter and returns strict results. */
@RunWith(AndroidJUnit4::class)
class OnDevicePythonAnalyzerInstrumentedTest {
    @Test
    fun packagedPythonAnalyzesClosedCandleSnapshotsOnEverySupportedTimeframe() = runBlocking {
        val now = System.currentTimeMillis()
        val retrievedAt = now
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val analyzer = ChaquopyPythonAnalyzer(context)
        val frames = listOf(
            Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30,
            Timeframe.H1, Timeframe.H4, Timeframe.D1, Timeframe.W1
        )
        for (frame in frames) {
            val interval = frame.millis
            val candles = (0 until 40).map { index ->
                val close = 2000.0 + index * 0.1
                if (index == 39) {
                    val priorClose = 2000.0 + 38 * 0.1
                    Candle(
                        timeMs = now - interval - 60_000L,
                        open = priorClose + 0.2,
                        high = priorClose + 0.8,
                        low = priorClose + 0.1,
                        close = priorClose + 0.7,
                        volume = 10.0,
                        complete = true
                    )
                } else {
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
            }
            val result = analyzer.analyze(
                symbol = "XAU/USD", timeframe = frame.label, strategyId = "smc-confluence-1.0.0",
                source = "instrumentation-fixture", retrievedAtMs = retrievedAt, nowMs = now,
                intervalMs = interval, allowSessionGaps = true, candles = candles
            )
            assertEquals("XAU/USD", result.symbol)
            assertEquals("$frame", frame.label, result.timeframe)
            assertEquals("smc-confluence-1.0.0", result.strategyId)
            assertEquals("python-smc-confluence-1.0.0", result.strategyVersion)
            assertEquals(candles.last().timeMs, result.candleTimestampMs)
            assertEquals("instrumentation-fixture", result.source)
            assertEquals(retrievedAt, result.retrievedAtMs)
            assertEquals("CANDIDATE", result.state)
            assertEquals("LONG", result.direction)
            assertNotNull(result.entry)
            assertNotNull(result.stopLoss)
            assertNotNull(result.takeProfit)
            assertNotNull(result.rewardToRisk)
            assertTrue(result.evidence.isNotEmpty())
            assertTrue(result.scoreBasis.isNotEmpty())
            assertFalse(result.executable)
        }
    }


    @Test
    fun staleClosedCandlesCannotProducePythonCandidate() = runBlocking {
        val now = System.currentTimeMillis()
        val interval = Timeframe.M15.millis
        val candles = fixtureCandles(now, interval, ageOffsetMs = interval * 4)
        val result = ChaquopyPythonAnalyzer(
            InstrumentationRegistry.getInstrumentation().targetContext
        ).analyze(
            symbol = "XAU/USD", timeframe = "M15", strategyId = "smc-confluence-1.0.0",
            source = "instrumentation-fixture", retrievedAtMs = now, nowMs = now,
            intervalMs = interval, allowSessionGaps = true, candles = candles
        )
        assertEquals("DATA_UNAVAILABLE", result.state)
        assertEquals("WAIT", result.direction)
        assertEquals("STALE_CANDLES", result.reason)
        assertEquals(candles.last().timeMs, result.candleTimestampMs)
        assertEquals("instrumentation-fixture", result.source)
        assertFalse(result.executable)
        assertEquals(null, result.entry)
        assertEquals(null, result.stopLoss)
        assertEquals(null, result.takeProfit)
    }

    @Test
    fun staleSnapshotRetrievalIsRejectedIndependentlyOfClosedStatus() = runBlocking {
        val now = System.currentTimeMillis()
        val interval = Timeframe.M15.millis
        val result = runCatching {
            ChaquopyPythonAnalyzer(InstrumentationRegistry.getInstrumentation().targetContext).analyze(
                symbol = "XAU/USD", timeframe = "M15", strategyId = "smc-confluence-1.0.0",
                source = "instrumentation-fixture", retrievedAtMs = now - interval * 3,
                nowMs = now, intervalMs = interval, allowSessionGaps = true,
                candles = fixtureCandles(now, interval)
            )
        }
        assertTrue(result.exceptionOrNull() is PythonInputUnavailableException)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("STALE_RETRIEVAL"))
    }

    private fun fixtureCandles(now: Long, interval: Long, ageOffsetMs: Long = 0L): List<Candle> =
        (0 until 40).map { index ->
            val close = 2000.0 + index * 0.1
            val timestamp = now - (40 - index) * interval - 60_000L - ageOffsetMs
            if (index == 39) {
                val priorClose = 2000.0 + 38 * 0.1
                Candle(timestamp, priorClose + 0.2, priorClose + 0.8, priorClose + 0.1,
                    priorClose + 0.7, 10.0, true)
            } else {
                Candle(timestamp, close - 0.05, close + 0.2, close - 0.2, close, 10.0, true)
            }
        }
}
