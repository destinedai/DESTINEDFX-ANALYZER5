package com.destinedfx.core.trade

import com.destinedfx.core.analysis.TradePlan
import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.marketdata.MarketQuote
import com.destinedfx.core.marketdata.PriceConflictGuard
import com.destinedfx.core.risk.RiskEngine

object PostBrokerChecks {
    fun evaluate(
        plan: TradePlan,
        spec: InstrumentSpec,
        minRR: Double,
        quote: MarketQuote?,
        brokerQuote: MarketQuote?,
        atr: Double?,
        nowMs: Long
    ): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        
        // 1. Stop Distance Check
        val slDist = kotlin.math.abs(plan.entry - plan.stopLoss)
        if (slDist < spec.minStopDistance) {
            results.add(
                CheckResult(
                    name = "BROKER STOP DISTANCE",
                    status = CheckStatus.FAIL,
                    detail = "Stop loss distance ($slDist) less than broker minimum stop distance (${spec.minStopDistance})"
                )
            )
        }
        
        // 2. Reward-to-Risk Check
        val rr = RiskEngine.rewardToRisk(plan.entry, plan.stopLoss, plan.primary.price, plan.isLong) ?: 0.0
        val requiredRR = RiskEngine.requiredMinimumRewardToRisk(minRR)
        if (requiredRR == null || rr < requiredRR) {
            results.add(
                CheckResult(
                    name = "BROKER RR",
                    status = CheckStatus.FAIL,
                    detail = if (requiredRR == null) "Configured minimum reward-to-risk is invalid" else
                        "Reward-to-risk ($rr) less than minimum required ($requiredRR; 2.0R safety floor)"
                )
            )
        }
        
        // 3. Price Conflict Check
        if (quote != null && brokerQuote != null) {
            val pc = PriceConflictGuard.check(quote, brokerQuote, atr, nowMs = nowMs)
            if (pc.conflict) {
                results.add(
                    CheckResult(
                        name = "BROKER PRICE CONFLICT",
                        status = CheckStatus.FAIL,
                        detail = pc.message
                    )
                )
            }
        }
        
        return results
    }
}
