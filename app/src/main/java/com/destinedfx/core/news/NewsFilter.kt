package com.destinedfx.core.news

enum class NewsImpact { LOW, MEDIUM, HIGH }
enum class NewsStatus { CLEAR, HIGH_IMPACT_WINDOW, UNKNOWN }

/** Only provider-verified events. Nothing is ever generated from calendar rules (NFP "first Friday", etc). */
data class EconomicEvent(val name: String, val country: String, val currency: String, val impact: NewsImpact, val timeMs: Long,
                         val actual: String?, val forecast: String?, val previous: String?, val source: String, val retrievedAtMs: Long)

/** Result of a calendar fetch. Failure is explicit; it is never converted to an empty list. */
sealed interface CalendarResult {
    data class Ok(val events: List<EconomicEvent>, val retrievedAtMs: Long, val coverageFromMs: Long, val coverageToMs: Long) : CalendarResult
    data class Failed(val reason: String) : CalendarResult
}

data class NewsVerdict(val status: NewsStatus, val detail: String, val event: EconomicEvent? = null) {
    /** UNKNOWN blocks automated execution; only an explicit, labelled manual-risk mode may override it. */
    fun blocksAutomation(manualRiskMode: Boolean) = status == NewsStatus.HIGH_IMPACT_WINDOW || (status == NewsStatus.UNKNOWN && !manualRiskMode)
}

object NewsFilter {
    fun evaluate(result: CalendarResult?, relevantCurrencies: Set<String>, nowMs: Long,
                 beforeMs: Long = 30 * 60_000L, afterMs: Long = 15 * 60_000L, maxDataAgeMs: Long = 6 * 3_600_000L): NewsVerdict {
        if (result == null) return NewsVerdict(NewsStatus.UNKNOWN, "NEWS STATUS UNKNOWN: calendar not loaded")
        if (result is CalendarResult.Failed) return NewsVerdict(NewsStatus.UNKNOWN, "NEWS STATUS UNKNOWN: ${result.reason}")
        result as CalendarResult.Ok
        if (nowMs - result.retrievedAtMs > maxDataAgeMs) return NewsVerdict(NewsStatus.UNKNOWN, "NEWS STATUS UNKNOWN: calendar data is older than ${maxDataAgeMs / 3_300_000}h")
        if (nowMs < result.coverageFromMs || nowMs + beforeMs > result.coverageToMs) return NewsVerdict(NewsStatus.UNKNOWN, "NEWS STATUS UNKNOWN: calendar does not cover the current window")
        val hit = result.events.filter { it.impact == NewsImpact.HIGH && it.currency.uppercase() in relevantCurrencies }
            .firstOrNull { nowMs >= it.timeMs - beforeMs && nowMs <= it.timeMs + afterMs }
        return if (hit != null) NewsVerdict(NewsStatus.HIGH_IMPACT_WINDOW, "High-impact ${hit.currency} event: ${hit.name}", hit)
        else NewsVerdict(NewsStatus.CLEAR, "No high-impact events for ${relevantCurrencies.joinToString()} in window (source: ${result.events.firstOrNull()?.source ?: "provider"})")
    }
}
