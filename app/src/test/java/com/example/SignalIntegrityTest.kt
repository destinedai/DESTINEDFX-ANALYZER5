package com.example.domain.usecase.impl

import com.destinedfx.core.analysis.SignalState
import com.destinedfx.core.analysis.Target
import com.destinedfx.core.analysis.TradePlan
import com.example.domain.model.SignalDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalIntegrityTest {
    private fun plan(
        isLong: Boolean = true,
        entry: Double = 100.0,
        stop: Double = if (isLong) 98.0 else 102.0,
        targetPrice: Double = if (isLong) 104.0 else 96.0,
        reportedRr: Double = 2.0
    ) = TradePlan(
        isLong = isLong,
        entry = entry,
        stopLoss = stop,
        slAtr = 1.5,
        targets = listOf(Target("TP1", targetPrice, reportedRr, "test")),
        primary = Target("TP1", targetPrice, reportedRr, "test"),
        slReason = "test structure"
    )

    @Test
    fun stableIdentityDoesNotChangeAcrossRepeatedScansOfTheSameClosedCandle() {
        val first = stableAnalysisSignalId("EUR/USD", "H1", "smc-confluence-1.0.0", 1_800_000L)
        val repeated = stableAnalysisSignalId("eur/usd", " h1 ", "smc-confluence-1.0.0", 1_800_000L)
        assertEquals(first, repeated)
        java.util.UUID.fromString(first)
    }

    @Test
    fun stableIdentityChangesWhenTheClosedCandleChanges() {
        val first = stableAnalysisSignalId("EUR/USD", "H1", "smc-confluence-1.0.0", 1_800_000L)
        val next = stableAnalysisSignalId("EUR/USD", "H1", "smc-confluence-1.0.0", 1_803_600L)
        assertNotEquals(first, next)
    }

    @Test
    fun validatesActualPriceRewardRiskInsteadOfTrustingReportedRr() {
        assertTrue(isValidExecutionPlan(plan()))
        assertTrue(isValidExecutionPlan(plan(isLong = false)))
        assertFalse(isValidExecutionPlan(plan(targetPrice = 103.9, reportedRr = 2.0)))
        assertFalse(isValidExecutionPlan(plan(stop = 101.0)))
        assertFalse(isValidExecutionPlan(null))
    }

    @Test
    fun waitAndBlockedStatesNeverBecomeDirectionalSignals() {
        val longPlan = plan()
        assertEquals(SignalDirection.WAIT, signalDirectionFor(SignalState.WAIT, longPlan))
        assertEquals(SignalDirection.WAIT, signalDirectionFor(SignalState.BLOCKED, longPlan))
        assertEquals(SignalDirection.LONG, signalDirectionFor(SignalState.BUY, longPlan))
        assertEquals(SignalDirection.WAIT, signalDirectionFor(SignalState.BUY, plan(isLong = false)))
        assertEquals(SignalDirection.SHORT, signalDirectionFor(SignalState.SELL, plan(isLong = false)))
        assertEquals(SignalDirection.WAIT, signalDirectionFor(SignalState.SELL, longPlan))
    }
}
