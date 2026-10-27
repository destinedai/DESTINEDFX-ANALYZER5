package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.security.SecretsManager
import org.junit.Assert.assertEquals
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
}
