package com.example.domain.usecase.impl

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.destinedfx.core.marketdata.Candle
import com.example.domain.model.PythonAnalysisReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A strictly identity-bound, analysis-only result returned by the packaged Python rules. */
data class PythonAnalysisResult(
    val state: String,
    val direction: String,
    val symbol: String,
    val strategyId: String,
    val strategyVersion: String,
    val timeframe: String,
    val candleTimestampMs: Long,
    val source: String,
    val retrievedAtMs: Long,
    val entry: Double?,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val rewardToRisk: Double?,
    val scoreBasis: List<String>,
    val evidence: List<String>,
    val rejectionReasons: List<String>,
    val reason: String,
    val executable: Boolean
) {
    fun toReport() = PythonAnalysisReport(
        state = state, runtimeAvailable = true, direction = direction, symbol = symbol,
        strategyId = strategyId, strategyVersion = strategyVersion, timeframe = timeframe,
        candleTimestampMs = candleTimestampMs, source = source, retrievedAtMs = retrievedAtMs,
        entry = entry, stopLoss = stopLoss, takeProfit = takeProfit, rewardToRisk = rewardToRisk,
        scoreBasis = scoreBasis, evidence = evidence, rejectionReasons = rejectionReasons,
        reason = reason, executable = executable
    )
}

interface PythonAnalyzer {
    suspend fun analyze(
        symbol: String,
        timeframe: String,
        strategyId: String,
        source: String,
        retrievedAtMs: Long,
        nowMs: Long,
        intervalMs: Long,
        allowSessionGaps: Boolean,
        candles: List<Candle>
    ): PythonAnalysisResult
}

class PythonInputUnavailableException(message: String) : IllegalStateException(message)

class ChaquopyPythonAnalyzer(context: Context) : PythonAnalyzer {
    private val appContext = context.applicationContext

