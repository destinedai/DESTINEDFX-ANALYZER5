package com.example.ui.bot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LoginContractParityTest {
    private fun contract(): JSONObject {
        val candidates = listOf(File("../docs/contracts/mt5-login.json"), File("docs/contracts/mt5-login.json"))
        return JSONObject(candidates.first { it.isFile }.readText())
    }

    @Test fun sharedFixtureIsExplicitlyLabelledAndStagesMatchModel() {
        val json = contract()
        assertTrue(json.getBoolean("fixture"))
        val stages = json.getJSONArray("statuses")
        val expected = listOf("SENT", "HOST_QUEUED", "TERMINAL_LOGGING_IN", "HEARTBEAT", "VERIFIED")
        assertEquals(expected.size, stages.length())
        val actual = (0 until stages.length()).map { stages.getJSONObject(it).getString("stage") }
        assertEquals(expected, actual)
        actual.forEach { assertEquals(it, ConnectStage.valueOf(it).name) }
    }

    @Test fun everySharedFailureHasPlainSentence() {
        val failures = contract().getJSONArray("failures")
        val codes = (0 until failures.length()).map { failures.getString(it) }.toSet()
        assertEquals(ConnectFailures.knownCodes, codes)
        for (index in 0 until failures.length()) {
            val code = failures.getString(index)
            assertTrue(code, ConnectFailures.sentence(code).isNotBlank())
            assertTrue(code, !ConnectFailures.sentence(code).contains('\n'))
        }
    }

    @Test fun scenariosAreFixtureOnlyAndCoverTerminalAndHeartbeatFailures() {
        val scenarios = contract().getJSONArray("scenarios")
        val names = (0 until scenarios.length()).map { scenarios.getJSONObject(it).getString("name") }.toSet()
        assertTrue(names.containsAll(setOf("wrong_password", "wrong_server", "no_connection", "terminal_killed", "ea_killed", "stale_heartbeat", "mode_mismatch")))
    }
}
