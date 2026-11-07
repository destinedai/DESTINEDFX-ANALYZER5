package com.example.data.broker

/** Where an account value came from. This is metadata, not proof of a successful session. */
enum class DataOrigin {
    BROKER_API,
    PUBLIC_MARKET_DATA,
    LOCAL_DEMO_DATA,
    UNAVAILABLE
}

/** Whether the value is safe to present as current account information. */
enum class DataValidity {
    FRESH,
    CACHED,
    STALE,
    INVALID,
    UNAVAILABLE
}

/** Normalized account snapshot with explicit source, identity and retrieval evidence. */
data class BrokerAccountSnapshot(
    val providerId: String,
    val origin: DataOrigin,
    val status: BrokerConnectionStatus,
    val retrievedAtMs: Long,
    val environment: String?,
    val currency: String?,
    val balance: Double?,
    val equity: Double?,
    val margin: Double?,
    val freeMargin: Double?,
    val validity: DataValidity,
    val errorCode: String? = null
) {
    fun isDisplayable(nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = 15_000L): Boolean {
        val values = listOf(balance, equity, margin, freeMargin)
        return origin == DataOrigin.BROKER_API &&
            status == BrokerConnectionStatus.CONNECTED &&
            validity == DataValidity.FRESH &&
            retrievedAtMs > 0L && retrievedAtMs <= nowMs && nowMs - retrievedAtMs <= maxAgeMs &&
            !currency.isNullOrBlank() && values.all { it != null && it.isFinite() && it >= 0.0 }
    }

    companion object {
        fun unavailable(providerId: String, status: BrokerConnectionStatus, errorCode: String? = null) =
            BrokerAccountSnapshot(
                providerId = providerId,
                origin = DataOrigin.UNAVAILABLE,
                status = status,
                retrievedAtMs = 0L,
                environment = null,
                currency = null,
                balance = null,
                equity = null,
                margin = null,
                freeMargin = null,
                validity = DataValidity.UNAVAILABLE,
                errorCode = errorCode
            )
    }
}
