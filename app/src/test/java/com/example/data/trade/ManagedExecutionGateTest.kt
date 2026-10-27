package com.example.data.trade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedExecutionGateTest {
    @Test fun startRequiresReadyFreshAuthenticatedMasterWithTradePermission() {
        assertTrue(MT5RemoteBridgeClient.canStart("READY", "MASTER", true, true, true, true))
        assertFalse(MT5RemoteBridgeClient.canStart("PROVISIONING", "MASTER", true, true, true, true))
        assertFalse(MT5RemoteBridgeClient.canStart("READY", "MASTER", false, true, true, true))
        assertFalse(MT5RemoteBridgeClient.canStart("READY", "MASTER", true, false, true, true))
        assertFalse(MT5RemoteBridgeClient.canStart("READY", "MASTER", true, true, false, true))
    }

    @Test fun investorIsAlwaysReadOnly() {
        assertFalse(MT5RemoteBridgeClient.canStart("READY", "INVESTOR", true, true, true, true))
    }
}
