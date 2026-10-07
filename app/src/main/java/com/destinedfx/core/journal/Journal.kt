package com.destinedfx.core.journal

import com.destinedfx.core.analysis.*

data class JournalEntry(
    val id: String, val symbol: String, val timeframe: String, val direction: String, val entry: Double?, val sl: Double?, val tp: Double?,
    val riskPct: Double?, val confluence: Int, val mtfState: String, val structure: String, val liquidity: String, val volume: String, val momentum: String,
    val session: String, val newsState: String, val spread: Double?, val strategyVersion: String, val rulesetVersion: String, val modelVersion: String,
    val dataSource: String?, val timestampMs: Long, val result: String? = null, val resultR: Double? = null, val mae: Double? = null, val mfe: Double? = null
) {
    companion object {
        fun from(id: String, r: SignalReport, session: String, newsState: String, spread: Double?, riskPct: Double?) = JournalEntry(
            id, r.symbol, r.timeframe.label, r.state.name, r.plan?.entry, r.plan?.stopLoss, r.plan?.primary?.price, riskPct, r.confluenceScore,
            r.mtf?.state?.name ?: "N/A", r.components.firstOrNull { it.name == "Structure" }?.notes?.joinToString() ?: "", r.components.firstOrNull { it.name == "Liquidity" }?.notes?.joinToString() ?: "",
            r.components.firstOrNull { it.name == "Volume" }?.notes?.joinToString() ?: "", r.components.firstOrNull { it.name == "Momentum" }?.notes?.joinToString() ?: "",
            session, newsState, spread, r.strategyVersion, r.rulesetVersion, r.modelVersion, r.dataSource, r.timestampMs)
    }
}

/** A statistic is only ever shown together with its sample size, market, timeframe and strategy version. Never invented. */
data class VerifiedStat(val winRatePct: Double, val sampleSize: Int, val market: String, val timeframe: String, val strategyVersion: String, val from: Long, val to: Long) {
    val display get() = "Historical Win Rate: %.1f%%  Sample Size: %,d trades  (%s %s, strategy %s)".format(winRatePct, sampleSize, market, timeframe, strategyVersion)
}
object JournalStats {
    fun winRate(entries: List<JournalEntry>, market: String, tf: String, version: String, minSample: Int = 30): VerifiedStat? {
        val done = entries.filter { it.symbol == market && it.timeframe == tf && it.strategyVersion == version && it.resultR != null }
        if (done.size < minSample) return null            // too few closed trades: show nothing rather than a misleading number
        return VerifiedStat(done.count { it.resultR!! > 0 } * 100.0 / done.size, done.size, market, tf, version, done.minOf { it.timestampMs }, done.maxOf { it.timestampMs })
    }
}
