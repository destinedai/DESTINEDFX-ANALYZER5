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
    override suspend fun preflight(spec: BrokerLoginSpec, form: LoginForm): String? {
        if (form.mode == LoginMode.LIVE && form.type == AccessType.TRADE) {
            val hostProfile = client.state().hostProfile
            if (hostProfile != HostProfile.PERSISTENT) return "EPHEMERAL_HOST_LIVE_BLOCKED"
        }
        return null
    }

    override suspend fun submit(spec: BrokerLoginSpec, form: LoginForm) {
        client.registerManagedAccount(
            login = form.login.trim(), password = form.password, server = form.server.trim(),
            accessType = form.type.wireValue, consent = true, savePasswordLocally = form.savePassword,
        )
    }

    override suspend fun observe(): ConnectObservation {
        val s = client.state()
        val managed: JSONObject? = s.raw.optJSONObject("managed_execution")
        val code = managed?.takeIf { !it.isNull("error_code") }?.optString("error_code").orEmpty().takeIf { it.isNotBlank() }
        val stage = when {
            s.managedState == "READY" && s.accountVerified && s.brokerIdentityVerified && s.heartbeatFresh -> ConnectStage.VERIFIED
            s.terminalOnline -> ConnectStage.HEARTBEAT
            s.workerStatus in setOf("PROVISIONING", "AUTHENTICATING", "READY") -> ConnectStage.TERMINAL_LOGGING_IN
            else -> null
        }
        return ConnectObservation(stage, code, s.actualAccountMode.takeIf { stage == ConnectStage.VERIFIED })
    }
}
