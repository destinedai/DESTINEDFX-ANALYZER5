package com.example.domain.usecase.impl

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.destinedfx.core.marketdata.Candle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Analysis-only result returned by the packaged Python rules. It can never authorize execution. */
data class PythonAnalysisResult(
    val state: String,
    val direction: String,
    val symbol: String,
    val strategyId: String,
    val strategyVersion: String,
    val timeframe: String,
    val candleTimestampMs: Long,
    val source: String,
    val evidence: List<String>,
    val rejectionReasons: List<String>,
    val reason: String,
    val executable: Boolean
) {
    fun displayLine(): String = if (state == "CANDIDATE") {
        "ON-DEVICE PYTHON (analysis only): $direction candidate; rules $strategyVersion; evidence: ${evidence.joinToString().ifBlank { "none" }}"
    } else {
        "ON-DEVICE PYTHON: $state — ${reason.ifBlank { rejectionReasons.joinToString().ifBlank { "No rule accepted this snapshot" } }}"
    }
}

interface PythonAnalyzer {
    suspend fun analyze(symbol: String, timeframe: String, strategyId: String, source: String,
                        nowMs: Long, intervalMs: Long, allowSessionGaps: Boolean,
                        candles: List<Candle>): PythonAnalysisResult
}

class ChaquopyPythonAnalyzer(context: Context) : PythonAnalyzer {
    private val appContext = context.applicationContext

    override suspend fun analyze(
        symbol: String, timeframe: String, strategyId: String, source: String,
        nowMs: Long, intervalMs: Long, allowSessionGaps: Boolean, candles: List<Candle>
    ): PythonAnalysisResult = withTimeout(5_000L) {
        withContext(Dispatchers.IO) {
            require(symbol.isNotBlank() && timeframe.isNotBlank() && strategyId.isNotBlank() && source.isNotBlank())
            require(nowMs > 0 && intervalMs > 0 && candles.isNotEmpty())
            val payload = JSONObject().apply {
                put("symbol", symbol); put("timeframe", timeframe); put("strategy_id", strategyId)
                put("source", source); put("now_ms", nowMs); put("provider_retrieved_at_ms", nowMs)
                put("expected_interval_ms", intervalMs); put("max_candle_age_ms", intervalMs * 2 + 60_000L)
                put("allow_session_gaps", allowSessionGaps)
                put("candles", JSONArray().apply {
                    candles.forEach { c -> put(JSONObject().apply {
                        put("timestamp_ms", c.timeMs); put("open", c.open); put("high", c.high)
                        put("low", c.low); put("close", c.close); put("volume", c.volume)
                        put("complete", c.complete); put("source", source); put("retrieved_at_ms", nowMs)
                    }) }
                })
            }.toString()
            val py = synchronized(Python::class.java) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
                Python.getInstance()
            }
            val raw = py.getModule("quantitative").callAttr("analyze_json", payload).toString()
            parseAndValidate(raw, symbol, timeframe, strategyId, source, candles.last().timeMs)
        }
    }

    private fun parseAndValidate(raw: String, symbol: String, timeframe: String, strategyId: String,
                                 source: String, candleTimestampMs: Long): PythonAnalysisResult {
        val o = JSONObject(raw)
        val responseSymbol = o.optString("symbol", symbol)
        val responseTimeframe = o.optString("timeframe", timeframe)
        val responseStrategy = o.optString("strategy_id", strategyId)
        val responseSource = o.optString("source", source)
        val responseTimestamp = o.optLong("candle_timestamp_ms", candleTimestampMs)
        require(responseSymbol == symbol && responseTimeframe == timeframe && responseStrategy == strategyId && responseSource == source)
        require(responseTimestamp == candleTimestampMs)
        val state = o.getString("state").uppercase(Locale.ROOT)
        val direction = o.optString("direction", "WAIT").uppercase(Locale.ROOT)
        val executable = o.optBoolean("executable", false)
        require(state in setOf("CANDIDATE", "WAITING", "DATA_UNAVAILABLE"))
        require(direction in setOf("LONG", "SHORT", "WAIT"))
        require(!executable) { "Python analyzer must never return executable output" }
        fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        return PythonAnalysisResult(state, direction, symbol, strategyId, o.optString("strategy_version", "UNSPECIFIED"),
            timeframe, responseTimestamp, source, strings("evidence"),
            listOfNotNull(o.optString("reason").takeIf { it.isNotBlank() }) + strings("rejection_reasons"),
            o.optString("reason", ""), executable)
    }
}
