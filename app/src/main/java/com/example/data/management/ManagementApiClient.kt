package com.example.data.management

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Management-only client; intentionally independent of broker, trading, and bridge gateways. */
class ManagementApiClient(
    origin: String,
    private val tokens: AccessTokenStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
) {
    private val baseUrl: String? = validatedOrigin(origin)

    fun register(email: String, password: String): ManagementUser {
        requireSecureStorage()
        return userRequest("POST", "/api/v1/auth/register", credentials(email, password), auth = false)
    }

    fun login(email: String, password: String): ManagementUser {
        requireSecureStorage()
        val json = execute("POST", "/api/v1/auth/login", credentials(email, password), auth = false)
        val token = json.optString("access_token")
        val expires = json.optString("expires_at")
        val expiryMs = runCatching { java.time.Instant.parse(expires).toEpochMilli() }
            .recoverCatching { java.time.OffsetDateTime.parse(expires).toInstant().toEpochMilli() }.getOrNull()
        if (token.length !in 32..256 || expiryMs == null || expiryMs <= System.currentTimeMillis()) {
            throw ManagementException(ManagementFailure.INVALID_RESPONSE)
        }
        val user = json.optJSONObject("user")?.let(::userFrom)
            ?: throw ManagementException(ManagementFailure.INVALID_RESPONSE)
        if (!tokens.save(token, expires)) throw ManagementException(ManagementFailure.SECURE_STORAGE_UNAVAILABLE)
        return user
    }

    fun logout() {
        try { execute("POST", "/api/v1/auth/logout", body = null, auth = true, allowEmpty = true) }
        finally { tokens.clear() }
    }

    fun currentUser(): ManagementUser = userFrom(execute("GET", "/api/v1/auth/me", auth = true))

    fun status(): ManagementStatus {
        val service = execute("GET", "/api/v1/service-status")
        val health = execute("GET", "/health")
        val ready = execute("GET", "/ready")
        val version = execute("GET", "/api/v1/app-version")
        val serviceState = service.optString("status")
        val healthState = health.optString("status")
        val readinessState = ready.optString("status")
        val databaseState = service.optString("database")
        val versionName = version.optString("version")
        if (serviceState != "operational" || healthState != "ok" || readinessState != "ready" ||
            databaseState != "connected" || versionName.isBlank()) {
            throw ManagementException(ManagementFailure.SERVICE_UNAVAILABLE)
        }
        return ManagementStatus(serviceState, healthState, readinessState, databaseState, versionName)
    }

    fun getPreferences(): JSONObject = execute("GET", "/api/v1/preferences", auth = true)

    fun putPreference(key: String, value: JSONObject): JSONObject =
        execute("PUT", "/api/v1/preferences/${key.encodePathSegment()}", JSONObject().put("value", value), auth = true)

    fun registerDevice(deviceId: String, platform: String, appVersion: String): JSONObject =
        execute("PUT", "/api/v1/devices", JSONObject()
            .put("device_id", deviceId).put("platform", platform).put("app_version", appVersion), auth = true)

    private fun userRequest(method: String, path: String, body: JSONObject, auth: Boolean): ManagementUser =
        userFrom(execute(method, path, body, auth))

    private fun requireSecureStorage() {
        if (!tokens.isAvailable) throw ManagementException(ManagementFailure.SECURE_STORAGE_UNAVAILABLE)
    }

    private fun credentials(email: String, password: String) = JSONObject().put("email", email.trim()).put("password", password)

    private fun execute(method: String, path: String, body: JSONObject? = null, auth: Boolean = false, allowEmpty: Boolean = false): JSONObject {
        val base = baseUrl ?: throw ManagementException(ManagementFailure.UNCONFIGURED)
        val requestBuilder = Request.Builder().url(base + path).header("Accept", "application/json")
        if (body != null) requestBuilder.header("Content-Type", "application/json; charset=utf-8")
        if (auth) {
            val token = tokens.validToken() ?: throw ManagementException(ManagementFailure.AUTH_EXPIRED)
            requestBuilder.header("Authorization", "Bearer $token")
        }
        val requestBody = body?.toString()?.toRequestBody(JSON)
        val request = when (method) {
            "GET" -> requestBuilder.get().build()
            "POST" -> requestBuilder.post(requestBody ?: ByteArray(0).toRequestBody(null)).build()
            "PUT" -> requestBuilder.put(requestBody ?: ByteArray(0).toRequestBody(null)).build()
            else -> throw IllegalArgumentException("Unsupported management operation")
        }
        val attempts = if (method == "GET") 2 else 1
        repeat(attempts) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw statusFailure(response.code, path)
                    val text = response.body?.string().orEmpty()
                    if (text.isBlank() && allowEmpty) return JSONObject()
                    return try { JSONObject(text) } catch (_: Exception) { throw ManagementException(ManagementFailure.INVALID_RESPONSE) }
                }
            } catch (e: ManagementException) {
                throw e
            } catch (_: IOException) {
                if (attempt == attempts - 1) throw ManagementException(ManagementFailure.OFFLINE)
            }
        }
        throw ManagementException(ManagementFailure.OFFLINE)
    }

    private fun statusFailure(code: Int, path: String): ManagementException = ManagementException(when {
        code == 401 && path.endsWith("/auth/login") -> ManagementFailure.INVALID_CREDENTIALS
        code == 401 -> ManagementFailure.AUTH_EXPIRED
        code == 403 -> ManagementFailure.FORBIDDEN
        code == 404 -> ManagementFailure.NOT_FOUND
        code == 409 -> ManagementFailure.CONFLICT
        code == 422 -> ManagementFailure.INVALID_REQUEST
        code == 429 -> ManagementFailure.RATE_LIMITED
        code in 500..599 -> ManagementFailure.SERVICE_UNAVAILABLE
        else -> ManagementFailure.REQUEST_FAILED
    })

    private fun userFrom(json: JSONObject): ManagementUser {
        val id = json.optString("id")
        val email = json.optString("email")
        if (id.isBlank() || email.isBlank()) throw ManagementException(ManagementFailure.INVALID_RESPONSE)
        return ManagementUser(id, email)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        /** Accept only a bare, real HTTPS origin. Placeholder hosts are rejected in every build type. */
        fun validatedOrigin(raw: String): String? {
            val value = raw.trim()
            if (value.isBlank() || value.contains("CONFIGURATION_REQUIRED", true) || value.contains("placeholder", true)) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val host = uri.host?.lowercase().orEmpty()
            if (!uri.scheme.equals("https", true) || host.isBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.rawPath.orEmpty().trimEnd('/').isNotEmpty()) return null
            if (uri.port == 0 || uri.port > 65535 || host in setOf("localhost", "example.com", "0.0.0.0", "127.0.0.1", "::1") || host.endsWith(".invalid") || host.endsWith(".test") || host.endsWith(".localhost") || host.endsWith(".example")) return null
            return "https://${uri.rawAuthority}"
        }
    }
}

