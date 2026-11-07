package com.example

import com.example.domain.usecase.impl.ReadOnlyExecutionPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyExecutionPolicyTest {
    @Test
    fun tradingIsRejectedWithAnExplicitReadOnlyReason() {
        try {
            ReadOnlyExecutionPolicy.requireTradingDisabled()
            throw AssertionError("Trading boundary unexpectedly allowed the request")
        } catch (expected: UnsupportedOperationException) {
            assertTrue(expected.message.orEmpty().contains(ReadOnlyExecutionPolicy.REJECTION_CODE))
            assertTrue(expected.message.orEmpty().contains("order submission"))
        }
    }
}
