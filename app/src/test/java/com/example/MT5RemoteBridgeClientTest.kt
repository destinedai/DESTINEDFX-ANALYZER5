package com.example

import com.example.data.trade.MT5RemoteBridgeClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MT5RemoteBridgeClientTest {
    @Test fun riskMustBeFiniteAndWithinTheExactSupportedInterval() {
        assertTrue(MT5RemoteBridgeClient.isValidRiskPercent(0.25))
        assertTrue(MT5RemoteBridgeClient.isValidRiskPercent(1.0))
        assertFalse(MT5RemoteBridgeClient.isValidRiskPercent(0.0))
        assertFalse(MT5RemoteBridgeClient.isValidRiskPercent(-0.1))
        assertFalse(MT5RemoteBridgeClient.isValidRiskPercent(1.01))
        assertFalse(MT5RemoteBridgeClient.isValidRiskPercent(Double.NaN))
        assertFalse(MT5RemoteBridgeClient.isValidRiskPercent(Double.POSITIVE_INFINITY))
    }

    @Test fun commandPayloadMatchesProtocolAndCarriesStableRequestId() {
        val id = "2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18"
        val config = JSONObject().put("strategy", "ict-silver-bullet").put("risk_percent", 0.25)
        val request = MT5RemoteBridgeClient.commandPayload(id, "START", config)
        assertEquals(id, request.getString("request_id"))
        assertEquals("START", request.getString("action"))
        assertEquals("ict-silver-bullet", request.getJSONObject("config").getString("strategy"))
        assertEquals(0.25, request.getJSONObject("config").getDouble("risk_percent"), 0.0)
    }

    @Test fun commandPayloadRejectsUnknownActionAndNonUuid() {
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.commandPayload("2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18", "CLOSE_ALL")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.commandPayload("not-a-uuid", "STOP")
        }
    }

    @Test fun payloadPreservesCallerGeneratedRequestIdExactly() {
        val id = "2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18"
        assertEquals(id, MT5RemoteBridgeClient.commandPayload(id, "STOP").getString("request_id"))
    }
}
