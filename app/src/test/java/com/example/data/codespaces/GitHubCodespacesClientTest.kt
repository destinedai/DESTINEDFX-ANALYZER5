package com.example.data.codespaces

import android.content.Context
import com.example.security.SecretsManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class GitHubCodespacesClientTest {
    private lateinit var context: Context
    private lateinit var secrets: SecretsManager
    private lateinit var prefs: android.content.SharedPreferences
    private val servers = mutableListOf<MockWebServer>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("github-codespaces-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        secrets = SecretsManager(context) { prefs }
        assertTrue(secrets.isSecureStorageAvailable)
        assertTrue(secrets.saveCodespacesGitHubClientId("Iv1.testclientid"))
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
        prefs.edit().clear().commit()
    }

    private fun server(): MockWebServer = MockWebServer().also {
        it.start()
        servers += it
    }

    private fun client(server: MockWebServer): GitHubCodespacesClient =
        GitHubCodespacesClient(
            secrets,
            OkHttpClient(),
            GitHubCodespacesEndpoints(
                deviceCodeUrl = server.url("/login/device/code").toString(),
                accessTokenUrl = server.url("/login/oauth/access_token").toString(),
                apiBaseUrl = server.url("/").toString().removeSuffix("/")
            )
        )

    private fun saveUsableTokens() {
        val now = System.currentTimeMillis()
        assertTrue(secrets.saveCodespacesGitHubTokens(
            accessToken = "ghu_fixture_access_token",
            refreshToken = "ghr_fixture_refresh_token",
            expiresAtMillis = now + 60 * 60 * 1000L,
            refreshExpiresAtMillis = now + 30L * 24 * 60 * 60 * 1000
        ))
    }

    @Test
    fun deviceFlowExchangesCodeAndPersistsRotatingTokens() = runBlocking {
        val mock = server()
        mock.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"device_code":"device-fixture","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","expires_in":900,"interval":1}"""
        ))
        mock.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"access_token":"ghu_fixture_access_token","expires_in":28800,"refresh_token":"ghr_fixture_refresh_token","refresh_token_expires_in":15897600,"token_type":"bearer"}"""
        ))
        val client = client(mock)
        val device = client.beginDeviceAuthorization()
        assertEquals("ABCD-EFGH", device.userCode)
        assertEquals("https://github.com/login/device", device.verificationUri)
        assertTrue(client.completeDeviceAuthorization(device))
        assertEquals("ghu_fixture_access_token", secrets.getCodespacesGitHubAccessToken())
        assertEquals("ghr_fixture_refresh_token", secrets.getCodespacesGitHubRefreshToken())
        assertTrue(secrets.getCodespacesGitHubAccessExpiresAtMillis() > System.currentTimeMillis())
        assertTrue(secrets.getCodespacesGitHubRefreshExpiresAtMillis() > secrets.getCodespacesGitHubAccessExpiresAtMillis())
        val deviceRequest = mock.takeRequest()
        assertEquals("/login/device/code", deviceRequest.path)
        val deviceRequestBody = deviceRequest.body.readUtf8()
        assertTrue(deviceRequestBody.contains("client_id=Iv1.testclientid"))
        assertTrue(deviceRequestBody.contains("scope=offline_access"))
        val tokenRequest = mock.takeRequest()
        assertEquals("/login/oauth/access_token", tokenRequest.path)
        val tokenRequestBody = tokenRequest.body.readUtf8()
        assertTrue(tokenRequestBody.contains("device_code=device-fixture"))
        assertTrue(tokenRequestBody.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
    }

    @Test
    fun refreshUsesRotatingRefreshTokenAndReplacesBothTokens() = runBlocking {
        val mock = server()
        val expiredAt = System.currentTimeMillis() - 1_000L
        assertTrue(secrets.saveCodespacesGitHubTokens(
            "ghu_expired_token", "ghr_old_refresh_token", expiredAt,
            System.currentTimeMillis() + 5L * 24 * 60 * 60 * 1000
        ))
        mock.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"access_token":"ghu_rotated_access_token","expires_in":28800,"refresh_token":"ghr_rotated_refresh_token","refresh_token_expires_in":15897600,"token_type":"bearer"}"""
        ))
        val token = client(mock).validAccessToken()
        assertEquals("ghu_rotated_access_token", token)
        assertEquals("ghr_rotated_refresh_token", secrets.getCodespacesGitHubRefreshToken())
        val request = mock.takeRequest()
        assertEquals("/login/oauth/access_token", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=ghr_old_refresh_token"))
        assertFalse(body.contains("client_secret"))
    }

    @Test
    fun expiredRefreshTokenClearsAuthorizationAndRequiresSignIn() = runBlocking {
        val expiredAt = System.currentTimeMillis() - 1_000L
        // The production storage API correctly refuses an already-expired refresh
        // expiry. Seed a valid token pair first, then advance only the test clock
        // metadata to exercise the expired-token branch.
        assertTrue(secrets.saveCodespacesGitHubTokens(
            "ghu_expired_token", "ghr_expired_refresh", expiredAt,
            System.currentTimeMillis() + 60_000L
        ))
        assertTrue(prefs.edit()
            .putLong("codespaces_github_refresh_expires_at", expiredAt)
            .commit())
        val failure = runCatching { client(server()).validAccessToken() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("sign-in expired"))
        assertEquals("", secrets.getCodespacesGitHubAccessToken())
        assertEquals("", secrets.getCodespacesGitHubRefreshToken())
    }

    @Test
    fun discoveryFiltersByExactRepositoryAndBranchAndStartRevalidatesTarget() = runBlocking {
        saveUsableTokens()
        val mock = server()
        val matching = """
            {
              "name":"codespace-generated-name",
              "state":"Shutdown",
              "repository":{"full_name":"destinedai/DESTINEDFX-ANALYZER5"},
              "git_status":{"ref":"refs/heads/free/phone-only-v1"},
              "machine":{"cpus":2},
              "last_used_at":"2026-11-20T00:00:00Z"
            }
        """.trimIndent()
        val wrongBranch = matching.replace("free/phone-only-v1", "main").replace("codespace-generated-name", "wrong-branch")
        mock.enqueue(MockResponse().setBody("""{"codespaces":[$matching,$wrongBranch]}"""))
        mock.enqueue(MockResponse().setBody(matching))
        mock.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        val client = client(mock)
        val found = client.listTargetCodespaces()
        assertEquals(1, found.size)
        assertEquals("codespace-generated-name", found.single().name)
        assertEquals(2, found.single().machineCpus)
        client.startCodespace(found.single())
        assertEquals("/user/codespaces?per_page=100&page=1", mock.takeRequest().path)
        assertEquals("/user/codespaces/codespace-generated-name", mock.takeRequest().path)
        val startRequest = mock.takeRequest()
        assertEquals("/user/codespaces/codespace-generated-name/start", startRequest.path)
        assertEquals("POST", startRequest.method)
        assertTrue(startRequest.getHeader("Authorization")!!.startsWith("Bearer ghu_"))
    }

    @Test
    fun discoveryPaginatesBeyondFirstHundredAndDeduplicatesCodespaces() = runBlocking {
        saveUsableTokens()
        val mock = server()
        val firstPage = org.json.JSONArray()
        repeat(100) { index ->
            firstPage.put(org.json.JSONObject()
                .put("name", "codespace-$index")
                .put("state", "Shutdown")
                .put("repository", org.json.JSONObject().put("full_name", "destinedai/DESTINEDFX-ANALYZER5"))
                .put("git_status", org.json.JSONObject().put("ref", "refs/heads/free/phone-only-v1"))
                .put("machine", org.json.JSONObject().put("cpus", 2)))
        }
        val secondPage = org.json.JSONArray().put(org.json.JSONObject()
            .put("name", "codespace-100")
            .put("state", "Available")
            .put("repository", org.json.JSONObject().put("full_name", "destinedai/DESTINEDFX-ANALYZER5"))
            .put("git_status", org.json.JSONObject().put("ref", "refs/heads/free/phone-only-v1"))
            .put("machine", org.json.JSONObject().put("cpus", 2)))
        mock.enqueue(MockResponse().setBody(org.json.JSONObject().put("codespaces", firstPage).toString()))
        mock.enqueue(MockResponse().setBody(org.json.JSONObject().put("codespaces", secondPage).toString()))
        val found = client(mock).listTargetCodespaces()
        assertEquals(101, found.size)
        assertEquals("/user/codespaces?per_page=100&page=1", mock.takeRequest().path)
        assertEquals("/user/codespaces?per_page=100&page=2", mock.takeRequest().path)
    }

    @Test
    fun stopRevalidatesRepositoryAndBranchBeforeRequest() = runBlocking {
        saveUsableTokens()
        val mock = server()
        val matching = """
            {
              "name":"owner-codespace-dynamic",
              "state":"Available",
              "repository":{"full_name":"destinedai/DESTINEDFX-ANALYZER5"},
              "git_status":{"ref":"free/phone-only-v1"},
              "machine":{"cpus":2}
            }
        """.trimIndent()
        mock.enqueue(MockResponse().setBody(matching))
        mock.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        val target = GitHubCodespacesClient.Codespace(
            "owner-codespace-dynamic", "Available",
            "destinedai/DESTINEDFX-ANALYZER5", "free/phone-only-v1", 2, ""
        )
        client(mock).stopCodespace(target)
        assertEquals("/user/codespaces/owner-codespace-dynamic", mock.takeRequest().path)
        assertEquals("/user/codespaces/owner-codespace-dynamic/stop", mock.takeRequest().path)
    }

    @Test
    fun usageLedgerCountsWallMinutesAndCoreHoursAndDoesNotDoubleOpen() {
        val start = 1_800_000_000_000L
        val ledger = GitHubCodespacesClient.UsageLedger.parse("")
        ledger.observeRunning("codespace", 2, start)
        ledger.observeRunning("codespace", 2, start + 10_000L)
        val active = ledger.totals(start + 90L * 60L * 1000L)
        assertEquals(3.0, active.first, 0.001)
        assertEquals(90L, active.second)
        ledger.observeStopped("codespace", start + 30L * 60L * 1000L)
        val stopped = ledger.totals(start + 3L * 60L * 60L * 1000L)
        assertEquals(1.0, stopped.first, 0.001)
        assertEquals(30L, stopped.second)
    }
}
