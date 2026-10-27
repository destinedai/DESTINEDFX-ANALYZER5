package com.example.data.trade

import com.example.security.SecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Android control/status client for the remote MT5 relay. Never places orders. */
class MT5RemoteBridgeClient(private val secrets: SecretsManager) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class Pairing(val code: String, val expiresAt: String)
    data class CommandResult(val requestId: String, val queued: Boolean, val detail: String = "")
    data class Snapshot(
        val managedState: String, val accessType: String,
        val connected: Boolean, val terminalOnline: Boolean, val heartbeatFresh: Boolean,
        val state: String, val account: String, val tradeAllowed: Boolean,
        val terminalConnected: Boolean, val accountVerified: Boolean,
        val currency: String, val balance: Double?, val equity: Double?,
        val positions: List<JSONObject>, val closedDeals: List<JSONObject>,
        val lastSignal: JSONObject?, val raw: JSONObject
    )

    fun saveBaseUrl(input: String): Boolean {
        if (!secrets.isSecureStorageAvailable) return false
        if (secrets.getMT5BridgeAppToken().isNotBlank()) return false
        val url = validateBaseUrl(input) ?: return false
        secrets.saveMT5BridgeBaseUrl(url)
        return true
    }
    fun baseUrl(): String = com.example.BuildConfig.MT5_MANAGED_SERVICE_URL.trim().trimEnd('/').ifBlank { secrets.getMT5BridgeBaseUrl() }

    fun managedConfigured(): Boolean = com.example.BuildConfig.MT5_MANAGED_SERVICE_URL.isNotBlank()

    /** Creates/retains opaque app bearer and registers credentials over HTTPS; broker password is never logged or returned. */
    suspend fun registerManagedAccount(login: String, password: String, server: String, accessType: String, consent: Boolean): Snapshot {
        require(managedConfigured()) { "CONFIGURATION_REQUIRED" }
        require(accessType == "MASTER" || accessType == "INVESTOR")
        require(consent) { "Explicit consent is required before sending broker credentials." }
        if (secrets.getMT5BridgeAppToken().isBlank()) pair() // pairing code is intentionally discarded
        val payload = JSONObject().put("login", login.trim()).put("server", server).put("password", password)
            .put("access_type", accessType).put("consent", consent)
        request("POST", "/v1/app/managed-account", payload)
        return state()
    }

    suspend fun pair(): Pairing = request("POST", "/v1/pairings", JSONObject()).let { body ->
        val code = body.optString("pairing_code"); val token = body.optString("app_token")
        require(code.isNotBlank() && token.isNotBlank()) { "Bridge returned an incomplete pairing response." }
        if (!secrets.isSecureStorageAvailable) throw IllegalStateException("Secure storage is unavailable; app token was not saved.")
        check(secrets.saveMT5BridgeAppToken(token)) { "Bridge app token could not be durably stored in encrypted preferences." }
        Pairing(code, body.optString("expires_at"))
    }

    suspend fun state(): Snapshot {
        val obj = request("GET", "/v1/app/state")
        val account = obj.optJSONObject("account")
        val age = obj.optLong("heartbeat_age_seconds", Long.MAX_VALUE)
        val staleAfter = obj.optLong("heartbeat_stale_seconds", 15L)
        val online = obj.optBoolean("terminal_online", false)
        val freshness = age >= 0 && age <= staleAfter && online
        val terminalConnected = obj.optBoolean("terminal_connected", false)
        val verified = account != null && account.has("login") && !account.isNull("login") && account.optString("login").isNotBlank()
        fun array(key: String) = buildList { obj.optJSONArray(key)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::add) } }
        val managed = obj.optJSONObject("managed_execution")
        val managedState = managed?.let { it.optString("status", it.optString("state", "")) }.orEmpty().uppercase()
        return Snapshot(managedState, managed?.optString("access_type", "").orEmpty().uppercase(), true, online, freshness,
            when { !online -> "OFFLINE"; !freshness -> "STALE"; else -> obj.optString("ea_state", "OFFLINE").uppercase() },
            listOf(account?.optString("login").orEmpty(), account?.optString("server").orEmpty()).filter { it.isNotBlank() }.joinToString(" @ "),
            obj.optBoolean("trade_allowed", false), terminalConnected, verified,
            account?.optString("currency").orEmpty(), account?.let { finite(it, "balance") }, account?.let { finite(it, "equity") },
            array("open_positions"), array("closed_deals"), obj.optJSONObject("last_signal"), obj)
    }

    /** Each command gets one idempotency key; UNKNOWN retains it for reconciliation and is never retried here. */
    suspend fun command(requestId: String, action: String, config: JSONObject = JSONObject()): CommandResult {
        require(action in setOf("START", "STOP", "PAUSE", "RESUME", "EMERGENCY_STOP"))
        require(runCatching { UUID.fromString(requestId) }.isSuccess) { "request_id must be a UUID" }
        return try {
            request("POST", "/v1/app/commands", commandPayload(requestId, action, config))
            CommandResult(requestId, true)
        } catch (e: Exception) { CommandResult(requestId, false, safeMessage(e)) }
    }

    suspend fun revoke() {
        request("DELETE", "/v1/app/pairing")
        secrets.clearMT5BridgeAppToken()
        secrets.clearMT5BridgePendingCommand()
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val base = validateBaseUrl(baseUrl()) ?: throw IllegalStateException(if (!managedConfigured()) "CONFIGURATION_REQUIRED" else "Managed service URL is invalid; HTTPS is required.")
        val builder = Request.Builder().url(base.trimEnd('/') + path).header("Accept", "application/json")
        secrets.getMT5BridgeAppToken().takeIf(String::isNotBlank)?.let { builder.header("Authorization", "Bearer $it") }
        when (method) { "GET" -> builder.get(); "DELETE" -> builder.delete(); else -> builder.method(method, (body ?: JSONObject()).toString().toRequestBody(jsonType)) }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty().take(256 * 1024)
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val value = JSONObject(text).opt("detail")
                    if (value is JSONObject) value.optString("code", "") else value?.toString().orEmpty()
                }.getOrDefault("")
                val safe = detail.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "Bridge request failed (${response.code})."
                throw BridgeHttpException(response.code, safe)
            }
            if (text.isBlank()) JSONObject() else try { JSONObject(text) } catch (_: Exception) { throw IllegalStateException("Bridge returned invalid JSON.") }
        }
    }

    private fun validateBaseUrl(raw: String): String? = try {
        val uri = URI(raw.trim()).normalize(); val host = uri.host?.lowercase() ?: return null
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (uri.userInfo != null || uri.query != null || uri.fragment != null || uri.path.orEmpty().trimEnd('/').isNotEmpty()) return null
        if (uri.scheme != "https" && !(uri.scheme == "http" && loopback)) return null
        if (uri.port !in -1..65535) return null
        uri.toString().trimEnd('/')
    } catch (_: Exception) { null }
    private fun finite(obj: JSONObject, key: String): Double? = obj.optDouble(key).takeIf { it.isFinite() }
    private fun safeMessage(e: Exception) = (e as? BridgeHttpException)?.safeDescription ?: "No confirmed response; reconcile the same request before any recovery."
    class BridgeHttpException(val status: Int, val safeDescription: String) : Exception(safeDescription)

    companion object {
        internal fun canStart(managedState: String, accessType: String, heartbeatFresh: Boolean, terminalConnected: Boolean, tradeAllowed: Boolean, accountVerified: Boolean): Boolean =
            managedState.equals("READY", true) && accessType.equals("MASTER", true) && heartbeatFresh && terminalConnected && tradeAllowed && accountVerified

        internal fun isValidRiskPercent(value: Double): Boolean = value.isFinite() && value > 0.0 && value <= 1.0

        internal fun commandPayload(requestId: String, action: String, config: JSONObject = JSONObject()): JSONObject {
            require(runCatching { UUID.fromString(requestId) }.isSuccess) { "request_id must be a UUID" }
            require(action in setOf("START", "STOP", "PAUSE", "RESUME", "EMERGENCY_STOP"))
            return JSONObject().put("request_id", requestId).put("action", action).put("config", config)
        }
    }
}
