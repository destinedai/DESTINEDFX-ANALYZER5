package com.destinedfx.core.risk

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class PortfolioLimits(
    val maxDailyLossPct: Double = 3.0, val maxWeeklyLossPct: Double = 6.0,
    val maxTotalOpenRiskPct: Double = 5.0, val maxOpenTrades: Int = 5,
    val maxCurrencyExposurePct: Double = 3.0,          // net risk-% on any single currency (the correlation guard)
    val resetZone: ZoneId = ZoneId.of("America/New_York"), val resetHour: Int = 17   // FX day rolls 17:00 New York
)

data class OpenRisk(val symbol: String, val isLong: Boolean, val riskPct: Double, val base: String?, val quote: String?)
data class RealizedPnl(val closedAtMs: Long, val pnl: Double)

data class PortfolioVerdict(val allowed: Boolean, val code: String, val detail: String)

/**
 * Correlation is handled structurally: every FX/metal position is decomposed into currency legs
 * (long EUR/USD = +EUR, -USD). Net exposure per currency is capped, so EUR/USD long + GBP/USD long
 * (both short USD) is recognised as double USD-short risk rather than two "independent" trades.
 */
object PortfolioRiskEngine {
    fun tradingDayStart(nowMs: Long, l: PortfolioLimits): Long {
        val z = Instant.ofEpochMilli(nowMs).atZone(l.resetZone)
        var start = z.withHour(l.resetHour).withMinute(0).withSecond(0).withNano(0)
        if (start.isAfter(z)) start = start.minusDays(1)
        return start.toInstant().toEpochMilli()
    }
    fun tradingWeekStart(nowMs: Long, l: PortfolioLimits): Long {
        val dayStart = Instant.ofEpochMilli(tradingDayStart(nowMs, l)).atZone(l.resetZone)
        return dayStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).toInstant().toEpochMilli()
    }

    fun currencyExposure(open: List<OpenRisk>): Map<String, Double> {
        val m = HashMap<String, Double>()
        for (p in open) {
            val b = p.base ?: continue; val q = p.quote ?: continue
            val s = if (p.isLong) 1.0 else -1.0
            m[b] = (m[b] ?: 0.0) + s * p.riskPct
            m[q] = (m[q] ?: 0.0) - s * p.riskPct
        }
        return m
    }

    fun evaluate(startBalance: Double, closed: List<RealizedPnl>, floatingPnl: Double, open: List<OpenRisk>,
                 candidate: OpenRisk, nowMs: Long, l: PortfolioLimits): PortfolioVerdict {
        if (!(startBalance > 0)) return PortfolioVerdict(false, "NO_BALANCE", "Balance unavailable")
        val dayLoss = -(closed.filter { it.closedAtMs >= tradingDayStart(nowMs, l) }.sumOf { it.pnl } + floatingPnl.coerceAtMost(0.0))
        if (dayLoss / startBalance * 100 >= l.maxDailyLossPct)
            return PortfolioVerdict(false, "DAILY_LOSS_LIMIT_REACHED", "Daily loss %.2f%% >= %.2f%%. Trading disabled until next reset."
                .format(dayLoss / startBalance * 100, l.maxDailyLossPct))
        val weekLoss = -closed.filter { it.closedAtMs >= tradingWeekStart(nowMs, l) }.sumOf { it.pnl }
        if (weekLoss / startBalance * 100 >= l.maxWeeklyLossPct)
            return PortfolioVerdict(false, "WEEKLY_LOSS_LIMIT_REACHED", "Weekly loss limit reached")
        if (open.size >= l.maxOpenTrades) return PortfolioVerdict(false, "MAX_TRADES", "${open.size} open trades (max ${l.maxOpenTrades})")
        val total = open.sumOf { it.riskPct } + candidate.riskPct
        if (total > l.maxTotalOpenRiskPct) return PortfolioVerdict(false, "MAX_OPEN_RISK", "Total open risk %.2f%% > %.2f%%".format(total, l.maxTotalOpenRiskPct))
        val after = currencyExposure(open + candidate)
        val worst = after.maxByOrNull { kotlin.math.abs(it.value) }
        if (worst != null && kotlin.math.abs(worst.value) > l.maxCurrencyExposurePct)
            return PortfolioVerdict(false, "CORRELATED_EXPOSURE", "Net %s exposure %.2f%% > %.2f%% (correlated pairs)".format(worst.key, worst.value, l.maxCurrencyExposurePct))
        return PortfolioVerdict(true, "OK", "Portfolio limits OK")
    }
}
