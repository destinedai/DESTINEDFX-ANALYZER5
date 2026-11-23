package com.example.ui.bot

import com.example.data.broker.BrokerLoginSpec
import com.example.data.broker.BrokerLoginSpecs
import com.example.data.broker.SavedBrokerAccountProfile
import com.example.data.broker.LoginConnector
import com.example.data.broker.LoginField
import com.example.data.broker.LoginMode
import com.example.data.broker.HostProfile
import com.example.data.broker.ServerSuggestionSource
import com.example.data.mt5.MT5AccountSlot
import com.example.data.trade.BridgeGatewayException
import com.example.data.trade.BridgeGatewayFailure
import kotlinx.coroutines.delay
import org.json.JSONObject

/** One row of the Brokers list, built only from BrokerLoginSpecs. */
data class BrokerRow(
    val specId: String,
    /** Company name in white. Exact MT5 company names appear only where the owner's screenshots verified them. */
    val title: String,
    /** Brand line in blue. */
    val subtitle: String,
    val companyVerified: Boolean,
    val searchTerms: List<String>,
)

data class SavedLoginAccount(
    val spec: BrokerLoginSpec,
    val profileKey: String,
    val login: String,
    val server: String,
    val mode: LoginMode,
)

object SavedLoginAccounts {
    fun build(
        mt5Profiles: List<MT5AccountSlot>,
        providerProfiles: List<SavedBrokerAccountProfile>,
    ): List<SavedLoginAccount> {
        val mt5 = mt5Profiles.mapNotNull { profile ->
            val spec = BrokerLoginSpecs.byId("${profile.broker}_MT5") ?: return@mapNotNull null
            val mode = runCatching { LoginMode.valueOf(profile.accountMode) }.getOrNull() ?: return@mapNotNull null
            if (profile.login.isBlank()) return@mapNotNull null
            SavedLoginAccount(spec, profile.accountId, profile.login, profile.server, mode)
        }
        val providers = providerProfiles.mapNotNull { profile ->
            val spec = BrokerLoginSpecs.byId(profile.specId) ?: return@mapNotNull null
            val login = profile.login.ifBlank { profile.accountId }
            if (spec.connector == LoginConnector.MT5_TERMINAL || login.isBlank()) return@mapNotNull null
            SavedLoginAccount(spec, profile.accountId, login, profile.server, profile.mode)
        }
        return (mt5 + providers).distinctBy { "${it.spec.id}:${it.profileKey}:${it.mode.name}" }
            .sortedWith(compareBy<SavedLoginAccount> { it.spec.displayName }.thenBy { it.mode.name }.thenBy { it.login })
    }
}

object BrokerCatalog {
    const val EMPTY_STATE = "Use search to find a company"
    const val CAUTION = "Log in only with an account issued by your own broker. Never share your password."
    const val CANT_FIND = "CAN'T FIND YOUR BROKER?"
    const val UNVERIFIED_STATUS = "unverified broker"

    /** Verified from the owner's MetaTrader 5 screenshots. Never extended by guessing. */
    private val verifiedCompanyNames = mapOf("DERIV_MT5" to "Deriv.com Limited", "FBS_MT5" to "FBS Markets Inc.")

    fun rows(specs: List<BrokerLoginSpec> = BrokerLoginSpecs.all): List<BrokerRow> = specs.map { spec ->
        val verified = verifiedCompanyNames[spec.id]
        BrokerRow(
            specId = spec.id,
            title = verified ?: spec.displayName,
            subtitle = if (verified != null) spec.displayName else spec.companyAliases.first(),
            companyVerified = verified != null,
            searchTerms = (listOf(spec.displayName) + spec.companyAliases + listOfNotNull(verified)).distinct(),
        )
    }

    /** Filters by brand and alias as the owner types. A blank query returns every row. */
    fun search(rows: List<BrokerRow>, query: String): List<BrokerRow> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return rows
        return rows.filter { row -> row.searchTerms.any { it.lowercase().contains(q) } }
    }
}

