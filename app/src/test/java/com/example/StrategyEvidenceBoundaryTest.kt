package com.example

import com.destinedfx.core.analysis.Dir
import com.destinedfx.core.analysis.ScoreComponent
import com.destinedfx.core.analysis.SignalReport
import com.destinedfx.core.analysis.SignalState
import com.destinedfx.core.marketdata.Timeframe
import com.destinedfx.core.marketdata.VolumeType
import com.destinedfx.core.trade.CheckStatus
import com.destinedfx.core.trade.strategyEvidenceChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioral tests for the exact evidence helper used by TradeValidationPipeline. */
class StrategyEvidenceBoundaryTest {
    private data class Evidence(val name: String, val score: Double, val available: Double)

    private val fixtures = mapOf(
        "smc-confluence-1.0.0" to listOf(
            Evidence("MTF", 8.0, 25.0),
            Evidence("Structure", 8.0, 20.0),
            Evidence("Liquidity", 5.0, 15.0),
            Evidence("Order Block / FVG", 4.0, 15.0)
        ),
        "ict-silver-bullet" to listOf(
            Evidence("Killzone Timing", 25.0, 25.0),
            Evidence("Liquidity Sweep", 25.0, 25.0),
            Evidence("FVG Displacement", 15.0, 25.0),
            Evidence("Structure Shift", 10.0, 15.0),
            Evidence("Target Draw", 10.0, 10.0)
        ),
        "wyckoff-schematics" to listOf(
            Evidence("Spring Test (Phase C)", 30.0, 30.0),
            Evidence("Volume Absorption", 10.0, 25.0),
            Evidence("Trading Range Phase", 14.0, 20.0),
            Evidence("Sign of Strength (SOS)", 10.0, 15.0),
            Evidence("Opposite Boundary Target", 10.0, 10.0)
        ),
        "supply-and-demand" to listOf(
            Evidence("Zone Freshness", 20.0, 30.0),
            Evidence("Departure Imbalance", 18.0, 25.0),
            Evidence("Base Structure Quality", 12.0, 20.0),
            Evidence("HTF Flow Alignment", 10.0, 15.0),
            Evidence("Path to Opposing Zone", 5.0, 10.0)
        ),
        "breakout-momentum" to listOf(
            Evidence("Donchian Breakout", 30.0, 30.0),
            Evidence("ATR Volatility Expansion", 18.0, 25.0),
            Evidence("Volume Surge", 8.0, 20.0),
            Evidence("Momentum Velocity", 8.0, 15.0),
            Evidence("Runway Extension", 10.0, 10.0)
        ),
        "mean-reversion" to listOf(
            Evidence("Bollinger Band 2.0σ Extreme", 18.0, 30.0),
            Evidence("RSI Exhaustion", 16.0, 25.0),
            Evidence("Reversal Candlestick", 20.0, 20.0),
            Evidence("Consolidation Regime", 12.0, 15.0),
            Evidence("Reversion R:R to Mean", 10.0, 10.0)
        ),
        "trend-following" to listOf(
            Evidence("Multi-EMA Trend Stack", 20.0, 30.0),
            Evidence("Value Zone Pullback", 15.0, 25.0),
            Evidence("Trend Continuation Signal", 20.0, 20.0),
            Evidence("Higher Timeframe Trend", 10.0, 15.0),
            Evidence("Runway to Previous Swing", 5.0, 10.0)
        ),
        "liquidity-stop-hunts" to listOf(
            Evidence("Liquidity Sweep Confirmation", 30.0, 30.0),
            Evidence("Equal Highs / Lows (EQH/EQL) Pool", 25.0, 25.0),
            Evidence("Wick Raid Rejection", 20.0, 20.0),
            Evidence("HTF Flow Alignment", 10.0, 15.0),
            Evidence("Tight Invalidation Runway", 5.0, 10.0)
        )
    )

    private fun report(
        strategy: String,
        evidence: List<Evidence> = fixtures[strategy].orEmpty(),
        volumeKind: VolumeType = VolumeType.REAL,
        volumeSource: String = volumeKind.name,
        state: SignalState = SignalState.BUY,
        direction: Dir? = Dir.BULL
    ): SignalReport {
        val components = evidence.map { item ->
            ScoreComponent(
                name = item.name,
                earned = item.score,
                available = item.available,
                notes = listOf("behavioral fixture source=$volumeSource"),
                formula = "test fixture"
            )
        }
        return SignalReport(
            symbol = "R_100",
            timeframe = Timeframe.M5,
            state = state,
            dir = direction,
            confluenceScore = 85,
            bullScore = 85,
            bearScore = 10,
            components = components,
            mtf = null,
            plan = null,
            reasons = emptyList(),
            blockers = emptyList(),
            patterns = emptyList(),
            orderBlocks = emptyList(),
            fvgs = emptyList(),
            sweeps = emptyList(),
            levels = emptyList(),
            structure = null,
            knowledge = emptyList(),
            volumeKind = volumeKind,
            strategyVersion = strategy,
            dataSource = "behavioral-test",
            timestampMs = 1_800_000_000_000L
        )
    }

    @Test
    fun allEightStrategiesPassOnlyWhenEveryRequiredEvidenceThresholdIsPresent() {
        assertEquals(8, fixtures.size)
        fixtures.keys.forEach { strategy ->
            val failures = strategyEvidenceChecks(report(strategy)).filter { it.status == CheckStatus.FAIL }
            assertTrue("$strategy should pass its positive evidence fixture: $failures", failures.isEmpty())
        }
    }

    @Test
    fun eachStrategyRejectsMissingRequiredEvidence() {
        fixtures.forEach { (strategy, evidence) ->
            val incomplete = report(strategy, evidence.drop(1))
            val failures = strategyEvidenceChecks(incomplete).filter { it.status == CheckStatus.FAIL }
            assertTrue("$strategy must reject missing evidence", failures.any { it.name.startsWith("EVIDENCE:") })
        }
    }

    @Test
    fun breakoutCannotPassWithNoneOrMismatchedVolumeSource() {
        val evidence = fixtures.getValue("breakout-momentum")
        val none = strategyEvidenceChecks(report("breakout-momentum", evidence, VolumeType.NONE, "REAL"))
        assertTrue(none.any { it.name == "VOLUME SOURCE" && it.status == CheckStatus.FAIL })
        val mismatch = strategyEvidenceChecks(report("breakout-momentum", evidence, VolumeType.TICK, "REAL"))
        assertTrue(mismatch.any { it.name == "VOLUME SOURCE" && it.status == CheckStatus.FAIL })
        val real = strategyEvidenceChecks(report("breakout-momentum", evidence, VolumeType.REAL, "REAL"))
        assertFalse(real.any { it.name == "VOLUME SOURCE" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun unknownStrategyAndContradictoryDirectionFailClosed() {
        val unknown = strategyEvidenceChecks(report("not-a-registered-strategy"))
        assertTrue(unknown.any { it.name == "STRATEGY EVIDENCE" && it.status == CheckStatus.FAIL })
        val wrongDirection = strategyEvidenceChecks(report(
            "ict-silver-bullet",
            state = SignalState.SELL,
            direction = Dir.BULL
        ))
        assertTrue(wrongDirection.any { it.name == "STRATEGY DIRECTION" && it.status == CheckStatus.FAIL })
    }
}
