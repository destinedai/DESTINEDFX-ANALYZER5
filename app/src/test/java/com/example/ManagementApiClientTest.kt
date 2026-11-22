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
        assertEquals("https://api.destinedfxhost.com", ManagementApiClient.validatedOrigin("https://api.destinedfxhost.com/"))
        listOf("", "__CONFIGURATION_REQUIRED__", "http://api.example.org", "https://u:p@api.example.org",
            "https://api.destinedfxhost.com/path", "https://api.destinedfxhost.com?x=1", "https://localhost",
            "https://demo.invalid", "https://api.example.org", "https://api.example.net",
            "https://api.destinedfx.example.org")
            .forEach { assertNull("must reject $it", ManagementApiClient.validatedOrigin(it)) }
    }

    @Test fun lockedBuildAlwaysUsesEmbeddedManagementOriginAndIgnoresRuntimeOverrides() {
        assertEquals(
            "https://api.destinedfxhost.com",
            ManagementApiClient.resolveConfiguredOrigin(
                "https://attacker-controlled.example.org",
                "https://api.destinedfxhost.com",
                locked = true
            )
        )
        assertEquals(
            "",
            ManagementApiClient.resolveConfiguredOrigin(
                "https://old-host.example",
                "__CONFIGURATION_REQUIRED__",
                locked = true
            )
        )
        assertEquals(
            "https://developer-bridge.destinedfxhost.com",
            ManagementApiClient.resolveConfiguredOrigin(
                "https://developer-bridge.destinedfxhost.com",
                "https://build-default.destinedfxhost.com",
                locked = false
            )
        )
    }

    @Test fun runtimeOriginIsAcceptedOnlyWhenBackendAndEdDsaKeyAreReady() {
        val requests = mutableListOf<String>()
        val pem = "-----BEGIN PUBLIC KEY-----\\n" + "A".repeat(48) + "\\n-----END PUBLIC KEY-----"
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request.url.encodedPath
            assertNull("Public configuration probes must not carry a bearer", request.header("Authorization"))
            val body = when (request.url.encodedPath) {
                "/ready" -> """{"status":"ready","database":"ok"}"""
                "/api/v1/auth/bridge-verification-key" -> JSONObject()
                    .put("algorithm", "EdDSA")
                    .put("public_key_pem", pem)
                    .put("issuer", "destinedfx-management")
                    .put("audience", "destinedfx-bridge").toString()
                else -> "{}"
            }
            response(request, 200, body)
        }).build()

        assertEquals("https://api.destinedfxhost.com", ManagementApiClient.verifyOrigin("https://api.destinedfxhost.com/", http))
        assertEquals(listOf("/ready", "/api/v1/auth/bridge-verification-key"), requests)
    }

    @Test fun runtimeOriginProbeRejectsBackendWithoutBridgeVerificationKey() {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath == "/ready") """{"status":"ready"}"""
                else """{"algorithm":"RS256","public_key_pem":"not-ed25519","issuer":"wrong","audience":"wrong"}"""
            response(request, 200, body)
        }).build()
        val error = runCatching {
            ManagementApiClient.verifyOrigin("https://api.destinedfxhost.com", http)
        }.exceptionOrNull() as ManagementException
        assertEquals(ManagementFailure.INVALID_RESPONSE, error.failure)
    }

    @Test fun managementBearerRemainsBoundToTheOriginThatIssuedIt() {
        var origin = "https://first.destinedfxhost.com"
        var savedOrigin = ""
        val requests = mutableListOf<Pair<String, String?>>()
        val store = object : FakeTokenStore(null) {
            override fun saveForOrigin(token: String, expiresAt: String, origin: String): Boolean {
                savedOrigin = origin
                return save(token, expiresAt)
            }
            override fun validTokenForOrigin(origin: String, nowMs: Long): String? =
                if (savedOrigin == origin) validToken(nowMs) else null
        }
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request.url.host to request.header("Authorization")
            when (request.url.encodedPath) {
                "/ready" -> response(request, 200, """{"status":"ready"}""")
                "/api/v1/auth/bridge-verification-key" -> response(request, 200, validKeyDocument())
                else -> if (request.url.encodedPath.endsWith("/auth/login")) {
                    response(request, 200,
                        """{"access_token":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx","expires_at":"2099-01-01T00:00:00Z","user":{"id":"u1","email":"a@example.org"}}""")
                } else {
                    response(request, 200, """{"id":"u1","email":"a@example.org"}""")
                }
            }
        }).build()
        val client = ManagementApiClient({ origin }, store, http)
        client.login("a@example.org", "pw")
        assertEquals("https://first.destinedfxhost.com", savedOrigin)
        origin = "https://second.destinedfxhost.com"

        val error = runCatching { client.currentUser() }.exceptionOrNull() as ManagementException
        assertEquals(ManagementFailure.AUTH_EXPIRED, error.failure)
        // The new origin is probed without credentials, but the previous bearer
        // must never be forwarded and /auth/me must not be reached on that origin.
        assertEquals(5, requests.size)
        assertEquals(3, requests.count { it.first == "first.destinedfxhost.com" })
        assertEquals(2, requests.count { it.first == "second.destinedfxhost.com" })
        assertTrue(requests.all { it.second == null })
    }

    @Test fun absentOrExpiredTokenNeverReachesNetwork() {
        var networkCalled = false
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            networkCalled = true
            response(chain.request(), 200, "{}")
        }).build()
        val client = ManagementApiClient("https://api.destinedfxhost.com", FakeTokenStore(null), http)
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
        val client = ManagementApiClient("https://api.destinedfxhost.com", FakeTokenStore(null, isAvailable = false), http)
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
        val getClient = ManagementApiClient("https://api.destinedfxhost.com", FakeTokenStore("x".repeat(64)), getHttp)
        assertTrue(getClient.getPreferences().has("theme"))
        assertEquals(2, getAttempts)

        var postAttempts = 0
        val postHttp = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            postAttempts++
            throw IOException("temporary network failure")
        }).build()
        val postClient = ManagementApiClient("https://api.destinedfxhost.com", FakeTokenStore(null), postHttp)
        val error = runCatching { postClient.register("a@example.org", "long enough password") }
            .exceptionOrNull() as ManagementException
        assertEquals(ManagementFailure.OFFLINE, error.failure)
        assertEquals(1, postAttempts)
    }


    @Test fun registrationSendsOwnerSetupTokenOnlyInTheHttpsJsonBody() {
        val token = "owner-setup-" + "x".repeat(64)
        var captured = false
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/ready" -> response(request, 200, """{"status":"ready"}""")
                "/api/v1/auth/bridge-verification-key" -> response(request, 200, validKeyDocument())
                "/api/v1/auth/register" -> {
                    assertNull(request.url.query)
                    assertNull(request.header("Authorization"))
                    val buffer = okio.Buffer()
                    request.body!!.writeTo(buffer)
                    val body = JSONObject(buffer.readUtf8())
                    assertEquals(token, body.getString("owner_setup_token"))
                    assertEquals("owner@example.org", body.getString("email"))
                    captured = true
                    response(request, 201, """{"id":"1","email":"owner@example.org"}""")
                }
                else -> response(request, 404, "{}")
            }
        }).build()
        val client = ManagementApiClient("https://api.destinedfxhost.com", FakeTokenStore(null), http)
        assertEquals("owner@example.org", client.register("owner@example.org", "long enough password", token).email)
        assertTrue(captured)
    }

    @Test fun loginStoresOpaqueTokenAndExpiryAndPreferencePutUsesBearer() {
        val store = FakeTokenStore(null)
        var preferenceBody: JSONObject? = null
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            when {
                request.url.encodedPath == "/ready" -> response(request, 200, """{"status":"ready"}""")
                request.url.encodedPath == "/api/v1/auth/bridge-verification-key" -> response(request, 200, validKeyDocument())
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
        val client = ManagementApiClient("https://api.destinedfxhost.com", store, http)
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
        val error = runCatching { ManagementApiClient("https://api.destinedfxhost.com", FakeTokenStore(null), http).status() }
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
        val error = runCatching { ManagementApiClient("https://api.destinedfxhost.com", FakeTokenStore(null), http).status() }
            .exceptionOrNull() as ManagementException
        assertEquals(ManagementFailure.SERVICE_UNAVAILABLE, error.failure)
    }

    private open class FakeTokenStore(var token: String?, var expiry: String? = null, override val isAvailable: Boolean = true) : AccessTokenStore {
        override fun save(token: String, expiresAt: String): Boolean { this.token = token; expiry = expiresAt; return true }
        override fun validToken(nowMs: Long): String? = token
        override fun clear(): Boolean { token = null; expiry = null; return true }
    }

    private fun validKeyDocument(): String = JSONObject()
        .put("algorithm", "EdDSA")
        .put("public_key_pem", "-----BEGIN PUBLIC KEY-----" + "A".repeat(48) + "-----END PUBLIC KEY-----")
        .put("issuer", "destinedfx-management")
        .put("audience", "destinedfx-bridge")
        .toString()

    private fun response(request: okhttp3.Request, code: Int, text: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("mock")
        .body(text.toResponseBody("application/json".toMediaType())).build()
}
