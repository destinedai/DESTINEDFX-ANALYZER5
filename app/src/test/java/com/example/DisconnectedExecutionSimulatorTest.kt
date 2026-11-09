package com.example

import com.example.ui.DisconnectedExecutionSimulator
import com.example.ui.SimulatedOrderState
import com.example.ui.SimulatorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectedExecutionSimulatorTest {
    @Test
    fun initialSnapshotIsClearlySynthetic() {
        val snapshot = DisconnectedExecutionSimulator.initialSnapshot()

        assertTrue(snapshot.identity.startsWith("SIM-"))
        assertEquals("Fictional Demo Markets", snapshot.broker)
        assertEquals("simulator-local", snapshot.server)
        assertEquals(SimulatedOrderState.IDLE, snapshot.orderState)
        assertTrue(DisconnectedExecutionSimulator.safetyNotice.contains("NO NETWORK"))
    }

    @Test
    fun demoOrderOnlyChangesLocalHypotheticalState() {
        val initial = DisconnectedExecutionSimulator.initialSnapshot()
        val next = DisconnectedExecutionSimulator.requestOrder(SimulatorMode.DEMO, initial)

        assertEquals(SimulatedOrderState.SIMULATED_FILLED, next.orderState)
        assertEquals(initial.openPositions + 1, next.openPositions)
        assertTrue(next.activity.contains("no request left the device"))
    }

    @Test
    fun liveLabelIsAlwaysBlocked() {
        val initial = DisconnectedExecutionSimulator.initialSnapshot()
        val next = DisconnectedExecutionSimulator.requestOrder(SimulatorMode.LIVE, initial)

        assertEquals(SimulatedOrderState.BLOCKED_LIVE, next.orderState)
        assertEquals(initial.openPositions, next.openPositions)
        assertTrue(next.activity.contains("cannot authenticate or execute"))
    }
}
