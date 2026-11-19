package com.example.data.trade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MT5BridgeIsolationPolicyTest {
    @Test
    fun acceptsOnlyExplicitlyKnownTerminalIsolationStates() {
        assertTrue(isManagedTerminalIsolationStatusAccepted("VERIFIED"))
        assertTrue(isManagedTerminalIsolationStatusAccepted("WORKER_ATTESTATION_REQUIRED"))
        assertFalse(isManagedTerminalIsolationStatusAccepted("BLOCKED"))
        assertFalse(isManagedTerminalIsolationStatusAccepted("UNKNOWN"))
        assertFalse(isManagedTerminalIsolationStatusAccepted(""))
        assertFalse(isManagedTerminalIsolationStatusAccepted("verified-but-unrecognized"))
    }
}
