package com.example.data.ctrader

import android.content.Context
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
@Config(sdk = [34])
class CTraderOAuthCallbackTest {
    @Test
    fun acceptsConfiguredCallbackOnlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secrets = SecretsManager(context) { context.getSharedPreferences("oauth_callback_test", Context.MODE_PRIVATE) }
        assertTrue(secrets.saveCTraderApplication("client", "secret", "https://owner.example/ctrader/callback"))
        val client = CTraderOAuthClient(secrets)
        val callback = android.net.Uri.parse("https://owner.example/ctrader/callback?code=one")

        assertEquals("one", client.consumeAuthorizationCallback(callback).getOrThrow())
        assertFalse(client.consumeAuthorizationCallback(callback).isSuccess)
    }

    @Test
    fun rejectsErrorWrongRedirectAndOldCallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secrets = SecretsManager(context) { context.getSharedPreferences("oauth_callback_rejection_test", Context.MODE_PRIVATE) }
        assertTrue(secrets.saveCTraderApplication("client", "secret", "https://owner.example/ctrader/callback"))
        val client = CTraderOAuthClient(secrets)

        assertFalse(client.consumeAuthorizationCallback(android.net.Uri.parse("https://evil.example/ctrader/callback?code=x")).isSuccess)
        assertFalse(client.consumeAuthorizationCallback(android.net.Uri.parse("https://owner.example/ctrader/callback?error=denied")).isSuccess)
        assertFalse(client.consumeAuthorizationCallback(android.net.Uri.parse("https://owner.example/ctrader/callback?code=old"), System.currentTimeMillis() - 61_000L).isSuccess)
    }
}
