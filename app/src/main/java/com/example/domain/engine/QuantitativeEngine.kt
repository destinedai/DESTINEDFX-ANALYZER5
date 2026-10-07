package com.example.domain.engine

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.instrument.SpecRegistry
import com.destinedfx.core.risk.AccountState
import com.destinedfx.core.risk.RiskEngine
import com.destinedfx.core.risk.SizingResult

/**
 * Thin compatibility facade. There is NO hardcoded tick table any more: the broker symbol spec comes from [SpecRegistry]
 * (filled from the broker gateway) and all math is in the single authoritative [RiskEngine].
 */
object QuantitativeEngine {
    /** Returns lots, or 0.0 when sizing is impossible (no spec, risk budget below min volume, invalid input). Never rounds up to min lot. */
    fun calculateDynamicLotSize(accountBalance: Double, entryPrice: Double, stopLoss: Double, riskPercent: Double, symbol: String,
                                spec: InstrumentSpec? = SpecRegistry.get(symbol)): Double =
        (sizing(accountBalance, entryPrice, stopLoss, riskPercent, spec) as? SizingResult.Sized)?.lots ?: 0.0

    fun sizing(accountBalance: Double, entryPrice: Double, stopLoss: Double, riskPercent: Double, spec: InstrumentSpec?): SizingResult {
        if (spec == null) return SizingResult.Rejected("Broker symbol specification unavailable")
        return RiskEngine.positionSize(AccountState(accountBalance, accountBalance, null, null, "ACC"), riskPercent, entryPrice, stopLoss, spec)
    }

    fun checkMarginSafety(accountBalance: Double, requiredMarginForLot: Double): Boolean =
        accountBalance > requiredMarginForLot && requiredMarginForLot >= 0.0
}