/** A broker the owner typed in manually. It is never treated as verified. */
data class ManualBrokerProfile(val companyOrServer: String, val server: String, val status: String = BrokerCatalog.UNVERIFIED_STATUS) {
    companion object {
        fun create(name: String): ManualBrokerProfile? {
            val trimmed = name.trim().take(128)
            return if (trimmed.isEmpty()) null else ManualBrokerProfile(trimmed, trimmed)
        }
    }
}

enum class AccessType(val label: String, val wireValue: String) {
    TRADE("Trade account", "MASTER"),
    INVESTOR("Investor (read-only)", "INVESTOR"),
}

data class LoginForm(
    val type: AccessType = AccessType.INVESTOR,
    val mode: LoginMode = LoginMode.LIVE,
    val accountId: String? = null,
    val login: String = "",
    val password: String = "",
    val server: String = "",
    val appId: String = "",
    val token: String = "",
    val savePassword: Boolean = false,
    /** One-use public bridge bootstrap; never persisted with broker credentials. */
    val ownerBootstrapCode: String = "",
) {
    // Never print secrets, even in logs or test failures.
    override fun toString(): String = "LoginForm(type=$type, login=${login.length} chars, server=$server, secrets=<redacted>)"
}

data class SavePasswordPolicy(val defaultOn: Boolean, val enabled: Boolean, val reason: String?)
enum class ServerModeHint { DEMO_LIKELY, UNKNOWN }

object LoginRules {
    private val digits = Regex("[0-9]{1,20}")

    fun loginEnabled(spec: BrokerLoginSpec, form: LoginForm): Boolean = when (spec.connector) {
        LoginConnector.MT5_TERMINAL -> digits.matches(form.login.trim()) && form.password.isNotBlank() && form.server.isNotBlank()
        LoginConnector.DERIV_OPTIONS -> form.appId.isNotBlank() && form.token.isNotBlank()
        LoginConnector.CTRADER -> true // Grant access opens the provider's own consent page; no secret is typed here.
    }

    fun canSubmitLogin(spec: BrokerLoginSpec, form: LoginForm, mt5ModeHintChosen: Boolean): Boolean =
        loginEnabled(spec, form) && (spec.connector != LoginConnector.MT5_TERMINAL || mt5ModeHintChosen)

    fun availableTypes(
        spec: BrokerLoginSpec,
        mode: LoginMode = LoginMode.DEMO,
        hostProfile: HostProfile = HostProfile.PERSISTENT,
    ): List<AccessType> = when {
        !spec.investorAccess -> listOf(AccessType.TRADE)
        mode == LoginMode.LIVE && hostProfile != HostProfile.PERSISTENT -> listOf(AccessType.INVESTOR)
        else -> AccessType.values().toList()
    }

    fun modeForServer(server: String): LoginMode =
        if (server.contains("demo", ignoreCase = true) || server.contains("trial", ignoreCase = true)) LoginMode.DEMO
        else LoginMode.LIVE

    fun withMode(form: LoginForm, mode: LoginMode): LoginForm = form.copy(
        mode = mode,
        type = when {
            mode == LoginMode.LIVE -> AccessType.INVESTOR
            form.type == AccessType.INVESTOR -> AccessType.TRADE
            else -> form.type
        },
    )

    /** Save password is ON by default only with a secure lock screen or biometrics; otherwise OFF, disabled, and says why. */
    fun savePasswordPolicy(secureLockAvailable: Boolean): SavePasswordPolicy =
        if (secureLockAvailable) SavePasswordPolicy(defaultOn = true, enabled = true, reason = null)
        else SavePasswordPolicy(defaultOn = false, enabled = false, reason = "Set a screen lock or biometrics to save passwords.")

    /** A hint only. DEMO or LIVE is confirmed from the terminal's own account mode after login. */
    fun serverModeHint(server: String): ServerModeHint =
        if (server.lowercase().contains("demo")) ServerModeHint.DEMO_LIKELY else ServerModeHint.UNKNOWN
}

sealed interface ServerOption {
    data class Suggested(val value: String, val origin: String) : ServerOption
    data object TypeServerName : ServerOption
}

