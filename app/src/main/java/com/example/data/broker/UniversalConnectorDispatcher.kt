package com.example.data.broker

/** The provider choices shown by the unified Connect and Verify action. */
enum class ConnectorKind {
    DERIV_OPTIONS,
    CTRADER_ACCOUNT,
    FBS_MT5,
    EXNESS_MT5,
    HFM_MT5,
    DERIV_MT5,
}

private fun BrokerLoginSpec.connectorKind(): ConnectorKind = when (id) {
    "DERIV_OPTIONS" -> ConnectorKind.DERIV_OPTIONS
    "DERIV_CTRADER", "CTRADER" -> ConnectorKind.CTRADER_ACCOUNT
    "FBS_MT5" -> ConnectorKind.FBS_MT5
    "EXNESS_MT5" -> ConnectorKind.EXNESS_MT5
    "HFM_MT5" -> ConnectorKind.HFM_MT5
    "DERIV_MT5" -> ConnectorKind.DERIV_MT5
    else -> error("Unknown BrokerLoginSpec id: $id")
}

data class ConnectorEvidence(
    val authenticatedSession: Boolean,
    val freshAccountData: Boolean,
    val accountIdentityMatched: Boolean,
    val freshQuote: Boolean = false,
    val terminalIdentityMatched: Boolean = false,
    val exactLogin: Boolean = false,
    val exactServer: Boolean = false,
    val exactCompany: Boolean = false,
    val exactAccountMode: Boolean = false,
) {
    fun permitsConnected(kind: ConnectorKind): Boolean = when (kind) {
        ConnectorKind.DERIV_OPTIONS -> authenticatedSession && freshAccountData && accountIdentityMatched
        ConnectorKind.CTRADER_ACCOUNT -> authenticatedSession && freshAccountData && accountIdentityMatched && freshQuote
        ConnectorKind.FBS_MT5, ConnectorKind.EXNESS_MT5, ConnectorKind.HFM_MT5, ConnectorKind.DERIV_MT5 ->
            authenticatedSession && freshAccountData && terminalIdentityMatched && exactLogin && exactServer && exactCompany && exactAccountMode && freshQuote
    }
}

data class ConnectorVerification(
    val kind: ConnectorKind,
    val evidence: ConnectorEvidence,
    val detail: String,
    val identity: ModeAccountIdentity? = null,
    val readOnlyVerification: ModeReadOnlyVerification? = null,
    val demoAcceptance: ModeDemoAcceptance? = null,
    val currentDemoCredentialGeneration: String = "",
    val hostProfile: HostProfile = HostProfile.PERSISTENT
)

data class ConnectorCapabilityRow(
    val kind: ConnectorKind,
    val label: String,
    val automatedOnPhone: Boolean,
    val automatedOnOwnerHost: Boolean,
    val manualSignalsAvailable: Boolean,
    val missingSetup: String,
)

object UniversalConnectorCapabilities {
    val rows: List<ConnectorCapabilityRow> = ConnectorKind.values().map { kind ->
        val specId = when (kind) {
            ConnectorKind.DERIV_OPTIONS -> "DERIV_OPTIONS"
            ConnectorKind.CTRADER_ACCOUNT -> "DERIV_CTRADER"
            ConnectorKind.FBS_MT5 -> "FBS_MT5"
            ConnectorKind.EXNESS_MT5 -> "EXNESS_MT5"
            ConnectorKind.HFM_MT5 -> "HFM_MT5"
            ConnectorKind.DERIV_MT5 -> "DERIV_MT5"
        }
        val spec = requireNotNull(BrokerLoginSpecs.byId(specId))
        ConnectorCapabilityRow(
            kind = kind,
            label = spec.displayName,
            automatedOnPhone = spec.connector != LoginConnector.MT5_TERMINAL,
            automatedOnOwnerHost = spec.connector == LoginConnector.MT5_TERMINAL,
            manualSignalsAvailable = spec.connector == LoginConnector.MT5_TERMINAL,
            missingSetup = spec.capabilityRow,
        )
    }
}

/**
 * A single dispatcher for Connect and Verify. A connector may return HTTP success or saved
 * credentials, but this dispatcher only returns CONNECTED after its evidence predicate passes.
 */
