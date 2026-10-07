package com.destinedfx.analyzer

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.instrument.SpecRegistry
import com.destinedfx.core.risk.AccountState
import com.destinedfx.core.risk.RiskEngine
import com.destinedfx.core.risk.SizingResult
import kotlin.math.abs

/**
 * Compatibility wrapper so existing screens keep compiling. It performs NO math of its own: sizing is delegated to the central
 * [RiskEngine] using the broker spec. Without a spec the result is explicitly UNAVAILABLE (0 lots), never a guessed pip value.
 */
class DestinedFXRiskEngine {
    companion object {
        @JvmStatic
        fun analyzeTradeParameters(
            totalInvestment: Double, riskPercentage: Double, entryPoint: Double, stopLoss: Double, takeProfit: Double,
            isGold: Boolean = false, symbol: String = "", spec: InstrumentSpec? = if (symbol.isNotBlank()) SpecRegistry.get(symbol) else null
        ): RiskAnalysisResult {
            val r = RiskAnalysisResult(); r.accountBalance = totalInvestment
            val slD = abs(entryPoint - stopLoss); val tpD = abs(takeProfit - entryPoint)
            val isLong = takeProfit > entryPoint
            if (spec == null) { r.executionAdvice = "SIZING UNAVAILABLE: broker symbol specification (tick size/value, volume limits) not loaded."; return r }
            r.stopLossPips = spec.priceToPips(slD) ?: spec.priceToPoints(slD)
            r.takeProfitPips = spec.priceToPips(tpD) ?: spec.priceToPoints(tpD)
            r.riskRewardRatio = RiskEngine.rewardToRisk(entryPoint, stopLoss, takeProfit, isLong) ?: 0.0
            when (val s = RiskEngine.positionSize(AccountState(totalInvestment, totalInvestment, null, null, "ACC"), riskPercentage, entryPoint, stopLoss, spec)) {
                is SizingResult.Sized -> { r.calculatedLotSize = s.lots; r.cashAmountAtRisk = s.moneyRiskActual
                    r.projectedProfitAmount = tpD / spec.tickSize * spec.tickValue * s.lots
                    r.projectedProfitPercent = if (totalInvestment > 0) r.projectedProfitAmount / totalInvestment * 100 else 0.0
                    r.executionAdvice = if (r.riskRewardRatio < 2.0) "Risk/Reward below 2.0 - not recommended." else "Sizing OK (rules-based; not a guarantee of outcome)." }
                is SizingResult.Rejected -> r.executionAdvice = "NOT SIZED: ${s.reason}"
            }
            return r
        }
    }

    class RiskAnalysisResult {
        var accountBalance = 0.0; var cashAmountAtRisk = 0.0; var calculatedLotSize = 0.0
        var stopLossPips = 0.0; var takeProfitPips = 0.0; var riskRewardRatio = 0.0
        var projectedProfitAmount = 0.0; var projectedProfitPercent = 0.0
        var nearestProfitZone = ""; var entryReason = ""; var stopLossReason = ""; var takeProfitReason = ""; var executionAdvice = ""
        override fun toString() = "BALANCE: %.2f | RISK: %.2f | SL: %.1f | TP: %.1f | RR 1:%.2f | LOTS: %.2f | %s".format(
            accountBalance, cashAmountAtRisk, stopLossPips, takeProfitPips, riskRewardRatio, calculatedLotSize, executionAdvice)
    }
}
