package com.example

import com.destinedfx.core.marketdata.Decision
import com.destinedfx.core.marketdata.QuoteArbiter
import com.destinedfx.core.marketdata.QuoteView
import com.example.data.market.QuoteFreshness
import com.example.data.market.DERIV_ACTIVE_SYMBOLS_TIMEOUT_MS
import com.example.data.market.DERIV_SYMBOL_RESOLUTION_TIMEOUT_MS
import com.example.data.market.DERIV_CANDLE_HISTORY_TIMEOUT_MS
import com.example.data.market.classifyQuoteFreshness
import com.example.data.market.candleSourceKey
import com.example.data.market.canMergeQuoteIntoCandle
import com.example.data.market.canonicalTickerAfterRefresh
import com.example.data.market.processPollingItemsIndependently
import com.example.data.market.resilientPollingLoop
import com.example.data.market.sanitizeOhlcCandles
import com.example.data.market.isCandleSnapshotFresh
import com.example.data.market.isExplicitDerivPublicSource
import com.example.data.market.buildDerivCanonicalSymbolMap
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
    fun derivOuterTimeoutsAllowNestedConnectAndProviderResponseBudgetsToFinish() {
        // Deriv active-symbol discovery waits up to 16s for the WebSocket plus 12s
        // for its response. The repository must not cancel that request at 5s or 18s.
        assertTrue(DERIV_ACTIVE_SYMBOLS_TIMEOUT_MS > 16_000L + 12_000L)
        assertTrue(DERIV_SYMBOL_RESOLUTION_TIMEOUT_MS > DERIV_ACTIVE_SYMBOLS_TIMEOUT_MS)

        // Candle history waits up to 16s for the socket plus 10s for the history response.
        assertTrue(DERIV_CANDLE_HISTORY_TIMEOUT_MS > 16_000L + 10_000L)
    }

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
            isLive = true, source = "Deriv public WebSocket", kind = "STALE"
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
    fun rejectedRefreshReturnsExistingCanonicalQuoteInsteadOfFetchedQuote() {
        val existing = MarketTicker(
            symbol = "EUR/USD", price = 1.1, changePercent = 0.0, high = 0.0, low = 0.0,
            spread = 0.0002, bias = "NEUTRAL", confidence = 0, lastUpdateMillis = 99_000L,
            isLive = true, source = "Deriv public WebSocket", kind = "STREAM",
            bid = 1.0999, ask = 1.1001, hasRealSpread = true
        )
        val rejected = existing.copy(price = 1.2, source = "Unverified external chart snapshot", lastUpdateMillis = 100_000L)
        assertEquals(existing, canonicalTickerAfterRefresh(existing, rejected, accepted = false))
        assertEquals(rejected, canonicalTickerAfterRefresh(existing, rejected, accepted = true))
        val staleCanonical = existing.copy(lastUpdateMillis = 80_000L)
        assertEquals(staleCanonical, canonicalTickerAfterRefresh(staleCanonical, rejected, accepted = false))
        assertEquals(QuoteFreshness.STALE, classifyQuoteFreshness(staleCanonical, 100_000L))
        assertEquals("Deriv public WebSocket", canonicalTickerAfterRefresh(existing, rejected, accepted = false)?.source)
        assertEquals(99_000L, canonicalTickerAfterRefresh(existing, rejected, accepted = false)?.lastUpdateMillis)
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
    fun quoteUpdatesCannotBeMixedIntoAnotherVenueCandleHistory() {
        assertTrue(canMergeQuoteIntoCandle("Deriv public OHLC", "Deriv public WebSocket"))
        assertTrue(canMergeQuoteIntoCandle("OANDA", "OANDA Pricing stream"))
        assertFalse(canMergeQuoteIntoCandle("Deriv public OHLC", "Unverified external chart snapshot"))
        assertFalse(canMergeQuoteIntoCandle("OANDA", "Deriv public WebSocket"))
        assertFalse(canMergeQuoteIntoCandle("", "OANDA"))
    }

    @Test
    fun analyzerUsesPublicDerivMarketFeedAndVerifiesCryptoMappingsToo() {
        assertTrue(isExplicitDerivPublicSource("DERIV_PUBLIC"))
        assertTrue(isExplicitDerivPublicSource("deriv_public"))
        assertFalse(isExplicitDerivPublicSource("TRADINGVIEW"))
        assertFalse(isExplicitDerivPublicSource("BINANCE"))
        assertFalse(isExplicitDerivPublicSource("DERIV_MT5_DEMO"))
        assertEquals("BTC/USD", buildDerivCanonicalSymbolMap(listOf("BTC/USD"))["cryBTCUSD"])
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
        val existing = quote("Deriv public WebSocket", "STREAM", 99_000L)
        val incoming = quote("Unverified external polled quote", "POLLED_BIDASK", 100_000L)
        val result = QuoteArbiter.accept(
            existing, incoming, "Deriv public WebSocket", 99_000L, 2_500L, 100_000L
        )
        assertTrue(result is Decision.Reject)
    }

    @Test
    fun arbiterRejectsMalformedQuotesAndDoesNotSilentlyAcceptWrongSource() {
        val existing = quote("Deriv public WebSocket", "STREAM", 95_000L)
        val invalid = quote("Deriv public WebSocket", "STREAM", 100_000L, bid = -1.0, ask = 1.1)
        assertTrue(QuoteArbiter.accept(existing, invalid, "Deriv public WebSocket", 95_000L, 2_500L, 100_000L) is Decision.Reject)

        val wrongSource = quote("Unverified external chart snapshot (External venue)", "POLLED_BIDASK", 100_000L)
        assertTrue(QuoteArbiter.accept(
            existing, wrongSource, "Deriv public WebSocket", 99_000L, 2_500L, 100_000L
        ) is Decision.Reject)
    }

    @Test
    fun arbiterAllowsSourceSwitchOnlyAfterActiveSourceIsSilent() {
        val existing = quote("Deriv public WebSocket", "POLLED_BIDASK", 95_000L)
        val incoming = quote("Unverified external chart snapshot (External venue)", "POLLED_BIDASK", 100_000L)
        assertTrue(QuoteArbiter.accept(
            existing, incoming, "Deriv public WebSocket", 99_000L, 2_500L, 100_000L
        ) is Decision.Reject)
        assertTrue(QuoteArbiter.accept(
            existing, incoming, "Deriv public WebSocket", 90_000L, 2_500L, 100_000L
        ) is Decision.SwitchSource)
    }


    @Test
    fun freshStreamingProviderCannotBeReplacedByAnotherStream() {
        val active = quote("Deriv public WebSocket", "STREAM", 99_000L)
        val competing = quote("OANDA Pricing stream", "STREAM", 100_000L)
        assertTrue(QuoteArbiter.accept(
            active, competing, "Deriv public WebSocket", 99_000L, 2_500L, 100_000L
        ) is Decision.Reject)

        val staleActive = active.copy(lastUpdateMillis = 90_000L)
        assertTrue(QuoteArbiter.accept(
            staleActive, competing, "Deriv public WebSocket", 90_000L, 2_500L, 100_000L
        ) is Decision.Accept)
    }

    @Test
    fun arbiterRejectsFutureAndNonFiniteQuotesBeforeCanonicalization() {
        val existing = quote("Deriv public WebSocket", "STREAM", 99_000L)
        val future = quote("Deriv public WebSocket", "STREAM", 100_001L)
        val nanPrice = future.copy(price = Double.NaN, lastUpdateMillis = 100_000L)
        assertTrue(QuoteArbiter.accept(existing, future, "Deriv public WebSocket", 99_000L, 2_500L, 100_000L) is Decision.Reject)
        assertTrue(QuoteArbiter.accept(existing, nanPrice, "Deriv public WebSocket", 99_000L, 2_500L, 100_000L) is Decision.Reject)
    }

    @Test
    fun candleSnapshotChecksRetrievalAgeAndLatestClosedBar() {
        val now = 500_000_000L
        assertTrue(isCandleSnapshotFresh(now - 1_000L, now - 60_000L, now, "M1"))
        assertFalse(isCandleSnapshotFresh(now - 30_000L, now - 60_000L, now, "M1"))
        assertFalse(isCandleSnapshotFresh(now + 1L, now - 60_000L, now, "M1"))
        assertFalse(isCandleSnapshotFresh(now - 1_000L, 0L, now, "M1"))
        assertFalse(isCandleSnapshotFresh(now - 1_000L, now + 1L, now, "M1"))
        assertFalse(isCandleSnapshotFresh(now - 1_000L, now - 300_000L, now, "M1"))
        assertTrue(isCandleSnapshotFresh(
            now - 1_000L, now - 48L * 60L * 60L * 1000L, now, "H1",
            allowWeekendGap = true
        ))
        assertFalse(isCandleSnapshotFresh(
            now - 1_000L, now - 48L * 60L * 60L * 1000L, now, "H1"
        ))
        assertFalse(isCandleSnapshotFresh(now - 1_000L, now - 60_000L, now, "UNKNOWN"))
    }

}
