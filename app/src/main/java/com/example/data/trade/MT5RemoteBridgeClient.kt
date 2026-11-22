package com.example.data.trade

import com.example.security.SecretsManager
import com.example.data.codespaces.isCodespacesForwardedBridgeOrigin
import com.example.data.broker.ModeArmingPolicy
import com.example.data.broker.ModeAccountIdentity
import com.example.data.broker.ModePath
import com.example.data.broker.AccountMode
import com.example.data.broker.HostProfile
import com.example.data.broker.ModeReadOnlyVerification
import com.destinedfx.core.strategy.StrategyRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.InetAddress
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Android account-bound control client for a remote MT5 terminal and its managed EA. */
data class VerifiedBrokerQuote(
    val symbol: String,
    val bid: Double,
    val ask: Double,
    val digits: Int,
    val timestampMs: Long,
    val ageMs: Long,
    val source: String = "MT5_TERMINAL"
)

/** Parses only finite, two-sided, fresh quotes returned by the account-bound terminal heartbeat. */
internal fun parseVerifiedBrokerQuotes(
    rows: org.json.JSONArray?,
    nowMs: Long,
    maxAgeMs: Long = 15_000L
): List<VerifiedBrokerQuote>? {
    if (rows == null) return null
    if (nowMs <= 0L || maxAgeMs <= 0L) return emptyList()
    val accepted = mutableListOf<VerifiedBrokerQuote>()
    val seen = mutableSetOf<String>()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val symbol = row.optString("symbol").trim()
        val bid = row.optDouble("bid", Double.NaN)
        val ask = row.optDouble("ask", Double.NaN)
        val digits = row.optInt("digits", 5)
        val timestampMs = row.optLong("timestamp_ms", 0L)
        val rawAgeMs = nowMs - timestampMs
        val ageMs = rawAgeMs.coerceAtLeast(0L)
        val key = symbol.uppercase(java.util.Locale.ROOT)
        if (symbol.isBlank() || symbol.any { it.code < 32 || it.code == 127 } ||
            !bid.isFinite() || !ask.isFinite() || bid <= 0.0 || ask < bid ||
            digits !in 0..12 || timestampMs <= 0L || rawAgeMs < -3_000L ||
            rawAgeMs > maxAgeMs || !seen.add(key)) {
            continue
        }
        accepted += VerifiedBrokerQuote(symbol, bid, ask, digits, timestampMs, ageMs)
    }
    return accepted
}

/**
 * Missing or unknown isolation metadata must never be interpreted as a usable terminal host.
 * WORKER_ATTESTATION_REQUIRED is a known non-Linux worker path; BLOCKED and unknown values fail closed.
 */
internal fun isManagedTerminalIsolationStatusAccepted(isolationStatus: String): Boolean =
    isolationStatus.trim().uppercase(Locale.ROOT) in
        setOf("VERIFIED", "WORKER_ATTESTATION_REQUIRED")

