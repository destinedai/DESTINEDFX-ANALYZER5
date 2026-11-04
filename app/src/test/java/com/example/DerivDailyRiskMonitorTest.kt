package com.example

import com.example.data.deriv.DerivDailyRiskMonitor
import com.example.data.deriv.DerivDailyRiskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class DerivDailyRiskMonitorTest {
    @Test fun normalAndWarningThresholdsUseTheConfiguredDailyLimit() {
        val normal = DerivDailyRiskMonitor.evaluate(1_000.0, 990.0)
        assertEquals(DerivDailyRiskSnapshot.Status.NORMAL, normal.status)

        val warning80 = DerivDailyRiskMonitor.evaluate(1_000.0, 984.0)
        assertEquals(DerivDailyRiskSnapshot.Status.WARNING_80, warning80.status)
        assertEquals(80.0, warning80.limitUsedPercent, 1e-9)

        val warning90 = DerivDailyRiskMonitor.evaluate(1_000.0, 982.0)
        assertEquals(DerivDailyRiskSnapshot.Status.WARNING_90, warning90.status)
        assertEquals(90.0, warning90.limitUsedPercent, 1e-9)
    }

    @Test fun fullLimitLocksAndCannotBeClearedByEquityRecovery() {
        val breached = DerivDailyRiskMonitor.evaluate(1_000.0, 980.0)
        assertEquals(DerivDailyRiskSnapshot.Status.LOCKED, breached.status)
        val recovered = DerivDailyRiskMonitor.evaluate(1_000.0, 995.0, alreadyLocked = true)
        assertEquals(DerivDailyRiskSnapshot.Status.LOCKED, recovered.status)
    }

    @Test fun missingBaselineMayOnlyBeCapturedNearUtcDayStart() {
        assertEquals(true, DerivDailyRiskMonitor.canInitializeDailyBaseline(60_000L))
        assertEquals(true, DerivDailyRiskMonitor.canInitializeDailyBaseline(120_000L))
        assertEquals(false, DerivDailyRiskMonitor.canInitializeDailyBaseline(121_000L))
        assertEquals(false, DerivDailyRiskMonitor.canInitializeDailyBaseline(12 * 60 * 60 * 1000L))
    }

    @Test fun persistedLockTakesPriorityOverUnavailableBrokerData() {
        val locked = DerivDailyRiskMonitor.evaluate(
            startingEquity = null,
            currentEquity = null,
            alreadyLocked = true
        )
        assertEquals(DerivDailyRiskSnapshot.Status.LOCKED, locked.status)
    }

    @Test fun missingOrInvalidBaselineFailsClosed() {
        assertEquals(DerivDailyRiskSnapshot.Status.DATA_UNAVAILABLE,
            DerivDailyRiskMonitor.evaluate(null, 100.0).status)
        assertEquals(DerivDailyRiskSnapshot.Status.DATA_UNAVAILABLE,
            DerivDailyRiskMonitor.evaluate(0.0, 100.0).status)
    }
}
