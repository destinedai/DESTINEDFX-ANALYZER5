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
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Android control/status client for the remote MT5 relay. Never places orders. */
class MT5RemoteBridgeClient(private val secrets: SecretsManager, profile: String = "DEMO") {
    val profile: String = profile.trim().uppercase().also { require(it == "DEMO" || it == "LIVE") }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class Pairing(val code: String, val expiresAt: String)
    data class CommandResult(val requestId: String, val queued: Boolean, val detail: String = "", val rejected: Boolean = false)
    data class Snapshot(
        val managedState: String, val accessType: String, val profileMode: String,
        val connected: Boolean, val terminalOnline: Boolean, val heartbeatFresh: Boolean,
        val state: String, val account: String, val actualLogin: String, val actualServer: String,
        val actualAccountMode: String, val liveAuthorized: Boolean, val tradeAllowed: Boolean,
        val terminalConnected: Boolean, val accountVerified: Boolean,
        val currency: String, val balance: Double?, val equity: Double?,
        val margin: Double?, val freeMargin: Double?, val marginLevel: Double?, val leverage: Int?,
        val positions: List<JSONObject>?, val activeOrders: List<JSONObject>?, val closedDeals: List<JSONObject>?,
        val snapshotsValid: Boolean, val lastSignal: JSONObject?, val newsVerdict: JSONObject?, val raw: JSONObject
    )

    private fun configuredServiceUrl(): String = resolveServiceOrigin(
        secrets.getManagedMT5ServiceUrl(), com.example.BuildConfig.MT5_MANAGED_SERVICE_URL
    )
    fun baseUrl(): String = configuredServiceUrl().trimEnd('/')
    fun managedConfigured(): Boolean = validateBaseUrl(configuredServiceUrl()) != null
    fun hasPairing(): Boolean = secrets.getMT5BridgeAppToken(profile).isNotBlank()

    /** Registers one mode-scoped pairing. The bridge stores broker credentials encrypted; this client never logs them. */
    suspend fun registerManagedAccount(login: String, password: String, server: String, accessType: String, consent: Boolean, savePasswordLocally: Boolean = false): Snapshot {
        require(managedConfigured()) { "CONFIGURATION_REQUIRED" }
        require(accessType == "MASTER" || accessType == "INVESTOR")
        require(consent) { "Explicit consent is required before sending broker credentials." }
        require(login.trim().matches(Regex("[0-9]{1,20}"))) { "Account login must contain digits only." }
        require(server.isNotBlank() && password.isNotBlank()) { "Exact broker server and password are required." }
        if (secrets.getMT5BridgeAppToken(profile).isBlank()) pair()
        val payload = JSONObject().put("login", login.trim()).put("server", server).put("password", password)
            .put("access_type", accessType).put("account_mode", profile).put("consent", consent)
        request("POST", "/v1/app/managed-account", payload)
        secrets.saveManagedMT5Profile(profile, login.trim(), server, accessType, password, savePasswordLocally)
        return state()
    }

    suspend fun pair(): Pairing = request("POST", "/v1/pairings", JSONObject()).let { body ->
        val code = body.optString("pairing_code"); val token = body.optString("app_token")
        require(code.isNotBlank() && token.isNotBlank()) { "Bridge returned an incomplete pairing response." }
        if (!secrets.isSecureStorageAvailable) throw IllegalStateException("Secure storage is unavailable; app token was not saved.")
        check(secrets.saveMT5BridgeAppToken(token, profile)) { "Bridge app token could not be durably stored in encrypted preferences." }
        Pairing(code, body.optString("expires_at"))
    }