object ServerOptions {
    /** Exact account-issued instructions shown below each MT5 server field. */
    fun fieldHint(spec: BrokerLoginSpec): String {
        val dashboardPath = if (spec.id == "DERIV_MT5") {
            "Copy from Deriv Dashboard > MT5 > Server"
        } else {
            "Copy from Broker Dashboard > MT5 account details > Server"
        }
        return "$dashboardPath. Enter the exact account-issued value only; do NOT add :port."
    }

    /** Owner history and host-reported server entries only; never guess a provider server name. */
    fun build(spec: BrokerLoginSpec, ownerHistory: List<String>, hostReported: List<String>): List<ServerOption> {
        val suggestions = LinkedHashMap<String, String>()
        ownerHistory.filter { it.isNotBlank() }.forEach { suggestions.putIfAbsent(it.trim(), "Your history") }
        hostReported.filter { it.isNotBlank() }.forEach { suggestions.putIfAbsent(it.trim(), "Reported by host") }
        spec.serverSuggestions.filter { it.value.isNotBlank() }.forEach { suggestion ->
            val origin = when (suggestion.source) {
                ServerSuggestionSource.OWNER_HISTORY -> "Your history"
                ServerSuggestionSource.HOST_REPORTED -> "Reported by host"
                ServerSuggestionSource.OFFICIAL_PAGE -> "Official provider reference"
                ServerSuggestionSource.USER_SUPPLIED_REFERENCE -> "Reference name · verify with broker"
            }
            suggestions.putIfAbsent(suggestion.value.trim(), origin)
        }
        return suggestions.map { ServerOption.Suggested(it.key, it.value) } + ServerOption.TypeServerName
    }
}

object ConnectFailures {
    private val sentences = mapOf(
        "WRONG_CREDENTIALS" to "The broker rejected the login or password.",
        "INVALID_ACCOUNT" to "The broker rejected the account number. Check the exact account login from the broker.",
        "ACCOUNT_OR_SERVER_MISMATCH" to "The terminal logged in to a different account or server than the one entered.",
        "NO_CONNECTION" to "The test host could not reach the broker. Check the server name and try again.",
        "SERVER_NOT_FOUND" to "That server name was not found. Enter it exactly as your broker issued it.",
        "TERMINAL_OFFLINE" to "The MetaTrader terminal on the test host is offline.",
        "EA_OFFLINE" to "The EA on the test host is not sending heartbeats.",
        "ALGO_TRADING_DISABLED" to "Algo trading is disabled in the terminal.",
        "WEBREQUEST_NOT_ALLOWED" to "The terminal is blocking the bridge address (WebRequest not allowed).",
        "ACCOUNT_DISABLED" to "The broker reports this account as disabled.",
        "OWNER_BOOTSTRAP_REQUIRED" to "Enter the one-time owner bootstrap code for this first public bridge pairing. It expires after 10 minutes.",
        "MANAGEMENT_SIGN_IN_REQUIRED" to "Sign in to your DestinedFX management account before connecting this managed MT5 broker. Secure account pairing requires that identity.",
        "MANAGEMENT_SERVICE_UNAVAILABLE" to "The DestinedFX management service is not reachable. Broker credentials were not submitted.",
        "EPHEMERAL_HOST_LIVE_BLOCKED" to "LIVE Trade account passwords are blocked on an EPHEMERAL host; choose Investor access.",
        "WAITING_FOR_SPOTWARE_APPROVAL" to "WAITING FOR SPOTWARE APPROVAL",
        "PROVIDER_AUTH_FAILED" to "Provider authorization failed. Check the grant and try again.",
        "ACCOUNT_IDENTITY_MISMATCH" to "Provider account identity did not match the selected authorized account.",
        "PROVIDER_DATA_STALE" to "Provider session or account data is not fresh.",
        "SECURE_STORAGE_UNAVAILABLE" to "Secure device storage is unavailable; provider login is blocked.",
        "CONNECT_TIMEOUT" to "The terminal did not answer in time. Retry.",
        "HOST_CAPACITY_TIMEOUT" to "Your broker login is queued because the isolated MT5 host is at capacity. The request is retained; tap TEST LOGIN & VERIFY again to resume the same account without resubmitting its password.",
        "PAIRING_TERMINAL_ALREADY_ACTIVE" to "This broker profile already has another account provisioning or connected. Revoke it and wait for verified cleanup before switching accounts.",
        "FORM_INCOMPLETE" to "Fill in every field first.",
        "LOGIN_FAILED_UNCLASSIFIED" to "Login failed for a reason the host could not classify.",
        "ACCOUNT_BALANCE_UNAVAILABLE" to "MT5 returned a zero or unavailable balance. The account is DISCONNECTED; no balance is shown.",
    ) + BridgeGatewayFailure.values().associate { it.name to it.userMessage }

