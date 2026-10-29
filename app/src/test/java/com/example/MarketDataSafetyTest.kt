package com.example

import com.destinedfx.core.marketdata.Decision
import com.destinedfx.core.marketdata.QuoteArbiter
import com.destinedfx.core.marketdata.QuoteView
import com.example.data.market.QuoteFreshness
import com.example.data.market.classifyQuoteFreshness
import com.example.data.market.candleSourceKey
import com.example.data.market.canMergeQuoteIntoCandle
import com.example.data.market.scannerQuoteFromRow
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
    fun candleSourceKeysDoNotAliasTimeframes() {
        assertEquals("EUR/USD-M15", candleSourceKey("EUR/USD", "m15"))
        assertEquals("EUR/USD-H1", candleSourceKey("EUR/USD", "H1"))
        assertFalse(candleSourceKey("EUR/USD", "M15") == candleSourceKey("EUR/USD", "H1"))
    }

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

    @Test
    fun scannerSnapshotNeverInventsBidAskSpreadOrConfidence() {
        val ticker = scannerQuoteFromRow(
            symbol = "EUR/USD",
            close = 1.1000,
            changePercent = 0.25,
            high = 1.1050,
            low = 1.0950,
            bid = Double.NaN,
            ask = Double.NaN,
            retrievedAtMs = 100_000L
        )
        assertTrue(ticker != null)
        assertEquals(null, ticker!!.bid)
        assertEquals(null, ticker.ask)
        assertEquals(0.0, ticker.spread, 0.0)
        assertFalse(ticker.hasRealSpread)
        assertEquals("POLLED_LAST", ticker.kind)
        assertEquals(0, ticker.confidence)
        assertEquals(QuoteFreshness.LIVE, classifyQuoteFreshness(ticker, 105_000L))
    }

    @Test
    fun scannerSnapshotPreservesOnlyValidActualBidAsk() {
        val ticker = scannerQuoteFromRow(
            symbol = "EUR/USD",
            close = 1.1000,
            changePercent = 0.25,
            high = 1.1050,
            low = 1.0950,
            bid = 1.0999,
            ask = 1.1001,
            retrievedAtMs = 100_000L
        )!!
        assertEquals(1.0999, ticker.bid!!, 0.0)
        assertEquals(1.1001, ticker.ask!!, 0.0)
        assertEquals(0.0002, ticker.spread, 0.0000000001)
        assertTrue(ticker.hasRealSpread)
        assertEquals("POLLED_BIDASK", ticker.kind)
        assertEquals(0, ticker.confidence)
    }

    @Test
    fun scannerSnapshotRejectsInvalidCloseAndNeverInventsMissingRange() {
        assertEquals(null, scannerQuoteFromRow("EUR/USD", Double.NaN, 0.0, 1.1, 1.0, 1.0, 1.1, 100_000L))
        val ticker = scannerQuoteFromRow("EUR/USD", 1.1, Double.NaN, 0.0, 0.0, 0.0, 0.0, 100_000L)!!
        assertEquals(0.0, ticker.high, 0.0)
        assertEquals(0.0, ticker.low, 0.0)
        assertEquals("NEUTRAL", ticker.bias)
    }

    @Test
    fun quoteUpdatesCannotBeMixedIntoAnotherVenueCandleHistory() {
        assertTrue(canMergeQuoteIntoCandle("Binance Spot OHLC candles", "Binance Spot (USDT pairs)"))
        assertTrue(canMergeQuoteIntoCandle("Deriv WebSocket", "Deriv WebSocket"))
        assertTrue(canMergeQuoteIntoCandle("OANDA", "OANDA Pricing stream"))
        assertFalse(canMergeQuoteIntoCandle("Deriv WebSocket", "TradingView scanner snapshot"))
        assertFalse(canMergeQuoteIntoCandle("OANDA", "Binance Spot (USDT pairs)"))
        assertFalse(canMergeQuoteIntoCandle("", "OANDA"))
    }


    private fun quote(
        source: String,
        kind: String,
        timestamp: Long,
        bid: Double? = 1.0999,
        ask: Double? = 1.1001
    ) = QuoteView(
        symbol = "EUR/USD",
        price = 1.1,
        bid = bid,
        ask = ask,
        source = source,
        kind = kind,
        lastUpdateMillis = timestamp,
        hasRealSpread = bid != null && ask != null
    )

    @Test
    fun arbiterRejectsPolledQuoteWhenStreamIsFresh() {
        val existing = quote("Deriv WebSocket", "STREAM", 99_000L)
        val incoming = quote("TradingView scanner snapshot (FOREXCOM)", "POLLED_BIDASK", 100_001L)
        val result = QuoteArbiter.accept(
            existing, incoming, "Deriv WebSocket", 99_000L, 2_500L, 100_000L
        )
        assertTrue(result is Decision.Reject)
    }

    @Test
    fun arbiterRejectsMalformedQuotesAndDoesNotSilentlyAcceptWrongSource() {
        val existing = quote("Deriv WebSocket", "STREAM", 95_000L)
        val invalid = quote("Deriv WebSocket", "STREAM", 100_001L, bid = -1.0, ask = 1.1)
        assertTrue(QuoteArbiter.accept(existing, invalid, "Deriv WebSocket", 95_000L, 2_500L, 100_000L) is Decision.Reject)

        val wrongSource = quote("TradingView scanner snapshot (FOREXCOM)", "POLLED_BIDASK", 100_001L)
        assertTrue(QuoteArbiter.accept(
            existing, wrongSource, "Deriv WebSocket", 99_000L, 2_500L, 100_000L
        ) is Decision.Reject)
    }

    @Test
    fun arbiterAllowsSourceSwitchOnlyAfterActiveSourceIsSilent() {
        val existing = quote("Deriv WebSocket", "POLLED_BIDASK", 95_000L)
        val incoming = quote("TradingView scanner snapshot (FOREXCOM)", "POLLED_BIDASK", 100_001L)
        assertTrue(QuoteArbiter.accept(
            existing, incoming, "Deriv WebSocket", 99_000L, 2_500L, 100_000L
        ) is Decision.Reject)
        assertTrue(QuoteArbiter.accept(
            existing, incoming, "Deriv WebSocket", 90_000L, 2_500L, 100_000L
        ) is Decision.SwitchSource)
    }


    @Test
    fun freshStreamingProviderCannotBeReplacedByAnotherStream() {
        val active = quote("Deriv WebSocket", "STREAM", 99_000L)
        val competing = quote("OANDA Pricing stream", "STREAM", 100_001L)
        assertTrue(QuoteArbiter.accept(
            active, competing, "Deriv WebSocket", 99_000L, 2_500L, 100_000L
        ) is Decision.Reject)

        val staleActive = active.copy(lastUpdateMillis = 90_000L)
        assertTrue(QuoteArbiter.accept(
            staleActive, competing, "Deriv WebSocket", 90_000L, 2_500L, 100_000L
        ) is Decision.Accept)
    }

}
