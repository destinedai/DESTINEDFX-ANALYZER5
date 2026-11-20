package com.example.data.codespaces

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStopPreflightTest {
    private fun cleanReport(): JSONObject =
        JSONObject().put("safe", true).put("reasons", JSONArray())

    private fun acceptedResponse(): JSONObject =
        JSONObject()
            .put("status", "PREPARED")
            .put("host_generation", "host-generation-fixture")
            .put("backup_name", "bridge-backup-fixture.sqlite3")
            .put("backup_verified", true)
            .put("stop_script_ack", true)
            .put("stop_script_status", "PREFLIGHT_PASSED")
            .put("broker_safety", cleanReport())
            .put("backup_safety", cleanReport())

    @Test
    fun acceptsOnlyCompleteCurrentAndBackupSafetyAcknowledgements() {
        assertTrue(isSafeHostStopPreflight(acceptedResponse()))
    }

    @Test
    fun missingBrokerOrBackupSafetyReportFailsClosed() {
        assertFalse(isSafeHostStopPreflight(acceptedResponse().put("broker_safety", JSONObject.NULL)))
        assertFalse(isSafeHostStopPreflight(acceptedResponse().put("backup_safety", JSONObject.NULL)))
    }

    @Test
    fun activePositionsOrAnySafetyReasonBlocksStop() {
        val currentUnsafe = cleanReport().put("safe", false)
            .put("reasons", JSONArray().put("OPEN_POSITIONS"))
        assertFalse(isSafeHostStopPreflight(acceptedResponse().put("broker_safety", currentUnsafe)))

        val backupUnsafe = cleanReport().put("safe", false)
            .put("reasons", JSONArray().put("EA_STATE_UNVERIFIED"))
        assertFalse(isSafeHostStopPreflight(acceptedResponse().put("backup_safety", backupUnsafe)))
    }

    @Test
    fun missingGenerationBackupOrLocalStopAcknowledgementFailsClosed() {
        assertFalse(isSafeHostStopPreflight(acceptedResponse().put("host_generation", "")))
        assertFalse(isSafeHostStopPreflight(acceptedResponse().put("backup_name", "")))
        assertFalse(isSafeHostStopPreflight(acceptedResponse().put("backup_verified", false)))
        assertFalse(isSafeHostStopPreflight(acceptedResponse().put("stop_script_ack", false)))
        assertFalse(isSafeHostStopPreflight(acceptedResponse().put("stop_script_status", "UNKNOWN")))
    }

    @Test
    fun absentOrMalformedResponseFailsClosed() {
        assertFalse(isSafeHostStopPreflight(null))
        assertFalse(isSafeHostStopPreflight(JSONObject().put("status", "PREPARED")))
        assertFalse(isSafeHostStopPreflight(acceptedResponse().put("broker_safety",
            JSONObject().put("safe", true))))
    }
}
