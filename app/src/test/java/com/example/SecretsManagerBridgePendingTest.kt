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
    @Test fun botConfigurationPersistsSeparatelyFromAnalyzerStrategy() {
        val manager = SecretsManager(ApplicationProvider.getApplicationContext())
        manager.saveSelectedStrategy("ict-silver-bullet")
        manager.saveBotSymbol("R_100")
        manager.saveBotStrategy("smc-confluence-1.0.0")
        manager.saveBotTimeframe("H1")
        assertTrue(manager.saveBotRiskPercent(0.5))
        assertFalse(manager.saveBotRiskPercent(1.01))

        val restored = SecretsManager(ApplicationProvider.getApplicationContext())
        assertEquals("ict-silver-bullet", manager.getSelectedStrategy())
        assertEquals("R_100", restored.getBotSymbol())
        assertEquals("smc-confluence-1.0.0", restored.getBotStrategy())
        assertEquals("H1", restored.getBotTimeframe())
        assertEquals(0.5, restored.getBotRiskPercent(), 0.0)
    }

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

    @Test fun manuallyChangedManagedBridgeUrlPersistsAcrossManagerRestart() {
        val manager = SecretsManager(ApplicationProvider.getApplicationContext())
        if (!manager.isSecureStorageAvailable) return

        val changedUrl = "https://new-demo-tunnel.trycloudflare.com"
        assertTrue(manager.saveManagedMT5ServiceUrl(changedUrl))
        val restored = SecretsManager(ApplicationProvider.getApplicationContext())
        assertEquals(changedUrl, restored.getManagedMT5ServiceUrl())
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

        assertTrue(manager.saveManagedMT5Profile("DEMO", "111", "Demo-Server", "MASTER", "demo-password", true, "FBS"))
        assertTrue(manager.saveManagedMT5Profile("LIVE", "999", "Live-Server", "MASTER", "live-password", true, "HFM"))
        assertEquals("111", manager.getManagedMT5Login("DEMO"))
        assertEquals("999", manager.getManagedMT5Login("LIVE"))
        assertEquals("Demo-Server", manager.getManagedMT5Server("DEMO"))
        assertEquals("Live-Server", manager.getManagedMT5Server("LIVE"))
        assertEquals("demo-password", manager.getManagedMT5Password("DEMO"))
        assertEquals("live-password", manager.getManagedMT5Password("LIVE"))
        assertEquals("FBS", manager.getManagedMT5Broker("DEMO"))
        assertEquals("HFM", manager.getManagedMT5Broker("LIVE"))

        manager.clearMT5BridgePendingCommand("LIVE")
        assertEquals("demo-request", manager.getMT5BridgePendingCommandId("DEMO"))
        assertEquals("", manager.getMT5BridgePendingCommandId("LIVE"))
        manager.clearMT5BridgeAppToken("LIVE")
        assertEquals("demo-token", manager.getMT5BridgeAppToken("DEMO"))
        assertFalse(manager.getMT5BridgeAppToken("LIVE").isNotBlank())
    }

    @Test fun managedProfilePersistenceNeverClaimsSuccessWithoutEncryptedStorage() {
        val manager = SecretsManager(ApplicationProvider.getApplicationContext())
        if (manager.isSecureStorageAvailable) return
        assertFalse(manager.saveManagedMT5Profile("DEMO", "123", "Demo-Server", "MASTER", "secret", true, "FBS"))
    }

    @Test fun derivCredentialsAreStoredOnlyInEncryptedPreferencesAndModeIsExplicit() {
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
        assertTrue(manager.saveDerivAccountMode("real"))
        assertEquals("real", manager.getDerivAccountMode())
        assertEquals("test-pat", manager.getDerivApiToken())
        assertEquals("123456", manager.getDerivAppId())
        assertTrue(manager.clearDerivCredentials())
        assertEquals("", manager.getDerivApiToken())
        assertEquals("", manager.getDerivAppId())
    }
}
