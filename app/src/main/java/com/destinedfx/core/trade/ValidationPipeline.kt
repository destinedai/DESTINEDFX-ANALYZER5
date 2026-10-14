package com.destinedfx.core.trade

import com.destinedfx.core.analysis.*
import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.instrument.AssetClassifier
import com.destinedfx.core.marketdata.AssetClass
import com.destinedfx.core.marketdata.MarketDataHealth
import com.destinedfx.core.marketdata.MarketDataHealthEvaluator
import com.destinedfx.core.marketdata.MarketQuote
import com.destinedfx.core.marketdata.PriceConflictGuard
import com.destinedfx.core.news.NewsStatus
import com.destinedfx.core.news.NewsVerdict
import com.destinedfx.core.risk.*
import com.destinedfx.core.session.Killzone
import com.destinedfx.core.session.SessionState

enum class CheckStatus { PASS, FAIL }
data class CheckResult(val name: String, val status: CheckStatus, val detail: String)

data class TradingConfig(
    val riskPercent: Double = 1.0, val maxSpreadPips: Double? = null, val maxSpreadAtrFraction: Double = 0.15, val minRR: Double = 2.0,
    val allowedSessions: Set<com.destinedfx.core.session.Session>? = null, val requireKillzone: Boolean = false,
    val manualRiskMode: Boolean = false, val minConfluence: Int = 65, val commissionPerLot: Double = 0.0, val portfolio: PortfolioLimits = PortfolioLimits()
)

data class ValidationContext(
    val report: SignalReport, val health: MarketDataHealth, val quote: MarketQuote?, val news: NewsVerdict?, val session: SessionState,
    val spec: InstrumentSpec?, val account: AccountState?, val brokerConnected: Boolean, val requiredMargin: Double?, val startOfDayBalance: Double,
    val closedPnl: List<RealizedPnl>, val floatingPnl: Double, val openRisk: List<OpenRisk>, val nowMs: Long, val cfg: TradingConfig,
    val duplicateOrder: Boolean = false,
    /** false = user-specified manual trade: analysis gates (MTF/structure/liquidity/volume/momentum/signal) are reported as not applied. All safety gates still run. */
    val analysisGatesRequired: Boolean = true,
    val planOverride: TradePlan? = null, val atr: Double? = null, val brokerQuote: MarketQuote? = null
)

data class TradeDecision(val approved: Boolean, val checks: List<CheckResult>, val sizing: SizingResult.Sized?, val plan: TradePlan?, val signalState: SignalState) {
    val failures: List<CheckResult> get() = checks.filter { it.status == CheckStatus.FAIL }
    val explanation: String get() = if (approved) "ALL CHECKS PASSED" else "NO TRADE: " + failures.joinToString("; ") { "${it.name} - ${it.detail}" }
}

/**
 * The ONLY path to execution. Every gate runs (so the user sees ALL reasons) and any failure => NO TRADE.
 * AI output is not an input here; the deterministic engines have final authority.
 */
object TradeValidationPipeline {
    fun validate(x: ValidationContext): TradeDecision {
        val r = ArrayList<CheckResult>()
        fun check(name: String, ok: Boolean, pass: String, fail: String) { r += CheckResult(name, if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) pass else fail) }
        val rep = x.report; val plan = x.planOverride ?: rep.plan; val q = x.quote; val spec = x.spec
        val gates = x.analysisGatesRequired
        fun gate(name: String, ok: Boolean, pass: String, fail: String) { if (gates) check(name, ok, pass, fail) else r += CheckResult(name, CheckStatus.PASS, "Manual trade: analysis gate not applied") }

