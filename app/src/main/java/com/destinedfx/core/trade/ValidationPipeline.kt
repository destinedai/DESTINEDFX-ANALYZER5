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

private data class StrategyEvidenceRule(
    val required: List<Pair<Set<String>, Double>>,
    val requireVolume: Boolean = false,
    val requiresStrictMtf: Boolean = false
)

private object StrategyEvidenceRules {
    private fun one(name: String, threshold: Double) = setOf(name) to threshold
    private val rules = mapOf(
        "smc-confluence-1.0.0" to StrategyEvidenceRule(
            listOf(one("MTF", 8.0), one("Structure", 8.0), one("Liquidity", 5.0), one("Order Block / FVG", 4.0)),
            requiresStrictMtf = true
        ),
        "ict-silver-bullet" to StrategyEvidenceRule(
            listOf(
                one("Killzone Timing", 25.0),
                one("Liquidity Sweep", 25.0),
                one("FVG Displacement", 15.0),
                one("Structure Shift", 10.0),
                one("Target Draw", 10.0)
            )
        ),
        "wyckoff-schematics" to StrategyEvidenceRule(
            listOf(
                setOf("Spring Test (Phase C)", "UTAD Test (Phase C)") to 30.0,
                one("Volume Absorption", 10.0),
                one("Trading Range Phase", 14.0),
                setOf("Sign of Strength (SOS)", "Sign of Weakness (SOW)") to 10.0,
                one("Opposite Boundary Target", 10.0)
            )
        ),
        "supply-and-demand" to StrategyEvidenceRule(
            listOf(
                one("Zone Freshness", 20.0),
                one("Departure Imbalance", 18.0),
                one("Base Structure Quality", 12.0),
                one("HTF Flow Alignment", 10.0),
                one("Path to Opposing Zone", 5.0)
            )
        ),
        "breakout-momentum" to StrategyEvidenceRule(
            listOf(
                one("Donchian Breakout", 30.0),
                one("ATR Volatility Expansion", 18.0),
                one("Volume Surge", 8.0),
                one("Momentum Velocity", 8.0),
                one("Runway Extension", 10.0)
            ),
            requireVolume = true
        ),
        "mean-reversion" to StrategyEvidenceRule(
            listOf(
                one("Bollinger Band 2.0σ Extreme", 18.0),
                one("RSI Exhaustion", 16.0),
                one("Reversal Candlestick", 20.0),
                one("Consolidation Regime", 12.0),
                one("Reversion R:R to Mean", 10.0)
            )
        ),
        "trend-following" to StrategyEvidenceRule(
            listOf(
                one("Multi-EMA Trend Stack", 20.0),
                one("Value Zone Pullback", 15.0),
                one("Trend Continuation Signal", 20.0),
                one("Higher Timeframe Trend", 10.0),
                one("Runway to Previous Swing", 5.0)
            )
        ),
        "liquidity-stop-hunts" to StrategyEvidenceRule(
            listOf(
                one("Liquidity Sweep Confirmation", 30.0),
                one("Equal Highs / Lows (EQH/EQL) Pool", 25.0),
                one("Wick Raid Rejection", 20.0),
                one("HTF Flow Alignment", 10.0),
                one("Tight Invalidation Runway", 5.0)
            )
        )
    )

    fun forStrategy(id: String): StrategyEvidenceRule? = rules[id]
}

