package com.example.ui.bot

import com.example.data.broker.BrokerLoginSpec
import com.example.data.trade.MT5RemoteBridgeClient
import com.example.data.broker.LoginMode
import com.example.data.broker.HostProfile
import org.json.JSONObject

/**
 * Real adapter over the verified bridge. It sends the credentials once through registerManagedAccount
 * and then only reads the bridge's own state; nothing here decides that an account is connected.
 */
class MT5BridgeConnectBackend(private val client: MT5RemoteBridgeClient) : ConnectBackend {
    private var requestedForm: LoginForm? = null

    override suspend fun preflight(spec: BrokerLoginSpec, form: LoginForm): String? {
        if (form.mode == LoginMode.LIVE && form.type == AccessType.TRADE) {
            val hostProfile = client.state().hostProfile
            if (hostProfile != HostProfile.PERSISTENT) return "EPHEMERAL_HOST_LIVE_BLOCKED"
        }
        return null
    }

    override suspend fun submit(spec: BrokerLoginSpec, form: LoginForm) {
        requestedForm = form
        client.registerManagedAccount(
            login = form.login.trim(), password = form.password, server = form.server.trim(),
            accessType = form.type.wireValue, consent = true, savePasswordLocally = form.savePassword,
        )
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
        val stage = when {
            s.managedState == "READY" && s.accountVerified && s.brokerIdentityVerified && s.heartbeatFresh -> ConnectStage.VERIFIED
            modeMismatchEvidence -> ConnectStage.VERIFIED
            s.terminalOnline -> ConnectStage.HEARTBEAT
            s.workerStatus in setOf("PROVISIONING", "AUTHENTICATING", "READY") -> ConnectStage.TERMINAL_LOGGING_IN
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
            failureCode = code.takeUnless { modeMismatchEvidence },
            accountMode = s.actualAccountMode.takeIf { verified },
            account = account,
        )
    }
}

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
