package com.example.domain.engine

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.domain.model.Candle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private const val PYTHON_ENGINE_VERSION = "python-quantitative-android-1.0.0"

/** A non-executable Python analysis result. Kotlin/RiskEngine remains authoritative. */
data class PythonSignalCandidate(
    val symbol: String,
    val strategyId: String,
    val strategyVersion: String,
    val timeframe: String,
    val state: String,
    val direction: String,
    val entry: Double?,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val rewardToRisk: Double?,
    val confluenceScore: Int,
    val scoreBasis: List<String>,
    val evidence: List<String>,
    val reason: String,
    val source: String,
    val candleTimestampMs: Long,
    val executable: Boolean,
    val engineVersion: String = PYTHON_ENGINE_VERSION
)

/**
 * Process-wide startup guard. Chaquopy's interpreter is initialized exactly once per
 * Android process; this object does not expose broker credentials or an execution API.
 */
object PythonQuantitativeRuntime {
    private val lock = Any()
    @Volatile private var started = false

    fun ensureStarted(context: android.content.Context) {
        if (started) return
        synchronized(lock) {
            if (!started) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
                started = true
            }
        }
    }
}

class PythonQuantitativeSignalEngine(
    private val context: android.content.Context,
    private val timeoutMs: Long = 5_000L
) {
    suspend fun analyze(
        symbol: String,
        timeframe: String,
        strategyId: String,
        candles: List<Candle>,
        source: String,
        nowMs: Long = System.currentTimeMillis(),
        expectedIntervalMs: Long? = null,
        providerRetrievedAtMs: Long? = null,
        allowSessionGaps: Boolean = false,
        quotePrice: Double? = null,
    ): PythonSignalCandidate = withContext(Dispatchers.Default) {
        try {
            withTimeout(timeoutMs) {
                PythonQuantitativeRuntime.ensureStarted(context)
                val request = JSONObject()
                    .put("symbol", symbol)
                    .put("timeframe", timeframe)
                    .put("strategy_id", strategyId)
                    .put("source", source)
                    .put("now_ms", nowMs)
                    .putOpt("expected_interval_ms", expectedIntervalMs)
                    .putOpt("provider_retrieved_at_ms", providerRetrievedAtMs)
                    .put("allow_session_gaps", allowSessionGaps)
                    .putOpt("quote_price", quotePrice)
                    .put("candles", JSONArray().apply {
                        candles.forEach { candle ->
                            put(JSONObject()
                                .put("timestamp_ms", candle.timestamp)
                                .put("open", candle.open)
                                .put("high", candle.high)
                                .put("low", candle.low)
                                .put("close", candle.close)
                                .put("volume", candle.volume)
                                .put("complete", candle.complete)
                                .put("source", source)
                                .putOpt("retrieved_at_ms", providerRetrievedAtMs))
                        }
                    })
                val module = Python.getInstance().getModule("quantitative")
                val raw = module.callAttr("analyze_json", request.toString()).toString()
                parseResult(raw, symbol, timeframe, strategyId, source)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unavailable(symbol, timeframe, strategyId, source, "PYTHON_ENGINE_ERROR:${e.javaClass.simpleName}")
        }
    }

    private fun parseResult(raw: String, symbol: String, timeframe: String,
                            strategyId: String, source: String): PythonSignalCandidate {
        val json = JSONObject(raw)
        return PythonSignalCandidate(
            symbol = json.optString("symbol", symbol),
            strategyId = json.optString("strategy_id", strategyId),
            strategyVersion = json.optString("strategy_version"),
            timeframe = json.optString("timeframe", timeframe),
            state = json.optString("state", "DATA_UNAVAILABLE"),
            direction = json.optString("direction", "WAIT"),
            entry = json.optNullableDouble("entry"),
            stopLoss = json.optNullableDouble("stop_loss"),
            takeProfit = json.optNullableDouble("take_profit"),
            rewardToRisk = json.optNullableDouble("reward_to_risk"),
            confluenceScore = json.optInt("confidence_score", 0).coerceIn(0, 100),
            scoreBasis = json.optStringList("score_basis"),
            evidence = json.optStringList("evidence"),
            reason = json.optString("reason", "No Python analysis reason was returned."),
            source = json.optString("source", source),
            candleTimestampMs = json.optLong("candle_timestamp_ms", 0L),
            executable = false,
        )
    }

    private fun unavailable(symbol: String, timeframe: String, strategyId: String,
                            source: String, reason: String) = PythonSignalCandidate(
        symbol = symbol, strategyId = strategyId, strategyVersion = "",
        timeframe = timeframe, state = "DATA_UNAVAILABLE", direction = "WAIT",
        entry = null, stopLoss = null, takeProfit = null, rewardToRisk = null,
        confluenceScore = 0, scoreBasis = emptyList(), evidence = emptyList(),
        reason = reason, source = source, candleTimestampMs = 0L, executable = false
    )

    companion object {
        const val ENGINE_VERSION = PYTHON_ENGINE_VERSION
    }
}

private fun JSONObject.optNullableDouble(name: String): Double? =
    if (isNull(name)) null else optDouble(name).takeIf { it.isFinite() }

private fun JSONObject.optStringList(name: String): List<String> =
    optJSONArray(name)?.let { array -> List(array.length()) { index -> array.optString(index) } } ?: emptyList()
