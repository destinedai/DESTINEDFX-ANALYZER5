package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.ctrader.CTraderOAuthClient
import com.example.data.ctrader.CTraderReadOnlyGateway
import com.example.security.SecretsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CTraderReadOnlySafetyTest {
    @Test
    fun readOnlyGatewayDoesNotExposeOrderOperations() {
        val methodNames = CTraderReadOnlyGateway::class.java.declaredMethods.map { it.name.lowercase() }
        assertFalse(methodNames.any { it.contains("order") || it.contains("amend") || it.contains("closeposition") })
    }

    @Test
    fun authorizationUriRequiresConfiguredSupportedScope() {
        val secrets = SecretsManager(ApplicationProvider.getApplicationContext())
        val redirect = "https://owner.example/ctrader/callback"
        assertTrue(secrets.saveCTraderOAuth("client", "secret", redirect, "access", "refresh", System.currentTimeMillis() + 60_000L))
        val client = CTraderOAuthClient(secrets)
        val uri = client.authorizationUri(CTraderOAuthClient.VIEW_SCOPE)
        assertEquals("https", uri.scheme)
        assertEquals("id.ctrader.com", uri.host)
        assertEquals("/my/settings/openapi/grantingaccess/", uri.path)
        assertEquals("client", uri.getQueryParameter("client_id"))
        assertEquals(redirect, uri.getQueryParameter("redirect_uri"))
        assertEquals("accounts", uri.getQueryParameter("scope"))
        assertEquals("web", uri.getQueryParameter("product"))
        runCatching { client.authorizationUri("orders") }.onSuccess { error("unsupported scope was accepted") }
    }
}