interface AccessTokenStore {
    val isAvailable: Boolean
    fun save(token: String, expiresAt: String): Boolean
    fun validToken(nowMs: Long = System.currentTimeMillis()): String?
    fun clear(): Boolean
}

class SecretsManagerAccessTokenStore(private val secrets: com.example.security.SecretsManager) : AccessTokenStore {
    override val isAvailable: Boolean get() = secrets.isSecureStorageAvailable
    override fun save(token: String, expiresAt: String): Boolean {
        val expiry = runCatching { java.time.Instant.parse(expiresAt).toEpochMilli() }
            .recoverCatching { java.time.OffsetDateTime.parse(expiresAt).toInstant().toEpochMilli() }
            .getOrNull() ?: return false
        return secrets.saveManagementAccessToken(token, expiry)
    }
    override fun validToken(nowMs: Long): String? = secrets.getValidManagementAccessToken(nowMs)
    override fun clear(): Boolean = secrets.clearManagementAccessToken()
}

data class ManagementUser(val id: String, val email: String)
data class ManagementStatus(val service: String, val health: String, val readiness: String, val database: String, val version: String)
enum class ManagementFailure { UNCONFIGURED, SECURE_STORAGE_UNAVAILABLE, AUTH_EXPIRED, INVALID_CREDENTIALS, OFFLINE, FORBIDDEN, NOT_FOUND, CONFLICT, INVALID_REQUEST, RATE_LIMITED, SERVICE_UNAVAILABLE, REQUEST_FAILED, INVALID_RESPONSE }
class ManagementException(val failure: ManagementFailure) : Exception(failure.name)

private fun String.encodePathSegment(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
