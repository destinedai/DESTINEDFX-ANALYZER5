package com.example.data.deriv

data class DerivTick(
    val symbol: String,
    val quote: Double,
    val bid: Double?,
    val ask: Double?,
    val epochSeconds: Long
)

data class DerivCandle(
    val epochSeconds: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

enum class DerivConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    AUTH_FAILED,
    ERROR,
    RECONNECTING
}

data class DerivAccountInfo(
    val loginId: String,
    val currency: String,
    val balance: Double,
    val email: String,
    val accountType: String = "demo",
    val demoVerified: Boolean = false,
    val mode: String = accountType.lowercase(),
    val realExecutionAuthorized: Boolean = false
)

/** Broker snapshot used by the execution authority; failure is never an empty portfolio. */
data class DerivPortfolioSnapshot(
    val successful: Boolean,
    val accountId: String,
    val accountMode: String,
    val retrievedAtMs: Long,
    val contracts: List<DerivContractInfo> = emptyList(),
    val error: String? = null,
    val currency: String = ""
) {
    /** Invalid or failed snapshots must never masquerade as zero exposure. */
    val exposure: Double get() = contracts.takeIf { successful && it.all(DerivContractInfo::isExecutionValid) }
        ?.sumOf { it.buyPrice }
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?: Double.NaN
}

enum class DerivReconciliationStatus {
    READY,
    RETRYABLE_FAILURE,
    MANUAL_RECONCILIATION_REQUIRED
}

data class DerivReconciliationResult(
    val status: DerivReconciliationStatus,
    val reason: String
) {
    val ready: Boolean get() = status == DerivReconciliationStatus.READY
}

data class DerivProposalResult(
    val proposalId: String,
    val askPrice: Double,
    val payout: Double,
    val spot: Double
)

data class DerivBuyResult(
    val contractId: Long,
    val transactionId: Long,
    val buyPrice: Double,
    val proposalId: String? = null,
    val requestId: Int? = null
)

data class DerivSellResult(
    val contractId: Long,
    val transactionId: Long,
    val soldFor: Double
)