    suspend fun state(): Snapshot {
        val obj = request("GET", "/v1/app/state")
        val account = obj.optJSONObject("account")
        val managed = obj.optJSONObject("managed_execution")
        val age = obj.optLong("heartbeat_age_seconds", Long.MAX_VALUE)
        val staleAfter = obj.optLong("heartbeat_stale_seconds", 15L)
        val online = obj.optBoolean("terminal_online", false)
        val freshness = age >= 0 && age <= staleAfter && online
        val terminalConnected = obj.optBoolean("terminal_connected", false)
        val actualLogin = account?.optString("login").orEmpty()
        val actualServer = account?.optString("server").orEmpty()
        val actualMode = account?.optString("account_mode").orEmpty().uppercase()
        val verified = obj.optBoolean("account_identity_verified", false) && actualLogin.isNotBlank() &&
            actualServer.isNotBlank() && actualMode in setOf("DEMO", "LIVE")
        fun array(key: String): List<JSONObject>? {
            val a = obj.optJSONArray(key) ?: return null
            val out = mutableListOf<JSONObject>()
            for (i in 0 until a.length()) {
                val item = a.opt(i)
                if (item !is JSONObject) return null
                out += item
            }
            return out
        }
        val managedState = managed?.let { it.optString("status", it.optString("state", "")) }.orEmpty().uppercase()
        val access = managed?.optString("access_type", "").orEmpty().uppercase()
        val mode = managed?.optString("account_mode", "").orEmpty().uppercase()
        return Snapshot(
            managedState, access, mode, true, online, freshness,
            when { !online -> "OFFLINE"; !freshness -> "STALE"; else -> obj.optString("ea_state", "OFFLINE").uppercase() },
            listOf(actualLogin, actualServer).filter { it.isNotBlank() }.joinToString(" @ "), actualLogin, actualServer,
            actualMode, managed?.optBoolean("live_authorized", false) == true,
            obj.optBoolean("trade_allowed", false), terminalConnected, verified,
            account?.optString("currency").orEmpty(), account?.let { finite(it, "balance") }, account?.let { finite(it, "equity") },
            account?.let { finite(it, "margin") }, account?.let { finite(it, "free_margin") }, account?.let { finite(it, "margin_level") },
            account?.let { finiteInt(it, "leverage") }, array("open_positions"), array("active_orders"), array("closed_deals"),
            obj.has("open_positions") && !obj.isNull("open_positions") && obj.has("active_orders") && !obj.isNull("active_orders") && obj.has("closed_deals") && !obj.isNull("closed_deals") && array("open_positions") != null && array("active_orders") != null && array("closed_deals") != null,
            obj.optJSONObject("last_signal"), obj.optJSONObject("news_verdict"), obj
        )
    }

    data class TradePage(val cursor: Int, val watermark: Double, val trades: List<JSONObject>)

    suspend fun tradeHistory(cursor: Int = 0, updatedAfter: Double = 0.0): TradePage {
        val body = request("GET", "/v1/app/trades?cursor=$cursor&updated_after=$updatedAfter&limit=200")
        val rows = body.optJSONArray("trades") ?: return TradePage(body.optInt("cursor", cursor), body.optDouble("watermark", updatedAfter), emptyList())
        val out = mutableListOf<JSONObject>()
        for (i in 0 until rows.length()) rows.optJSONObject(i)?.let(out::add)
        return TradePage(body.optInt("cursor", cursor), body.optDouble("watermark", updatedAfter), out)
    }

    /** Creates one profile-scoped idempotency key; UNKNOWN is retained for reconciliation, never retried here. */
    suspend fun command(requestId: String, action: String, config: JSONObject = JSONObject()): CommandResult {
        require(action in setOf("START", "STOP", "PAUSE", "RESUME", "EMERGENCY_STOP"))
        require(runCatching { UUID.fromString(requestId) }.isSuccess) { "request_id must be a UUID" }
        if (action in setOf("START", "RESUME", "PAUSE")) {
            val fresh = try { state() } catch (e: Exception) {
                return CommandResult(requestId, false, safeMessage(e), rejected = (e as? BridgeHttpException)?.status?.let { it in 400..499 } == true)
            }
            val reason = gateFailure(fresh, requireLiveAuthorization = action != "PAUSE")
            if (reason != null) return CommandResult(requestId, false, reason, rejected = true)
        }
        if (action == "START") {
            if (config.optString("strategy") !in setOf("smc-confluence-1.0.0", "ict-silver-bullet", "wyckoff-schematics", "supply-and-demand", "breakout-momentum", "mean-reversion", "trend-following", "liquidity-stop-hunts")) return CommandResult(requestId, false, "START requires a registered managed strategy.", rejected = true)
            val risk = config.optDouble("risk_percent", Double.NaN)
            if (!isValidRiskPercent(risk)) return CommandResult(requestId, false, "Risk must be finite and in (0, 1] percent.", rejected = true)
            val daily = config.optDouble("max_daily_loss_percent", 2.0)
            if (!isValidDailyLossPercent(daily)) return CommandResult(requestId, false, "Daily loss must be finite, positive, and no greater than 2 percent.", rejected = true)
        }
        return try {
            request("POST", "/v1/app/commands", commandPayload(requestId, action, config))
            CommandResult(requestId, true)
        } catch (e: Exception) {
            CommandResult(requestId, false, safeMessage(e), rejected = (e as? BridgeHttpException)?.status?.let { it in 400..499 } == true)
        }
    }

