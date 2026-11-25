package com.example

import com.example.data.broker.BrokerEndpointVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerEndpointVerifierTest {
    @Test fun hfmLiveServerUsesOnlyOfficialPublishedHostAndPort() {
        val endpoint = BrokerEndpointVerifier.officialEndpointFor("HFM", "HFMarketsGlobal-Live1 Server")
        assertNotNull(endpoint)
        assertEquals("mt5-global.dcglobalfarm.com", endpoint!!.host)
        assertEquals(1951, endpoint.port)
        assertEquals("LIVE", endpoint.mode)
        assertTrue(endpoint.sourceUrl.startsWith("https://www.hfm.com/"))
    }

    @Test fun hfmDemoEndpointIsNotAcceptedAsALiveEndpoint() {
        val endpoint = BrokerEndpointVerifier.officialEndpointFor("HFM", "HFMarketsGlobal-Demo")
        assertNotNull(endpoint)
        assertEquals("DEMO", endpoint!!.mode)
    }

    @Test fun unknownHfmServerIsNotGuessed() {
        assertNull(BrokerEndpointVerifier.officialEndpointFor("HFM", "HFM-MT5Real-999"))
    }

    @Test fun accountSpecificBrokerLabelsAreNeverInventedAsDnsHosts() {
        assertNull(BrokerEndpointVerifier.officialEndpointFor("FBS", "FBS-Real"))
        assertNull(BrokerEndpointVerifier.officialEndpointFor("EXNESS", "Exness-MT5Real"))
        assertNull(BrokerEndpointVerifier.officialEndpointFor("DERIV", "Deriv-Real"))
    }

    @Test fun everyManagedMt5BrokerHasAnOfficialServerInstruction() {
        listOf("FBS", "EXNESS", "HFM", "DERIV").forEach { brand ->
            assertTrue(BrokerEndpointVerifier.officialServerReference(brand, "LIVE").isNotBlank())
        }
    }
}
