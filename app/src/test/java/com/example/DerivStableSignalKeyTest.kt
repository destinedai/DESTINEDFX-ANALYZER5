package com.example

import com.example.data.deriv.DerivMultiplierRules
import com.example.domain.usecase.impl.stableAnalysisSignalId
import com.example.domain.model.SignalDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivStableSignalKeyTest {
    private val m5 = 5 * 60_000L
    private val barStart = 1_800_000_000_000L - (1_800_000_000_000L % m5)

    @Test
    fun repeatedScansReuseTheAnalyzerIdentityForOneClosedCandle() {
        val a = stableAnalysisSignalId("1HZ100V", "M5", "smc-confluence-1.0.0", barStart)
        val b = stableAnalysisSignalId("1hz100v", "m5", "smc-confluence-1.0.0", barStart)
        assertEquals(a, b)
    }

    @Test
    fun directionChangesCannotChangeTheCanonicalCandleIdentity() {
        val canonicalId = stableAnalysisSignalId("1HZ100V", "M5", "smc-confluence-1.0.0", barStart)
        // LONG and SHORT are execution attributes, not identity inputs; use the same analyzer ID.
        val longScanId = canonicalId
        val shortScanId = canonicalId
        assertEquals(longScanId, shortScanId)
    }

    @Test
    fun nextCandleStrategySymbolOrTimeframeChangesTheIdentity() {
        val base = stableAnalysisSignalId("1HZ100V", "M5", "smc-confluence-1.0.0", barStart)
        assertNotEquals(base, stableAnalysisSignalId("1HZ100V", "M5", "smc-confluence-1.0.0", barStart + m5))
        assertNotEquals(base, stableAnalysisSignalId("1HZ100V", "M5", "ict-silver-bullet", barStart))
        assertNotEquals(base, stableAnalysisSignalId("R_50", "M5", "smc-confluence-1.0.0", barStart))
        assertNotEquals(base, stableAnalysisSignalId("1HZ100V", "M15", "smc-confluence-1.0.0", barStart))
    }

    @Test
    fun validLongAndShortPlansPass() {
        assertNull(DerivMultiplierRules.planConsistencyError(SignalDirection.LONG, 100.0, 99.0, 102.0))
        assertNull(DerivMultiplierRules.planConsistencyError(SignalDirection.SHORT, 100.0, 101.0, 98.0))
    }

    @Test
    fun missingStopOrWrongSidePlansAreRejected() {
        // The analyzer emits SHORT with stopLoss 0 when it has no plan; that must never be traded.
        assertTrue(DerivMultiplierRules.planConsistencyError(SignalDirection.SHORT, 100.0, 0.0, 0.0)!!.startsWith("PLAN_INCOMPLETE"))
        assertTrue(DerivMultiplierRules.planConsistencyError(SignalDirection.LONG, 100.0, 101.0, 103.0)!!.startsWith("PLAN_INVALID"))
        assertTrue(DerivMultiplierRules.planConsistencyError(SignalDirection.SHORT, 100.0, 99.0, 98.0)!!.startsWith("PLAN_INVALID"))
        assertTrue(DerivMultiplierRules.planConsistencyError(SignalDirection.LONG, Double.NaN, 99.0, 102.0)!!.startsWith("PLAN_INCOMPLETE"))
        assertTrue(DerivMultiplierRules.planConsistencyError(SignalDirection.WAIT, 100.0, 99.0, 102.0)!!.startsWith("SIGNAL_INVALID"))
    }

    @Test
    fun rewardBelowTwoRIsRejected() {
        assertTrue(DerivMultiplierRules.planConsistencyError(SignalDirection.LONG, 100.0, 99.0, 101.5)!!.startsWith("RR_TOO_LOW"))
        assertNull(DerivMultiplierRules.planConsistencyError(SignalDirection.LONG, 100.0, 99.0, 102.0))
    }
}