/** Shared final-boundary evidence checks; internal so behavioral tests exercise the same rules. */
internal fun strategyEvidenceChecks(report: SignalReport): List<CheckResult> {
    val rule = StrategyEvidenceRules.forStrategy(report.strategyVersion)
        ?: return listOf(CheckResult("STRATEGY EVIDENCE", CheckStatus.FAIL,
            "Unknown strategy '${report.strategyVersion}'; no final validation rules exist"))
    val results = mutableListOf<CheckResult>()
    rule.required.forEach { (names, threshold) ->
        val component = report.components.firstOrNull { it.name in names }
        val valid = component != null &&
            component.available.isFinite() && component.available > 0.0 &&
            component.earned.isFinite() && component.earned >= threshold &&
            component.earned <= component.available
        val label = names.joinToString(" / ")
        results += CheckResult(
            "EVIDENCE:$label",
            if (valid) CheckStatus.PASS else CheckStatus.FAIL,
            if (valid) "$label evidence ${component?.earned}" else "$label evidence missing, invalid, or below required threshold $threshold"
        )
    }
    if (rule.requireVolume) {
        val volume = report.components.firstOrNull { it.name == "Volume Surge" }
        val type = report.volumeKind
        val sourceBacked = type in setOf(VolumeType.REAL, VolumeType.TICK) &&
            volume != null && volume.available.isFinite() && volume.available > 0.0 &&
            volume.earned.isFinite() && volume.earned >= 8.0 && volume.earned <= volume.available &&
            volume.notes.any { it.contains("source=$type") }
        results += CheckResult(
            "VOLUME SOURCE",
            if (sourceBacked) CheckStatus.PASS else CheckStatus.FAIL,
            if (sourceBacked) "Breakout volume is source-backed ($type)" else "Breakout requires at least 8 points of source-backed REAL or TICK volume evidence"
        )
    }
    val directionConsistent = when (report.state) {
        SignalState.STRONG_BUY, SignalState.BUY -> report.dir == Dir.BULL
        SignalState.STRONG_SELL, SignalState.SELL -> report.dir == Dir.BEAR
        else -> report.dir != null
    }
    results += CheckResult(
        "STRATEGY DIRECTION",
        if (directionConsistent) CheckStatus.PASS else CheckStatus.FAIL,
        if (directionConsistent) "Signal state and direction agree" else "Signal state is missing or contradicts the report direction"
    )
    return results
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
        // Full MTF completeness is an SMC-specific execution gate. Other strategies
        // must satisfy the explicit higher-timeframe evidence in their own score components.
        val evidenceRule = StrategyEvidenceRules.forStrategy(rep.strategyVersion)
        val requiresStrictMtf = evidenceRule?.requiresStrictMtf == true
        gate(
            "MTF",
            !requiresStrictMtf || (
                rep.mtfComplete &&
                    rep.mtf?.state in setOf(MtfState.FULL_BULLISH, MtfState.FULL_BEARISH) &&
                    rep.mtf?.dir == rep.dir
                ),
            rep.mtf?.summary ?: "No separate MTF state",
            if (requiresStrictMtf) "SMC requires complete, direction-aligned MTF evidence" else "This strategy uses its own HTF/evidence rules"
        )
        strategyEvidenceChecks(rep).forEach { evidence ->
            gate(evidence.name, evidence.status == CheckStatus.PASS, evidence.detail, evidence.detail)
        }
        gate("SIGNAL", rep.state in setOf(SignalState.STRONG_BUY, SignalState.BUY, SignalState.STRONG_SELL, SignalState.SELL) && rep.confluenceScore >= x.cfg.minConfluence,
            "${rep.state} ${rep.confluenceScore}/100", "State ${rep.state}, score ${rep.confluenceScore} (need >= ${x.cfg.minConfluence})")
        check("DIRECTION / PLAN", !gates || (plan != null && rep.dir != null && plan.isLong == (rep.dir == Dir.BULL)),
            "Trade plan direction matches the strategy signal",
            "Trade plan direction is missing or does not match the strategy signal")
        check("SL", plan != null && plan.stopLoss > 0 && (spec == null || kotlin.math.abs(plan.entry - plan.stopLoss) >= spec.minStopDistance), "SL structural, %.1f ATR".format(plan?.slAtr ?: 0.0), "No valid stop loss")
        check("TP", plan != null && plan.targets.isNotEmpty(), "Targets: ${plan?.targets?.joinToString { it.label }}", "No structural target")
        val requiredRR = RiskEngine.requiredMinimumRewardToRisk(x.cfg.minRR)
        check("RR", plan != null && requiredRR != null && plan.primary.rr.isFinite() && plan.primary.rr >= requiredRR,
            "RR 1:%.2f".format(plan?.primary?.rr ?: 0.0),
            if (requiredRR == null) "Configured minimum reward-to-risk is invalid" else "RR below required minimum $requiredRR (2.0R safety floor)")

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
