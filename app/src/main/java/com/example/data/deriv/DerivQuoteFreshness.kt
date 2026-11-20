package com.example.data.deriv

internal const val DERIV_MAX_QUOTE_AGE_MS = 15_000L
internal const val DERIV_MAX_FUTURE_QUOTE_SKEW_MS = 2_000L

internal fun isFreshDerivQuoteTimestamp(timestampMs: Long, nowMs: Long): Boolean {
    if (timestampMs <= 0L) return false
    val ageMs = nowMs - timestampMs
    return ageMs in -DERIV_MAX_FUTURE_QUOTE_SKEW_MS..DERIV_MAX_QUOTE_AGE_MS
}

internal fun maySendDerivPurchase(quoteFresh: Boolean, riskSnapshotFresh: Boolean, accountSessionCurrent: Boolean): Boolean =
    quoteFresh && riskSnapshotFresh && accountSessionCurrent
