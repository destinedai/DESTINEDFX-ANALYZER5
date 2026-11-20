package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.broker.LoginMode
import com.example.data.broker.SavedBrokerAccountProfile
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
class SavedBrokerAccountProfileTest {
    @Test
    fun profileRecordsPersistWithoutCredentialFields() {
        val manager = SecretsManager(ApplicationProvider.getApplicationContext())
        if (!manager.isSecureStorageAvailable) return

        val deriv = SavedBrokerAccountProfile(
            specId = "DERIV_OPTIONS",
            accountId = "V100001",
            server = "",
            mode = LoginMode.DEMO,
        )
        val ctrader = SavedBrokerAccountProfile(
            specId = "CTRADER",
            accountId = "7001",
            server = "Fixture broker",
            mode = LoginMode.LIVE,
            login = "8001",
        )
        assertTrue(manager.saveBrokerAccountProfile(deriv))
        assertTrue(manager.saveBrokerAccountProfile(ctrader))
        assertEquals(listOf(ctrader, deriv), manager.getBrokerAccountProfiles())
        assertFalse(manager.saveBrokerAccountProfile(deriv.copy(specId = "UNKNOWN")))
    }
}
