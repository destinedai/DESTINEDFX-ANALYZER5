package com.example.ui.bot

import com.example.data.broker.BrokerLoginSpec
import com.example.data.broker.BrokerLoginSpecs
import com.example.data.broker.LoginConnector
import com.example.data.broker.LoginField
import com.example.data.broker.LoginMode
import com.example.data.broker.HostProfile
import com.example.data.trade.BridgeGatewayException
import com.example.data.trade.BridgeGatewayFailure
import kotlinx.coroutines.delay

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
    val login: String = "",
    val password: String = "",
    val server: String = "",
    val appId: String = "",
    val token: String = "",
    val savePassword: Boolean = false,
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
    /** Owner history, host-reported servers and official-page entries. Blank entries are skipped; nothing is guessed. */
    fun build(spec: BrokerLoginSpec, ownerHistory: List<String>, hostReported: List<String>): List<ServerOption> {
        val suggestions = LinkedHashMap<String, String>()
        ownerHistory.filter { it.isNotBlank() }.forEach { suggestions.putIfAbsent(it.trim(), "Your history") }
        hostReported.filter { it.isNotBlank() }.forEach { suggestions.putIfAbsent(it.trim(), "Reported by host") }
        spec.serverSuggestions.filter { it.value.isNotBlank() }.forEach { suggestions.putIfAbsent(it.value.trim(), "Official page") }
        return suggestions.map { ServerOption.Suggested(it.key, it.value) } + ServerOption.TypeServerName
    }
}

object ConnectFailures {
    private val sentences = mapOf(
        "WRONG_CREDENTIALS" to "The broker rejected the login or password.",
        "ACCOUNT_OR_SERVER_MISMATCH" to "The terminal logged in to a different account or server than the one entered.",
        "NO_CONNECTION" to "The test host could not reach the broker. Check the server name and try again.",
        "SERVER_NOT_FOUND" to "That server name was not found. Enter it exactly as your broker issued it.",
        "TERMINAL_OFFLINE" to "The MetaTrader terminal on the test host is offline.",
        "EA_OFFLINE" to "The EA on the test host is not sending heartbeats.",
        "ALGO_TRADING_DISABLED" to "Algo trading is disabled in the terminal.",
        "WEBREQUEST_NOT_ALLOWED" to "The terminal is blocking the bridge address (WebRequest not allowed).",
        "ACCOUNT_DISABLED" to "The broker reports this account as disabled.",
        "EPHEMERAL_HOST_LIVE_BLOCKED" to "LIVE Trade account passwords are blocked on an EPHEMERAL host; choose Investor access.",
        "CONNECT_TIMEOUT" to "The terminal did not answer in time. Retry.",
        "FORM_INCOMPLETE" to "Fill in every field first.",
        "LOGIN_FAILED_UNCLASSIFIED" to "Login failed for a reason the host could not classify.",
    ) + BridgeGatewayFailure.values().associate { it.name to it.userMessage }

    val knownCodes: Set<String> get() = sentences.keys
    fun sentence(code: String): String = sentences[code] ?: sentences.getValue("LOGIN_FAILED_UNCLASSIFIED")
}

enum class ConnectStage(val label: String) {
    SENT("Sent"), TERMINAL_LOGGING_IN("Terminal logging in"), HEARTBEAT("Heartbeat"), VERIFIED("Verified")
}

data class ConnectObservation(val stage: ConnectStage?, val failureCode: String? = null, val accountMode: String? = null)

/** Everything the host exposes to the use case. The real adapter talks to the verified bridge; tests use a labelled fake. */
interface ConnectBackend {
    suspend fun submit(spec: BrokerLoginSpec, form: LoginForm)
    suspend fun observe(): ConnectObservation
    /** A rejection happens before any credentials are submitted. */
    suspend fun preflight(spec: BrokerLoginSpec, form: LoginForm): String? = null
}

sealed interface ConnectOutcome {
    /** [accountMode] is what the terminal reported, never what the form implied. */
    data class Verified(val accountMode: String, val modeMismatch: Boolean) : ConnectOutcome
    data class Failed(val code: String, val sentence: String) : ConnectOutcome
}

class ConnectUseCase(
    private val backend: ConnectBackend,
    private val pollIntervalMs: Long = 2_000,
    private val timeoutMs: Long = 120_000,
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
                seen.failureCode?.let { return failed(it) }
                seen.stage?.takeIf { it.ordinal > reached.ordinal }?.let { reached = it; onStage(it) }
                if (reached == ConnectStage.VERIFIED) {
                    val actual = seen.accountMode?.uppercase().orEmpty()
                    if (actual != "DEMO" && actual != "LIVE") return failed("LOGIN_FAILED_UNCLASSIFIED")
                    return ConnectOutcome.Verified(actual, modeMismatch = actual != form.mode.name)
                }
                if (clock() >= deadline) return failed("CONNECT_TIMEOUT")
                pause(pollIntervalMs)
            }
        } catch (e: BridgeGatewayException) {
            return failed(e.failure.name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return failed("LOGIN_FAILED_UNCLASSIFIED")
        }
    }

    private fun failed(code: String) = ConnectOutcome.Failed(code, ConnectFailures.sentence(code))
}
