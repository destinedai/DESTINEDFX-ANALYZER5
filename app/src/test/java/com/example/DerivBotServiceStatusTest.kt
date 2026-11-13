package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.service.DerivDemoBotService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DerivBotServiceStatusTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun blockedStartPersistsExactOwnerFacingReasonAndDisablesAutomaticRecovery() {
        context.getSharedPreferences("deriv_bot_config", 0).edit().clear().putBoolean("enabled", true).commit()
        val reason = "BOT NOT RUNNING — notification permission was denied."

        DerivDemoBotService.recordStartBlocked(context, reason)

        val prefs = context.getSharedPreferences("deriv_bot_config", 0)
        assertEquals("STOPPED", DerivDemoBotService.state.value)
        assertEquals(reason, DerivDemoBotService.statusReason.value)
        assertEquals(reason, prefs.getString("service_status_reason", null))
        assertFalse(prefs.getBoolean("enabled", true))
    }

    @Test
    fun previousRunningSnapshotIsNeverRestoredAsCurrentRunning() {
        DerivDemoBotService.recordStartBlocked(context, "BOT NOT RUNNING — test reset")
        context.getSharedPreferences("deriv_bot_config", 0).edit()
            .putString("service_status_state", "RUNNING")
            .putString("service_status_reason", "Running — prior process")
            .commit()

        DerivDemoBotService.restorePersistedStatus(context)

        assertEquals("STOPPED", DerivDemoBotService.state.value)
        assertTrue(DerivDemoBotService.statusReason.value.startsWith("BOT NOT RUNNING"))
        assertFalse(DerivDemoBotService.statusReason.value.contains("prior process"))
    }
}
