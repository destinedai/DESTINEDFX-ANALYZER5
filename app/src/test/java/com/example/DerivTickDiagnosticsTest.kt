package com.example

import com.example.data.deriv.classifyDerivTickProviderError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivTickDiagnosticsTest {
    @Test
    fun onlyProviderExplicitMarketClosedResponseClaimsMarketClosed() {
        val closed = classifyDerivTickProviderError("MarketIsClosed", "Market is closed right now")
        assertTrue(closed.marketClosed)
        assertTrue(closed.diagnostic.startsWith("MARKET CLOSED"))
        assertTrue(classifyDerivTickProviderError("MarketIsClosed", "Temporary provider response").marketClosed)

        val generic = classifyDerivTickProviderError("RateLimit", "Too many requests")
        assertFalse(generic.marketClosed)
        assertTrue(generic.diagnostic.startsWith("SUBSCRIPTION REJECTED"))

        val quietFeedIsNotMarketClosed = classifyDerivTickProviderError("UNKNOWN", "No response")
        assertFalse(quietFeedIsNotMarketClosed.marketClosed)
    }

    @Test
    fun providerDiagnosticsRedactCredentialLikeValues() {
        val result = classifyDerivTickProviderError("AuthError", "token=secret-value")
        assertFalse(result.diagnostic.contains("secret-value"))
        assertTrue(result.diagnostic.contains("[redacted]"))
    }
}
