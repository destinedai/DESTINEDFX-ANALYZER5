package com.example.data.codespaces

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.example.BuildConfig
import com.example.security.SecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class GitHubCodespacesEndpoints(
    val deviceCodeUrl: String = "https://github.com/login/device/code",
    val accessTokenUrl: String = "https://github.com/login/oauth/access_token",
    val apiBaseUrl: String = "https://api.github.com"
)

/**
 * Device-flow GitHub App client for the signed-in owner.
 *
 * A native app must not contain the GitHub App client secret or private key.
 * GitHub App Device Flow lets this app exchange a public client ID + device code
 * and refresh its expiring user token without shipping a client secret.
 */
class GitHubCodespacesClient(
    private val secrets: SecretsManager,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val endpoints: GitHubCodespacesEndpoints = GitHubCodespacesEndpoints()
) {
    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long
    )

    data class Codespace(
        val name: String,
        val state: String,
        val repository: String,
        val gitRef: String,
        val machineCpus: Int,
        val lastUsedAt: String
    )

    data class UsageEstimate(
        val usedTodayMinutes: Long,
        val usedMonthCoreHours: Double,
        val remainingMonthCoreHours: Double,
        val remainingDailyMinutes: Long,
        val blockStart: Boolean
    )

    companion object {
        const val EXPECTED_REPOSITORY = "destinedai/DESTINEDFX-ANALYZER5"
        const val EXPECTED_REF = "free/phone-only-v1"
        const val MONTHLY_CORE_HOURS = 120.0
        const val DAILY_WALL_MINUTES = 90L
        const val LOW_BUDGET_WARNING_CORE_HOURS = 10.0
        private const val API = "https://api.github.com"
        private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val AUTH_EARLY_REFRESH_MS = 60_000L
        private const val USER_AGENT = "DestinedFX-Android-Codespaces"
    }

    private fun clientId(): String {
        val configured = secrets.getCodespacesGitHubClientId().ifBlank {
            BuildConfig.CODESPACES_GITHUB_CLIENT_ID
        }.trim()
        require(configured.matches(Regex("[A-Za-z0-9._-]{8,128}"))) {
            "Configure the public GitHub App Client ID before connecting."
        }
        if (secrets.getCodespacesGitHubClientId() != configured) {
            check(secrets.saveCodespacesGitHubClientId(configured)) {
                "GitHub App Client ID could not be saved in encrypted storage."
            }
        }
        return configured
    }

    private fun postForm(url: String, values: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply {
            values.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                val error = json.optString("error").ifBlank { "HTTP_${response.code}" }
                throw IllegalStateException("GitHub authorization failed: $error")
            }
            return json
        }
    }

    private fun apiRequest(
        url: String,
        token: String,
        method: String = "GET"
    ): JSONObject {
        require(url.startsWith(endpoints.apiBaseUrl.trimEnd('/') + "/")) { "Blocked non-GitHub API URL." }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .apply {
                when (method) {
                    "GET" -> get()
                    "POST" -> post(FormBody.Builder().build())
                    else -> throw IllegalArgumentException("Unsupported GitHub API method.")
                }
            }
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val body = if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful && response.code != 202 && response.code != 204) {
                val message = body.optString("message").take(160).ifBlank { "HTTP_${response.code}" }
                throw IllegalStateException("GitHub Codespaces API rejected the request: $message")
            }
            return body
        }
    }

    suspend fun beginDeviceAuthorization(): DeviceAuthorization = withContext(Dispatchers.IO) {
        check(secrets.isSecureStorageAvailable) {
            "Android Keystore-backed storage is unavailable; GitHub tokens will not be stored."
        }
        val body = postForm(
            endpoints.deviceCodeUrl,
            mapOf("client_id" to clientId(), "scope" to "offline_access")
        )
        val deviceCode = body.optString("device_code")
        val userCode = body.optString("user_code")
        val verificationUri = body.optString("verification_uri")
        val expiresIn = body.optLong("expires_in", 0L)
        val interval = body.optLong("interval", 5L).coerceAtLeast(1L)
        require(deviceCode.isNotBlank() && userCode.isNotBlank() &&
            verificationUri == "https://github.com/login/device" && expiresIn in 60L..1800L) {
            "GitHub returned an incomplete device authorization response."
        }
        DeviceAuthorization(deviceCode, userCode, verificationUri, expiresIn, interval)
    }

    fun openVerificationPage(context: Context, authorization: DeviceAuthorization) {
        require(authorization.verificationUri == "https://github.com/login/device")
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authorization.verificationUri))
    }

    suspend fun completeDeviceAuthorization(
        authorization: DeviceAuthorization,
        onWaiting: (suspend (Long) -> Unit)? = null
    ): Boolean {
        val deadline = System.currentTimeMillis() + authorization.expiresInSeconds * 1000L
        var interval = authorization.intervalSeconds
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val result = withContext(Dispatchers.IO) {
                postForm(
                    endpoints.accessTokenUrl,
                    mapOf(
                        "client_id" to clientId(),
                        "device_code" to authorization.deviceCode,
                        "grant_type" to DEVICE_GRANT
                    )
                )
            }
            val access = result.optString("access_token")
            val refresh = result.optString("refresh_token")
            if (access.isNotBlank() && refresh.isNotBlank()) {
                persistTokens(result)
                return true
            }
            when (result.optString("error")) {
                "authorization_pending" -> onWaiting?.invoke((deadline - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)
                "slow_down" -> {
                    interval = (result.optLong("interval", interval + 5L)).coerceAtLeast(interval + 5L)
                    onWaiting?.invoke((deadline - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)
                }
                "expired_token" -> throw IllegalStateException("GitHub device code expired. Start a new sign-in.")
                "access_denied" -> throw IllegalStateException("GitHub authorization was denied.")
                "device_flow_disabled" -> throw IllegalStateException("Enable Device Flow in the GitHub App settings.")
                else -> throw IllegalStateException("GitHub device authorization failed: ${result.optString("error").ifBlank { "UNKNOWN" }}")
            }
        }
        throw IllegalStateException("GitHub device authorization expired. Start a new sign-in.")
    }

    private fun persistTokens(response: JSONObject) {
        val access = response.optString("access_token")
        val refresh = response.optString("refresh_token")
        val accessSeconds = response.optLong("expires_in", 0L)
        val refreshSeconds = response.optLong("refresh_token_expires_in", 0L)
        require(access.startsWith("ghu_") && refresh.startsWith("ghr_") &&
            accessSeconds in 60L..86_400L && refreshSeconds in 60L..20_000_000L) {
            "GitHub did not return an expiring GitHub App user token and refresh token."
        }
        val now = System.currentTimeMillis()
        check(secrets.saveCodespacesGitHubTokens(
            access, refresh,
            now + accessSeconds * 1000L,
            now + refreshSeconds * 1000L
        )) { "GitHub tokens could not be saved in Android Keystore-backed storage." }
    }

    suspend fun validAccessToken(): String {
        val current = secrets.getCodespacesGitHubAccessToken()
        val expiresAt = secrets.getCodespacesGitHubAccessExpiresAtMillis()
        if (current.isNotBlank() && expiresAt > System.currentTimeMillis() + AUTH_EARLY_REFRESH_MS) {
            return current
        }
        val refresh = secrets.getCodespacesGitHubRefreshToken()
        val refreshExpires = secrets.getCodespacesGitHubRefreshExpiresAtMillis()
        if (refresh.isBlank() || refreshExpires <= System.currentTimeMillis()) {
            secrets.clearCodespacesGitHubAuthorization()
            throw IllegalStateException("GitHub sign-in expired. Connect the GitHub App again.")
        }
        val refreshed = withContext(Dispatchers.IO) {
            postForm(
                ACCESS_TOKEN_URL,
                mapOf(
                    "client_id" to clientId(),
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh
                )
            )
        }
        if (refreshed.optString("error").isNotBlank()) {
            secrets.clearCodespacesGitHubAuthorization()
            throw IllegalStateException("GitHub refresh token is no longer valid. Connect the GitHub App again.")
        }
        persistTokens(refreshed)
        return secrets.getCodespacesGitHubAccessToken().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Refreshed GitHub token could not be read from encrypted storage.")
    }

    suspend fun listTargetCodespaces(): List<Codespace> = withContext(Dispatchers.IO) {
        val token = validAccessToken()
        val url = "${endpoints.apiBaseUrl.trimEnd('/')}/user/codespaces?per_page=100"
        val body = apiRequest(url, token)
        val rows = body.optJSONArray("codespaces") ?: JSONArray()
        val matches = mutableListOf<Codespace>()
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val repository = item.optJSONObject("repository")?.optString("full_name").orEmpty()
            val ref = normalizeRef(item.optJSONObject("git_status")?.optString("ref").orEmpty())
            if (repository != EXPECTED_REPOSITORY || ref != EXPECTED_REF) continue
            val name = item.optString("name")
            if (name.isBlank()) continue
            val machine = item.optJSONObject("machine")
            matches += Codespace(
                name = name,
                state = item.optString("state", "Unknown"),
                repository = repository,
                gitRef = ref,
                machineCpus = machine?.optInt("cpus", 2)?.coerceIn(1, 64) ?: 2,
                lastUsedAt = item.optString("last_used_at")
            )
        }
        matches.sortedByDescending { it.lastUsedAt }
    }

    suspend fun getCodespace(name: String): Codespace = withContext(Dispatchers.IO) {
        requireValidName(name)
        val token = validAccessToken()
        val item = apiRequest("${endpoints.apiBaseUrl.trimEnd('/')}/user/codespaces/${encodeSegment(name)}", token)
        val repository = item.optJSONObject("repository")?.optString("full_name").orEmpty()
        val ref = normalizeRef(item.optJSONObject("git_status")?.optString("ref").orEmpty())
        require(repository == EXPECTED_REPOSITORY && ref == EXPECTED_REF) {
            "Selected Codespace no longer matches the expected repository and branch."
        }
        Codespace(
            name = item.optString("name").also { requireValidName(it) },
            state = item.optString("state", "Unknown"),
            repository = repository,
            gitRef = ref,
            machineCpus = item.optJSONObject("machine")?.optInt("cpus", 2)?.coerceIn(1, 64) ?: 2,
            lastUsedAt = item.optString("last_used_at")
        )
    }

    suspend fun startCodespace(codespace: Codespace) = withContext(Dispatchers.IO) {
        validateTarget(codespace)
        val token = validAccessToken()
        val current = apiRequest("${endpoints.apiBaseUrl.trimEnd('/')}/user/codespaces/${encodeSegment(codespace.name)}", token)
        requireTarget(current)
        apiRequest("${endpoints.apiBaseUrl.trimEnd('/')}/user/codespaces/${encodeSegment(codespace.name)}/start", token, "POST")
    }

    suspend fun stopCodespace(codespace: Codespace) = withContext(Dispatchers.IO) {
        validateTarget(codespace)
        val token = validAccessToken()
        val current = apiRequest("${endpoints.apiBaseUrl.trimEnd('/')}/user/codespaces/${encodeSegment(codespace.name)}", token)
        requireTarget(current)
        apiRequest("${endpoints.apiBaseUrl.trimEnd('/')}/user/codespaces/${encodeSegment(codespace.name)}/stop", token, "POST")
    }

    fun recordCodespaceStarted(codespace: Codespace) {
        val now = System.currentTimeMillis()
        val ledger = UsageLedger.parse(secrets.getCodespacesGitHubUsageLedgerJson())
        ledger.observeRunning(codespace.name, codespace.machineCpus, now)
        check(secrets.saveCodespacesGitHubUsageLedgerJson(ledger.serialize())) {
            "Codespaces usage estimate could not be saved securely."
        }
    }

    fun recordCodespaceStopped(codespace: Codespace) {
        val ledger = UsageLedger.parse(secrets.getCodespacesGitHubUsageLedgerJson())
        ledger.observeStopped(codespace.name, System.currentTimeMillis())
        check(secrets.saveCodespacesGitHubUsageLedgerJson(ledger.serialize())) {
            "Codespaces usage estimate could not be saved securely."
        }
    }

    fun usageEstimate(nowMs: Long = System.currentTimeMillis()): UsageEstimate {
        val ledger = UsageLedger.parse(secrets.getCodespacesGitHubUsageLedgerJson())
        val totals = ledger.totals(nowMs)
        val remainingMonthly = (MONTHLY_CORE_HOURS - totals.first).coerceAtLeast(0.0)
        val remainingDaily = (DAILY_WALL_MINUTES - totals.second).coerceAtLeast(0L)
        return UsageEstimate(
            usedTodayMinutes = totals.second,
            usedMonthCoreHours = totals.first,
            remainingMonthCoreHours = remainingMonthly,
            remainingDailyMinutes = remainingDaily,
            blockStart = remainingMonthly < LOW_BUDGET_WARNING_CORE_HOURS || remainingDaily <= 0L
        )
    }

    fun disconnectLocally(): Boolean = secrets.clearCodespacesGitHubAuthorization()

    private fun validateTarget(value: Codespace) {
        requireValidName(value.name)
        require(value.repository == EXPECTED_REPOSITORY && value.gitRef == EXPECTED_REF) {
            "Only the configured DestinedFX feature-branch Codespace can be controlled."
        }
    }

    private fun requireTarget(value: JSONObject) {
        val repository = value.optJSONObject("repository")?.optString("full_name").orEmpty()
        val ref = normalizeRef(value.optJSONObject("git_status")?.optString("ref").orEmpty())
        require(repository == EXPECTED_REPOSITORY && ref == EXPECTED_REF) {
            "GitHub Codespace repository/branch verification failed; lifecycle request was not sent."
        }
    }

    private fun requireValidName(name: String) {
        require(name.isNotBlank() && name.length <= 128 &&
            name.matches(Regex("[A-Za-z0-9-]+"))) { "GitHub returned an invalid Codespace name." }
    }

    private fun normalizeRef(ref: String): String =
        ref.trim().removePrefix("refs/heads/").removePrefix("refs/remotes/origin/")

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    /** Small, device-local estimator. It is not GitHub's authoritative billing meter. */
    internal data class UsageSession(
        val name: String,
        val cores: Int,
        val startedAt: Long,
        val stoppedAt: Long?
    )

    internal class UsageLedger private constructor(private val sessions: MutableList<UsageSession>) {
        fun observeRunning(name: String, cores: Int, now: Long) {
            val current = sessions.lastOrNull { it.name == name && it.stoppedAt == null }
            if (current == null) sessions += UsageSession(name, cores.coerceIn(1, 64), now, null)
        }

        fun observeStopped(name: String, now: Long) {
            val index = sessions.indexOfLast { it.name == name && it.stoppedAt == null }
            if (index >= 0) {
                val current = sessions[index]
                sessions[index] = current.copy(stoppedAt = maxOf(current.startedAt, now))
            }
        }

        fun totals(now: Long): Pair<Double, Long> {
            val monthStart = utcMonthStart(now)
            val todayStart = utcDayStart(now)
            var coreHours = 0.0
            var wallTodayMs = 0L
            sessions.forEach { session ->
                val end = (session.stoppedAt ?: now).coerceAtLeast(session.startedAt)
                val monthDuration = overlap(session.startedAt, end, monthStart, now)
                coreHours += monthDuration.toDouble() / 3_600_000.0 * session.cores
                wallTodayMs += overlap(session.startedAt, end, todayStart, now)
            }
            return coreHours to (wallTodayMs / 60_000L)
        }

        fun serialize(): String {
            val array = JSONArray()
            sessions.takeLast(400).forEach { session ->
                array.put(JSONObject()
                    .put("name", session.name)
                    .put("cores", session.cores)
                    .put("started_at", session.startedAt)
                    .put("stopped_at", session.stoppedAt ?: JSONObject.NULL))
            }
            return JSONObject().put("version", 1).put("sessions", array).toString()
        }

        companion object {
            fun parse(raw: String): UsageLedger {
                val sessions = mutableListOf<UsageSession>()
                val rows = runCatching { JSONObject(raw).optJSONArray("sessions") }.getOrNull()
                for (index in 0 until (rows?.length() ?: 0)) {
                    val row = rows?.optJSONObject(index) ?: continue
                    val name = row.optString("name")
                    val started = row.optLong("started_at", 0L)
                    val cores = row.optInt("cores", 0)
                    if (name.isBlank() || started <= 0L || cores !in 1..64) continue
                    val stopped = if (row.isNull("stopped_at")) null else row.optLong("stopped_at", 0L).takeIf { it >= started }
                    sessions += UsageSession(name, cores, started, stopped)
                }
                return UsageLedger(sessions)
            }

            private fun overlap(start: Long, end: Long, lower: Long, upper: Long): Long =
                (minOf(end, upper) - maxOf(start, lower)).coerceAtLeast(0L)

            private fun utcDayStart(now: Long): Long =
                java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

            private fun utcMonthStart(now: Long): Long {
                val date = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                return date.withDayOfMonth(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            }
        }
    }
}
