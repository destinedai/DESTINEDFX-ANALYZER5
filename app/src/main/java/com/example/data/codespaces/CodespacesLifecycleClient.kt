package com.example.data.codespaces

import android.net.Uri
import com.example.BuildConfig
import com.example.security.SecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Build the documented HTTPS forwarding origin without accepting arbitrary hosts or URL
 * components. GitHub's forwarding domain is supplied by the Codespace environment; the
 * mobile client uses the documented default and verifies it against the Bridge's host check.
 */
/** OAuth scope needed for the Codespaces API plus rotating native-device refresh tokens. */
internal const val CODESPACES_OAUTH_SCOPES = "codespace offline_access"

internal fun hasCodespacesOAuthScope(rawScopes: String): Boolean =
    rawScopes.split(Regex("[,\\s]+")).any { it == "codespace" }

/** Reject a token response unless GitHub confirms that the OAuth app received Codespaces access. */
private fun requireCodespacesOAuthScope(json: JSONObject) {
    check(hasCodespacesOAuthScope(json.optString("scope"))) {
        "GitHub did not grant the required codespace OAuth scope. Re-authorize the OAuth App and approve Codespaces access."
    }
}

internal fun codespacesBridgeOrigin(
    codespaceName: String,
    forwardingDomain: String = "app.github.dev"
): String {
    val name = codespaceName.trim().lowercase()
    require(name.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))) {
        "GitHub returned an invalid Codespace name."
    }
    val domain = forwardingDomain.trim().lowercase().trimEnd('.')
    require(domain.matches(Regex("(?:[a-z0-9-]+\\.)*app\\.github\\.dev")) &&
        !domain.startsWith(".") && !domain.contains("..")) {
        "Unsupported GitHub Codespaces forwarding domain."
    }
    return "https://$name-8000.$domain"
}

/**
 * A configured Bridge may be saved by the phone even while terminal isolation is BLOCKED.
 * This only establishes a trusted connection target; broker registration has a separate
 * fail-closed isolation gate in MT5RemoteBridgeClient.
 */
internal fun codespacesBridgeConfigurationAllowsOriginSave(
    status: String,
    database: String,
    managedExecution: String,
    isolationStatus: String
): Boolean {
    val knownIsolation = isolationStatus.trim().uppercase() in
        setOf("VERIFIED", "WORKER_ATTESTATION_REQUIRED", "BLOCKED")
    return status.trim().equals("configured", ignoreCase = true) &&
        database.trim().equals("ok", ignoreCase = true) &&
        managedExecution.trim().equals("configured", ignoreCase = true) &&
        knownIsolation
}

internal fun codespacesBridgeIsolationNotice(isolationStatus: String): String =
    when (isolationStatus.trim().uppercase()) {
        "VERIFIED" -> "Per-user terminal isolation is verified."
        "WORKER_ATTESTATION_REQUIRED" -> "Worker attestation is still required before a terminal is accepted."
        "BLOCKED" -> "Per-user terminal isolation is BLOCKED; the Bridge is connected for diagnostics only and broker credentials cannot be registered."
        else -> "Per-user terminal isolation is UNKNOWN; broker credential registration remains blocked."
    }

