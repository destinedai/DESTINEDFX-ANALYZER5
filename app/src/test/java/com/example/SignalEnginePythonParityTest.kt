package com.example

import com.destinedfx.core.analysis.AnalysisInput
import com.destinedfx.core.analysis.SignalEngine
import com.destinedfx.core.marketdata.AssetClass
import com.destinedfx.core.marketdata.Candle
import com.destinedfx.core.marketdata.MarketQuote
import com.destinedfx.core.marketdata.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SignalEnginePythonParityTest {
    companion object {
        private const val NOW_MS = 1_699_975_860_000L
        private const val MINUTE_MS = 60_000L
        private const val COUNT = 60
        private const val SYMBOL = "EUR/USD"
        private val STRATEGIES = listOf("smc-confluence-1.0.0", "ict-silver-bullet")
    }

    private fun pythonGoldenRows(strategy: String): List<Candle> {
        val rows = (0 until COUNT).map { index ->
            val close = 100.0 + index * 0.2
            Candle(
                timeMs = NOW_MS - (COUNT - index) * MINUTE_MS,
                open = close - 0.05,
                high = close + 0.30,
                low = close - 0.30,
                close = close,
                volume = 100.0,
                complete = true
            )
        }.toMutableList()
        val last = rows.last()
        when (strategy) {
            "smc-confluence-1.0.0" -> {
                val previous = rows[rows.lastIndex - 1]
                rows[rows.lastIndex] = last.copy(
                    open = previous.high + 0.2,
                    high = previous.high + 0.6,
                    low = previous.high + 0.1,
                    close = previous.high + 0.5
                )
            }
            "ict-silver-bullet" -> {
                rows[rows.lastIndex - 5] = rows[rows.lastIndex - 5].copy(open = 105.0, high = 106.0, low = 100.0, close = 105.8)
                rows[rows.lastIndex - 3] = rows[rows.lastIndex - 3].copy(open = 105.8, high = 106.0, low = 105.5, close = 105.9)
                rows[rows.lastIndex - 2] = rows[rows.lastIndex - 2].copy(open = 105.9, high = 106.2, low = 105.8, close = 106.1)
                rows[rows.lastIndex - 1] = rows[rows.lastIndex - 1].copy(open = 106.5, high = 108.0, low = 106.4, close = 107.5)
                rows[rows.lastIndex] = last.copy(open = 106.3, high = 107.1, low = 106.1, close = 106.5)
            }
        }
        return rows
    }

    private fun summary(strategy: String): String {
        val candles = pythonGoldenRows(strategy)
        val last = candles.last()
        val quote = MarketQuote.twoSided(
            SYMBOL, AssetClass.FOREX, last.close, last.close + 0.0001,
            NOW_MS, NOW_MS, "fixture-provider"
        )
        val report = SignalEngine.analyse(
            AnalysisInput(
                symbol = SYMBOL,
                tf = Timeframe.M15,
                candles = candles,
                htf = emptyMap(),
                quote = quote,
                spec = null,
                nowMs = NOW_MS,
                strategyId = strategy
            )
        )
        assertNotNull(report)
        val plan = report.plan
        return listOf(
            "strategy=$strategy",
            "state=${report.state}",
            "direction=${report.dir}",
            "entry=${plan?.entry}",
            "stop_loss=${plan?.stopLoss}",
            "take_profit=${plan?.primary?.price}",
            "rr=${plan?.primary?.rr}",
            "reasons=${report.reasons.joinToString("|")}",
            "blockers=${report.blockers.joinToString("|")}",
            "components=${report.components.map { it.name }}",
            "sweeps=${report.sweeps.map { it.level.kind.name + ":" + it.impliedDir.name }}",
            "fvgs=${report.fvgs.size}"
        ).joinToString("; ")
    }

    @Test
    fun pythonGolden60CandleFixturesProduceStableSignalEngineReports() {
        val actual = STRATEGIES.map(::summary)
        assertEquals("Kotlin snapshot to document against the Python golden outputs", listOf("TODO", "TODO"), actual)
    }
}