    val knownCodes: Set<String> get() = sentences.keys
    fun sentence(code: String): String = sentences[code] ?: sentences.getValue("LOGIN_FAILED_UNCLASSIFIED")
}

enum class ConnectStage(val label: String) {
    SENT("Sent"),
    HOST_QUEUED("Waiting for an isolated MT5 slot"),
    TERMINAL_LOGGING_IN("MT5 terminal logging in"),
    HEARTBEAT("Authenticated terminal heartbeat"),
    VERIFIED("Broker account verified")
}

data class LoginAccountChoice(val id: String, val label: String, val mode: LoginMode)

data class ConnectObservation(
    val stage: ConnectStage?,
    val failureCode: String? = null,
    val accountMode: String? = null,
    val accountChoices: List<LoginAccountChoice>? = null,
    val account: VerifiedAccountDetails? = null,
    val failureMessage: String? = null,
)

data class VerifiedAccountDetails(
    val login: String,
    val server: String,
    val mode: String,
    val currency: String?,
    val balance: Double?,
    val equity: Double?,
    val freeMargin: Double?,
    val marginLevel: Double?,
    val leverage: Int?,
    val retrievedAtMs: Long,
    val positions: List<JSONObject>? = null,
    val pendingOrders: List<JSONObject>? = null,
    val deals: List<JSONObject>? = null,
    val hostProfile: HostProfile? = null,
    val engineState: String? = null,
) {
    fun isFreshAt(nowMs: Long, maxAgeMs: Long = 15_000L): Boolean =
        retrievedAtMs > 0L && retrievedAtMs <= nowMs && nowMs - retrievedAtMs <= maxAgeMs
}

internal fun displayableVerifiedAccountMetric(
    value: Double?,
    details: VerifiedAccountDetails,
    nowMs: Long,
    maxAgeMs: Long = 15_000L
): Double? = value?.takeIf {
    details.isFreshAt(nowMs, maxAgeMs) && it.isFinite() && it >= 0.0
}

internal fun matchesVerifiedAccountIdentity(
    current: VerifiedAccountDetails?,
    refreshed: VerifiedAccountDetails,
): Boolean = current != null &&
    refreshed.login == current.login &&
    refreshed.server == current.server &&
    refreshed.mode == current.mode

/** Everything the host exposes to the use case. The real adapter talks to the verified bridge; tests use a labelled fake. */
interface ConnectBackend {
    suspend fun submit(spec: BrokerLoginSpec, form: LoginForm)
    suspend fun observe(): ConnectObservation
    /** A rejection happens before any credentials are submitted. */
    suspend fun preflight(spec: BrokerLoginSpec, form: LoginForm): String? = null
}

sealed interface ConnectOutcome {
    /** [accountMode] is what the terminal reported, never what the form implied. */
    data class Verified(
        val accountMode: String,
        val modeMismatch: Boolean,
        val account: VerifiedAccountDetails? = null,
    ) : ConnectOutcome
    data class Failed(val code: String, val sentence: String) : ConnectOutcome
    data class SelectAccount(val accounts: List<LoginAccountChoice>) : ConnectOutcome
}

class ConnectSubmissionFailure(val code: String) : Exception(code)

