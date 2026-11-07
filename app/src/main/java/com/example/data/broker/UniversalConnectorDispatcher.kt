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
    val rows = listOf(
        ConnectorCapabilityRow(ConnectorKind.DERIV_OPTIONS, "Deriv Options / Multipliers", automatedOnPhone = true, automatedOnOwnerHost = false, manualSignalsAvailable = false, missingSetup = "Owner DEMO App ID, masked DEMO PAT, accepted account and fresh verification"),
        ConnectorCapabilityRow(ConnectorKind.CTRADER_ACCOUNT, "cTrader account", automatedOnPhone = true, automatedOnOwnerHost = false, manualSignalsAvailable = false, missingSetup = "Owner-registered redirect, approved cTrader app, DEMO account and fresh account-matched quote"),
        ConnectorCapabilityRow(ConnectorKind.FBS_MT5, "FBS MT5", automatedOnPhone = false, automatedOnOwnerHost = true, manualSignalsAvailable = true, missingSetup = "Verified owner MT5 host, exact login/server/company/mode and fresh EA heartbeat"),
        ConnectorCapabilityRow(ConnectorKind.EXNESS_MT5, "Exness MT5", automatedOnPhone = false, automatedOnOwnerHost = true, manualSignalsAvailable = true, missingSetup = "Verified owner MT5 host, exact login/server/company/mode and fresh EA heartbeat"),
        ConnectorCapabilityRow(ConnectorKind.HFM_MT5, "HFM MT5", automatedOnPhone = false, automatedOnOwnerHost = true, manualSignalsAvailable = true, missingSetup = "Verified owner MT5 host, exact login/server/company/mode and fresh EA heartbeat"),
        ConnectorCapabilityRow(ConnectorKind.DERIV_MT5, "Deriv MT5", automatedOnPhone = false, automatedOnOwnerHost = true, manualSignalsAvailable = true, missingSetup = "Verified owner MT5 host, exact login/server/company/mode and fresh EA heartbeat"),
    )
}

/**
 * A single dispatcher for Connect and Verify. A connector may return HTTP success or saved
 * credentials, but this dispatcher only returns CONNECTED after its evidence predicate passes.
 */
class UniversalConnectorDispatcher(
    private val connectors: Map<ConnectorKind, suspend () -> ConnectorVerification>,
) {
    suspend fun connectAndVerify(kind: ConnectorKind): Result<ConnectorVerification> {
        val connector = connectors[kind]
            ?: return Result.failure(IllegalStateException("SETUP REQUIRED: no connector configured for ${kind.name}"))
        return runCatching { connector() }.fold(
            onSuccess = { verification ->
                if (verification.kind != kind) Result.failure(IllegalStateException("BLOCKED: connector identity did not match the selected provider"))
                else if (!verification.evidence.permitsConnected(kind)) Result.failure(IllegalStateException("BLOCKED: ${verification.detail}"))
                else Result.success(verification)
            },
            onFailure = { Result.failure(IllegalStateException("CONNECT FAILED: connector result was not verified")) },
        )
    }
}
