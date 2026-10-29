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