class UniversalConnectorDispatcher(
    private val connectors: Map<ConnectorKind, suspend () -> ConnectorVerification>,
    private val modeArmingPolicy: ModeArmingPolicy
) {
    suspend fun connectAndVerify(kind: ConnectorKind): Result<ConnectorVerification> {
        val connector = connectors[kind]
            ?: return Result.failure(IllegalStateException("SETUP REQUIRED: no connector configured for ${kind.name}"))
        val result = runCatching { connector() }
            .getOrElse { return Result.failure(IllegalStateException("CONNECT FAILED: provider verification did not complete")) }
        return validateVerification(kind, result)
    }

    /** Shared validation entry used by each provider UI after its fresh read-only exchange. */
    fun validateVerification(kind: ConnectorKind, verification: ConnectorVerification): Result<ConnectorVerification> {
        if (verification.kind != kind) return Result.failure(IllegalStateException("BLOCKED: connector identity did not match the selected provider"))
        if (!verification.evidence.permitsConnected(kind)) {
            return Result.failure(IllegalStateException("BLOCKED: ${verification.detail}"))
        }
        val identity = verification.identity
            ?: return Result.failure(IllegalStateException("BLOCKED: exact account identity is missing"))
        val readOnly = verification.readOnlyVerification
            ?: return Result.failure(IllegalStateException("BLOCKED: read-only verification evidence is missing"))
        if (readOnly.identity != identity) return Result.failure(IllegalStateException("BLOCKED: verification evidence belongs to another account identity"))
        val now = System.currentTimeMillis()
        if (readOnly.checkedAtMs <= 0L || readOnly.checkedAtMs > now || now - readOnly.checkedAtMs > 15_000L) {
            return Result.failure(IllegalStateException("BLOCKED: read-only verification evidence is stale"))
        }
        if (!readOnly.authenticatedSession || !readOnly.freshAccountData ||
            !(readOnly.freshQuote || readOnly.freshHeartbeat) || !readOnly.accountIdentityMatched) {
            return Result.failure(IllegalStateException("BLOCKED: fresh matching read-only evidence is incomplete"))
        }
        val expectedPath = when (kind) {
            ConnectorKind.DERIV_OPTIONS -> ModePath.DERIV_OPTIONS
            ConnectorKind.CTRADER_ACCOUNT -> ModePath.CTRADER_ACCOUNT
            ConnectorKind.FBS_MT5 -> ModePath.FBS_MT5
            ConnectorKind.EXNESS_MT5 -> ModePath.EXNESS_MT5
            ConnectorKind.HFM_MT5 -> ModePath.HFM_MT5
            ConnectorKind.DERIV_MT5 -> ModePath.DERIV_MT5
        }
        if (identity.path != expectedPath) return Result.failure(IllegalStateException("BLOCKED: connector returned another path identity"))
        val expectedBroker = when (kind) {
            ConnectorKind.FBS_MT5 -> "FBS"
            ConnectorKind.EXNESS_MT5 -> "EXNESS"
            ConnectorKind.HFM_MT5 -> "HFM"
            ConnectorKind.DERIV_MT5 -> "DERIV"
            else -> null
        }
        if (!identity.isValid() || (expectedBroker != null && !identity.broker.equals(expectedBroker, true))) {
            return Result.failure(IllegalStateException("BLOCKED: broker or account identity does not match the selected connector"))
        }
        if (verification.hostProfile != HostProfile.PERSISTENT && identity.mode == AccountMode.LIVE) {
            return Result.failure(IllegalStateException("MODE_ARMING_BLOCKED: EPHEMERAL_HOST_LIVE_BLOCKED"))
        }
        val decision = if (identity.mode == AccountMode.DEMO) {
            modeArmingPolicy.demoDecision(identity, readOnly, verification.demoAcceptance,
                verification.currentDemoCredentialGeneration)
        } else modeArmingPolicy.liveDecision(identity, hostProfile = verification.hostProfile)
        return if (decision.allowed) Result.success(verification)
        else Result.failure(IllegalStateException("MODE_ARMING_BLOCKED: " + decision.reason))
    }
}
