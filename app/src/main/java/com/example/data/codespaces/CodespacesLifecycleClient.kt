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
/**
 * GitHub App user tokens use fine-grained App permissions, not OAuth scopes.
 * offline_access asks GitHub to issue the expiring access/refresh token pair.
 * The returned scope field is intentionally empty for GitHub App user tokens.
 */
internal const val CODESPACES_OAUTH_SCOPES = "offline_access"
internal const val DESTINEDFX_CODESPACES_BRANCH = "feat/self-hosted-linux-host-runtime"

internal fun isCodespaceStoppedState(state: String): Boolean =
    state.equals("Shutdown", ignoreCase = true) ||
        state.equals("Stopped", ignoreCase = true)

internal fun codespaceMatchesDestinedFxTarget(
    repositoryFullName: String,
    repositoryId: Long,
    gitRef: String
): Boolean = repositoryFullName.equals("destinedai/DESTINEDFX-ANALYZER5", ignoreCase = true) &&
    repositoryId == 1409048108L &&
    gitRef == "refs/heads/$DESTINEDFX_CODESPACES_BRANCH"

internal fun deviceFlowPollIntervalSeconds(serverIntervalSeconds: Long, expiresInSeconds: Long): Long {
    val expiry = expiresInSeconds.coerceAtLeast(1L)
    return serverIntervalSeconds.coerceAtLeast(1L).coerceAtMost(expiry)
}

internal fun nextDeviceFlowPollIntervalSeconds(currentIntervalSeconds: Long, expiresInSeconds: Long): Long {
    val current = deviceFlowPollIntervalSeconds(currentIntervalSeconds, expiresInSeconds)
    val expiry = expiresInSeconds.coerceAtLeast(1L)
    return (current + 5L).coerceAtMost(expiry)
}

internal fun hasUsableExpiringGitHubAppToken(
    accessToken: String,
    refreshToken: String,
    accessExpiresInSeconds: Long,
    refreshExpiresInSeconds: Long
): Boolean = accessToken.isNotBlank() && refreshToken.isNotBlank() &&
    accessExpiresInSeconds > 0L && refreshExpiresInSeconds > 0L

private val DESTINEDFX_CODESPACES_FORWARDING_DOMAINS = listOf(
    "app.github.dev",
    "preview.app.github.dev",
    "githubpreview.dev"
)

internal fun codespacesBridgeOrigin(
    codespaceName: String,
    forwardingDomain: String = "app.github.dev"
): String {
    val name = codespaceName.trim().lowercase()
    require(name.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))) {
        "GitHub returned an invalid Codespace name."
    }
    val domain = forwardingDomain.trim().lowercase().trimEnd('.')
    require(domain in DESTINEDFX_CODESPACES_FORWARDING_DOMAINS) {
        "Unsupported GitHub Codespaces forwarding domain."
    }
    return "https://$name-8000.$domain"
}

internal fun codespacesBridgeOriginCandidates(codespaceName: String): List<String> =
    DESTINEDFX_CODESPACES_FORWARDING_DOMAINS.map { codespacesBridgeOrigin(codespaceName, it) }

/**
 * A locked APK accepts a saved Codespaces URL only when it has the strict
 * forwarded Bridge port shape. The lifecycle flow separately verifies the
 * exact forwarded Host, EPHEMERAL profile, public exposure and Bridge config
 * before it saves this URL in encrypted app storage.
 */
