package com.example

import com.example.data.deriv.DerivMultiplierRules
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
    fun repeatedScansInsideOneCandleProduceTheSameKey() {
        val a = DerivMultiplierRules.stableSignalKey("1HZ100V", "smc", "M5", SignalDirection.LONG, barStart + 1_000)
        val b = DerivMultiplierRules.stableSignalKey("1HZ100V", "smc", "M5", SignalDirection.LONG, barStart + 31_000)
        assertNotNull(a)
        assertEquals(a, b)
    }

    @Test
    fun nextCandleDirectionStrategyOrSymbolChangeTheKey() {
        val base = DerivMultiplierRules.stableSignalKey("1HZ100V", "smc", "M5", SignalDirection.LONG, barStart)
        assertNotEquals(base, DerivMultiplierRules.stableSignalKey("1HZ100V", "smc", "M5", SignalDirection.LONG, barStart + m5))
        assertNotEquals(base, DerivMultiplierRules.stableSignalKey("1HZ100V", "smc", "M5", SignalDirection.SHORT, barStart))
        assertNotEquals(base, DerivMultiplierRules.stableSignalKey("1HZ100V", "wyckoff", "M5", SignalDirection.LONG, barStart))
        assertNotEquals(base, DerivMultiplierRules.stableSignalKey("R_50", "smc", "M5", SignalDirection.LONG, barStart))
    }

    @Test
    fun unknownTimeframeOrMissingInputsYieldNoKey() {
        assertNull(DerivMultiplierRules.stableSignalKey("1HZ100V", "smc", "M7", SignalDirection.LONG, barStart))
        assertNull(DerivMultiplierRules.stableSignalKey("", "smc", "M5", SignalDirection.LONG, barStart))
        assertNull(DerivMultiplierRules.stableSignalKey("1HZ100V", "smc", "M5", SignalDirection.LONG, 0L))
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
