package com.example.data.deriv

import com.example.domain.risk.TradingSafetyLimits

/** Pure calculation for a persisted per-account, per-mode daily loss baseline. */
data class DerivDailyRiskSnapshot(
    val startingEquity: Double,
    val currentEquity: Double,
    val lossPercent: Double,
    val limitUsedPercent: Double,
    val status: Status
) {
    enum class Status { NORMAL, WARNING_80, WARNING_90, LOCKED, DATA_UNAVAILABLE }
}

object DerivDailyRiskMonitor {
    /** A missing daily baseline may only be initialized in the first two minutes of a UTC day. */
    fun canInitializeDailyBaseline(nowMs: Long, graceMs: Long = 120_000L): Boolean {
        if (nowMs <= 0L || graceMs !in 1L..300_000L) return false
        val elapsed = nowMs % 86_400_000L
        return elapsed in 0L..graceMs
    }

    /**
     * The caller supplies verified balance plus broker-reported floating contract P/L,
     * and persists the first authenticated equity observation of the UTC day.
     */
    fun evaluate(
        startingEquity: Double?,
        currentEquity: Double?,
        maximumDailyLossPercent: Double = TradingSafetyLimits.MAX_DAILY_LOSS_PERCENT,
        alreadyLocked: Boolean = false
    ): DerivDailyRiskSnapshot {
        val start = startingEquity ?: Double.NaN
        val current = currentEquity ?: Double.NaN
        if (!start.isFinite() || start <= 0.0 || !current.isFinite() ||
            !maximumDailyLossPercent.isFinite() || maximumDailyLossPercent <= 0.0
        ) {
            return DerivDailyRiskSnapshot(
                start, current, Double.NaN, Double.NaN,
                DerivDailyRiskSnapshot.Status.DATA_UNAVAILABLE
            )
        }
        val lossPercent = ((start - current).coerceAtLeast(0.0) / start) * 100.0
        val usedPercent = lossPercent / maximumDailyLossPercent * 100.0
        val status = when {
            alreadyLocked || usedPercent >= 100.0 -> DerivDailyRiskSnapshot.Status.LOCKED
            usedPercent >= TradingSafetyLimits.DAILY_WARNING_90_PERCENT -> DerivDailyRiskSnapshot.Status.WARNING_90
            usedPercent >= TradingSafetyLimits.DAILY_WARNING_80_PERCENT -> DerivDailyRiskSnapshot.Status.WARNING_80
            else -> DerivDailyRiskSnapshot.Status.NORMAL
        }
        return DerivDailyRiskSnapshot(start, current, lossPercent, usedPercent, status)
    }
}
