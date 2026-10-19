package com.destinedfx.core.marketdata
import kotlin.math.abs
data class PriceConflict(val conflict: Boolean, val diff: Double?, val allowed: Double?, val message: String)
object PriceConflictGuard {
    /** Compares the analysis feed with the broker (execution) quote. Missing broker price = conflict (cannot verify). */
    fun check(analysis: MarketQuote?, broker: MarketQuote?, atr: Double?, maxSpreadMult: Double = 3.0, maxAtrFraction: Double = 0.10, maxAgeMs: Long = 5_000, nowMs: Long): PriceConflict {
        val aMid = analysis?.mid ?: return PriceConflict(true, null, null, "PRICE CONFLICT: no analysis price")
        val bMid = broker?.mid ?: return PriceConflict(true, null, null, "PRICE CONFLICT: broker price unavailable, cannot verify")
        if (broker.ageMs(nowMs) > maxAgeMs) return PriceConflict(true, null, null, "PRICE CONFLICT: broker price is ${broker.ageMs(nowMs)} ms old")
        val diff = abs(aMid - bMid)
        val allowed = maxOf((broker.spread ?: 0.0) * maxSpreadMult, (atr ?: 0.0) * maxAtrFraction)
        if (allowed <= 0.0) return PriceConflict(true, diff, allowed, "PRICE CONFLICT: no tolerance can be computed (spread/ATR unknown)")
        return if (diff > allowed) PriceConflict(true, diff, allowed, "PRICE CONFLICT: analysis %.5f vs broker %.5f (diff %.5f > %.5f)".format(aMid, bMid, diff, allowed))
        else PriceConflict(false, diff, allowed, "Prices agree (diff %.5f <= %.5f)".format(diff, allowed))
    }
}
