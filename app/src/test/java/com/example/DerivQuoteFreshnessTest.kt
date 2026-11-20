package com.example

import com.example.data.deriv.isFreshDerivQuoteTimestamp
import com.example.data.deriv.maySendDerivPurchase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivQuoteFreshnessTest {
    @Test
    fun providerTimestampMustBeCurrentAtPurchaseBoundary() {
        val now = 1_800_000_000_000L
        assertTrue(isFreshDerivQuoteTimestamp(now, now))
        assertTrue(isFreshDerivQuoteTimestamp(now - 15_000L, now))
        assertTrue(isFreshDerivQuoteTimestamp(now + 2_000L, now))
        assertFalse(isFreshDerivQuoteTimestamp(0L, now))
        assertFalse(isFreshDerivQuoteTimestamp(now - 15_001L, now))
        assertFalse(isFreshDerivQuoteTimestamp(now + 2_001L, now))
    }

    @Test
    fun missingOrStaleQuoteRiskDataOrSessionBlocksPurchase() {
        assertTrue(maySendDerivPurchase(quoteFresh = true, riskSnapshotFresh = true, accountSessionCurrent = true))
        assertFalse(maySendDerivPurchase(quoteFresh = false, riskSnapshotFresh = true, accountSessionCurrent = true))
        assertFalse(maySendDerivPurchase(quoteFresh = true, riskSnapshotFresh = false, accountSessionCurrent = true))
        assertFalse(maySendDerivPurchase(quoteFresh = true, riskSnapshotFresh = true, accountSessionCurrent = false))
    }
}