    override suspend fun analyze(
        symbol: String, timeframe: String, strategyId: String, source: String,
        retrievedAtMs: Long, nowMs: Long, intervalMs: Long,
        allowSessionGaps: Boolean, candles: List<Candle>
    ): PythonAnalysisResult = withTimeout(5_000L) {
        withContext(Dispatchers.IO) {
            if (symbol.isBlank() || timeframe.isBlank() || strategyId.isBlank() || source.isBlank())
                throw PythonInputUnavailableException("MISSING_SNAPSHOT_IDENTITY_OR_SOURCE")
            if (retrievedAtMs <= 0L || nowMs < retrievedAtMs || intervalMs <= 0L || candles.isEmpty())
                throw PythonInputUnavailableException("INVALID_SNAPSHOT_TIMESTAMP_OR_CANDLES")
            val maxAgeMs = intervalMs * 2L + 60_000L
            if (nowMs - retrievedAtMs > maxAgeMs) throw PythonInputUnavailableException("STALE_RETRIEVAL")
            val payload = JSONObject().apply {
                put("symbol", symbol); put("timeframe", timeframe); put("strategy_id", strategyId)
                put("source", source); put("now_ms", nowMs); put("provider_retrieved_at_ms", retrievedAtMs)
                put("expected_interval_ms", intervalMs); put("max_candle_age_ms", maxAgeMs)
                put("allow_session_gaps", allowSessionGaps)
                put("candles", JSONArray().apply {
                    candles.forEach { c -> put(JSONObject().apply {
                        put("timestamp_ms", c.timeMs); put("open", c.open); put("high", c.high)
                        put("low", c.low); put("close", c.close); put("volume", c.volume)
                        put("complete", c.complete); put("source", source); put("retrieved_at_ms", retrievedAtMs)
                    }) }
                })
            }.toString()
            val py = synchronized(Python::class.java) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
                Python.getInstance()
            }
            val raw = py.getModule("quantitative").callAttr("analyze_json", payload).toString()
            parseAndValidate(raw, symbol, timeframe, strategyId, source, retrievedAtMs, candles.last().timeMs)
        }
    }

    private fun parseAndValidate(
        raw: String, symbol: String, timeframe: String, strategyId: String,
        source: String, retrievedAtMs: Long, candleTimestampMs: Long
    ): PythonAnalysisResult {
        val o = JSONObject(raw)
        fun requiredString(key: String): String {
            require(o.has(key) && o.opt(key) is String) { "MISSING_OR_INVALID_$key" }
            return (o.opt(key) as String).also { require(it.isNotBlank()) { "EMPTY_$key" } }
        }
        fun requiredLong(key: String): Long {
            val value = o.opt(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0) { "MISSING_OR_INVALID_$key" }
            return value.toLong()
        }
        fun optionalFiniteNumber(key: String): Double? {
            require(o.has(key)) { "MISSING_$key" }
            val value = o.opt(key)
            if (value == null || value === JSONObject.NULL) return null
            require(value is Number && value.toDouble().isFinite()) { "INVALID_$key" }
            return value.toDouble()
        }
        fun requiredStringList(key: String): List<String> {
            val value = o.opt(key)
            require(value is JSONArray) { "MISSING_OR_INVALID_$key" }
            return (0 until value.length()).map { index ->
                val item = value.opt(index)
                require(item is String && item.isNotBlank()) { "INVALID_${key}_ITEM" }
                item
            }
        }

        val responseSymbol = requiredString("symbol")
        val responseStrategy = requiredString("strategy_id")
        val version = requiredString("strategy_version")
        val responseTimeframe = requiredString("timeframe")
        val responseSource = requiredString("source")
        val responseTimestamp = requiredLong("candle_timestamp_ms")
        require(responseSymbol == symbol && responseStrategy == strategyId &&
            responseTimeframe == timeframe && responseSource == source && responseTimestamp == candleTimestampMs) {
            "PYTHON_RESPONSE_IDENTITY_MISMATCH"
        }
        require(version == RULESET_VERSIONS[strategyId]) { "UNSUPPORTED_OR_MISMATCHED_RULESET_VERSION" }
        val state = requiredString("state")
        require(state in setOf("CANDIDATE", "WAITING", "DATA_UNAVAILABLE")) { "UNSUPPORTED_PYTHON_STATE" }
        val direction = requiredString("direction")
        require(direction in setOf("LONG", "SHORT", "WAIT")) { "UNSUPPORTED_PYTHON_DIRECTION" }
        val executableValue = o.opt("executable")
        require(executableValue is Boolean && !executableValue) { "PYTHON_EXECUTION_FLAG_INVALID" }
        val evidence = requiredStringList("evidence")
        val scoreBasis = requiredStringList("score_basis")
        val rejectionReasons = requiredStringList("rejection_reasons")
        val reason = requiredString("reason")
        val confidence = o.opt("confidence_score")
        require(confidence is Number && confidence.toDouble().isFinite() && confidence.toDouble() == 0.0) {
            "UNSUPPORTED_PYTHON_CONFIDENCE_SCORE"
        }
        val entry = optionalFiniteNumber("entry")
        val stop = optionalFiniteNumber("stop_loss")
        val target = optionalFiniteNumber("take_profit")
        val rr = optionalFiniteNumber("reward_to_risk")
        if (state == "CANDIDATE") {
            require(direction == "LONG" || direction == "SHORT")
            require(evidence.isNotEmpty() && entry != null && stop != null && target != null && rr != null)
            val candidateEntry = requireNotNull(entry)
            val candidateStop = requireNotNull(stop)
            val candidateTarget = requireNotNull(target)
            val candidateRr = requireNotNull(rr)
            require(listOf(candidateEntry, candidateStop, candidateTarget).all { it.isFinite() && it > 0.0 })
            val risk = if (direction == "LONG") candidateEntry - candidateStop else candidateStop - candidateEntry
            val reward = if (direction == "LONG") candidateTarget - candidateEntry else candidateEntry - candidateTarget
            val computedRr = reward / risk
            require(risk > 0.0 && reward > 0.0 && computedRr.isFinite() && computedRr >= 2.0 &&
                abs(computedRr - candidateRr) <= 0.02) { "INVALID_PYTHON_CANDIDATE_PLAN" }
        } else {
            require(direction == "WAIT" && entry == null && stop == null && target == null && rr == null)
        }
        return PythonAnalysisResult(
            state = state, direction = direction, symbol = responseSymbol, strategyId = responseStrategy,
            strategyVersion = version, timeframe = responseTimeframe, candleTimestampMs = responseTimestamp,
            source = responseSource, retrievedAtMs = retrievedAtMs, entry = entry, stopLoss = stop,
            takeProfit = target, rewardToRisk = rr, scoreBasis = scoreBasis, evidence = evidence,
            rejectionReasons = rejectionReasons, reason = reason, executable = false
        )
    }

    companion object {
        val RULESET_VERSIONS = mapOf(
            "smc-confluence-1.0.0" to "python-smc-confluence-1.0.0",
            "ict-silver-bullet" to "python-ict-silver-bullet-1.0.0",
            "wyckoff-schematics" to "python-wyckoff-schematics-1.0.0",
            "supply-and-demand" to "python-supply-and-demand-1.0.0",
            "volatility-breakout" to "python-volatility-breakout-1.0.0",
            "breakout-momentum" to "python-breakout-momentum-1.0.0",
            "statistical-mean-reversion" to "python-statistical-mean-reversion-1.0.0",
            "multi-ema-trend-following" to "python-multi-ema-trend-following-1.0.0"
        )
    }
}
