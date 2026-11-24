package com.example.ui.bot

import com.example.data.broker.BrokerLoginSpec
import com.example.data.broker.HostProfile
import com.example.data.broker.LoginMode
import com.example.data.management.ManagementException
import com.example.data.management.ManagementFailure
import com.example.data.trade.MT5ManagedAccountGateway
import com.example.data.trade.MT5ManagedAccountLogin
import com.example.data.trade.MT5RemoteBridgeClient
import org.json.JSONObject

/**
 * Adapter over the actual managed bridge. The owner bootstrap code is passed only to the first
 * pairing request; it is never stored with the broker password or retained for observation.
 */
class MT5BridgeConnectBackend(
    private val client: MT5ManagedAccountGateway,
    private val managementHealthCheck: suspend () -> Unit = {},
    private val wakeHost: (suspend ((ConnectStage) -> Unit) -> Unit)? = null,
) : ConnectBackend {
    private var requestedForm: LoginForm? = null
    private var stageListener: (ConnectStage) -> Unit = {}

    override suspend fun preflight(spec: BrokerLoginSpec, form: LoginForm, onStage: (ConnectStage) -> Unit): String? {
        stageListener = onStage
        onStage(ConnectStage.WAKING_ENGINE)
        try {
            wakeHost?.invoke(onStage)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: ConnectSubmissionFailure) {
            return failure.code
        } catch (_: Exception) {
            return "HOST_WAKE_FAILED"
        }
        try {
            managementHealthCheck()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return "MANAGEMENT_SERVICE_UNAVAILABLE"
        }
        onStage(ConnectStage.ENGINE_READY)
        if (form.mode == LoginMode.LIVE && form.type == AccessType.TRADE) {
            // /v1/app/state requires an app bearer and cannot be called before a new profile is paired.
            // /health is deliberately public and reports only the host's configured profile.
            val hostProfile = client.hostProfile()
            if (hostProfile != HostProfile.PERSISTENT) return "EPHEMERAL_HOST_LIVE_BLOCKED"
        }
        return null
    }

    override suspend fun submit(spec: BrokerLoginSpec, form: LoginForm) {
        // Keep only non-secret identity fields for later heartbeat comparison.
        requestedForm = form.copy(password = "", ownerBootstrapCode = "")
        try {
            // A phone may stop waiting while a one-slot Codespace is still provisioning. Reuse
            // the exact queued/active request instead of posting the password a second time.
            stageListener(ConnectStage.PAIRING)
            if (client.hasPairing()) {
                val current = client.state()
                val managed = current.raw.optJSONObject("managed_execution")
                val activeWorkerStates = setOf(
                    "QUEUED", "AWAITING_ATTESTATION", "LEASED", "PROVISIONING", "AUTHENTICATING", "READY"
                )
                if (current.workerStatus.uppercase() in activeWorkerStates) {
                    val sameAccount = managed?.optString("broker_brand").equals(spec.id.removeSuffix("_MT5"), true) &&
                        managed?.optString("account_mode").equals(form.mode.name, true) &&
                        managed?.optString("access_type").equals(form.type.wireValue, true) &&
                        managed?.optString("requested_login") == form.login.trim() &&
                        managed?.optString("requested_server") == form.server.trim()
                    if (sameAccount) {
                        (client as? MT5RemoteBridgeClient)?.touchHostActivity()
                        return
                    }
                    throw ConnectSubmissionFailure("PAIRING_TERMINAL_ALREADY_ACTIVE")
                }
            }
            client.registerManagedAccount(
                MT5ManagedAccountLogin(
                    brokerBrand = spec.id.removeSuffix("_MT5"),
                    accountMode = form.mode.name,
                    login = form.login.trim(),
                    password = form.password,
                    server = form.server.trim(),
                    accessType = form.type.wireValue,
                    savePasswordLocally = form.savePassword,
                    ownerBootstrapCode = form.ownerBootstrapCode.trim(),
                )
            )
            (client as? MT5RemoteBridgeClient)?.touchHostActivity()
        } catch (e: MT5RemoteBridgeClient.BridgeHttpException) {
            if (e.safeDescription in ConnectFailures.knownCodes) throw ConnectSubmissionFailure(e.safeDescription)
            throw e
        } catch (e: ManagementException) {
            val code = when (e.failure) {
                ManagementFailure.UNCONFIGURED,
                ManagementFailure.AUTH_EXPIRED,
                ManagementFailure.INVALID_CREDENTIALS -> "MANAGEMENT_SIGN_IN_REQUIRED"
                else -> "MANAGEMENT_SERVICE_UNAVAILABLE"
            }
            throw ConnectSubmissionFailure(code)
        } catch (e: IllegalArgumentException) {
            mapConfigurationFailure(e.message)?.let { throw ConnectSubmissionFailure(it) }
            throw e
        } catch (e: IllegalStateException) {
            mapConfigurationFailure(e.message)?.let { throw ConnectSubmissionFailure(it) }
            throw e
        }
    }

    private fun mapConfigurationFailure(message: String?): String? = when {
        message.orEmpty().contains("MANAGEMENT_SIGN_IN_REQUIRED") -> "MANAGEMENT_SIGN_IN_REQUIRED"
        message.orEmpty().contains("OWNER_BOOTSTRAP_REQUIRED") -> "OWNER_BOOTSTRAP_REQUIRED"
        else -> null
    }

    override suspend fun observe(): ConnectObservation {
        val s = client.state()
        val managed: JSONObject? = s.raw.optJSONObject("managed_execution")
        val code = managed?.takeIf { !it.isNull("error_code") }?.optString("error_code").orEmpty().takeIf { it.isNotBlank() }
        val form = requestedForm
        val modeMismatchEvidence = form?.let {
            hasVerifiedModeMismatchEvidence(
                requestedMode = it.mode.name,
                actualMode = s.actualAccountMode,
                requestedLogin = it.login.trim(),
                actualLogin = s.actualLogin,
                requestedServer = it.server.trim(),
                actualServer = s.actualServer,
                workerReady = s.workerStatus == "READY",
                terminalOnline = s.terminalOnline,
                terminalConnected = s.terminalConnected,
                heartbeatFresh = s.heartbeatFresh,
                brokerIdentityVerified = s.brokerIdentityVerified,
            )
        } == true
        val balanceUnavailable = s.managedState == "READY" &&
            (s.balance == null || s.balance <= 0.0 || s.equity == null || s.margin == null || s.freeMargin == null)
        val stage = when {
            s.managedState == "READY" && s.connected && s.accountVerified && s.brokerIdentityVerified && s.heartbeatFresh -> ConnectStage.VERIFIED
            modeMismatchEvidence -> ConnectStage.VERIFIED
            s.workerStatus in setOf("QUEUED", "AWAITING_ATTESTATION", "LEASED") -> ConnectStage.HOST_QUEUED
            s.workerStatus in setOf("PROVISIONING", "AUTHENTICATING", "READY") -> ConnectStage.TERMINAL_LOGGING_IN
            s.terminalOnline -> ConnectStage.HEARTBEAT
            else -> null
        }
        val verified = stage == ConnectStage.VERIFIED
        val heartbeatAgeMs = s.raw.optLong("heartbeat_age_seconds", 0L).coerceAtLeast(0L) * 1_000L
        val retrievedAtMs = System.currentTimeMillis() - heartbeatAgeMs
        val account = if (verified) VerifiedAccountDetails(
            login = s.actualLogin,
            server = s.actualServer,
            mode = s.actualAccountMode,
            currency = s.currency.takeIf { it.isNotBlank() },
            balance = s.balance,
            equity = s.equity,
            freeMargin = s.freeMargin,
            marginLevel = s.marginLevel,
            leverage = s.leverage,
            retrievedAtMs = retrievedAtMs,
            positions = s.positions.takeIf { s.snapshotsValid },
            pendingOrders = s.activeOrders.takeIf { s.snapshotsValid },
            deals = s.closedDeals.takeIf { s.snapshotsValid },
            hostProfile = s.hostProfile,
            engineState = s.state,
        ) else null
        return ConnectObservation(
            stage = stage,
            failureCode = code.takeUnless { modeMismatchEvidence }
                ?: if (balanceUnavailable) "ACCOUNT_BALANCE_UNAVAILABLE" else null,
            accountMode = s.actualAccountMode.takeIf { verified },
            failureMessage = managed?.optString("error_message")?.takeIf { !it.isNullOrBlank() && code != null },
            account = account,
        )
    }
}
