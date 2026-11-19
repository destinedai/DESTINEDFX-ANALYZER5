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
        val legacy = context.getSharedPreferences("deriv_bot_config", 0)
        if (secure.isSecureStorageAvailable) {
            assertTrue("Legacy recovery preferences must be cleared after encrypted migration", legacy.all.isEmpty())
        } else {
            // Preserve the only copy until the Keystore-backed migration can commit.
            // Boot recovery independently fails closed while secure storage is unavailable.
            assertTrue("Legacy preferences must remain intact when encrypted migration cannot run", legacy.getBoolean("enabled", false))
        }
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
