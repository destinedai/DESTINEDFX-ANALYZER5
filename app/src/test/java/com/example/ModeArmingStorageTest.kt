package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.broker.AccountMode
import com.example.data.broker.ModeAccountIdentity
import com.example.data.broker.ModePath
import com.example.data.broker.ModeArmingAuditEvent
import com.example.security.SecretsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModeArmingStorageTest {
    @Test
    fun demoAcceptanceIsEncryptedAndBoundToExactIdentityAndCredentialGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("mode_acceptance_test", Context.MODE_PRIVATE)
        val secrets = SecretsManager(context) { prefs }
        val identity = ModeAccountIdentity(ModePath.FBS_MT5, "FBS", "123456", "FBS-Demo", AccountMode.DEMO, "credential-generation-a")
        assertTrue(secrets.savePathDemoAcceptance(identity, "a".repeat(64)))
        assertEquals(identity, secrets.getPathDemoAcceptance(identity)?.identity)
        assertNull(secrets.getPathDemoAcceptance(identity.copy(credentialGeneration = "credential-generation-b")))
        assertNull(secrets.getPathDemoAcceptance(identity.copy(server = "FBS-Other-Demo")))
        assertTrue(secrets.clearPathDemoAcceptance(identity))
        assertNull(secrets.getPathDemoAcceptance(identity))
        assertFalse(secrets.savePathDemoAcceptance(identity.copy(mode = AccountMode.LIVE), "b".repeat(64)))
    }

    @Test
    fun localArmingAuditIsAppendOnlyAndContainsOnlyAHashAndReasonCode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("mode_audit_test", Context.MODE_PRIVATE)
        val secrets = SecretsManager(context) { prefs }
        val identity = ModeAccountIdentity(ModePath.DERIV_OPTIONS, "DERIV", "CR123456", "", AccountMode.LIVE, "generation")
        val event = ModeArmingAuditEvent(
            event = "LIVE_ARM_BLOCKED",
            path = identity.path.name,
            mode = "LIVE",
            identityFingerprint = identity.fingerprint(),
            timestampMs = 1_000L,
            allowed = false,
            reasonCode = "MATCHING_DEMO_PASS_REQUIRED"
        )
        assertTrue(secrets.appendModeArmingAudit(event))
        assertEquals(1, secrets.modeArmingAuditCount())
        val stored = prefs.all.values.joinToString("|")
        assertFalse(stored.contains("CR123456"))
        assertFalse(stored.contains("generation"))
        assertTrue(stored.contains(identity.fingerprint()))
    }
}
