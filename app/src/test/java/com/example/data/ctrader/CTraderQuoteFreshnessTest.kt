package com.example.data.ctrader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CTraderQuoteFreshnessTest {
    private val now = 1_000_000L

    @Test
    fun requiresMatchingAccountSymbolValidTimestampsAndAtLeastOnePrice() {
        val quote = CTraderQuote(7L, 42L, 1.10000, null, now - 1_000L, now - 500L)
        assertTrue(quote.isFresh(expectedAccountId = 7L, expectedSymbolId = 42L, nowMs = now))
        assertFalse(quote.isFresh(expectedAccountId = 8L, expectedSymbolId = 42L, nowMs = now))
        assertFalse(quote.isFresh(expectedAccountId = 7L, expectedSymbolId = 43L, nowMs = now))
        assertFalse(quote.copy(bid = null, ask = null).isFresh(nowMs = now))
    }

    @Test
    fun rejectsFutureStaleAndInvalidPrices() {
        assertFalse(CTraderQuote(7L, 42L, 1.1, null, now + 1L, now - 1L).isFresh(nowMs = now))
        assertFalse(CTraderQuote(7L, 42L, 1.1, null, now - 15_001L, now - 1L).isFresh(nowMs = now))
        assertFalse(CTraderQuote(7L, 42L, Double.NaN, 1.1, now - 1L, now - 1L).isFresh(nowMs = now))
        assertFalse(CTraderQuote(7L, 42L, -1.0, null, now - 1L, now - 1L).isFresh(nowMs = now))
        assertFalse(CTraderQuote(7L, 42L, 1.1, null, now - 1L, now + 1L).isFresh(nowMs = now))
    }
}
