package com.example

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.domain.engine.PythonQuantitativeSignalEngine
import com.example.domain.model.Candle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonQuantitativeSignalEngineInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val strategyId = "smc-confluence-1.0.0"

    @Test
    fun packagedPythonExecutesPositiveNegativeUnsupportedAndInvalidFixtures() = runBlocking {
        val now = System.currentTimeMillis()
        val source = "instrumentation-fixture"
        val candles = positiveFixture(now)
        val engine = PythonQuantitativeSignalEngine(context, timeoutMs = 15_000L)

        val positive = engine.analyze(
            symbol = "EURUSD",
            timeframe = "M1",
            strategyId = strategyId,
            candles = candles,
            source = source,
            nowMs = now,
            expectedIntervalMs = 60_000L,
            providerRetrievedAtMs = now - 2_000L
        )
        assertEquals("EURUSD", positive.symbol)
        assertEquals(strategyId, positive.strategyId)
        assertEquals("M1", positive.timeframe)
        assertEquals(source, positive.source)
        assertTrue(positive.candleTimestampMs > 0L)
        assertEquals("CANDIDATE", positive.state)
        assertEquals("LONG", positive.direction)
        assertTrue(positive.evidence.contains("BOS_UP_CLOSED_CANDLE"))
        assertFalse(positive.executable)

        val negative = engine.analyze(
            symbol = "EURUSD", timeframe = "M1", strategyId = strategyId,
            candles = candles.dropLast(1) + candles.last().copy(close = candles[candles.lastIndex - 1].high),
            source = source, nowMs = now, expectedIntervalMs = 60_000L,
            providerRetrievedAtMs = now - 2_000L
        )
        assertEquals("WAITING", negative.state)
        assertEquals("WAIT", negative.direction)

        val unsupported = engine.analyze(
            symbol = "EURUSD", timeframe = "M1", strategyId = "wyckoff-1.0.0",
            candles = candles, source = source, nowMs = now,
            expectedIntervalMs = 60_000L, providerRetrievedAtMs = now - 2_000L
        )
        assertEquals("NOT_IMPLEMENTED", unsupported.state)
        assertEquals("WAIT", unsupported.direction)
        assertFalse(unsupported.executable)

        val invalid = engine.analyze(
            symbol = "EURUSD", timeframe = "M1", strategyId = strategyId,
            candles = candles.dropLast(1) + candles.last().copy(close = Double.NaN),
            source = source, nowMs = now, expectedIntervalMs = 60_000L,
            providerRetrievedAtMs = now - 2_000L
        )
        assertEquals("DATA_UNAVAILABLE", invalid.state)
        assertEquals("WAIT", invalid.direction)
        assertFalse(invalid.executable)
    }

    @Test
    fun repeatedCallsReuseTheProcessPythonRuntimeAndDoNotCrashOnStartup() = runBlocking {
        val now = System.currentTimeMillis()
        val engine = PythonQuantitativeSignalEngine(context, timeoutMs = 15_000L)
        repeat(2) {
            val result = engine.analyze(
                symbol = "EURUSD", timeframe = "M1", strategyId = strategyId,
                candles = positiveFixture(now), source = "instrumentation-fixture",
                nowMs = now, expectedIntervalMs = 60_000L,
                providerRetrievedAtMs = now - 2_000L
            )
            assertTrue(result.state == "CANDIDATE" || result.state == "WAITING")
            assertFalse(result.executable)
        }
        assertTrue(com.chaquo.python.Python.isStarted())
    }

    private fun positiveFixture(now: Long): List<Candle> {
        val lastTimestamp = now - 60_000L
        return (0 until 30).map { index ->
            val timestamp = lastTimestamp - (29 - index) * 60_000L
            val base = 100.0 + index * 0.10
            if (index == 29) {
                Candle(timestamp, 103.0, 105.2, 102.8, 105.0, 1.0)
            } else {
                Candle(timestamp, base, base + 0.05, base - 0.05, base + 0.02, 1.0)
            }
        }
    }
}