    suspend fun authorizeLiveSession(): String {
        require(profile == "LIVE") { "LIVE authorization is available only for the LIVE profile." }
        val fresh = state()
        val reason = gateFailure(fresh, requireLiveAuthorization = false)
        require(reason == null) { reason ?: "LIVE profile is not ready." }
        val result = request("POST", "/v1/app/live-authorization", JSONObject().put("confirm_live_trading", true))
        return result.optString("expires_at")
    }

    suspend fun clearLiveAuthorization() {
        if (profile == "LIVE" && hasPairing()) request("DELETE", "/v1/app/live-authorization")
    }

    suspend fun revoke() {
        request("DELETE", "/v1/app/pairing")
        secrets.clearMT5BridgeAppToken(profile)
        secrets.clearMT5BridgePendingCommand(profile)
        secrets.clearMT5BridgeSafetyStop(profile)
    }

    private fun gateFailure(snapshot: Snapshot, requireLiveAuthorization: Boolean): String? {
        val expectedLogin = secrets.getManagedMT5Login(profile)
        val expectedServer = secrets.getManagedMT5Server(profile)
        return when {
            profile !in setOf("DEMO", "LIVE") -> "Selected profile is not DEMO or LIVE."
            snapshot.profileMode != profile -> "Selected $profile profile does not match the server-managed account mode."
            snapshot.accessType != "MASTER" -> "INVESTOR access is read-only and cannot execute trading commands."
            !snapshot.accountVerified -> "Broker-reported account identity is not verified by the bridge."
            snapshot.actualAccountMode != profile -> "Connected terminal reports ${snapshot.actualAccountMode.ifBlank { "UNVERIFIED" }} mode, not $profile."
            expectedLogin.isBlank() || snapshot.actualLogin != expectedLogin -> "Connected terminal login does not exactly match the selected $profile profile."
            expectedServer.isBlank() || snapshot.actualServer != expectedServer -> "Connected terminal server does not exactly match the selected $profile profile."
            !snapshot.heartbeatFresh -> "Authenticated terminal heartbeat is missing or stale."
            !snapshot.terminalConnected -> "MT5 terminal is not connected."
            !snapshot.tradeAllowed -> "Broker or terminal trade permission is unavailable."
            snapshot.managedState != "READY" -> "Managed service and terminal are not READY."
            snapshot.state !in setOf("READY", "RUNNING", "PAUSED", "STOPPED") -> "EA reports ${snapshot.state}; execution is blocked until it is reconciled."
            profile == "LIVE" && requireLiveAuthorization && !snapshot.liveAuthorized -> "Current explicit LIVE authorization is missing or expired."
            else -> null
        }
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val base = validateBaseUrl(baseUrl()) ?: throw IllegalStateException(if (!managedConfigured()) "CONFIGURATION_REQUIRED" else "Managed service URL is invalid; HTTPS is required.")
        val builder = Request.Builder().url(base.trimEnd('/') + path).header("Accept", "application/json")
        secrets.getMT5BridgeAppToken(profile).takeIf(String::isNotBlank)?.let { builder.header("Authorization", "Bearer $it") }
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

    private fun validateBaseUrl(raw: String): String? {
        return validServiceOrigin(raw)
    }
    private fun finite(obj: JSONObject, key: String): Double? = obj.optDouble(key).takeIf { it.isFinite() }
    private fun finiteInt(obj: JSONObject, key: String): Int? = obj.optInt(key).takeIf { it > 0 }
    private fun safeMessage(e: Exception) = (e as? BridgeHttpException)?.safeDescription ?: "No confirmed response; reconcile the same request before any recovery."
    class BridgeHttpException(val status: Int, val safeDescription: String) : Exception(safeDescription)

    companion object {
        internal fun resolveServiceOrigin(override: String, buildValue: String): String =
            validServiceOrigin(override).takeIf { override.isNotBlank() }
                ?: validServiceOrigin(buildValue).orEmpty()

        internal fun validServiceOrigin(raw: String): String? {
            return try {
                val uri = URI(raw.trim()).normalize()
                val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
                val scheme = uri.scheme?.lowercase() ?: return null
                val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
                val placeholder = host in setOf("example.com", "example.org", "example.net", "testserver", "bridge.example", "localhost") || host.endsWith(".example") || host.endsWith(".localhost") || host.endsWith(".test") || host.endsWith(".invalid") || host.startsWith("your-") || host.startsWith("placeholder") || host.contains("example")
                val port = uri.port
                if (uri.userInfo != null || uri.query != null || uri.fragment != null || uri.path.orEmpty().trimEnd('/').isNotEmpty()) return null
                if (placeholder || scheme != "https" && !(scheme == "http" && loopback)) return null
                if (port == 0 || port !in -1..65535 || scheme == "https" && loopback) return null
                val literal = if (host.matches(Regex("^[0-9a-fA-F:.%]+$"))) runCatching { InetAddress.getByName(host) }.getOrNull() else null
                if (literal == null && (host.contains(":") || host.matches(Regex("^[0-9.]+$")))) return null
                if (literal != null && !isPublicIpLiteral(host, literal)) return null
                val serializedHost = if (host.contains(":")) "[$host]" else host
                "$scheme://$serializedHost" + if (port >= 0) ":$port" else ""
            } catch (_: Exception) { null }
        }

        private fun isPublicIpLiteral(raw: String, address: InetAddress): Boolean {
            if (raw.contains('%') || address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val ipv4 = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(raw)
            if (ipv4 != null) {
                val o = ipv4.groupValues.drop(1).map { it.toIntOrNull() ?: -1 }
                if (o.any { it !in 0..255 }) return false
                val first = o[0]; val second = o[1]; val third = o[2]
                return first !in setOf(0, 10, 127, 169, 224) &&
                    !(first == 100 && second in 64..127) && !(first == 172 && second in 16..31) &&
                    !(first == 192 && (second == 168 || (second == 0 && third in setOf(0, 2)))) &&
                    !(first == 198 && second in 18..19) && !(first == 198 && second == 51 && third == 100) &&
                    !(first == 203 && second == 0 && third == 113)
            }
            val lower = raw.lowercase()
            return !lower.startsWith("::ffff:") && !lower.startsWith("2001:db8:") &&
                !lower.startsWith("fc") && !lower.startsWith("fd")
        }

        internal fun canStart(
            selectedProfile: String, managedState: String, accessType: String, actualMode: String,
            expectedLogin: String, actualLogin: String, expectedServer: String, actualServer: String,
            eaState: String,
            heartbeatFresh: Boolean, terminalConnected: Boolean, tradeAllowed: Boolean,
            accountVerified: Boolean, liveAuthorized: Boolean, newsClear: Boolean
        ): Boolean = selectedProfile.uppercase() in setOf("DEMO", "LIVE") &&
            (selectedProfile.equals("DEMO", true) || liveAuthorized) &&
            managedState.equals("READY", true) && accessType.equals("MASTER", true) &&
            actualMode.equals(selectedProfile, true) && expectedLogin.isNotBlank() && actualLogin == expectedLogin &&
            expectedServer.isNotBlank() && actualServer == expectedServer && heartbeatFresh && terminalConnected &&
            tradeAllowed && accountVerified && newsClear && eaState.uppercase() in setOf("READY", "RUNNING", "PAUSED", "STOPPED") && selectedProfile.uppercase() in setOf("DEMO", "LIVE")

        internal fun isValidRiskPercent(value: Double): Boolean = value.isFinite() && value > 0.0 && value <= 1.0
        internal fun isValidDailyLossPercent(value: Double): Boolean = value.isFinite() && value > 0.0 && value <= 2.0

        internal fun commandPayload(requestId: String, action: String, config: JSONObject = JSONObject()): JSONObject {
            require(runCatching { UUID.fromString(requestId) }.isSuccess) { "request_id must be a UUID" }
            require(action in setOf("START", "STOP", "PAUSE", "RESUME", "EMERGENCY_STOP"))
            return JSONObject().put("request_id", requestId).put("action", action).put("config", config)
        }
    }
}