class MT5RemoteBridgeClient(
    private val secrets: SecretsManager,
    profile: String,
    private val sensitiveActionAuthorized: () -> Boolean = { false },
    accountMode: String,
    brokerBrand: String,
    private val modeArmingPolicy: ModeArmingPolicy? = null,
    private val managementIdentityAssertionProvider: (suspend () -> String)? = null
) : MT5ManagedAccountGateway {
    override suspend fun registerManagedAccount(login: MT5ManagedAccountLogin): Snapshot {
        require(login.brokerBrand == brokerBrand && login.accountMode == accountMode) {
            "Managed login request does not match this broker-qualified profile."
        }
        return registerManagedAccount(
            login = login.login,
            password = login.password,
            server = login.server,
            accessType = login.accessType,
            consent = true,
            savePasswordLocally = login.savePasswordLocally,
            ownerBootstrapCode = login.ownerBootstrapCode
        )
    }

    /** Public liveness metadata is readable before pairing; do not call authenticated /state to learn host type. */
    override suspend fun hostProfile(): HostProfile = hostLifecycle().hostProfile

    /** Reads the Codespaces hard lifetime independently from its resettable idle timeout. */
    suspend fun hostLifecycle(): HostLifecycle = withContext(Dispatchers.IO) {
        val base = validateBaseUrl(baseUrl()) ?: throw IllegalStateException("CONFIGURATION_REQUIRED")
        val req = Request.Builder().url(base + "/health").header("Accept", "application/json")
            .apply { addOptionalAccessHeaders(this) }.get().build()
        http.newCall(req).execute().use { response ->
            val body = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { JSONObject() }
            if (!response.isSuccessful || body.optString("status") != "ok") {
                throw BridgeHttpException(response.code, "HOST_STATUS_UNAVAILABLE")
            }
            val hostProfile = when (body.optString("host_profile").uppercase()) {
                "PERSISTENT" -> HostProfile.PERSISTENT
                "EPHEMERAL" -> HostProfile.EPHEMERAL
                else -> HostProfile.EPHEMERAL
            }
            HostLifecycle(
                hostProfile = hostProfile,
                lifecycleStatus = body.optString("host_lifecycle_status", "UNKNOWN"),
                maxLifetimeSeconds = body.optLong("max_lifetime_seconds").takeIf { it > 0L },
                warningSeconds = body.optLong("host_warning_seconds", 3600L).coerceAtLeast(0L),
                emergencyStopLeadSeconds = body.optLong("host_emergency_stop_lead_seconds", 600L).coerceAtLeast(0L),
                expiryAtEpochSeconds = body.optLong("host_expiry_at_epoch_seconds").takeIf { it > 0L },
                remainingSeconds = if (body.has("host_remaining_seconds") && !body.isNull("host_remaining_seconds"))
                    body.optLong("host_remaining_seconds").coerceAtLeast(0L) else null,
                shutdownPending = body.optBoolean("host_shutdown_pending", false)
            )
        }
    }

    /** Legacy mode-keyed storage namespace; neutral identity migration is still pending. */
    val profile: String = profile.trim().uppercase().also {
        require(it.matches(Regex("[A-Z0-9_-]{1,80}"))) { "Invalid broker account profile key." }
    }
    /** Legacy connection hint required by callers; only fresh terminal reports establish account mode. */
    val accountMode: String = accountMode.trim().uppercase().also {
        require(it == "DEMO" || it == "LIVE") { "Account mode must be DEMO or LIVE." }
    }
    val brokerBrand: String = brokerBrand.trim().uppercase().also {
        require(it in setOf("FBS", "EXNESS", "HFM", "DERIV")) { "Unsupported MT5 broker brand." }
    }
    init { require(this.profile == "${this.brokerBrand}_${this.accountMode}") { "MT5 profile must be the explicit broker/mode slot." } }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .addInterceptor(BridgeGateway.interceptor).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class Pairing(val pairingId: String, val code: String, val expiresAt: String)
    data class DemoAcceptance(
        val acceptanceId: String,
        val pairingId: String,
        val identity: ModeAccountIdentity,
        val acceptedAtMs: Long,
        val expiresAtMs: Long,
        val evidenceDigest: String
    )
    data class LiveChallenge(val pairingId: String, val challengeId: String, val typedConfirmation: String, val expiresAt: String)
    data class ServiceReadiness(
        val httpReachable: Boolean, val httpStatus: Int?, val serviceStatus: String,
        val database: String, val managedExecution: String, val errorCode: String = "",
        val perUserTerminalIsolation: String = "unknown"
    )
    data class HostLifecycle(
        val hostProfile: HostProfile,
        val lifecycleStatus: String,
        val maxLifetimeSeconds: Long?,
        val warningSeconds: Long,
        val emergencyStopLeadSeconds: Long,
        val expiryAtEpochSeconds: Long?,
        val remainingSeconds: Long?,
        val shutdownPending: Boolean
    )
    data class CommandResult(
        val requestId: String, val queued: Boolean, val detail: String = "", val rejected: Boolean = false,
        val serverCommandId: String = "", val status: String = "",
        val targetTickets: List<String> = emptyList()
    )
    data class CloseAllConfirmation(val token: String, val expiresAt: String, val tickets: List<String>)
    data class Snapshot(
        val hostProfile: HostProfile = HostProfile.EPHEMERAL,
        val managedState: String, val workerStatus: String = "UNKNOWN", val accessType: String, val profileMode: String,
        val connected: Boolean, val terminalOnline: Boolean, val heartbeatFresh: Boolean,
        val state: String, val account: String, val actualLogin: String, val actualServer: String,
        val actualAccountMode: String, val liveAuthorized: Boolean, val tradeAllowed: Boolean,
        val terminalConnected: Boolean, val accountVerified: Boolean, val brokerIdentityVerified: Boolean,
        val currency: String, val balance: Double?, val equity: Double?,
        val margin: Double?, val freeMargin: Double?, val marginLevel: Double?, val leverage: Int?,
        val positions: List<JSONObject>?, val activeOrders: List<JSONObject>?, val closedDeals: List<JSONObject>?,
        val snapshotsValid: Boolean, val lastSignal: JSONObject?, val newsVerdict: JSONObject?, val raw: JSONObject,
        val brokerQuotes: List<VerifiedBrokerQuote>? = null
    )

    private fun configuredServiceUrl(): String = resolveServiceOrigin(
        secrets.getManagedMT5ServiceUrl(),
        com.example.BuildConfig.MT5_MANAGED_SERVICE_URL,
        com.example.BuildConfig.DESTINEDFX_ENDPOINTS_LOCKED,
        com.example.BuildConfig.DESTINEDFX_CODESPACES_HOST_DISCOVERY
    )
    fun baseUrl(): String = configuredServiceUrl().trimEnd('/')
    fun managedConfigured(): Boolean = validateBaseUrl(configuredServiceUrl()) != null
    override fun hasPairing(): Boolean = secrets.getMT5BridgeAccountAppToken(profile).isNotBlank()
    /** Pre-login configuration check. Unlike /health/ready, this does not require a paired terminal heartbeat. */
    suspend fun checkServiceConfiguration(): ServiceReadiness = withContext(Dispatchers.IO) {
        val base = validateBaseUrl(baseUrl()) ?: return@withContext ServiceReadiness(false, null, "CONFIGURATION_REQUIRED", "unknown", "unknown")
        val req = Request.Builder().url(base + "/health/config").header("Accept", "application/json")
            .apply { addOptionalAccessHeaders(this) }.get().build()
        try {
            http.newCall(req).execute().use { response ->
                val body = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { JSONObject() }
                val detail = body.optJSONObject("detail")
                ServiceReadiness(
                    httpReachable = true,
                    httpStatus = response.code,
                    serviceStatus = body.optString("status", if (response.isSuccessful) "unknown" else "not_configured"),
                    database = body.optString("database", "unknown"),
                    managedExecution = body.optString("managed_execution", "unknown"),
                    errorCode = detail?.optString("code").orEmpty(),
                    perUserTerminalIsolation = body.optString("per_user_terminal_isolation", "unknown")
                )
            }
        } catch (e: BridgeGatewayException) {
            ServiceReadiness(false, null, e.failure.name, "unknown", "unknown", e.failure.name)
        } catch (_: Exception) {
            ServiceReadiness(false, null, "unreachable", "unknown", "unknown")
        }
    }

    /** Probes service readiness independently of any paired account or terminal. HTTP 200 alone is not trading readiness. */
    suspend fun checkServiceReadiness(): ServiceReadiness = withContext(Dispatchers.IO) {
        val base = validateBaseUrl(baseUrl()) ?: return@withContext ServiceReadiness(false, null, "CONFIGURATION_REQUIRED", "unknown", "unknown")
        val req = Request.Builder().url(base + "/health/ready").header("Accept", "application/json")
            .apply { addOptionalAccessHeaders(this) }.get().build()
        try {
            http.newCall(req).execute().use { response ->
                val body = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { JSONObject() }
                val detail = body.optJSONObject("detail")
                ServiceReadiness(
                    httpReachable = true, httpStatus = response.code,
                    serviceStatus = body.optString("status", if (response.isSuccessful) "unknown" else "not_ready"),
                    database = body.optString("database", "unknown"),
                    managedExecution = body.optString("managed_execution", "unknown"),
                    errorCode = detail?.optString("code").orEmpty()
                )
            }
        } catch (e: BridgeGatewayException) {
            ServiceReadiness(false, null, e.failure.name, "unknown", "unknown", e.failure.name)
        } catch (_: Exception) { ServiceReadiness(false, null, "unreachable", "unknown", "unknown") }
    }

    /** Registers the credential identity; terminal-reported mode is resolved by the bridge after claim. */
    suspend fun registerManagedAccount(
        login: String,
        password: String,
        server: String,
        accessType: String,
        consent: Boolean,
        savePasswordLocally: Boolean = false,
        costModel: String = "OBSERVED_ROUND_TURN",
        commissionPerLot: Double = 0.0,
        costEvidence: String = "",
        ownerBootstrapCode: String = ""
    ): Snapshot {
        check(secrets.isSecureStorageAvailable) { "Secure storage is unavailable; managed account registration was not attempted." }
        require(managedConfigured()) { "CONFIGURATION_REQUIRED" }
        val managementIdentityAssertion = managementIdentityAssertionProvider?.invoke().orEmpty()
        require(managementIdentityAssertion.length >= 100) {
            "MANAGEMENT_SIGN_IN_REQUIRED: sign in to the DestinedFX management account before registering broker credentials."
        }
        val serviceConfiguration = checkServiceConfiguration()
        require(
            serviceConfiguration.httpReachable && serviceConfiguration.httpStatus == 200 &&
                serviceConfiguration.serviceStatus == "configured" &&
                serviceConfiguration.database == "ok" && serviceConfiguration.managedExecution == "configured"
        ) { "SERVICE_CONFIGURATION_NOT_READY: the managed bridge database and execution configuration must be ready before broker credentials are submitted." }
        require(isManagedTerminalIsolationStatusAccepted(serviceConfiguration.perUserTerminalIsolation)) {
            "PER_USER_TERMINAL_ISOLATION_REQUIRED: this host has not attested an accepted per-user MT5 terminal-isolation path. Broker credentials were not submitted."
        }
        require(accessType == "MASTER" || accessType == "INVESTOR")
        require(brokerBrand in setOf("FBS", "EXNESS", "HFM", "DERIV")) { "Unsupported MT5 broker brand." }
        require(consent) { "Explicit consent is required before sending broker credentials." }
        require(login.trim().matches(Regex("[0-9]{1,20}"))) { "Account login must contain digits only." }
        require(server.isNotBlank() && password.isNotBlank()) { "Exact broker server and password are required." }
        require(costModel in setOf("OBSERVED_ROUND_TURN", "CONFIGURED_ROUND_TURN", "CONFIGURED_PER_SIDE", "ZERO_COMMISSION_SPREAD_ONLY")) { "Invalid transaction cost model." }
        require(commissionPerLot.isFinite() && commissionPerLot >= 0.0) { "Commission must be finite and non-negative." }
        val evidence = costEvidence.trim()
        require(evidence.length <= 512 && evidence.none { it.code < 32 || it.code == 127 }) { "Cost evidence reference contains invalid characters." }
        when (costModel) {
            "OBSERVED_ROUND_TURN" -> require(commissionPerLot == 0.0 && evidence.isEmpty()) { "Observed mode uses broker deal history and must not include a configured commission." }
            "ZERO_COMMISSION_SPREAD_ONLY" -> require(commissionPerLot == 0.0 && evidence.length >= 8) { "Zero-commission mode requires a broker pricing reference; the EA will calculate spread and slippage costs live." }
        else -> require(commissionPerLot > 0.0 && evidence.length >= 8) { "Configured commission requires a positive account-currency amount per standard lot and a broker pricing reference." }
        }
        val previousLogin = secrets.getManagedMT5AccountLogin(profile)
        val previousServer = secrets.getManagedMT5AccountServer(profile)
        val previousGeneration = secrets.getManagedMT5CredentialGeneration(profile)
        check(secrets.saveManagedMT5AccountProfile(profile, login.trim(), server, accessType, password, savePasswordLocally)) {
            "Managed MT5 profile could not be durably saved in encrypted storage; no credentials were submitted."
        }
        val nextGeneration = secrets.getManagedMT5CredentialGeneration(profile)
        val modePath = when (brokerBrand) {
            "FBS" -> ModePath.FBS_MT5
            "EXNESS" -> ModePath.EXNESS_MT5
            "HFM" -> ModePath.HFM_MT5
            "DERIV" -> ModePath.DERIV_MT5
            else -> throw IllegalStateException("Unsupported MT5 broker brand")
        }
        if (previousGeneration.isNotBlank() && previousGeneration != nextGeneration) {
            if (accountMode == "DEMO" && previousLogin.isNotBlank() && previousServer.isNotBlank()) {
                secrets.clearPathDemoAcceptance(
                    ModeAccountIdentity(modePath, brokerBrand, previousLogin, previousServer, AccountMode.DEMO, previousGeneration)
                )
            }
            modeArmingPolicy?.onCredentialChanged(modePath, brokerBrand, previousLogin.ifBlank { login.trim() })
        }
        if (previousLogin.isNotBlank() && previousLogin != login.trim()) {
            modeArmingPolicy?.onAccountSwitch(modePath, brokerBrand, login.trim())
        }
        if (secrets.getMT5BridgeAccountAppToken(profile).isNotBlank()) {
            val existing = state()
            if (!existing.raw.optBoolean("management_identity_bound", false)) {
                revoke()
            }
        }
        if (secrets.getMT5BridgeAccountAppToken(profile).isBlank()) {
            pair(ownerBootstrapCode, managementIdentityAssertion)
        }
        val payload = JSONObject().put("login", login.trim()).put("server", server).put("password", password)
            .put("access_type", accessType).put("broker_brand", brokerBrand).put("consent", consent)
            .put("account_mode", accountMode)
            .put("credential_generation", nextGeneration)
            .put("cost_model", costModel).put("commission_per_lot", commissionPerLot).put("cost_evidence", evidence)
        request("POST", "/v1/app/managed-account", payload,
            managementIdentityAssertion = managementIdentityAssertion)
        return state()
    }

    suspend fun pair(ownerBootstrapCode: String = "", managementIdentityAssertion: String = ""): Pairing {
        check(secrets.isSecureStorageAvailable) { "Secure storage is unavailable; pairing request was not sent." }
        require(managementIdentityAssertion.length >= 100) {
            "MANAGEMENT_SIGN_IN_REQUIRED: a signed management identity assertion is required for MT5 pairing."
        }
        var bootstrap = ownerBootstrapCode.trim()
        require(bootstrap.length <= 200 && bootstrap.none { it.code < 32 || it.code == 127 }) {
            "Invalid owner bootstrap code."
        }
        // Public Codespaces mode requires a one-use owner code. Obtain it over HTTPS using
        // the same short-lived signed management identity; do not ask users to copy codes
        // from a Codespaces terminal or persist the code on the device.
        if (bootstrap.isBlank()) {
            val host = request("GET", "/health", includeAppAuthorization = false)
            if (host.optBoolean("public_exposure", false)) {
                val issued = request(
                    "POST", "/v1/owner/bootstrap-code", JSONObject(),
                    managementIdentityAssertion = managementIdentityAssertion,
                    includeAppAuthorization = false
                )
                bootstrap = issued.optString("owner_bootstrap_code")
                require(bootstrap.isNotBlank()) { "Bridge did not issue a one-time pairing bootstrap code." }
            }
        }
        val body = request(
            "POST", "/v1/pairings", JSONObject(),
            ownerBootstrapCode = bootstrap.takeIf { it.isNotEmpty() },
            managementIdentityAssertion = managementIdentityAssertion.takeIf { it.isNotBlank() }
        )
        val code = body.optString("pairing_code"); val token = body.optString("app_token")
        require(code.isNotBlank() && token.isNotBlank()) { "Bridge returned an incomplete pairing response." }
        val pairingId = body.optString("pairing_id")
        require(pairingId.isNotBlank()) { "Bridge returned an incomplete pairing identity." }
        check(secrets.saveMT5BridgeAccountAppToken(profile, token)) { "Bridge app token could not be durably stored in encrypted preferences." }
        return Pairing(pairingId, code, body.optString("expires_at"))
    }

    override suspend fun state(): Snapshot {
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
            actualServer.isNotBlank() && actualMode in setOf("DEMO", "LIVE") && actualMode == accountMode
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
        val brokerQuotes = parseVerifiedBrokerQuotes(
            obj.optJSONArray("broker_quotes"),
            System.currentTimeMillis(),
            obj.optLong("broker_quote_stale_ms", 15_000L).coerceIn(1L, 15_000L)
        )
        val managedState = managed?.let { it.optString("status", it.optString("state", "")) }.orEmpty().uppercase()
        val workerStatus = managed?.optString("worker_status", "UNKNOWN").orEmpty().uppercase()
        val access = managed?.optString("access_type", "").orEmpty().uppercase()
        val mode = managed?.optString("account_mode", "").orEmpty().uppercase()
        val hostProfile = when (obj.optString("BRIDGE_HOST_PROFILE", obj.optString("host_profile")).uppercase()) {
            "PERSISTENT" -> HostProfile.PERSISTENT
            "EPHEMERAL" -> HostProfile.EPHEMERAL
            else -> HostProfile.EPHEMERAL
        }
        return Snapshot(
            hostProfile, managedState, workerStatus, access, mode, online && terminalConnected && verified && actualMode == accountMode, online, freshness,
            when { !online -> "OFFLINE"; !freshness -> "STALE"; else -> obj.optString("ea_state", "OFFLINE").uppercase() },
            listOf(actualLogin, actualServer).filter { it.isNotBlank() }.joinToString(" @ "), actualLogin, actualServer,
            actualMode, managed?.optBoolean("live_authorized", false) == true,
            obj.optBoolean("trade_allowed", false), terminalConnected, verified,
            managed?.optBoolean("broker_identity_verified", obj.optBoolean("broker_identity_verified", false)) == true,
            account?.optString("currency").orEmpty(), account?.let { finite(it, "balance") }, account?.let { finite(it, "equity") },
            account?.let { finite(it, "margin") }, account?.let { finite(it, "free_margin") }, account?.let { finite(it, "margin_level") },
            account?.let { finiteInt(it, "leverage") }, array("open_positions"), array("active_orders"), array("closed_deals"),
            obj.has("open_positions") && !obj.isNull("open_positions") && obj.has("active_orders") && !obj.isNull("active_orders") && obj.has("closed_deals") && !obj.isNull("closed_deals") && array("open_positions") != null && array("active_orders") != null && array("closed_deals") != null,
            obj.optJSONObject("last_signal"), obj.optJSONObject("news_verdict"), obj, brokerQuotes
        )
    }

    /** Owner-only preflight; no stop is queued until GitHub confirms the Codespaces action. */
    suspend fun prepareHostStop(): JSONObject {
        val identity = managementIdentityAssertionProvider?.invoke()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Sign in to the DestinedFX management account before stopping the host.")
        return request(
            "POST",
            "/v1/app/host/shutdown-safe",
            JSONObject(),
            managementIdentityAssertion = identity
        )
    }

    /** Revokes a GitHub App user token through the owner-configured Bridge secret proxy. */
    suspend fun revokeCodespacesGitHubAuthorization(accessToken: String): JSONObject {
        require(accessToken.startsWith("ghu_") && accessToken.length <= 4096) {
            "A GitHub App user access token is required for revocation."
        }
        val identity = managementIdentityAssertionProvider?.invoke()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Sign in to the DestinedFX management account before revoking GitHub access.")
        return request(
            "POST",
            "/v1/app/github/revoke",
            JSONObject().put("access_token", accessToken),
            managementIdentityAssertion = identity
        )
    }

    suspend fun cancelHostStop(): JSONObject {
        val identity = managementIdentityAssertionProvider?.invoke()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Sign in to the DestinedFX management account before cancelling host shutdown.")
        return request(
            "POST",
            "/v1/app/host/cancel-stop",
            JSONObject(),
            managementIdentityAssertion = identity
        )
    }

    suspend fun hostStatus(): JSONObject = request("GET", "/v1/app/host/status")

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
        require(action in setOf("START", "STOP", "PAUSE", "RESUME", "EMERGENCY_STOP") + COMMAND_TRADE_ACTIONS)
        require(runCatching { UUID.fromString(requestId) }.isSuccess) { "request_id must be a UUID" }
        if (action in COMMAND_TRADE_ACTIONS + setOf("START", "RESUME") && !sensitiveActionAuthorized()) {
            return CommandResult(requestId, false, "REAUTHENTICATION_REQUIRED: authenticate again before this trading action", rejected = true)
        }
        if (action == "EMERGENCY_STOP") {
            val policy = modeArmingPolicy
                ?: return CommandResult(requestId, false, "MODE_POLICY_NOT_CONFIGURED: cannot confirm local disarming", rejected = true)
            policy.emergencyStop()
        }
        if (action in COMMAND_TRADE_ACTIONS) {
            val fresh = try { state() } catch (e: Exception) {
                return CommandResult(requestId, false, safeMessage(e), rejected = (e as? BridgeHttpException)?.status?.let { it in 400..499 } == true)
            }
            val reason = tradeActionFailure(fresh, action, config)
            if (reason != null) return CommandResult(requestId, false, reason, rejected = true)
        } else if (action in setOf("START", "RESUME", "PAUSE")) {
            val fresh = try { state() } catch (e: Exception) {
                return CommandResult(requestId, false, safeMessage(e), rejected = (e as? BridgeHttpException)?.status?.let { it in 400..499 } == true)
            }
            val reason = gateFailure(fresh, requireLiveAuthorization = action != "PAUSE")
            if (reason != null) return CommandResult(requestId, false, reason, rejected = true)
            if (action == "START" || action == "RESUME") {
                val policy = modeArmingPolicy
                    ?: return CommandResult(requestId, false, "MODE_POLICY_NOT_CONFIGURED: trading start is blocked", rejected = true)
                val identity = modeIdentity(fresh)
                if (!identity.isValid() || identity.mode.name != accountMode || identity.broker != brokerBrand ||
                    identity.accountId != secrets.getManagedMT5AccountLogin(profile) ||
                    identity.server != secrets.getManagedMT5AccountServer(profile)) {
                    return CommandResult(requestId, false, "MODE_ARMING_BLOCKED: selected MT5 mode/account identity does not match fresh terminal evidence", rejected = true)
                }
                val evidence = ModeReadOnlyVerification(
                    identity = identity,
                    checkedAtMs = System.currentTimeMillis(),
                    authenticatedSession = fresh.connected && fresh.terminalConnected,
                    freshAccountData = fresh.snapshotsValid,
                    freshQuote = false,
                    freshHeartbeat = fresh.heartbeatFresh,
                    accountIdentityMatched = fresh.accountVerified && fresh.brokerIdentityVerified &&
                        fresh.actualLogin == secrets.getManagedMT5AccountLogin(profile) &&
                        fresh.actualServer == secrets.getManagedMT5AccountServer(profile) &&
                        fresh.actualAccountMode == accountMode
                )
                val decision = if (accountMode == "DEMO") {
                    policy.demoDecision(identity, evidence, secrets.getPathDemoAcceptance(identity),
                        secrets.getManagedMT5CredentialGeneration(profile))
                } else policy.liveDecision(identity, hostProfile = fresh.hostProfile)
                if (!decision.allowed) {
                    return CommandResult(requestId, false, "MODE_ARMING_BLOCKED: " + decision.reason, rejected = true)
                }
            }
        }
        if (action == "START") {
            if (config.optString("strategy") !in StrategyRegistry.managedStrategyIds) return CommandResult(requestId, false, "START requires a registered managed strategy.", rejected = true)
            val risk = config.optDouble("risk_percent", Double.NaN)
            if (!isValidRiskPercent(risk)) return CommandResult(requestId, false, "Risk must be finite and in (0, 1] percent.", rejected = true)
            val daily = config.optDouble("max_daily_loss_percent", 2.0)
            if (!isValidDailyLossPercent(daily)) return CommandResult(requestId, false, "Daily loss must be finite, positive, and no greater than 2 percent.", rejected = true)
        }
        return try {
            val reply = request("POST", "/v1/app/commands", commandPayload(requestId, action, config))
            val serverId = exactIdentifier(reply, "id").orEmpty()
            val status = reply.optString("status").uppercase()
            val accepted = serverId.isNotBlank() && status in setOf("QUEUED", "ACKNOWLEDGED")
            val serverTickets = if (action in TRADE_ACTIONS) exactIdentifierList(reply.optJSONObject("config")?.optJSONArray("tickets")) else emptyList()
            val requestedTickets = exactIdentifierList(config.optJSONArray("tickets"))
            val targetMismatch = action in TRADE_ACTIONS && (
                serverTickets.isNullOrEmpty() ||
                    (action != "CANCEL_EA_PENDING" &&
                        (requestedTickets == null || serverTickets.toSet() != requestedTickets.toSet()))
                )
            val detail = when {
                !accepted -> "No complete command acknowledgement; reconcile this request ID before recovery."
                targetMismatch -> "Command was accepted but its returned target list is missing or differs from the request. Keep UNKNOWN and reconcile the terminal per-ticket results; do not resend."
                else -> ""
            }
            CommandResult(requestId, accepted && !targetMismatch, detail,
                rejected = false, serverCommandId = serverId, status = status, targetTickets = serverTickets.orEmpty())
        } catch (e: Exception) {
            CommandResult(requestId, false, safeMessage(e), rejected = (e as? BridgeHttpException)?.status?.let { it in 400..499 } == true)
        }
    }

    /** Obtain the server's short-lived, one-use token only after a fresh account/profile check. */
    suspend fun requestCloseAllConfirmation(): CloseAllConfirmation {
        val fresh = state()
        val reason = gateFailure(fresh, requireLiveAuthorization = false)
        require(reason == null) { reason ?: "Close-all is blocked until the verified snapshot is ready." }
        require(fresh.snapshotsValid && fresh.positions?.isNotEmpty() == true && fresh.positions.all { exactIdentifier(it, "position_id") != null }) {
            "A fresh complete position snapshot with exact identifiers is required before close-all confirmation."
        }
        val ticketIds = positionIdList(fresh) ?: throw IllegalStateException("Fresh position identifiers are incomplete or ambiguous.")
        val reply = request("POST", "/v1/app/close-all-confirmation", JSONObject().put("confirm_close_all", true))
        val token = reply.optString("confirmation_token").ifBlank { reply.optString("token") }
        require(token.isNotBlank()) { "Bridge returned no close-all confirmation token." }
        return CloseAllConfirmation(token, reply.optString("expires_at"), ticketIds)
    }

    private fun positionRows(snapshot: Snapshot): List<JSONObject>? {
        val parsed = snapshot.positions
        if (!parsed.isNullOrEmpty()) return parsed
        val array = snapshot.raw.optJSONArray("open_positions") ?: snapshot.raw.optJSONArray("positions")
            ?: return parsed
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun tradeActionFailure(snapshot: Snapshot, action: String, config: JSONObject): String? {
        val profileFailure = gateFailure(snapshot, requireLiveAuthorization = action in (OPENING_ACTIONS + POSITION_MANAGEMENT_ACTIONS) && accountMode == "LIVE")
        if (profileFailure != null) return profileFailure
        if (!snapshot.snapshotsValid || snapshot.positions == null || snapshot.activeOrders == null) return "Fresh, complete position and pending-order snapshots are required."
        val login = config.optString("account_login")
        val server = config.optString("account_server")
        val mode = config.optString("account_mode").uppercase()
        if (login != snapshot.actualLogin || server != snapshot.actualServer || mode != accountMode) return "Command account/environment does not match the verified selected profile."
        val tickets = config.optJSONArray("tickets")
        fun ticketsOrFailure(): List<String>? {
            tickets ?: return null
            val out = mutableListOf<String>()
            for (i in 0 until tickets.length()) {
                val raw = tickets.opt(i)
                if (raw !is String) return null
                val ticket = canonicalTicket(raw) ?: return null
                out += ticket
            }
            return out.takeIf { it.isNotEmpty() && it.distinct().size == it.size }
        }
        return when (action) {
            "TIGHTEN_POSITION_SL" -> {
                if (snapshot.hostProfile != HostProfile.PERSISTENT) return "A persistent owner host is required for a position protection edit."
                val ticket = config.optString("ticket")
                val newStop = config.optDouble("new_stop_loss", Double.NaN)
                val expectedStop = config.optDouble("expected_stop_loss", Double.NaN)
                val position = positionRows(snapshot)?.singleOrNull { exactIdentifier(it, "ticket") == ticket }
                    ?: return "The exact MT5 position ticket is no longer present."
                val symbol = position.optString("symbol")
                val direction = position.optString("direction").uppercase()
                val currentStop = position.optDouble("stop_loss", Double.NaN)
                val quote = snapshot.brokerQuotes?.singleOrNull { it.symbol == symbol }
                    ?: return "A fresh quote for the selected position symbol is unavailable."
                val tolerance = Math.pow(10.0, -quote.digits.toDouble()) * 0.51
                if (!newStop.isFinite() || newStop <= 0.0 || !expectedStop.isFinite() ||
                    !currentStop.isFinite() || kotlin.math.abs(currentStop - expectedStop) > tolerance) {
                    return "The selected position SL changed or the new SL is invalid; refresh the position."
                }
                if (quote.ageMs !in 0..15_000L || quote.timestampMs <= 0L) return "A fresh broker quote is required before editing a stop."
                val tighter = when (direction) {
                    "BUY" -> (expectedStop <= 0.0 || newStop > expectedStop + tolerance) && newStop < quote.bid
                    "SELL" -> (expectedStop <= 0.0 || newStop < expectedStop - tolerance) && newStop > quote.ask
                    else -> false
                }
                if (!tighter) return "Only a strictly tighter stop-loss is allowed; widening/removing stops is blocked."
                null
            }
            "PLACE_MARKET_ORDER" -> {
                if (snapshot.hostProfile != HostProfile.PERSISTENT) return "A persistent owner host is required for a new MT5 market order."
                val symbol = config.optString("symbol")
                val side = config.optString("side").uppercase()
                val risk = config.optDouble("risk_percent", Double.NaN)
                val sl = config.optDouble("stop_loss", Double.NaN)
                val tp = config.optDouble("take_profit", Double.NaN)
                val quote = snapshot.brokerQuotes?.singleOrNull { it.symbol == symbol }
                    ?: return "The exact selected symbol has no fresh quote from this MT5 terminal."
                val configured = snapshot.raw.optJSONObject("configuration")?.optJSONArray("symbols")
                val newsSymbol = configured?.takeIf { it.length() > 0 }?.optString(0).orEmpty()
                if (symbol.isBlank() || symbol != newsSymbol) return "This EA heartbeat has a fresh news verdict only for its first configured symbol."
                if (quote.ageMs !in 0..15_000L || quote.timestampMs <= 0L) return "A fresh MT5 broker quote is required."
                if (side !in setOf("BUY", "SELL") || !risk.isFinite() || risk <= 0.0 || risk > 1.0 ||
                    !sl.isFinite() || !tp.isFinite() || sl <= 0.0 || tp <= 0.0) return "Order side, risk or protective prices are invalid."
                val cap = snapshot.raw.optJSONObject("risk_controls")?.optDouble("risk_percent", Double.NaN) ?: Double.NaN
                if (!cap.isFinite() || risk > cap + 1e-9) return "Requested risk exceeds the current EA-configured per-trade risk cap."
                val controls = snapshot.raw.optJSONObject("risk_controls")
                    ?: return "Fresh EA daily-risk telemetry is unavailable."
                if (!controls.optBoolean("authorized", false) || !controls.optBoolean("daily_loss_clear", false) ||
                    !controls.optBoolean("risk_caps_clear", false)) return "Fresh EA daily-risk telemetry is not clear."
                val maxPositions = controls.optInt("max_concurrent_positions", 0)
                if (snapshot.positions.orEmpty().size >= maxPositions) return "The configured maximum concurrent-position limit is reached."
                val news = snapshot.newsVerdict
                if (news?.optString("status") != "CLEAR") return "Fresh CLEAR economic-calendar evidence is required."
                val priceOk = if (side == "BUY") sl < quote.ask && quote.ask < tp else tp < quote.bid && quote.bid < sl
                if (!priceOk) return "Stop-loss and take-profit must be on the correct sides of the current broker quote."
                null
            }
            "CANCEL_PENDING_SELECTED" -> {
                val selected = ticketsOrFailure()
                when {
                    selected == null || selected.size != 1 -> "Exactly one canonical pending-order ticket is required."
                    snapshot.activeOrders.none { exactIdentifier(it, "ticket") == selected.single() } -> "Selected pending order is not present in the fresh broker snapshot."
                    else -> null
                }
            }
            "CANCEL_EA_PENDING" -> if (managedOrderTicketList(snapshot).isNullOrEmpty())
                "No complete fresh EA pending-order snapshot is available." else null
            "CLOSE_POSITION_SELECTED" -> {
                val selected = ticketsOrFailure()
                when {
                    selected == null || selected.size != 1 -> "Exactly one canonical position identifier is required."
                    snapshot.positions.none { exactIdentifier(it, "position_id") == selected.single() } -> "Selected position identifier is not present in the fresh broker snapshot."
                    else -> null
                }
            }
            "CLOSE_ALL" -> {
                val selected = ticketsOrFailure()
                val current = snapshot.positions.mapNotNull { exactIdentifier(it, "position_id") }
                when {
                    selected == null || selected.isEmpty() -> "Close-all requires exact position identifiers from a fresh snapshot."
                    selected.toSet() != current.toSet() || current.size != snapshot.positions.size -> "Close-all position list no longer matches the fresh broker snapshot."
                    config.optString("confirmation").isBlank() -> "A fresh one-use close-all confirmation token is required."
                    else -> null
                }
            }
            else -> "Unsupported trade action."
        }
    }

    private fun modeIdentity(snapshot: Snapshot): ModeAccountIdentity {
        val path = when (brokerBrand) {
            "FBS" -> ModePath.FBS_MT5
            "EXNESS" -> ModePath.EXNESS_MT5
            "HFM" -> ModePath.HFM_MT5
            "DERIV" -> ModePath.DERIV_MT5
            else -> throw IllegalStateException("Unsupported MT5 broker brand")
        }
        return ModeAccountIdentity(
            path = path, broker = brokerBrand, accountId = snapshot.actualLogin, server = snapshot.actualServer,
            mode = if (accountMode == "DEMO") AccountMode.DEMO else AccountMode.LIVE,
            credentialGeneration = secrets.getManagedMT5CredentialGeneration(profile)
        )
    }

    /** Returns the pairing identity reported by the Bridge for this exact broker/mode slot. */
    suspend fun pairingId(): String {
        val id = state().raw.optString("pairing_id")
        require(id.isNotBlank()) { "Bridge did not return this profile's pairing identity." }
        return id
    }

    /** Records validated, broker-specific DEMO PASS evidence at the Bridge for this exact DEMO pairing. */
    suspend fun acceptDemo(evidenceDigest: String, evidenceDocument: String): DemoAcceptance {
        require(accountMode == "DEMO") { "DEMO acceptance can only be recorded by the DEMO profile." }
        require(sensitiveActionAuthorized()) { "REAUTHENTICATION_REQUIRED: authenticate before accepting this DEMO profile." }
        require(evidenceDocument.isNotBlank() && evidenceDocument.toByteArray(Charsets.UTF_8).size in 1..65536) {
            "The original DEMO PASS document is required and must be at most 64 KiB."
        }
        val documentDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(evidenceDocument.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        require(evidenceDigest.equals(documentDigest, ignoreCase = true)) {
            "The DEMO PASS document changed after its original file digest was calculated."
        }
        val fresh = state()
        require(fresh.hostProfile == HostProfile.PERSISTENT &&
            fresh.managedState == "READY" && fresh.workerStatus == "READY" &&
            fresh.accountVerified && fresh.brokerIdentityVerified && fresh.heartbeatFresh &&
            fresh.actualAccountMode == "DEMO" && fresh.accessType == "MASTER"
        ) { "DEMO_ACCEPTANCE_BLOCKED: a fresh identity-matched MASTER DEMO terminal is required." }
        val managementIdentity = managementIdentityAssertionProvider?.invoke().orEmpty()
        require(managementIdentity.length >= 100) {
            "MANAGEMENT_SIGN_IN_REQUIRED: sign in again before accepting this DEMO profile."
        }
        val result = request("POST", "/v1/app/demo-acceptance", JSONObject()
            .put("confirm_demo", true)
            .put("evidence_document", evidenceDocument),
            managementIdentityAssertion = managementIdentity)
        val accepted = requireNotNull(demoAcceptance()) {
            "Bridge DEMO acceptance could not be read back after it was recorded."
        }
        require(accepted.acceptanceId == result.optString("acceptance_id") &&
            accepted.identity.accountId == fresh.actualLogin &&
            accepted.identity.server == fresh.actualServer &&
            accepted.identity.credentialGeneration == secrets.getManagedMT5CredentialGeneration(profile) &&
            accepted.evidenceDigest.equals(evidenceDigest, ignoreCase = true)
        ) { "Bridge DEMO acceptance did not match the exact current account, credential generation and PASS digest." }
        return accepted
    }

    /** Reads and validates the current server-side acceptance for this exact DEMO pairing. */
    suspend fun demoAcceptance(): DemoAcceptance? {
        require(accountMode == "DEMO") { "DEMO acceptance can only be read from a DEMO profile." }
        val result = request("GET", "/v1/app/demo-acceptance")
        if (!result.optBoolean("accepted", false)) return null
        val pairingId = result.optString("pairing_id")
        val acceptanceId = result.optString("id")
        val brand = result.optString("broker_brand").uppercase()
        val login = result.optString("login")
        val server = result.optString("server")
        val mode = result.optString("account_mode").uppercase()
        val generation = result.optString("credential_generation")
        val digest = result.optString("pass_evidence_digest").lowercase()
        val acceptedAt = parseServerTimeMillis(result.optString("accepted_at"))
        val expiresAt = parseServerTimeMillis(result.optString("expires_at"))
        val path = modePath(brand) ?: return null
        val identity = ModeAccountIdentity(
            path, brand, login, server, AccountMode.DEMO, generation
        )
        if (pairingId.isBlank() || acceptanceId.isBlank() || mode != "DEMO" ||
            !identity.isValid() || acceptedAt <= 0L || expiresAt <= System.currentTimeMillis() ||
            !digest.matches(Regex("[0-9a-f]{64}")) ||
            brand != brokerBrand || generation != secrets.getManagedMT5CredentialGeneration(profile) ||
            login != secrets.getManagedMT5AccountLogin(profile) ||
            server != secrets.getManagedMT5AccountServer(profile)
        ) return null
        return DemoAcceptance(acceptanceId, pairingId, identity, acceptedAt, expiresAt, digest)
    }

    /**
     * Links this LIVE pairing to a different DEMO pairing. The Bridge independently checks
     * signed tenant ownership, broker, both account identities and DEMO worker/credential generations.
     */
    suspend fun associateDemoProfile(
        demoPairingId: String,
        acceptance: DemoAcceptance
    ): com.example.data.broker.VerifiedDemoProfileAssociation {
        require(accountMode == "LIVE") { "DEMO association must be initiated from the LIVE profile." }
        require(sensitiveActionAuthorized()) { "REAUTHENTICATION_REQUIRED: authenticate before linking account profiles." }
        require(demoPairingId.isNotBlank() && acceptance.pairingId == demoPairingId &&
            acceptance.identity.mode == AccountMode.DEMO &&
            acceptance.identity.broker.equals(brokerBrand, ignoreCase = true) &&
            acceptance.expiresAtMs > System.currentTimeMillis()
        ) { "DEMO_PROFILE_ASSOCIATION_INVALID: the DEMO acceptance does not match the selected broker profile." }
        val fresh = state()
        require(fresh.hostProfile == HostProfile.PERSISTENT &&
            fresh.managedState == "READY" && fresh.workerStatus == "READY" &&
            fresh.accountVerified && fresh.brokerIdentityVerified && fresh.heartbeatFresh &&
            fresh.actualAccountMode == "LIVE" && fresh.accessType == "MASTER"
        ) { "LIVE_PROFILE_NOT_READY: fresh identity-matched MASTER LIVE terminal evidence is required." }
        val livePairingId = fresh.raw.optString("pairing_id")
        require(livePairingId.isNotBlank() && livePairingId != demoPairingId) {
            "DEMO and LIVE must be independently paired profiles."
        }
        val managementIdentity = managementIdentityAssertionProvider?.invoke().orEmpty()
        require(managementIdentity.length >= 100) {
            "MANAGEMENT_SIGN_IN_REQUIRED: sign in again before linking DEMO and LIVE profiles."
        }
        val result = request("POST", "/v1/app/live-profile-association", JSONObject()
            .put("demo_pairing_id", demoPairingId)
            .put("demo_acceptance_id", acceptance.acceptanceId)
            .put("confirm_association", true),
            managementIdentityAssertion = managementIdentity)
        val associatedAt = parseServerTimeMillis(result.optString("associated_at"))
        val expiresAt = parseServerTimeMillis(result.optString("expires_at"))
        val proof = com.example.data.broker.VerifiedDemoProfileAssociation(
            demoIdentity = acceptance.identity,
            liveIdentity = modeIdentity(fresh),
            demoPairingId = result.optString("demo_pairing_id"),
            livePairingId = result.optString("live_pairing_id"),
            associatedAtMs = associatedAt,
            expiresAtMs = expiresAt,
            evidenceDigest = result.optString("evidence_digest").lowercase()
        )
        val localAcceptance = com.example.data.broker.ModeDemoAcceptance(
            acceptance.identity, acceptance.acceptedAtMs, acceptance.evidenceDigest
        )
        require(result.optBoolean("associated", false) &&
            proof.demoPairingId == demoPairingId && proof.livePairingId == livePairingId &&
            result.optString("demo_acceptance_id") == acceptance.acceptanceId &&
            result.optString("broker_brand").equals(brokerBrand, ignoreCase = true) &&
            result.optString("demo_login") == acceptance.identity.accountId &&
            result.optString("demo_server") == acceptance.identity.server &&
            result.optString("demo_credential_generation") == acceptance.identity.credentialGeneration &&
            result.optString("live_login") == fresh.actualLogin &&
            result.optString("live_server") == fresh.actualServer &&
            result.optString("live_credential_generation") == secrets.getManagedMT5CredentialGeneration(profile) &&
            proof.matches(localAcceptance, modeIdentity(fresh), System.currentTimeMillis())
        ) { "Bridge rejected or returned an inconsistent DEMO-to-LIVE profile association." }
        return proof
    }

    private fun modePath(brand: String): ModePath? = when (brand.uppercase()) {
        "FBS" -> ModePath.FBS_MT5
        "EXNESS" -> ModePath.EXNESS_MT5
        "HFM" -> ModePath.HFM_MT5
        "DERIV" -> ModePath.DERIV_MT5
        else -> null
    }

    private fun parseServerTimeMillis(value: String): Long {
        val match = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})$""")
            .matchEntire(value.trim()) ?: return 0L
        val fraction = match.groupValues[2]
        val millis = if (fraction.isBlank()) "" else "." + fraction.padEnd(3, '0').take(3)
        val normalized = match.groupValues[1] + millis + match.groupValues[3]
        val pattern = if (millis.isEmpty()) "yyyy-MM-dd'T'HH:mm:ssXXX" else "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
        return runCatching {
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(normalized)?.time ?: 0L
        }.getOrDefault(0L)
    }

    suspend fun liveChallenge(): LiveChallenge {
        require(sensitiveActionAuthorized()) { "REAUTHENTICATION_REQUIRED: authenticate again before LIVE authorization" }
        require(accountMode == "LIVE") { "LIVE authorization is available only for the LIVE profile." }
        val fresh = state()
        require(fresh.hostProfile == HostProfile.PERSISTENT) { "MODE_ARMING_BLOCKED: EPHEMERAL_HOST_LIVE_BLOCKED" }
        val reason = gateFailure(fresh, requireLiveAuthorization = false)
        require(reason == null) { reason ?: "LIVE profile is not ready." }
        val result = request("POST", "/v1/app/live-challenge")
        val pairingId = result.optString("pairing_id")
        val challengeId = result.optString("challenge_id")
        val phrase = result.optString("typed_confirmation")
        val expiresAt = result.optString("expires_at")
        require(pairingId.isNotBlank() && challengeId.isNotBlank() && phrase.isNotBlank() && expiresAt.isNotBlank()) {
            "Bridge returned an incomplete LIVE challenge."
        }
        return LiveChallenge(pairingId, challengeId, phrase, expiresAt)
    }

    suspend fun authorizeLiveSession(
        challengeId: String,
        typedConfirmation: String,
        stepUpAssertion: String
    ): String {
        require(sensitiveActionAuthorized()) { "REAUTHENTICATION_REQUIRED: authenticate again before LIVE authorization" }
        require(accountMode == "LIVE") { "LIVE authorization is available only for the LIVE profile." }
        require(challengeId.isNotBlank() && typedConfirmation.isNotBlank() && stepUpAssertion.length >= 100) {
            "LIVE authorization requires the server challenge, exact typed confirmation, and signed reauthentication."
        }
        val result = request("POST", "/v1/app/live-authorization", JSONObject()
            .put("challenge_id", challengeId)
            .put("typed_confirmation", typedConfirmation)
            .put("step_up_assertion", stepUpAssertion))
        return result.optString("expires_at").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Bridge returned no LIVE lease expiry.")
    }

    suspend fun clearLiveAuthorization() {
        if (accountMode == "LIVE" && hasPairing()) request("DELETE", "/v1/app/live-authorization")
    }

    suspend fun revoke() {
        request("DELETE", "/v1/app/pairing")
        secrets.clearMT5BridgeAccountAppToken(profile)
        secrets.clearMT5BridgeAccountPendingCommand(profile)
        secrets.clearMT5BridgeAccountSafetyStop(profile)
    }

    private fun gateFailure(snapshot: Snapshot, requireLiveAuthorization: Boolean): String? {
        val expectedLogin = secrets.getManagedMT5AccountLogin(profile)
        val expectedServer = secrets.getManagedMT5AccountServer(profile)
        return when {
            snapshot.hostProfile != HostProfile.PERSISTENT && accountMode == "LIVE" -> "EPHEMERAL_HOST_LIVE_BLOCKED"
            accountMode !in setOf("DEMO", "LIVE") -> "Selected account mode is not DEMO or LIVE."
            snapshot.profileMode != accountMode -> "Selected $brokerBrand $accountMode profile does not match the server-managed account mode."
            snapshot.accessType != "MASTER" -> "INVESTOR access is read-only and cannot execute trading commands."
            !snapshot.accountVerified -> "Broker-reported account identity is not verified by the bridge."
            !snapshot.brokerIdentityVerified -> "BROKER IDENTITY UNCONFIRMED: terminal company/server do not match the selected broker."
            snapshot.actualAccountMode != accountMode -> "Connected terminal reports ${snapshot.actualAccountMode.ifBlank { "UNVERIFIED" }} mode, not $accountMode."
            expectedLogin.isBlank() || snapshot.actualLogin != expectedLogin -> "Connected terminal login does not exactly match the selected $brokerBrand $accountMode account."
            expectedServer.isBlank() || snapshot.actualServer != expectedServer -> "Connected terminal server does not exactly match the selected $brokerBrand $accountMode account."
            !snapshot.heartbeatFresh -> "Authenticated terminal heartbeat is missing or stale."
            !snapshot.terminalConnected -> "MT5 terminal is not connected."
            !snapshot.tradeAllowed -> "Broker or terminal trade permission is unavailable."
            snapshot.managedState != "READY" -> "Managed service and terminal are not READY."
            snapshot.state !in setOf("READY", "RUNNING", "PAUSED", "STOPPED") -> "EA reports ${snapshot.state}; execution is blocked until it is reconciled."
            accountMode == "LIVE" && requireLiveAuthorization && !snapshot.liveAuthorized -> "Current explicit LIVE authorization is missing or expired."
            else -> null
        }
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null, ownerBootstrapCode: String? = null, managementIdentityAssertion: String? = null, includeAppAuthorization: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val base = validateBaseUrl(baseUrl()) ?: throw IllegalStateException(if (!managedConfigured()) "CONFIGURATION_REQUIRED" else "Managed service URL is invalid; HTTPS is required.")
        val builder = Request.Builder().url(base.trimEnd('/') + path).header("Accept", "application/json")
        addOptionalAccessHeaders(builder)
        addOwnerBootstrapHeader(builder, method, path, ownerBootstrapCode)
        addManagementIdentityHeader(builder, method, path, managementIdentityAssertion)
        if (includeAppAuthorization) secrets.getMT5BridgeAccountAppToken(profile).takeIf(String::isNotBlank)?.let { builder.header("Authorization", "Bearer $it") }
        when (method) { "GET" -> builder.get(); "DELETE" -> builder.delete(); else -> builder.method(method, (body ?: JSONObject()).toString().toRequestBody(jsonType)) }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty().take(256 * 1024)
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val value = JSONObject(text).opt("detail")
                    if (value is JSONObject) value.optString("code", "") else value?.toString().orEmpty()
                }.getOrDefault("")
                val normalizedDetail = when (detail) {
                    "Owner bootstrap code required" -> "OWNER_BOOTSTRAP_REQUIRED"
                    "Management identity required", "MANAGEMENT_IDENTITY_REQUIRED", "MANAGEMENT_IDENTITY_NOT_CONFIGURED", "MANAGEMENT_IDENTITY_INVALID" -> "MANAGEMENT_SIGN_IN_REQUIRED"
                    else -> detail
                }
                val safe = normalizedDetail.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "Bridge request failed (${response.code})."
                throw BridgeHttpException(response.code, safe)
            }
            if (text.isBlank()) JSONObject() else try { JSONObject(text) } catch (_: Exception) { throw IllegalStateException("Bridge returned invalid JSON.") }
        }
    }

    private fun validateBaseUrl(raw: String): String? {
        return validServiceOrigin(raw)
    }
    private fun addOptionalAccessHeaders(builder: Request.Builder) {
        val clientId = secrets.getCloudflareAccessClientId()
        val clientSecret = secrets.getCloudflareAccessClientSecret()
        require(clientId.isBlank() == clientSecret.isBlank()) { "Cloudflare Access service-token ID and secret must be configured together." }
        if (clientId.isNotBlank()) {
            builder.header("CF-Access-Client-Id", clientId)
            builder.header("CF-Access-Client-Secret", clientSecret)
        }
    }
    private fun finite(obj: JSONObject, key: String): Double? = obj.optDouble(key).takeIf { it.isFinite() }
    private fun finiteInt(obj: JSONObject, key: String): Int? = obj.optInt(key).takeIf { it > 0 }
    private fun safeMessage(e: Exception) = (e as? BridgeHttpException)?.safeDescription ?: "No confirmed response; reconcile the same request before any recovery."
    class BridgeHttpException(val status: Int, val safeDescription: String) : Exception(safeDescription)

    companion object {
        internal fun addOwnerBootstrapHeader(
            builder: Request.Builder,
            method: String,
            path: String,
            ownerBootstrapCode: String?
        ) {
            if (ownerBootstrapCode != null) {
                require(method == "POST" && path == "/v1/pairings") {
                    "Owner bootstrap is permitted only for initial pairing."
                }
                builder.header("X-Owner-Bootstrap", ownerBootstrapCode)
            }
        }

        internal fun addManagementIdentityHeader(
            builder: Request.Builder,
            method: String,
            path: String,
            assertion: String?
        ) {
            if (assertion == null) return
            require(method == "POST" && path in setOf(
                "/v1/pairings",
                "/v1/owner/bootstrap-code",
                "/v1/app/managed-account",
                "/v1/app/demo-acceptance",
                "/v1/app/live-profile-association"
            )) { "Management identity assertions are permitted only for pairing and owner-controlled profile changes." }
            require(assertion.length in 100..8192 &&
                assertion.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))) {
                "A valid signed management identity assertion is required."
            }
            builder.header("X-Management-Identity", assertion)
        }

        internal val TRADE_ACTIONS = setOf("CANCEL_PENDING_SELECTED", "CANCEL_EA_PENDING", "CLOSE_POSITION_SELECTED", "CLOSE_ALL")
        internal val OPENING_ACTIONS = setOf("PLACE_MARKET_ORDER")
        internal val POSITION_MANAGEMENT_ACTIONS = setOf("TIGHTEN_POSITION_SL")
        internal val COMMAND_TRADE_ACTIONS = TRADE_ACTIONS + OPENING_ACTIONS + POSITION_MANAGEMENT_ACTIONS
        private val MAX_UINT64 = BigInteger("18446744073709551615")

        /** Parses a non-empty unique list of canonical ticket strings; malformed/float IDs are rejected. */
        internal fun exactIdentifierList(array: org.json.JSONArray?): List<String>? {
            if (array == null || array.length() == 0) return null
            val ids = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val raw = array.opt(i)
                if (raw !is String) return null
                val id = canonicalTicket(raw) ?: return null
                ids += id
            }
            return ids.takeIf { it.distinct().size == it.size }
        }

        /** Only JSON strings or integral JSON integer objects are trusted; floating-point IDs are rejected. */
        internal fun exactIdentifier(obj: JSONObject, key: String): String? = canonicalTicket(obj.opt(key))

        internal fun canonicalTicket(value: Any?): String? {
            val text = when (value) {
                is String -> value
                is Byte, is Short, is Int, is Long -> value.toString()
                is BigInteger -> value.toString()
                else -> return null
            }
            if (!text.matches(Regex("[1-9][0-9]{0,19}"))) return null
            val integer = runCatching { BigInteger(text) }.getOrNull() ?: return null
            return text.takeIf { integer.signum() > 0 && integer <= MAX_UINT64 }
        }

        internal fun serverCommandId(obj: JSONObject): String? = exactIdentifier(obj, "id")

        internal fun positionStopTightenConfig(
            snapshot: Snapshot,
            ticket: String,
            newStopLoss: Double
        ): JSONObject {
            require(snapshot.snapshotsValid && snapshot.heartbeatFresh && snapshot.terminalConnected &&
                snapshot.accountVerified && snapshot.brokerIdentityVerified && snapshot.tradeAllowed &&
                snapshot.managedState == "READY" && snapshot.accessType == "MASTER") {
                "A fresh verified MASTER MT5 snapshot is required before editing position protection."
            }
            require(snapshot.hostProfile == HostProfile.PERSISTENT) {
                "A persistent Windows owner host is required before editing position protection."
            }
            if (snapshot.actualAccountMode == "LIVE") require(snapshot.liveAuthorized) {
                "A current account-bound LIVE lease is required before editing a real-money position stop."
            }
            require(ticket.matches(Regex("[1-9][0-9]{0,19}")) && newStopLoss.isFinite() && newStopLoss > 0.0) {
                "A canonical position ticket and finite positive stop price are required."
            }
            val rawPositions = snapshot.raw.optJSONArray("open_positions") ?: snapshot.raw.optJSONArray("positions")
            val positions = if (rawPositions != null) {
                (0 until rawPositions.length()).mapNotNull { rawPositions.optJSONObject(it) }
            } else snapshot.positions.orEmpty()
            val position = positions.singleOrNull { exactIdentifier(it, "ticket") == ticket }
                ?: throw IllegalStateException("The exact MT5 position ticket is no longer present.")
            val symbol = position.optString("symbol")
            val direction = position.optString("direction").uppercase()
            val currentStop = position.optDouble("stop_loss", Double.NaN)
            require(currentStop.isFinite() && currentStop >= 0.0 && symbol.isNotBlank() && direction in setOf("BUY", "SELL"))
            val quote = snapshot.brokerQuotes?.singleOrNull { it.symbol == symbol }
                ?: throw IllegalStateException("A verified quote for the selected position symbol is unavailable.")
            require(quote.ageMs in 0..15_000L && quote.timestampMs > 0L)
            val tolerance = Math.pow(10.0, -quote.digits.toDouble()) * 0.51
            val tighter = when (direction) {
                "BUY" -> (currentStop <= 0.0 || newStopLoss > currentStop + tolerance) && newStopLoss < quote.bid
                "SELL" -> (currentStop <= 0.0 || newStopLoss < currentStop - tolerance) && newStopLoss > quote.ask
                else -> false
            }
            require(tighter) { "Only a strictly tighter stop-loss is allowed; widening/removing stops is blocked." }
            return JSONObject()
                .put("account_login", snapshot.actualLogin)
                .put("account_server", snapshot.actualServer)
                .put("account_mode", snapshot.actualAccountMode)
                .put("ticket", ticket)
                .put("position_id", exactIdentifier(position, "position_id"))
                .put("symbol", symbol)
                .put("direction", direction)
                .put("expected_stop_loss", currentStop)
                .put("new_stop_loss", newStopLoss)
                .put("expected_bid", quote.bid)
                .put("expected_ask", quote.ask)
                .put("quote_timestamp_ms", quote.timestampMs)
        }

        internal fun marketOrderConfig(
            snapshot: Snapshot,
            side: String,
            stopLoss: Double,
            takeProfit: Double,
            riskPercent: Double
        ): JSONObject {
            require(snapshot.snapshotsValid && snapshot.heartbeatFresh && snapshot.terminalConnected &&
                snapshot.accountVerified && snapshot.brokerIdentityVerified && snapshot.tradeAllowed &&
                snapshot.managedState == "READY" && snapshot.accessType == "MASTER") {
                "A fresh verified MASTER MT5 terminal snapshot is required before an order."
            }
            require(snapshot.hostProfile == HostProfile.PERSISTENT) {
                "A persistent Windows owner host is required before a market order."
            }
            if (snapshot.actualAccountMode == "LIVE") require(snapshot.liveAuthorized) {
                "A current account-bound LIVE lease is required before a real-money order."
            }
            val normalizedSide = side.trim().uppercase()
            require(normalizedSide in setOf("BUY", "SELL"))
            require(riskPercent.isFinite() && riskPercent > 0.0 && riskPercent <= 1.0)
            require(stopLoss.isFinite() && takeProfit.isFinite() && stopLoss > 0.0 && takeProfit > 0.0)
            val symbols = snapshot.raw.optJSONObject("configuration")?.optJSONArray("symbols")
            val symbol = symbols?.takeIf { it.length() > 0 }?.optString(0).orEmpty()
            require(symbol.isNotBlank()) { "The EA has no configured broker symbol." }
            val quote = snapshot.brokerQuotes?.singleOrNull { it.symbol == symbol }
                ?: throw IllegalStateException("The first EA-configured symbol has no verified MT5 broker quote.")
            require(quote.ageMs in 0..15_000L && quote.timestampMs > 0L)
            val priceOk = if (normalizedSide == "BUY") stopLoss < quote.ask && quote.ask < takeProfit
                else takeProfit < quote.bid && quote.bid < stopLoss
            require(priceOk) { "Protective prices must be on the correct sides of the latest broker quote." }
            val controls = snapshot.raw.optJSONObject("risk_controls")
                ?: throw IllegalStateException("Fresh EA risk telemetry is unavailable.")
            val cap = controls.optDouble("risk_percent", Double.NaN)
            require(cap.isFinite() && riskPercent <= cap + 1e-9) {
                "Requested risk exceeds the current EA-configured per-trade risk cap."
            }
            require(controls.optBoolean("authorized", false) &&
                controls.optBoolean("daily_loss_clear", false) &&
                controls.optBoolean("risk_caps_clear", false)) {
                "Fresh EA daily-risk telemetry is not clear."
            }
            require(snapshot.newsVerdict?.optString("status") == "CLEAR") {
                "Fresh CLEAR economic-calendar evidence is required."
            }
            return JSONObject()
                .put("account_login", snapshot.actualLogin)
                .put("account_server", snapshot.actualServer)
                .put("account_mode", snapshot.actualAccountMode)
                .put("symbol", symbol)
                .put("side", normalizedSide)
                .put("risk_percent", riskPercent)
                .put("stop_loss", stopLoss)
                .put("take_profit", takeProfit)
                .put("expected_bid", quote.bid)
                .put("expected_ask", quote.ask)
                .put("quote_timestamp_ms", quote.timestampMs)
        }

        internal fun actionConfig(snapshot: Snapshot, action: String, tickets: List<String> = emptyList(), confirmationToken: String = ""): JSONObject {
            require(action in TRADE_ACTIONS)
            val config = JSONObject()
                .put("account_login", snapshot.actualLogin)
                .put("account_server", snapshot.actualServer)
                .put("account_mode", snapshot.actualAccountMode)
            when (action) {
                "CANCEL_PENDING_SELECTED", "CLOSE_POSITION_SELECTED", "CLOSE_ALL" -> {
                    require(tickets.isNotEmpty() && tickets.all { canonicalTicket(it) == it } && tickets.distinct().size == tickets.size)
                    config.put("tickets", org.json.JSONArray(tickets))
                }
                "CANCEL_EA_PENDING" -> Unit
            }
            if (action == "CLOSE_ALL") {
                require(confirmationToken.isNotBlank()) { "A one-use close-all confirmation token is required." }
                config.put("confirmation", confirmationToken)
            }
            return config
        }

        internal fun positionIdList(snapshot: Snapshot): List<String>? {
            val positions = snapshot.positions ?: return null
            val identifiers = positions.map { exactIdentifier(it, "position_id") ?: return null }
            return identifiers.takeIf { it.distinct().size == it.size }
        }

        internal fun orderTicketList(snapshot: Snapshot): List<String>? {
            val orders = snapshot.activeOrders ?: return null
            val tickets = orders.map { exactIdentifier(it, "ticket") ?: return null }
            return tickets.takeIf { it.distinct().size == it.size }
        }

        internal fun managedOrderTicketList(snapshot: Snapshot): List<String>? {
            val orders = snapshot.activeOrders ?: return null
            if (orders.isEmpty() || orders.any { !it.has("managed_by_ea") || it.opt("managed_by_ea") !is Boolean }) return null
            val tickets = orders.filter { it.optBoolean("managed_by_ea") }.map { exactIdentifier(it, "ticket") ?: return null }
            return tickets.takeIf { it.isNotEmpty() && it.distinct().size == it.size }
        }

        internal fun canManageSnapshot(selectedProfile: String, expectedLogin: String, expectedServer: String, snapshot: Snapshot): Boolean =
            selectedProfile.uppercase() in setOf("DEMO", "LIVE") && snapshot.connected && snapshot.terminalOnline && snapshot.heartbeatFresh &&
                snapshot.terminalConnected && snapshot.tradeAllowed && snapshot.accountVerified && snapshot.managedState == "READY" &&
                snapshot.accessType == "MASTER" && snapshot.profileMode == selectedProfile.uppercase() &&
                snapshot.actualAccountMode == selectedProfile.uppercase() && expectedLogin.isNotBlank() && snapshot.actualLogin == expectedLogin &&
                expectedServer.isNotBlank() && snapshot.actualServer == expectedServer && snapshot.snapshotsValid &&
                positionIdList(snapshot) != null && orderTicketList(snapshot) != null

        internal fun effectConfirmed(action: String, snapshot: Snapshot, tickets: List<String>): Boolean {
            if (!snapshot.snapshotsValid || !snapshot.heartbeatFresh) return false
            return when (action) {
                "CANCEL_PENDING_SELECTED", "CANCEL_EA_PENDING" -> snapshot.activeOrders?.none { exactIdentifier(it, "ticket")?.let { id -> id in tickets } == true } == true
                "CLOSE_POSITION_SELECTED", "CLOSE_ALL" -> snapshot.positions?.none { exactIdentifier(it, "position_id")?.let { id -> id in tickets } == true } == true
                else -> false
            }
        }

        internal fun resolveServiceOrigin(
            override: String,
            buildValue: String,
            locked: Boolean = false,
            allowCodespacesDiscovery: Boolean = false
        ): String = if (locked) {
            if (allowCodespacesDiscovery) {
                validServiceOrigin(override)?.takeIf(::isCodespacesForwardedBridgeOrigin)
                    ?: validServiceOrigin(buildValue).orEmpty()
            } else {
                validServiceOrigin(buildValue).orEmpty()
            }
        } else {
            validServiceOrigin(override).takeIf { override.isNotBlank() }
                ?: validServiceOrigin(buildValue).orEmpty()
        }

        internal fun initialServiceUrl(
            savedValue: String,
            buildValue: String,
            locked: Boolean = false,
            allowCodespacesDiscovery: Boolean = false
        ): String = if (locked) {
            if (allowCodespacesDiscovery) {
                validServiceOrigin(savedValue)?.takeIf(::isCodespacesForwardedBridgeOrigin)
                    ?: validServiceOrigin(buildValue).orEmpty()
            } else {
                validServiceOrigin(buildValue).orEmpty()
            }
        } else {
            savedValue.ifBlank { buildValue.takeUnless { it == "__CONFIGURATION_REQUIRED__" }.orEmpty() }
        }

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

        /** Null means a relevant closed deal is incomplete or has an invalid timestamp. */
        internal fun recentClosedDealNetPnl(deals: List<JSONObject>, nowMillis: Long): Double? {
            val cutoff = nowMillis - 7L * 24L * 60L * 60L * 1000L
            var total = 0.0
            for (deal in deals) {
                val closedAt = parseIsoTimestampMillis(deal.optString("closed_at")) ?: return null
                if (closedAt < cutoff) continue
                var dealNet = 0.0
                for (key in listOf("profit", "commission", "swap", "fees")) {
                    if (!deal.has(key) || deal.isNull(key)) return null
                    val value = deal.optDouble(key, Double.NaN)
                    if (!value.isFinite()) return null
                    dealNet += value
                    if (!dealNet.isFinite()) return null
                }
                total += dealNet
                if (!total.isFinite()) return null
            }
            return total
        }

        private fun parseIsoTimestampMillis(raw: String): Long? {
            val match = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})$""").matchEntire(raw.trim()) ?: return null
            val fraction = match.groupValues[2]
            val millis = if (fraction.isBlank()) "" else "." + fraction.padEnd(3, '0').take(3)
            val normalized = match.groupValues[1] + millis + match.groupValues[3]
            val pattern = if (millis.isEmpty()) "yyyy-MM-dd'T'HH:mm:ssXXX" else "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
            return runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(normalized)?.time
            }.getOrNull()
        }

        internal fun isValidRiskPercent(value: Double): Boolean = value.isFinite() && value > 0.0 && value <= 1.0
        internal fun isValidDailyLossPercent(value: Double): Boolean = value.isFinite() && value > 0.0 && value <= 2.0

        internal fun commandPayload(requestId: String, action: String, config: JSONObject = JSONObject()): JSONObject {
            require(runCatching { UUID.fromString(requestId) }.isSuccess) { "request_id must be a UUID" }
            require(action in setOf("START", "STOP", "PAUSE", "RESUME", "EMERGENCY_STOP") + COMMAND_TRADE_ACTIONS)
            if (action in POSITION_MANAGEMENT_ACTIONS) {
                require(config.optString("account_login").isNotBlank() && config.optString("account_server").isNotBlank() && config.optString("account_mode") in setOf("DEMO", "LIVE"))
                require(config.optString("ticket").matches(Regex("[1-9][0-9]{0,19}")))
                require(config.optString("position_id").matches(Regex("[1-9][0-9]{0,19}")))
                require(config.optString("symbol").matches(Regex("[A-Za-z0-9._-]{1,64}")) && config.optString("direction") in setOf("BUY", "SELL"))
                val oldStop = config.optDouble("expected_stop_loss", Double.NaN)
                val newStop = config.optDouble("new_stop_loss", Double.NaN)
                val bid = config.optDouble("expected_bid", Double.NaN)
                val ask = config.optDouble("expected_ask", Double.NaN)
                require(oldStop.isFinite() && oldStop >= 0.0 && newStop.isFinite() && newStop > 0.0)
                require(bid.isFinite() && ask.isFinite() && bid > 0.0 && ask >= bid && config.optLong("quote_timestamp_ms", 0L) > 0L)
                require(config.optJSONArray("tickets") == null && !config.has("take_profit"))
            }
            if (action in OPENING_ACTIONS) {
                require(config.optString("account_login").isNotBlank() && config.optString("account_server").isNotBlank() && config.optString("account_mode") in setOf("DEMO", "LIVE"))
                require(config.optString("symbol").matches(Regex("[A-Za-z0-9._-]{1,32}")) && config.optString("side") in setOf("BUY", "SELL"))
                val risk = config.optDouble("risk_percent", Double.NaN)
                val sl = config.optDouble("stop_loss", Double.NaN)
                val tp = config.optDouble("take_profit", Double.NaN)
                val bid = config.optDouble("expected_bid", Double.NaN)
                val ask = config.optDouble("expected_ask", Double.NaN)
                require(risk.isFinite() && risk > 0.0 && risk <= 1.0 && sl.isFinite() && tp.isFinite() && bid.isFinite() && ask.isFinite())
                require(sl > 0.0 && tp > 0.0 && bid > 0.0 && ask >= bid && config.optLong("quote_timestamp_ms", 0L) > 0L)
                require(config.optJSONArray("tickets") == null)
            }
            if (action in TRADE_ACTIONS) {
                require(config.optString("account_login").isNotBlank() && config.optString("account_server").isNotBlank() && config.optString("account_mode") in setOf("DEMO", "LIVE"))
                val tickets = config.optJSONArray("tickets")
                when (action) {
                    "CANCEL_PENDING_SELECTED", "CLOSE_POSITION_SELECTED" -> require(tickets != null && tickets.length() == 1 && tickets.opt(0) is String && canonicalTicket(tickets.opt(0)) != null) { "Exactly one canonical string ticket is required." }
                    "CLOSE_ALL" -> require(tickets != null && tickets.length() > 0 && config.optString("confirmation").isNotBlank()) { "Close-all requires position tickets and a one-use confirmation token." }
                    "CANCEL_EA_PENDING" -> require(tickets == null) { "EA pending cancellation uses the server's fresh heartbeat order snapshot." }
                }
                if (tickets != null) for (i in 0 until tickets.length()) require(tickets.opt(i) is String && canonicalTicket(tickets.opt(i)) != null) { "Tickets must be canonical positive uint64 decimal strings." }
            }
            return JSONObject().put("request_id", requestId).put("action", action).put("config", config)
        }
    }
}