        check("LIVE DATA", q != null && x.health == MarketDataHealth.LIVE, "Feed LIVE (${q?.source})", "Data health ${x.health}${if (q == null) " - WAITING FOR LIVE DATA" else ""}")
        check("DATA QUALITY", q?.isStructurallyValid == true && (!gates || rep.state != SignalState.DATA_UNAVAILABLE), "Quote/candles valid", rep.blockers.plus(rep.reasons).firstOrNull() ?: "Quote or candles invalid")
        val spread = q?.spread
        val spreadLimit = spec?.let { s -> x.cfg.maxSpreadPips?.let { p -> s.pipSize?.times(p) } } ?: (x.atr ?: rep.structure?.atr)?.times(x.cfg.maxSpreadAtrFraction)
        check("SPREAD", spread != null && spreadLimit != null && spread <= spreadLimit, "Spread %.5f <= %.5f".format(spread ?: 0.0, spreadLimit ?: 0.0),
            if (spread == null) "Spread unknown (source gives no bid/ask)" else "SIGNAL BLOCKED: spread %.5f > limit %.5f".format(spread, spreadLimit ?: 0.0))
        
        val pc = PriceConflictGuard.check(q, x.brokerQuote, x.atr ?: rep.structure?.atr, nowMs = x.nowMs)
        check("PRICE CONFLICT", !pc.conflict, pc.message, pc.message)

        check("NEWS", x.news != null && !x.news.blocksAutomation(x.cfg.manualRiskMode), x.news?.detail ?: "", x.news?.detail ?: "NEWS STATUS UNKNOWN")
        val sessOk = (x.cfg.allowedSessions == null || x.session.active.any { it in x.cfg.allowedSessions }) && (!x.cfg.requireKillzone || x.session.killzones.isNotEmpty())
        check("SESSION", sessOk, "Session ${x.session.label}", "Outside allowed session (${x.session.label})")
        gate("MTF", rep.mtfComplete && rep.mtf?.state in setOf(MtfState.FULL_BULLISH, MtfState.FULL_BEARISH) && rep.mtf?.dir == rep.dir, rep.mtf?.summary ?: "", "MTF DATA INCOMPLETE")
        gate("STRUCTURE", (rep.components.firstOrNull { it.name == "Structure" }?.earned ?: 0.0) >= 8.0, "Structure supports ${rep.dir}", "Structure does not support a trade")
        gate("LIQUIDITY", (rep.components.firstOrNull { it.name == "Liquidity" }?.earned ?: 0.0) >= 5.0, "Liquidity context present", "No liquidity target/sweep context")
        val vol = rep.components.firstOrNull { it.name == "Volume" }
        gate("VOLUME", vol == null || !vol.isAvailable || vol.earned >= 3.0, if (vol?.isAvailable == true) "Volume confirms" else "Volume unavailable (not required)", "Volume does not confirm")
        gate("MOMENTUM", (rep.components.firstOrNull { it.name == "Momentum" }?.earned ?: 0.0) >= 2.0, "Momentum confirms", "Momentum does not confirm")
        gate("SIGNAL", rep.state in setOf(SignalState.STRONG_BUY, SignalState.BUY, SignalState.STRONG_SELL, SignalState.SELL) && rep.confluenceScore >= x.cfg.minConfluence,
            "${rep.state} ${rep.confluenceScore}/100", "State ${rep.state}, score ${rep.confluenceScore} (need >= ${x.cfg.minConfluence})")
        check("SL", plan != null && plan.stopLoss > 0 && (spec == null || kotlin.math.abs(plan.entry - plan.stopLoss) >= spec.minStopDistance), "SL structural, %.1f ATR".format(plan?.slAtr ?: 0.0), "No valid stop loss")
        check("TP", plan != null && plan.targets.isNotEmpty(), "Targets: ${plan?.targets?.joinToString { it.label }}", "No structural target")
        check("RR", plan != null && plan.primary.rr >= x.cfg.minRR, "RR 1:%.2f".format(plan?.primary?.rr ?: 0.0), "RR below ${x.cfg.minRR}")