fun interface ProviderLoginVerifier {
    suspend fun verify(spec: BrokerLoginSpec, form: LoginForm): ConnectObservation
}

/** Adapts injected provider transports to the shared phone-login contract. */
class ProviderConnectBackend(private val verifier: ProviderLoginVerifier) : ConnectBackend {
    private var observation: ConnectObservation? = null

    override suspend fun submit(spec: BrokerLoginSpec, form: LoginForm) {
        observation = verifier.verify(spec, form)
    }

    override suspend fun observe(): ConnectObservation = observation ?: ConnectObservation(null)
}

class ConnectUseCase(
    private val backend: ConnectBackend,
    private val pollIntervalMs: Long = 2_000,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun connect(spec: BrokerLoginSpec, form: LoginForm, onStage: (ConnectStage) -> Unit = {}): ConnectOutcome {
        if (!LoginRules.loginEnabled(spec, form)) return failed("FORM_INCOMPLETE")
        try {
            backend.preflight(spec, form)?.let { return failed(it) }
            backend.submit(spec, form)
            onStage(ConnectStage.SENT)
            var reached = ConnectStage.SENT
            val deadline = clock() + timeoutMs
            while (true) {
                val seen = backend.observe()
                seen.failureCode?.let { return failed(it, seen.failureMessage, spec) }
                seen.accountChoices?.takeIf { it.isNotEmpty() }?.let { return ConnectOutcome.SelectAccount(it) }
                seen.stage?.takeIf { it.ordinal > reached.ordinal }?.let { reached = it; onStage(it) }
                if (reached == ConnectStage.VERIFIED) {
                    val actual = seen.accountMode?.uppercase().orEmpty()
                    if (actual != "DEMO" && actual != "LIVE") return failed("LOGIN_FAILED_UNCLASSIFIED", spec = spec)
                    return ConnectOutcome.Verified(
                        actual,
                        modeMismatch = actual != form.mode.name,
                        account = seen.account,
                    )
                }
                if (clock() >= deadline) {
                    return failed(if (reached == ConnectStage.HOST_QUEUED) "HOST_CAPACITY_TIMEOUT" else "CONNECT_TIMEOUT", spec = spec)
                }
                pause(pollIntervalMs)
            }
        } catch (e: BridgeGatewayException) {
            return failed(e.failure.name, spec = spec)
        } catch (e: ConnectSubmissionFailure) {
            return failed(e.code, spec = spec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return failed("LOGIN_FAILED_UNCLASSIFIED", spec = spec)
        }
    }

    private fun failed(
        code: String,
        brokerReason: String? = null,
        spec: BrokerLoginSpec? = null,
    ): ConnectOutcome.Failed {
        val reason = brokerReason?.trim()?.takeIf { it.isNotBlank() && it.length <= 160 }
            ?: ConnectFailures.sentence(code)
        return ConnectOutcome.Failed(code, reason)
    }

    companion object {
        /** Linux/Windows MT5 provisioning and EA authentication may outlast a short phone HTTP timeout. */
        const val DEFAULT_TIMEOUT_MS = 480_000L
    }
}

/** Shared evidence gate used by both the primary Bot workspace and the broker-login adapter. */
internal fun hasVerifiedModeMismatchEvidence(
    requestedMode: String,
    actualMode: String,
    requestedLogin: String,
    actualLogin: String,
    requestedServer: String,
    actualServer: String,
    workerReady: Boolean,
    terminalOnline: Boolean,
    terminalConnected: Boolean,
    heartbeatFresh: Boolean,
    brokerIdentityVerified: Boolean,
): Boolean =
    requestedMode.uppercase() in setOf("DEMO", "LIVE") &&
        actualMode.uppercase() in setOf("DEMO", "LIVE") &&
        requestedMode.uppercase() != actualMode.uppercase() &&
        requestedLogin.isNotBlank() && requestedLogin == actualLogin &&
        requestedServer.isNotBlank() && requestedServer == actualServer &&
        workerReady && terminalOnline && terminalConnected && heartbeatFresh && brokerIdentityVerified
