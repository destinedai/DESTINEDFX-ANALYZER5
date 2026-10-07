package com.destinedfx.core.marketdata

/** Freshness is defined per source TYPE: a streamed feed must be seconds old, a polled REST feed is allowed 2 poll intervals. */
data class HealthConfig(val liveMaxAgeMs: Long = 5_000, val delayedMaxAgeMs: Long = 30_000, val maxSpreadToMid: Double = 0.05) {
    companion object { fun polled(intervalMs: Long) = HealthConfig(liveMaxAgeMs = intervalMs * 2, delayedMaxAgeMs = intervalMs * 6) }
}

/** Single authority for "may this data be used". Signals and trading require LIVE. */
object MarketDataHealthEvaluator {
    fun evaluate(quote: MarketQuote?, nowMs: Long, connected: Boolean, sourceError: Boolean = false,
                 sequenceGap: Boolean = false, cfg: HealthConfig = HealthConfig()): MarketDataHealth = when {
        sourceError -> MarketDataHealth.SOURCE_ERROR
        !connected || quote == null -> MarketDataHealth.DISCONNECTED
        !quote.isStructurallyValid -> MarketDataHealth.INVALID
        quote.spread != null && quote.mid != null && quote.spread!! / quote.mid!! > cfg.maxSpreadToMid -> MarketDataHealth.INVALID
        quote.receivedAtMs > nowMs + 2_000 -> MarketDataHealth.INVALID
        sequenceGap -> MarketDataHealth.GAP_DETECTED
        quote.ageMs(nowMs) <= cfg.liveMaxAgeMs -> MarketDataHealth.LIVE
        quote.ageMs(nowMs) <= cfg.delayedMaxAgeMs -> MarketDataHealth.DELAYED
        else -> MarketDataHealth.STALE
    }
    fun isUsable(h: MarketDataHealth) = h == MarketDataHealth.LIVE
    fun displayText(h: MarketDataHealth, hasData: Boolean) = if (!hasData && h == MarketDataHealth.DISCONNECTED) "WAITING FOR LIVE DATA" else h.name
}

/** What the UI shows. The UI formats this; it never computes it. */
data class MarketSnapshot(val symbol: String, val quote: MarketQuote?, val health: MarketDataHealth, val ageMs: Long?,
                          val analysisSource: String, val executionSource: String?) {
    val statusText: String get() = MarketDataHealthEvaluator.displayText(health, quote != null)
    val canAnalyse: Boolean get() = MarketDataHealthEvaluator.isUsable(health)
}