        var sized: SizingResult.Sized? = null
        if (plan != null && spec != null && x.account != null) when (val s = RiskEngine.positionSize(x.account, x.cfg.riskPercent, plan.entry, plan.stopLoss, spec, x.cfg.commissionPerLot)) {
            is SizingResult.Sized -> { sized = s; r += CheckResult("POSITION SIZE", CheckStatus.PASS, "%.2f lots, risk %.2f".format(s.lots, s.moneyRiskActual)) }
            is SizingResult.Rejected -> r += CheckResult("POSITION SIZE", CheckStatus.FAIL, s.reason)
        } else r += CheckResult("POSITION SIZE", CheckStatus.FAIL, "Missing ${if (spec == null) "broker spec" else if (x.account == null) "account state" else "plan"}")

        val cand = OpenRisk(rep.symbol, plan?.isLong ?: true, x.cfg.riskPercent, spec?.baseCurrency ?: rep.symbol.take(3), spec?.profitCurrency ?: rep.symbol.takeLast(3))
        val pv = PortfolioRiskEngine.evaluate(x.startOfDayBalance, x.closedPnl, x.floatingPnl, x.openRisk, cand, x.nowMs, x.cfg.portfolio)
        val daily = pv.code.startsWith("DAILY") || pv.code.startsWith("WEEKLY") || pv.code == "NO_BALANCE"
        val lossCodes = setOf("DAILY_LOSS_LIMIT_REACHED", "WEEKLY_LOSS_LIMIT_REACHED", "NO_BALANCE")
        r += CheckResult("DAILY RISK", if (pv.code in lossCodes) CheckStatus.FAIL else CheckStatus.PASS, if (pv.code in lossCodes) pv.detail else "Loss limits OK")
        r += CheckResult("PORTFOLIO RISK", if (pv.code == "MAX_TRADES" || pv.code == "MAX_OPEN_RISK") CheckStatus.FAIL else CheckStatus.PASS, if (pv.code == "MAX_TRADES" || pv.code == "MAX_OPEN_RISK") pv.detail else "Open risk/trade count OK")
        r += CheckResult("CORRELATION", if (pv.code == "CORRELATED_EXPOSURE") CheckStatus.FAIL else CheckStatus.PASS, if (pv.code == "CORRELATED_EXPOSURE") pv.detail else "Currency exposure within limit")
        
        // A3: Weekend exposure limit - FOREX, METALS, INDICES only
        val aClass = AssetClassifier.of(rep.symbol)
        if (aClass == AssetClass.FOREX || aClass == AssetClass.METALS || aClass == AssetClass.INDICES) {
            val nyZone = java.time.ZoneId.of("America/New_York")
            val nyTime = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(x.nowMs), nyZone)
            val day = nyTime.dayOfWeek
            val time = nyTime.toLocalTime()
            val isWeekend = when (day) {
                java.time.DayOfWeek.FRIDAY -> !time.isBefore(java.time.LocalTime.of(16, 0))
                java.time.DayOfWeek.SATURDAY -> true
                java.time.DayOfWeek.SUNDAY -> time.isBefore(java.time.LocalTime.of(17, 0))
                else -> false
            }
            if (isWeekend) {
                r += CheckResult("WEEKEND", CheckStatus.FAIL, "MARKET CLOSING/CLOSED (weekend)")
            }
        }

        val m = if (x.account != null) RiskEngine.marginCheck(x.account, x.requiredMargin) else false to "No account"
        check("BROKER", x.brokerConnected && m.first && !x.duplicateOrder && spec != null,
            "Broker connected, margin OK, no duplicate", listOfNotNull(if (!x.brokerConnected) "BROKER DISCONNECTED" else null, if (!m.first) m.second else null,
                if (x.duplicateOrder) "Duplicate order" else null, if (spec == null) "Broker spec unavailable" else null).joinToString("; "))
        return TradeDecision(r.none { it.status == CheckStatus.FAIL }, r, sized, plan, rep.state)
    }
}
