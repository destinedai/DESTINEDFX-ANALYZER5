package com.example

import com.example.data.management.AccessTokenStore
import com.example.data.management.ManagementApiClient
import com.example.data.management.ManagementException
import com.example.data.management.ManagementFailure
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ManagementApiClientTest {
    @Test fun acceptsOnlyConfiguredBareHttpsOrigins() {
        assertEquals("https://api.example.org", ManagementApiClient.validatedOrigin("https://api.example.org/"))
        listOf("", "__CONFIGURATION_REQUIRED__", "http://api.example.org", "https://u:p@api.example.org",
            "https://api.example.org/path", "https://api.example.org?x=1", "https://localhost", "https://demo.invalid")
            .forEach { assertNull("must reject $it", ManagementApiClient.validatedOrigin(it)) }
    }

    @Test fun absentOrExpiredTokenNeverReachesNetwork() {
        var networkCalled = false
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            networkCalled = true
            response(chain.request(), 200, "{}")
        }).build()
        val client = ManagementApiClient("https://api.example.org", FakeTokenStore(null), http)
        val error = runCatching { client.currentUser() }.exceptionOrNull() as ManagementException
        assertEquals(ManagementFailure.AUTH_EXPIRED, error.failure)
        assertFalse(networkCalled)
    }

    @Test fun unavailableSecureStorageBlocksRegistrationAndLoginBeforeNetwork() {
        var networkCalled = false
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            networkCalled = true
            response(chain.request(), 200, "{}")
        }).build()
        val client = ManagementApiClient("https://api.example.org", FakeTokenStore(null, available = false), http)
        listOf(
            runCatching { client.register("a@example.org", "long enough password") }.exceptionOrNull(),
            runCatching { client.login("a@example.org", "long enough password") }.exceptionOrNull()
        ).forEach { assertEquals(ManagementFailure.SECURE_STORAGE_UNAVAILABLE, (it as ManagementException).failure) }
        assertFalse(networkCalled)
    }

    @Test fun onlySafeGetRequestsRetryOnceOnTransportFailure() {
        var getAttempts = 0
        val getHttp = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            getAttempts++
            if (getAttempts == 1) throw IOException("temporary network failure")
            response(chain.request(), 200, "{\"theme\":{}}")
        }).build()
        val getClient = ManagementApiClient("https://api.example.org", FakeTokenStore("x".repeat(64)), getHttp)
        assertTrue(getClient.getPreferences().has("theme"))
        assertEquals(2, getAttempts)

        var postAttempts = 0
        val postHttp = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            postAttempts++
            throw IOException("temporary network failure")
        }).build()
        val postClient = ManagementApiClient("https://api.example.org", FakeTokenStore(null), postHttp)
        val error = runCatching { postClient.register("a@example.org", "long enough password") }
            .exceptionOrNull() as ManagementException
        assertEquals(ManagementFailure.OFFLINE, error.failure)
        assertEquals(1, postAttempts)
    }

    @Test fun loginStoresOpaqueTokenAndExpiryAndPreferencePutUsesBearer() {
        val store = FakeTokenStore(null)
        var preferenceBody: JSONObject? = null
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            when {
                request.url.encodedPath.endsWith("/auth/login") -> response(request, 200,
                    """{"access_token":"${"x".repeat(64)}","expires_at":"2099-01-01T00:00:00Z","user":{"id":"u1","email":"a@example.org"}}""")
                request.url.encodedPath.endsWith("/preferences/theme") -> {
                    assertEquals("Bearer ${"x".repeat(64)}", request.header("Authorization"))
                    preferenceBody = JSONObject(request.body!!.let { body ->
                        val buffer = okio.Buffer(); body.writeTo(buffer); buffer.readUtf8()
                    })
                    response(request, 200, """{"key":"theme","value":{"mode":"Dark"}}""")
                }
                else -> response(request, 404, "{}")
            }
        }).build()
        val client = ManagementApiClient("https://api.example.org", store, http)
        assertEquals("a@example.org", client.login("a@example.org", "pw").email)
        assertEquals("${"x".repeat(64)}", store.token)
        assertEquals("2099-01-01T00:00:00Z", store.expiry)
        client.putPreference("theme", JSONObject().put("mode", "Dark"))
        assertEquals("Dark", preferenceBody!!.getJSONObject("value").getString("mode"))
    }

    @Test fun serviceStatusMapsServerFailureToUnavailable() {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            response(chain.request(), 503, """{"error":{"code":"internal_error"}}""")
        }).build()
        val error = runCatching { ManagementApiClient("https://api.example.org", FakeTokenStore(null), http).status() }
            .exceptionOrNull() as ManagementException
        assertEquals(ManagementFailure.SERVICE_UNAVAILABLE, error.failure)
    }

    @Test fun serviceStatusRejectsDegradedDatabaseEvenWhenEndpointsReturnSuccess() {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val body = when (chain.request().url.encodedPath) {
                "/api/v1/service-status" -> """{"status":"degraded","database":"unavailable"}"""
                "/health" -> """{"status":"ok"}"""
                "/ready" -> """{"status":"ready","database":"ok"}"""
                else -> """{"version":"1.0.0"}"""
            }
            response(chain.request(), 200, body)
        }).build()
        val error = runCatching { ManagementApiClient("https://api.example.org", FakeTokenStore(null), http).status() }
            .exceptionOrNull() as ManagementException
        assertEquals(ManagementFailure.SERVICE_UNAVAILABLE, error.failure)
    }

    private class FakeTokenStore(var token: String?, var expiry: String? = null, override val isAvailable: Boolean = true) : AccessTokenStore {
        override fun save(token: String, expiresAt: String): Boolean { this.token = token; expiry = expiresAt; return true }
        override fun validToken(nowMs: Long): String? = token
        override fun clear(): Boolean { token = null; expiry = null; return true }
    }

    private fun response(request: okhttp3.Request, code: Int, text: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("mock")
        .body(text.toResponseBody("application/json".toMediaType())).build()
}
