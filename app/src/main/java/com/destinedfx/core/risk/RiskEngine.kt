package com.destinedfx.core.risk

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.instrument.VolumeMath
import kotlin.math.abs

/**
 * THE one position-size / money-risk implementation. UI, backtester, validation pipeline and execution all call this.
 * Position size = money risk / loss per lot at SL, where loss per lot = (SL distance / tickSize) * tickValue (+ costs).
 */
sealed interface SizingResult {
    data class Sized(
        val lots: Double, val moneyRiskTarget: Double, val moneyRiskActual: Double,
        val lossPerLotAtSl: Double, val slDistance: Double, val slTicks: Double
    ) : SizingResult
    data class Rejected(val reason: String) : SizingResult
}

data class AccountState(val balance: Double, val equity: Double, val freeMargin: Double?, val leverage: Int?, val currency: String)

object RiskEngine {
    /** Risk is taken on min(balance, equity): a drawn-down account must not size off an inflated balance. */
    fun riskBase(a: AccountState) = minOf(a.balance, a.equity)

    fun positionSize(
        account: AccountState, riskPercent: Double, entry: Double, stopLoss: Double, spec: InstrumentSpec,
        commissionPerLotRoundTurn: Double = 0.0, estimatedSlippagePerLot: Double = 0.0
    ): SizingResult {
        if (!entry.isFinite() || !stopLoss.isFinite() || entry <= 0.0 || stopLoss <= 0.0) return SizingResult.Rejected("Invalid entry/SL price")
        if (!(riskPercent > 0.0 && riskPercent <= 100.0)) return SizingResult.Rejected("Invalid risk percent")
        val base = riskBase(account)
        if (!(base > 0.0 && base.isFinite())) return SizingResult.Rejected("Account balance/equity unavailable or non-positive")
        spec.validationErrors().takeIf { it.isNotEmpty() }?.let { return SizingResult.Rejected("Broker spec invalid: ${it.joinToString()}") }

        val slDistance = abs(entry - stopLoss)
        if (slDistance < spec.tickSize) return SizingResult.Rejected("SL distance smaller than one tick")
        if (slDistance < spec.minStopDistance) return SizingResult.Rejected("SL closer than broker minimum stop distance")

        val slTicks = slDistance / spec.tickSize
        val lossPerLot = slTicks * spec.tickValue + commissionPerLotRoundTurn + estimatedSlippagePerLot
        val target = base * riskPercent / 100.0
        val raw = target / lossPerLot
        val stepped = VolumeMath.floorToStep(raw, spec.volumeStep).coerceAtMost(spec.volumeMax)
        if (stepped < spec.volumeMin - 1e-12)
            return SizingResult.Rejected("Risk budget %.2f buys %.4f lots, below broker minimum %.4f. Not rounding up (would exceed risk)."
                .format(target, raw, spec.volumeMin))
        val lots = VolumeMath.normalise(stepped, spec.volumeStep)
        return SizingResult.Sized(lots, target, lots * lossPerLot, lossPerLot, slDistance, slTicks)
    }

    /** Margin must come from the broker (order-calc). Leverage is only a labelled estimate used when the broker value is absent. */
    fun marginCheck(account: AccountState, requiredMargin: Double?, safetyBuffer: Double = 1.10): Pair<Boolean, String> {
        val free = account.freeMargin ?: return false to "Free margin unavailable"
        val req = requiredMargin ?: return false to "Required margin unavailable from broker"
        return if (free >= req * safetyBuffer) true to "Margin OK (free %.2f, required %.2f)".format(free, req)
        else false to "INSUFFICIENT MARGIN (free %.2f < required %.2f x%.2f)".format(free, req, safetyBuffer)
    }

    fun rewardToRisk(entry: Double, sl: Double, tp: Double, isLong: Boolean): Double? {
        val risk = if (isLong) entry - sl else sl - entry
        val reward = if (isLong) tp - entry else entry - tp
        return if (risk > 0 && reward > 0) reward / risk else null
    }
}
