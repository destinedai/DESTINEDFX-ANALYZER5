package com.example

import com.example.ui.stopAndRevokeManagedMt5ForSwitch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class MT5AccountSwitchSafetyTest {
    @Test
    fun demoSwitchStopsAndVerifiesBeforeRevokingWithoutLiveDisarm() = runBlocking {
        val events = mutableListOf<String>()
        stopAndRevokeManagedMt5ForSwitch(
            accountMode = "DEMO",
            revocationPending = { false },
            clearLiveAuthorization = { events += "DISARM_LIVE" },
            stopAndConfirm = { events += "STOP_CONFIRMED"; "BOT STOPPED - All Bot trades closed" },
            revoke = { events += "LOGOUT" },
        )
        assertEquals(listOf("STOP_CONFIRMED", "LOGOUT"), events)
        assertFalse(events.contains("DISARM_LIVE"))
    }

    @Test
    fun liveSwitchDisarmsThenStopsAndLogsOutInOrder() = runBlocking {
        val events = mutableListOf<String>()
        stopAndRevokeManagedMt5ForSwitch(
            accountMode = "LIVE",
            revocationPending = { false },
            clearLiveAuthorization = { events += "DISARM_LIVE" },
            stopAndConfirm = { events += "STOP_CONFIRMED"; "BOT STOPPED - All Bot trades closed" },
            revoke = { events += "LOGOUT" },
        )
        assertEquals(listOf("DISARM_LIVE", "STOP_CONFIRMED", "LOGOUT"), events)
    }

    @Test
    fun failedCloseoutBlocksLogoutAndAccountSwitch() = runBlocking {
        var revoked = false
        try {
            stopAndRevokeManagedMt5ForSwitch(
                accountMode = "DEMO",
                revocationPending = { false },
                clearLiveAuthorization = {},
                stopAndConfirm = { "BOT STOPPED - close-out not confirmed; 1 Bot position(s) remain." },
                revoke = { revoked = true },
            )
            fail("The old MT5 account must not be logged out while a Bot trade remains.")
        } catch (expected: IllegalStateException) {
            assertFalse(revoked)
        }
    }

    @Test
    fun unknownAccountModeCannotBeSwitched() = runBlocking {
        try {
            stopAndRevokeManagedMt5ForSwitch(
                accountMode = "UNKNOWN",
                revocationPending = { false },
                clearLiveAuthorization = {},
                stopAndConfirm = { "BOT STOPPED - All Bot trades closed" },
                revoke = {},
            )
            fail("An unverified account mode must not be switched.")
        } catch (expected: IllegalArgumentException) {
            // Expected: fail closed before any account action.
        }
    }
    @Test
    fun liveSwitchResumesExistingRevocationWithoutTouchingTerminatedTerminal() = runBlocking {
        val events = mutableListOf<String>()
        stopAndRevokeManagedMt5ForSwitch(
            accountMode = "LIVE",
            revocationPending = { events += "REVOCATION_PENDING"; true },
            clearLiveAuthorization = { events += "DISARM_LIVE" },
            stopAndConfirm = { events += "STOP_UNEXPECTED"; "BOT STOPPED - All Bot trades closed" },
            revoke = { events += "LOGOUT_CONFIRMED" },
        )
        assertEquals(listOf("REVOCATION_PENDING", "LOGOUT_CONFIRMED"), events)
        assertFalse(events.contains("STOP_UNEXPECTED"))
        assertFalse(events.contains("DISARM_LIVE"))
    }

}
