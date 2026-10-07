package com.destinedfx.core.analysis

import com.destinedfx.core.marketdata.Candle
import com.destinedfx.core.marketdata.Timeframe

enum class MtfState { FULL_BULLISH, FULL_BEARISH, PARTIAL_BULLISH, PARTIAL_BEARISH, CONFLICT, NEUTRAL, DATA_INCOMPLETE }

data class MtfConfig(val required: List<Timeframe> = listOf(Timeframe.W1, Timeframe.D1, Timeframe.H4), val minCandles: Int = 40)
data class MtfResult(val state: MtfState, val perTf: Map<Timeframe, Trend>, val missing: List<Timeframe>, val aligned: Int, val required: Int) {
    val summary: String get() = if (missing.isNotEmpty()) "MTF DATA INCOMPLETE (missing ${missing.joinToString { it.label }})" else "$aligned/$required aligned"
    val dir: Dir? get() = when (state) {
        MtfState.FULL_BULLISH, MtfState.PARTIAL_BULLISH -> Dir.BULL
        MtfState.FULL_BEARISH, MtfState.PARTIAL_BEARISH -> Dir.BEAR
        MtfState.DATA_INCOMPLETE -> {
            val bull = perTf.values.count { it == Trend.BULLISH }
            val bear = perTf.values.count { it == Trend.BEARISH }
            when {
                bull > bear -> Dir.BULL
                bear > bull -> Dir.BEAR
                else -> null
            }
        }
        else -> null
    }
}

object MtfEngine {
    /** FULL requires EVERY required timeframe to agree. A missing timeframe is DATA_INCOMPLETE - never inferred from the others. */
    fun analyse(data: Map<Timeframe, List<Candle>>, cfg: MtfConfig = MtfConfig(), structureCfg: StructureConfig = StructureConfig()): MtfResult {
        val missing = cfg.required.filter { (data[it]?.size ?: 0) < cfg.minCandles }
        val available = cfg.required.filter { (data[it]?.size ?: 0) >= cfg.minCandles }
        val trends = available.associateWith { 
            StructureEngine.analyze(data.getValue(it), it, structureCfg).trend
        }
        val bull = trends.values.count { it == Trend.BULLISH }
        val bear = trends.values.count { it == Trend.BEARISH }
        val n = cfg.required.size
        if (missing.isNotEmpty()) {
            return MtfResult(MtfState.DATA_INCOMPLETE, trends, missing, maxOf(bull, bear), n)
        }
        val state = when {
            bull == n -> MtfState.FULL_BULLISH
            bear == n -> MtfState.FULL_BEARISH
            bull > 0 && bear > 0 -> MtfState.CONFLICT
            bull > 0 -> MtfState.PARTIAL_BULLISH
            bear > 0 -> MtfState.PARTIAL_BEARISH
            else -> MtfState.NEUTRAL
        }
        return MtfResult(state, trends, emptyList(), maxOf(bull, bear), n)
    }
}