internal fun isCodespacesForwardedBridgeOrigin(raw: String): Boolean {
    return try {
        val uri = URI(raw.trim()).normalize()
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (!uri.scheme.equals("https", true) || uri.userInfo != null ||
            uri.query != null || uri.fragment != null ||
            uri.path.orEmpty().trimEnd('/').isNotEmpty() ||
            uri.port !in setOf(-1, 443)) return false
        val suffix = DESTINEDFX_CODESPACES_FORWARDING_DOMAINS
            .map { "-8000.$it" }
            .firstOrNull { host.endsWith(it) } ?: return false
        val name = host.removeSuffix(suffix)
        name.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
    } catch (_: Exception) {
        false
    }
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
        private const val CODESPACES_PAGE_SIZE = 100
        private const val CODESPACES_MAX_PAGES = 50
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
        DeviceAuthorization(deviceCode, userCode, verificationUri, expires, deviceFlowPollIntervalSeconds(interval, expires))
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
                for (origin in (listOf(forwardingDomain) + DESTINEDFX_CODESPACES_FORWARDING_DOMAINS).distinct()) {
                    if (origin !in DESTINEDFX_CODESPACES_FORWARDING_DOMAINS) continue
                    val candidate = codespacesBridgeOrigin(codespaceName, origin)
                    try {
                        val live = bridgeJson(candidate, "/health/live")
                        check(live.first == 200 && live.second.optString("status") == "ok") {
                            "Bridge liveness check did not pass."
                        }
                        val health = bridgeJson(candidate, "/health")
                        val healthBody = health.second
                        check(health.first == 200 &&
                            healthBody.optString("status") == "ok" &&
                            healthBody.optString("host_profile") == "EPHEMERAL" &&
                            healthBody.optBoolean("public_exposure", false) &&
                            healthBody.optString("expected_forwarded_host").equals(
                                URI(candidate).host.orEmpty(), ignoreCase = true
                            )) {
                            "Forwarded Bridge host did not pass the exact-host/public-exposure safety check."
                        }
                        val config = bridgeJson(candidate, "/health/config")
                        val configBody = config.second
                        check(config.first == 200 && codespacesBridgeConfigurationAllowsOriginSave(
                            status = configBody.optString("status"),
                            database = configBody.optString("database"),
                            managedExecution = configBody.optString("managed_execution"),
                            isolationStatus = configBody.optString("per_user_terminal_isolation")
                        )) {
                            "Bridge durable configuration is unavailable or its terminal-isolation status is unknown."
                        }
                        check(isCodespacesForwardedBridgeOrigin(candidate)) {
                            "Verified Bridge origin is not an approved GitHub Codespaces forwarded-host pattern."
                        }
                        check(secrets.saveManagedMT5ServiceUrl(candidate)) {
                            "The verified Bridge URL could not be saved in secure app storage."
                        }
                        return@withContext candidate
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        lastFailure = error.message ?: "Forwarded Bridge health check failed for this GitHub domain."
                    }
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

    suspend fun stopRepositoryCodespace(timeoutMs: Long = 300_000L): LifecycleResult = withContext(Dispatchers.IO) {
        require(timeoutMs in 5_000L..600_000L) {
            "Codespaces stop confirmation timeout is outside the allowed range."
        }
        val accessToken = validAccessToken()
        val codespace = findOwnedCodespace(accessToken)
        val name = codespace.optString("name")
        val initialState = codespace.optString("state")
        require(name.isNotBlank()) { "GitHub returned a Codespace without a name." }
        if (isCodespaceStoppedState(initialState)) {
            return@withContext LifecycleResult(true, name, initialState, "The DestinedFX Codespace is already stopped.")
        }
        executeSuccess(apiRequest(accessToken, "POST", "/user/codespaces/" + Uri.encode(name) + "/stop"))
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastState = initialState
        while (System.currentTimeMillis() < deadline) {
            delay(4_000L)
            val latest = findOwnedCodespace(accessToken)
            lastState = latest.optString("state").ifBlank { "UNKNOWN" }
            if (isCodespaceStoppedState(lastState)) {
                return@withContext LifecycleResult(
                    true, name, lastState,
                    "GitHub confirmed the DestinedFX Codespace is shut down."
                )
            }
            if (lastState.equals("Failed", ignoreCase = true) || lastState.equals("Deleted", ignoreCase = true)) {
                throw IOException("GitHub Codespaces stop ended in state $lastState; verify the owner host before cancelling the Bridge safety gate.")
            }
        }
        throw IOException(
            "GitHub accepted STOP HOST but did not confirm Shutdown within the allowed interval (last state: $lastState). " +
                "The Bridge safety gate must remain armed until the owner verifies host state."
        )
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
        val now = System.currentTimeMillis()
        val refreshExpiresAt = secrets.getCodespacesGitHubRefreshExpiresAtMillis()
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            throw IllegalStateException("Authorize GitHub before controlling the Codespaces host.")
        }
        if (refreshExpiresAt > 0L && refreshExpiresAt <= now + 60_000L) {
            secrets.clearCodespacesGitHubAuthorization()
            throw IllegalStateException("GitHub authorization expired. Authorize this device again.")
        }
        if (expiresAt > now + 60_000L) return accessToken

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
        // GitHub App user-token responses return an empty "scope"; the App's
        // configured fine-grained permissions, not OAuth scopes, authorize API calls.
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val expires = json.optLong("expires_in", 0L)
        val refreshExpires = json.optLong("refresh_token_expires_in", 0L)
        require(hasUsableExpiringGitHubAppToken(access, refresh, expires, refreshExpires)) {
            "GitHub did not return an expiring App user token and refresh token. Enable expiring user tokens or request offline_access."
        }
        val now = System.currentTimeMillis()
        val expiry = now + expires.coerceAtMost(31_536_000L) * 1000L
        val refreshExpiry = now + refreshExpires.coerceAtMost(31_536_000L) * 1000L
        check(secrets.saveCodespacesGitHubTokens(access, refresh, expiry, refreshExpiry)) {
            "GitHub authorization could not be saved in Android Keystore-backed storage."
        }
    }

    private fun findOwnedCodespace(accessToken: String): JSONObject {
        val matches = mutableListOf<JSONObject>()
        var reachedEnd = false
        for (page in 1..CODESPACES_MAX_PAGES) {
            val request = apiRequest(
                accessToken,
                "GET",
                "/user/codespaces?per_page=$CODESPACES_PAGE_SIZE&page=$page"
            )
            val json = executeJson(request)
            val items = json.optJSONArray("codespaces")
                ?: throw IOException("GitHub returned an invalid Codespaces list.")
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val repository = item.optJSONObject("repository")
                val fullName = repository?.optString("full_name").orEmpty()
                val repositoryId = repository?.optLong("id", -1L) ?: -1L
                val gitRef = item.optJSONObject("git_status")?.optString("ref").orEmpty()
                if (codespaceMatchesDestinedFxTarget(fullName, repositoryId, gitRef)) {
                    matches += item
                }
            }
            if (items.length() < CODESPACES_PAGE_SIZE) {
                reachedEnd = true
                break
            }
        }
        if (!reachedEnd) {
            throw IOException(
                "GitHub returned too many Codespace pages to verify the complete owner list safely."
            )
        }
        if (matches.size != 1) {
            throw IllegalStateException(
                if (matches.isEmpty()) {
                    "No Codespace was found for $REPOSITORY on branch $DESTINEDFX_CODESPACES_BRANCH."
                } else {
                    "More than one Codespace belongs to $REPOSITORY on branch $DESTINEDFX_CODESPACES_BRANCH. Remove the ambiguity before using phone controls."
                }
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
