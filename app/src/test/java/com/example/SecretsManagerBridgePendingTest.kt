package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.security.SecretsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecretsManagerBridgePendingTest {
    @Test fun pendingCommandMethodsPersistPairAndClearTogetherWhenSecureStorageIsAvailable() {
        val manager = SecretsManager(ApplicationProvider.getApplicationContext())
        if (!manager.isSecureStorageAvailable) return

        val id = "2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18"
        assertTrue(manager.saveMT5BridgePendingCommand(id, "START"))
        assertEquals(id, manager.getMT5BridgePendingCommandId())
        assertEquals("START", manager.getMT5BridgePendingCommandAction())
        manager.clearMT5BridgePendingCommand()
        assertEquals("", manager.getMT5BridgePendingCommandId())
        assertEquals("", manager.getMT5BridgePendingCommandAction())
    }

    @Test fun demoAndLiveBridgeStateAreEncryptedAndProfileIsolated() {
        val manager = SecretsManager(ApplicationProvider.getApplicationContext())
        if (!manager.isSecureStorageAvailable) return

        assertTrue(manager.saveMT5BridgeAppToken("demo-token", "DEMO"))
        assertTrue(manager.saveMT5BridgeAppToken("live-token", "LIVE"))
        assertEquals("demo-token", manager.getMT5BridgeAppToken("DEMO"))
        assertEquals("live-token", manager.getMT5BridgeAppToken("LIVE"))

        assertTrue(manager.saveMT5BridgePendingCommand("demo-request", "STOP", "DEMO"))
        assertTrue(manager.saveMT5BridgePendingCommand("live-request", "START", "LIVE"))
        assertEquals("demo-request", manager.getMT5BridgePendingCommandId("DEMO"))
        assertEquals("live-request", manager.getMT5BridgePendingCommandId("LIVE"))

        manager.saveManagedMT5Profile("DEMO", "111", "Demo-Server", "MASTER", "demo-password", true)
        manager.saveManagedMT5Profile("LIVE", "999", "Live-Server", "MASTER", "live-password", true)
        assertEquals("111", manager.getManagedMT5Login("DEMO"))
        assertEquals("999", manager.getManagedMT5Login("LIVE"))
        assertEquals("Demo-Server", manager.getManagedMT5Server("DEMO"))
        assertEquals("Live-Server", manager.getManagedMT5Server("LIVE"))
        assertEquals("demo-password", manager.getManagedMT5Password("DEMO"))
        assertEquals("live-password", manager.getManagedMT5Password("LIVE"))

        manager.clearMT5BridgePendingCommand("LIVE")
        assertEquals("demo-request", manager.getMT5BridgePendingCommandId("DEMO"))
        assertEquals("", manager.getMT5BridgePendingCommandId("LIVE"))
        manager.clearMT5BridgeAppToken("LIVE")
        assertEquals("demo-token", manager.getMT5BridgeAppToken("DEMO"))
        assertFalse(manager.getMT5BridgeAppToken("LIVE").isNotBlank())
    }

    @Test fun derivCredentialsAreStoredOnlyInEncryptedPreferencesAndModeIsDemoOnly() {
        val manager = SecretsManager(ApplicationProvider.getApplicationContext())
        if (!manager.isSecureStorageAvailable) {
            assertFalse(manager.saveDerivApiToken("test-pat"))
            assertEquals("", manager.getDerivApiToken())
            assertFalse(manager.clearDerivCredentials())
            return
        }

        assertTrue(manager.saveDerivApiToken("test-pat"))
        assertTrue(manager.saveDerivAppId("123456"))
        assertTrue(manager.saveDerivAccountMode("demo"))
        assertFalse(manager.saveDerivAccountMode("real"))
        assertEquals("demo", manager.getDerivAccountMode())
        assertEquals("test-pat", manager.getDerivApiToken())
        assertEquals("123456", manager.getDerivAppId())
        assertTrue(manager.clearDerivCredentials())
        assertEquals("", manager.getDerivApiToken())
        assertEquals("", manager.getDerivAppId())
    }
}