/** Owner-only GitHub Codespaces lifecycle client. No OAuth client secret is packaged in the APK. */
class CodespacesLifecycleClient(
    private val secrets: SecretsManager,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val forwardingDomain: String = "app.github.dev"
) {
    companion object {
        private const val API = "https://api.github.com"
        private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val REPOSITORY = "destinedai/DESTINEDFX-ANALYZER5"
        private const val REPOSITORY_ID = 1409048108L
        private const val API_VERSION = "2022-11-28"
    }

    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long
    )

    data class PollResult(val authorized: Boolean, val message: String, val slowDown: Boolean = false)
    data class LifecycleResult(
        val accepted: Boolean,
        val codespaceName: String,
        val state: String,
        val message: String
    )

    private fun clientId(): String =
        BuildConfig.CODESPACES_GITHUB_CLIENT_ID.trim()
            .takeIf { it.matches(Regex("[A-Za-z0-9._-]{8,128}")) }
            ?: secrets.getCodespacesGitHubClientId()
                .takeIf { it.matches(Regex("[A-Za-z0-9._-]{8,128}")) }
            ?: throw IllegalStateException("Configure the public GitHub OAuth App Client ID in this build or enter it in the Bot host controls.")

    suspend fun beginDeviceAuthorization(): DeviceAuthorization = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId())
            .add("scope", CODESPACES_OAUTH_SCOPES)
            .build()
        val request = Request.Builder().url(DEVICE_CODE_URL)
            .header("Accept", "application/json").post(body).build()
        val json = executeJson(request)
        val deviceCode = json.optString("device_code")
        val userCode = json.optString("user_code")
        val verificationUri = json.optString("verification_uri")
        val expires = json.optLong("expires_in", 0L)
        val interval = json.optLong("interval", 5L)
        require(deviceCode.isNotBlank() && userCode.isNotBlank() &&
            verificationUri == "https://github.com/login/device" && expires > 0L) {
            "GitHub did not return a valid device authorization request."
        }
        DeviceAuthorization(deviceCode, userCode, verificationUri, expires, interval.coerceIn(1L, 30L))
    }

    suspend fun checkDeviceAuthorization(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        require(deviceCode.isNotBlank() && deviceCode.length <= 1024) {
            "The GitHub device authorization request has expired; start again."
        }
        val body = FormBody.Builder()
            .add("client_id", clientId())
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val request = Request.Builder().url(TOKEN_URL)
            .header("Accept", "application/json").post(body).build()
        val json = executeJson(request, allowOAuthError = true)
        when (val error = json.optString("error")) {
            "authorization_pending" -> PollResult(false, "Waiting for GitHub authorization.")
            "slow_down" -> PollResult(false, "GitHub requested a slower authorization check.", slowDown = true)
            "access_denied" -> throw IllegalStateException("GitHub authorization was denied.")
            "expired_token", "incorrect_device_code" ->
                throw IllegalStateException("The GitHub device code expired. Start authorization again.")
            else -> {
                if (error.isNotBlank()) throw IllegalStateException("GitHub device authorization failed ($error).")
                saveTokenResponse(json)
                PollResult(true, "GitHub owner authorization is securely saved on this device.")
            }
        }
    }

    suspend fun startRepositoryCodespace(): LifecycleResult = withContext(Dispatchers.IO) {
        val accessToken = validAccessToken()
        val codespace = findOwnedCodespace(accessToken)
        val name = codespace.optString("name")
        val state = codespace.optString("state")
        require(name.isNotBlank()) { "GitHub returned a Codespace without a name." }
        if (state.equals("Available", ignoreCase = true)) {
            return@withContext LifecycleResult(true, name, state, "The DestinedFX Codespace is already available.")
        }
        executeSuccess(apiRequest(accessToken, "POST", "/user/codespaces/" + Uri.encode(name) + "/start"))
        LifecycleResult(true, name, state, "GitHub accepted the Codespaces start request. The Python host supervisor starts when the container becomes available.")
    }

    /**
     * Discover the repository Codespace's forwarded Bridge URL after the host is available.
     * The app never sends its GitHub OAuth token to the forwarded service. It accepts the
     * URL only when the public health endpoint reports the exact expected host, EPHEMERAL
     * profile, public-exposure guard, and durable Bridge configuration.
     */
    suspend fun discoverAndSaveBridge(timeoutMs: Long = 180_000L): String = withContext(Dispatchers.IO) {
        require(timeoutMs in 5_000L..300_000L) { "Bridge discovery timeout is outside the allowed range." }
        val accessToken = validAccessToken()
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastFailure = "Codespace has not reached Available state."
        var codespaceName = ""
        var codespaceState = "UNKNOWN"
        var nextStateRefreshAt = 0L
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            if (codespaceName.isBlank() || now >= nextStateRefreshAt) {
                val codespace = findOwnedCodespace(accessToken)
                codespaceName = codespace.optString("name")
                codespaceState = codespace.optString("state")
                nextStateRefreshAt = now + 15_000L
            }
            if (codespaceState.equals("Available", ignoreCase = true)) {
                val origin = codespacesBridgeOrigin(codespaceName, forwardingDomain)
                try {
                    val live = bridgeJson(origin, "/health/live")
                    check(live.first == 200 && live.second.optString("status") == "ok") {
                        "Bridge liveness check did not pass."
                    }
                    val health = bridgeJson(origin, "/health")
                    val healthBody = health.second
                    check(health.first == 200 &&
                        healthBody.optString("status") == "ok" &&
                        healthBody.optString("host_profile") == "EPHEMERAL" &&
                        healthBody.optBoolean("public_exposure", false) &&
                        healthBody.optString("expected_forwarded_host").equals(
                            URI(origin).host.orEmpty(), ignoreCase = true
                        )) {
                        "Forwarded Bridge host did not pass the exact-host/public-exposure safety check."
                    }
                    val config = bridgeJson(origin, "/health/config")
                    val configBody = config.second
                    check(config.first == 200 && codespacesBridgeConfigurationAllowsOriginSave(
                        status = configBody.optString("status"),
                        database = configBody.optString("database"),
                        managedExecution = configBody.optString("managed_execution"),
                        isolationStatus = configBody.optString("per_user_terminal_isolation")
                    )) {
                        "Bridge durable configuration is unavailable or its terminal-isolation status is unknown."
                    }
                    check(secrets.saveManagedMT5ServiceUrl(origin)) {
                        "The verified Bridge URL could not be saved in secure app storage."
                    }
                    return@withContext origin
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    lastFailure = error.message ?: "Forwarded Bridge health check failed."
                }
            } else {
                lastFailure = "GitHub Codespace state is $codespaceState; waiting for Available."
            }
            delay(4_000L)
        }
        throw IOException("Automatic Bridge URL discovery did not complete: $lastFailure")
    }

    private fun bridgeJson(origin: String, path: String): Pair<Int, JSONObject> {
        val request = Request.Builder()
            .url(origin + path)
            .header("Accept", "application/json")
            .header("X-Tunnel-Skip-AntiPhishing-Page", "true")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (response.priorResponse != null) throw IOException("Bridge probe was redirected; refusing an unverified host.")
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw IOException("Forwarded port did not return the expected Bridge JSON.") }
            return response.code to json
        }
    }

    suspend fun stopRepositoryCodespace(): LifecycleResult = withContext(Dispatchers.IO) {
        val accessToken = validAccessToken()
        val codespace = findOwnedCodespace(accessToken)
        val name = codespace.optString("name")
        val state = codespace.optString("state")
        require(name.isNotBlank()) { "GitHub returned a Codespace without a name." }
        if (state.equals("Shutdown", ignoreCase = true)) {
            return@withContext LifecycleResult(true, name, state, "The DestinedFX Codespace is already stopped.")
        }
        executeSuccess(apiRequest(accessToken, "POST", "/user/codespaces/" + Uri.encode(name) + "/stop"))
        LifecycleResult(true, name, state, "GitHub accepted the Codespaces stop request.")
    }

    suspend fun repositoryCodespaceState(): String = withContext(Dispatchers.IO) {
        val accessToken = validAccessToken()
        findOwnedCodespace(accessToken).optString("state").ifBlank { "UNKNOWN" }
    }

    suspend fun revokeLocalAuthorization() = withContext(Dispatchers.IO) {
        secrets.clearCodespacesGitHubAuthorization()
    }

    private fun validAccessToken(): String {
        val accessToken = secrets.getCodespacesGitHubAccessToken()
        val refreshToken = secrets.getCodespacesGitHubRefreshToken()
        val expiresAt = secrets.getCodespacesGitHubAccessExpiresAtMillis()
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            throw IllegalStateException("Authorize GitHub before controlling the Codespaces host.")
        }
        if (expiresAt > System.currentTimeMillis() + 60_000L) return accessToken

        val body = FormBody.Builder()
            .add("client_id", clientId())
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder().url(TOKEN_URL)
            .header("Accept", "application/json").post(body).build()
        val json = executeJson(request, allowOAuthError = true)
        if (json.has("error")) {
            secrets.clearCodespacesGitHubAuthorization()
            throw IllegalStateException("GitHub authorization expired. Authorize this device again.")
        }
        saveTokenResponse(json)
        return secrets.getCodespacesGitHubAccessToken()
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub did not issue a usable access token.")
    }

    private fun saveTokenResponse(json: JSONObject) {
        requireCodespacesOAuthScope(json)
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val expires = json.optLong("expires_in", 0L)
        require(access.isNotBlank() && refresh.isNotBlank() && expires > 0L) {
            "GitHub did not return an expiring access token and refresh token. Ensure offline_access is enabled for this OAuth App."
        }
        val expiry = System.currentTimeMillis() + expires.coerceAtMost(31_536_000L) * 1000L
        check(secrets.saveCodespacesGitHubTokens(access, refresh, expiry)) {
            "GitHub authorization could not be saved in Android Keystore-backed storage."
        }
    }

    private fun findOwnedCodespace(accessToken: String): JSONObject {
        val request = apiRequest(accessToken, "GET", "/user/codespaces?per_page=100")
        val json = executeJson(request)
        val items = json.optJSONArray("codespaces")
            ?: throw IOException("GitHub returned an invalid Codespaces list.")
        val matches = (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let { item ->
                val repository = item.optJSONObject("repository")
                val fullName = repository?.optString("full_name").orEmpty()
                val repositoryId = repository?.optLong("id", -1L) ?: -1L
                item.takeIf { fullName.equals(REPOSITORY, ignoreCase = true) && repositoryId == REPOSITORY_ID }
            }
        }
        if (matches.size != 1) {
            throw IllegalStateException(
                if (matches.isEmpty()) "No Codespace was found for $REPOSITORY."
                else "More than one Codespace belongs to $REPOSITORY. Remove the ambiguity before using phone controls."
            )
        }
        return matches.single()
    }

    private fun apiRequest(accessToken: String, method: String, path: String): Request {
        val builder = Request.Builder().url(API + path)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $accessToken")
            .header("X-GitHub-Api-Version", API_VERSION)
        return when (method) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(ByteArray(0).toRequestBody(null)).build()
            else -> throw IllegalArgumentException("Unsupported GitHub API method.")
        }
    }

    private fun executeJson(request: Request, allowOAuthError: Boolean = false): JSONObject {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful && !allowOAuthError) {
                val detail = runCatching { JSONObject(raw).optString("message") }.getOrDefault("")
                throw IOException(
                    if (response.code == 401 || response.code == 403)
                        "GitHub denied Codespaces access. Check GitHub App permissions and re-authorize."
                    else "GitHub request failed (" + response.code + ")" +
                        (if (detail.isNotBlank()) ": " + detail else ".")
                )
            }
            return runCatching { JSONObject(raw) }
                .getOrElse { throw IOException("GitHub returned an invalid response.") }
        }
    }

    private fun executeSuccess(request: Request) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                val detail = runCatching { JSONObject(raw).optString("message") }.getOrDefault("")
                throw IOException(
                    if (response.code == 401 || response.code == 403)
                        "GitHub denied the Codespaces lifecycle action. Verify Codespaces lifecycle admin permission."
                    else "Codespaces lifecycle request failed (" + response.code + ")" +
                        (if (detail.isNotBlank()) ": " + detail else ".")
                )
            }
        }
    }
}
