package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.service.DerivDemoBotService
import com.example.security.SecretsManager
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

        val secure = SecretsManager(context)
        assertEquals("STOPPED", DerivDemoBotService.state.value)
        assertEquals(reason, DerivDemoBotService.statusReason.value)
        if (secure.isSecureStorageAvailable) {
            assertEquals("STOPPED", secure.getDerivBotServiceState())
            assertEquals(reason, secure.getDerivBotServiceReason())
            assertFalse(secure.isDerivBotRecoveryEnabled())
        }
        assertTrue(context.getSharedPreferences("deriv_bot_config", 0).all.isEmpty())
    }

    @Test
    fun previousRunningSnapshotIsNeverRestoredAsCurrentRunning() {
        val secure = SecretsManager(context)
        if (!secure.isSecureStorageAvailable) return
        assertTrue(secure.saveDerivBotRecoveryConfiguration("R_100", "smc-confluence-1.0.0", "M15"))
        assertTrue(secure.setDerivBotRecoveryEnabled(true))
        assertTrue(secure.saveDerivBotServiceStatus("RUNNING", "Running — prior process"))
        
        DerivDemoBotService.restorePersistedStatus(context)

        assertEquals("STOPPED", DerivDemoBotService.state.value)
        assertTrue(DerivDemoBotService.statusReason.value.startsWith("BOT NOT RUNNING"))
        assertFalse(DerivDemoBotService.statusReason.value.contains("prior process"))
        assertTrue(secure.isDerivBotRecoveryEnabled())
    }
}
