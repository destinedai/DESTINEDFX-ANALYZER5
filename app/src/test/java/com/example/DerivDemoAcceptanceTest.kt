package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.security.SecretsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DerivDemoAcceptanceTest {
    @Test
    fun acceptanceIsNotImplicitAndMustMatchTheVerifiedAccount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secrets = SecretsManager(context) { context.getSharedPreferences("deriv_acceptance_test", Context.MODE_PRIVATE) }
        assertTrue(secrets.saveDerivAccountMode("demo"))
        assertFalse(secrets.hasCurrentDerivDemoAcceptance())
        assertTrue(secrets.saveDerivDemoAcceptance("CR123", "USD", "session-1", "credential-1"))
        assertTrue(secrets.hasCurrentDerivDemoAcceptance())
        assertTrue(secrets.hasCurrentDerivDemoAcceptance("CR123", "USD", credentialGeneration = "credential-1"))
        assertFalse(secrets.hasCurrentDerivDemoAcceptance("CR999", "USD", credentialGeneration = "credential-1"))
        assertFalse(secrets.hasCurrentDerivDemoAcceptance("CR123", "EUR", credentialGeneration = "credential-1"))
    }

    @Test
    fun changingModeOrCredentialInvalidatesAcceptance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secrets = SecretsManager(context) { context.getSharedPreferences("deriv_acceptance_invalidate_test", Context.MODE_PRIVATE) }
        assertTrue(secrets.saveDerivAccountMode("demo"))
        assertTrue(secrets.saveDerivDemoAcceptance("CR123", "USD", "session-1", "credential-1"))
        assertTrue(secrets.saveDerivAccountMode("real"))
        assertFalse(secrets.hasCurrentDerivDemoAcceptance())
        assertTrue(secrets.saveDerivAccountMode("demo"))
        assertTrue(secrets.saveDerivApiToken("new-token", "demo"))
        assertFalse(secrets.hasCurrentDerivDemoAcceptance())
    }

    @Test
    fun changingRegisteredAppIdInvalidatesPriorAcceptance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secrets = SecretsManager(context) { context.getSharedPreferences("deriv_acceptance_app_id_test", Context.MODE_PRIVATE) }
        assertTrue(secrets.saveDerivAccountMode("demo"))
        assertTrue(secrets.saveDerivAppId("123456"))
        assertTrue(secrets.saveDerivDemoAcceptance("CR123", "USD", "session-1", "credential-1"))
        assertTrue(secrets.hasCurrentDerivDemoAcceptance())

        assertTrue(secrets.saveDerivAppId("654321"))

        assertFalse(secrets.hasCurrentDerivDemoAcceptance())
    }
}
