package com.example.domain.model

data class MarketTicker(
    val symbol: String,
    val price: Double,
    val changePercent: Double,
    val high: Double,
    val low: Double,
    val spread: Double,
    val bias: String,
    val confidence: Int,
    val lastUpdateMillis: Long = System.currentTimeMillis(),
    val isLive: Boolean = false,
    val source: String = "",
    /** Real two-sided quote fields; null when the source only provides a last/mid price. */
    val bid: Double? = null,
    val ask: Double? = null,
    /** true only when [spread] was computed as ask - bid from a real two-sided quote. */
    val hasRealSpread: Boolean = false,
    /** STREAM (true bid/ask), POLLED_LAST (REST last price), DAILY_REFERENCE (end-of-day rate, never live). */
    val kind: String = "POLLED_LAST"
)
