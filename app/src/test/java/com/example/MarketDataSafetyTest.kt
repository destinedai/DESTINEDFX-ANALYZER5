package com.example

import com.example.data.market.QuoteFreshness
import com.example.data.market.classifyQuoteFreshness
import com.example.data.market.processPollingItemsIndependently
import com.example.data.market.resilientPollingLoop
import com.example.data.market.sanitizeOhlcCandles
import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class MarketDataSafetyTest {
    @Test
    fun quoteFreshnessUsesTimestampAndFeedKindNotStickyLiveBoolean() {
        val streamThatWasDemotedButHasStickyBoolean = MarketTicker(
            symbol = "EUR/USD", price = 1.1, changePercent = 0.0, high = 0.0, low = 0.0,
            spread = 0.0, bias = "NEUTRAL", confidence = 0, lastUpdateMillis = 95_000L,
            isLive = true, source = "Deriv WebSocket", kind = "STALE"
        )
        assertEquals(QuoteFreshness.STALE, classifyQuoteFreshness(streamThatWasDemotedButHasStickyBoolean, 100_000L))
        assertEquals(QuoteFreshness.STALE, classifyQuoteFreshness(
            streamThatWasDemotedButHasStickyBoolean.copy(kind = "STREAM", lastUpdateMillis = 100_001L), 100_000L
        ))
        assertEquals(QuoteFreshness.STALE, classifyQuoteFreshness(
            streamThatWasDemotedButHasStickyBoolean.copy(kind = "STREAM", lastUpdateMillis = 95_000L), 100_000L
        ))
        assertEquals(QuoteFreshness.LIVE, classifyQuoteFreshness(
            streamThatWasDemotedButHasStickyBoolean.copy(kind = "POLLED_LAST", lastUpdateMillis = 99_000L), 100_000L
        ))
        assertEquals(QuoteFreshness.STALE, classifyQuoteFreshness(
            streamThatWasDemotedButHasStickyBoolean.copy(kind = "DAILY_REFERENCE", lastUpdateMillis = 99_000L), 100_000L
        ))
        assertEquals(QuoteFreshness.UNAVAILABLE, classifyQuoteFreshness(null, 100_000L))
        assertEquals(QuoteFreshness.CONNECTING, classifyQuoteFreshness(null, 100_000L, connecting = true))
    }

    @Test
    fun candleSanitizerSortsDeduplicatesAndDropsInvalidOrFutureBars() {
        val rows = listOf(
            Candle(2_000L, 10.0, 12.0, 9.0, 11.0, 5.0),
            Candle(1_000L, 8.0, 9.0, 7.0, 8.5, 1.0),
            Candle(3_000L, 10.0, 9.0, 8.0, 8.5, 1.0), // high below open
            Candle(4_000L, 10.0, 11.0, 9.0, Double.NaN, 1.0),
            Candle(120_000L, 10.0, 12.0, 9.0, 11.0, 1.0), // beyond future tolerance
            Candle(2_000L, 10.0, 13.0, 9.0, 12.0, 8.0) // duplicate timestamp: first retained
        )
        val sanitized = sanitizeOhlcCandles(rows, nowMs = 50_000L)
        assertEquals(listOf(1_000L, 2_000L), sanitized.map { it.timestamp })
        assertEquals(12.0, sanitized.last().high, 0.0)
    }

    @Test
    fun perSymbolFailuresDoNotCancelOtherPollingItems() = runBlocking {
        val succeeded = ConcurrentLinkedQueue<Int>()
        val failed = ConcurrentLinkedQueue<Int>()
        processPollingItemsIndependently(
            items = listOf(1, 2, 3),
            parallelism = 2,
            onError = { item, _ -> failed.add(item) }
        ) { item ->
            if (item == 2) error("simulated one-symbol failure")
            succeeded.add(item)
        }
        assertEquals(setOf(1, 3), succeeded.toSet())
        assertEquals(listOf(2), failed.toList())
    }

    @Test
    fun pollingLoopContinuesAfterOneFailedPass() = runBlocking {
        val calls = AtomicInteger(0)
        val reported = AtomicInteger(0)
        val completed = CompletableDeferred<Unit>()
        val job = launch {
            resilientPollingLoop(
                intervalMs = 1L,
                onError = { reported.incrementAndGet() }
            ) {
                if (calls.incrementAndGet() == 1) error("temporary failure")
                completed.complete(Unit)
                throw kotlinx.coroutines.CancellationException("test complete")
            }
        }
        withTimeout(1_000L) { completed.await() }
        job.cancelAndJoin()
        assertTrue(calls.get() >= 2)
        assertTrue(reported.get() >= 1)
        assertFalse(job.isActive)
    }
}
