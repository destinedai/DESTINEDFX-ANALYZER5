package com.example

import com.example.service.AccountSessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MT5AccountSessionRegistryTest {
    @Test fun brokerAndModeAreParsedAsIndependentProfileDimensions() {
        assertEquals("FBS" to "DEMO", AccountSessionRegistry.brandAndMode("FBS_DEMO"))
        assertEquals("EXNESS" to "DEMO", AccountSessionRegistry.brandAndMode("EXNESS_DEMO"))
        assertEquals("HFM" to "LIVE", AccountSessionRegistry.brandAndMode("HFM_LIVE"))
        assertEquals("DERIV" to "DEMO", AccountSessionRegistry.brandAndMode("DERIV_DEMO"))
    }

    @Test fun unknownOrIncompleteProfilesAreRejected() {
        assertNull(AccountSessionRegistry.brandAndMode("UNKNOWN_DEMO"))
        assertNull(AccountSessionRegistry.brandAndMode("FBS"))
        assertNull(AccountSessionRegistry.brandAndMode("FBS_PAPER"))
    }
}
