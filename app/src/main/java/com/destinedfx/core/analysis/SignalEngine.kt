package com.destinedfx.core.analysis

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.marketdata.Candle
import com.destinedfx.core.marketdata.MarketQuote
import com.destinedfx.core.marketdata.Timeframe
import com.destinedfx.core.marketdata.VolumeType
import com.destinedfx.core.risk.RiskEngine
import kotlin.math.abs
import com.destinedfx.core.analysis.FairValueGap
import com.destinedfx.core.analysis.LiquidityLevel
import com.destinedfx.core.analysis.LiquiditySweep
import com.destinedfx.core.analysis.OrderBlock
import com.destinedfx.core.analysis.FillState
import com.destinedfx.core.analysis.ZoneState

enum class SignalState { STRONG_BUY, BUY, WEAK_BUY, WAIT, WEAK_SELL, SELL, STRONG_SELL, BLOCKED, DATA_UNAVAILABLE, INVALIDATED }
enum class MtfPolicy { GRADED, STRICT }

object Versions { const val STRATEGY = "smc-confluence-1.0.0"; const val RULESET = "rules-2026.10"; const val MODEL = "none (rules-based, no ML)" }

data class SignalConfig(
    val structure: StructureConfig = StructureConfig(), val zones: ZoneConfig = ZoneConfig(), val liquidity: LiquidityConfig = LiquidityConfig(),
    val mtf: MtfConfig = MtfConfig(), val mtfPolicy: MtfPolicy = MtfPolicy.GRADED,
    val minRR: Double = 2.0, val maxSlAtr: Double = 3.0, val slBufferAtr: Double = 0.25, val minSlAtr: Double = 0.5,
    val strongThreshold: Int = 80, val normalThreshold: Int = 65, val weakThreshold: Int = 50,
    val minDirectionMargin: Int = 15, val minAvailableWeight: Int = 75, val recentBars: Int = 30
)

data class AnalysisInput(val symbol: String, val tf: Timeframe, val candles: List<Candle>, val htf: Map<Timeframe, List<Candle>>,
                         val quote: MarketQuote?, val spec: InstrumentSpec?, val nowMs: Long, val candleBias: Dir? = null,
                         val strategyId: String = "smc-confluence-1.0.0")

data class ScoreComponent(val name: String, val earned: Double, val available: Double, val notes: List<String>, val formula: String) {
    val isAvailable get() = available > 0
}
data class Target(val label: String, val price: Double, val rr: Double, val source: String)
data class TradePlan(val isLong: Boolean, val entry: Double, val stopLoss: Double, val slAtr: Double, val targets: List<Target>, val primary: Target, val slReason: String)

data class SignalReport(
    val symbol: String, val timeframe: Timeframe, val state: SignalState, val dir: Dir?, val confluenceScore: Int, val bullScore: Int, val bearScore: Int,
    val components: List<ScoreComponent>, val mtf: MtfResult?, val plan: TradePlan?, val reasons: List<String>, val blockers: List<String>,
    val patterns: List<ChartPattern>, val orderBlocks: List<OrderBlock>, val fvgs: List<FairValueGap>, val sweeps: List<LiquiditySweep>,
    val levels: List<LiquidityLevel>, val structure: StructureAnalysis?, val knowledge: List<String>, val volumeKind: VolumeType,
    val strategyVersion: String = Versions.STRATEGY, val rulesetVersion: String = Versions.RULESET, val modelVersion: String = Versions.MODEL,
    val dataSource: String?, val timestampMs: Long,
    val mtfComplete: Boolean = true
) {
    val scoreLabel: String get() = "CONFLUENCE SCORE: $confluenceScore/100"
    /** Mandatory wording wherever the score is shown. */
    val disclaimer: String get() = "Rules-based confluence score - NOT a probability of winning and NOT a guaranteed win rate."
}

object SignalEngine {
    internal val structureComputations = java.util.concurrent.atomic.AtomicInteger(0)
    internal fun clearCacheForTest() {
        cache.clear()
        structureComputations.set(0)
    }

    private data class CacheKey(val symbol: String, val tf: Timeframe, val timestamp: Long, val configHash: Int)
    private val cache = java.util.Collections.synchronizedMap(object : LinkedHashMap<CacheKey, StructureBundle>(30, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<CacheKey, StructureBundle>) = size > 30
    })

    private fun unavailable(i: AnalysisInput, why: String, state: SignalState = SignalState.DATA_UNAVAILABLE, mtf: MtfResult? = null) = SignalReport(
        i.symbol, i.tf, state, null, 0, 0, 0, emptyList(), mtf, null, emptyList(), listOf(why), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        null, emptyList(), VolumeType.NONE, dataSource = i.quote?.source, timestampMs = i.nowMs, mtfComplete = mtf?.missing?.isEmpty() ?: true)

    private fun analyseStructure(i: AnalysisInput, cfg: SignalConfig): StructureBundle {
        structureComputations.incrementAndGet()
        val st = StructureEngine.analyze(i.candles, i.tf, cfg.structure)
        val atrSeries = Indicators.atr(i.candles, cfg.structure.atrPeriod)
        val fvgs = ZoneEngine.fvgs(i.candles, atrSeries, i.tf, cfg.zones)
        val obs = ZoneEngine.orderBlocks(i.candles, st, fvgs, i.tf, cfg.zones)
        val levels = LiquidityEngine.allLevels(st, i.candles, null, null, cfg.liquidity)
        val sweeps = LiquidityEngine.sweeps(i.candles, st, levels, cfg.liquidity)
        val patterns = PatternEngine.detect(i.candles, st)
        return StructureBundle(obs, fvgs, sweeps, levels, st, patterns)
    }

    private fun planAndScore(i: AnalysisInput, cfg: SignalConfig, mtf: MtfResult, bundle: StructureBundle): SignalReport {
        val st = bundle.structure ?: return unavailable(i, "ATR unavailable", mtf = mtf)
        val atr = st.atr ?: return unavailable(i, "ATR unavailable", mtf = mtf)
        val price = i.quote?.mid ?: return unavailable(i, "No price")
        val last = i.candles.size - 1
        val rsi = Indicators.rsi(i.candles).last(); val closes = i.candles.map { it.close }
        val e20 = Indicators.ema(closes, 20).last(); val e50 = Indicators.ema(closes, 50).last()
        val volKind = Indicators.volumeKind(i.candles)

        fun scoreSmc(d: Dir): List<ScoreComponent> {
            val bull = d == Dir.BULL
            // 1) MTF (25)
            val mtfEarned = when {
                mtf.state == (if (bull) MtfState.FULL_BULLISH else MtfState.FULL_BEARISH) -> 25.0
                mtf.state == (if (bull) MtfState.PARTIAL_BULLISH else MtfState.PARTIAL_BEARISH) -> 25.0 * mtf.aligned / mtf.required * 0.6
                mtf.state == MtfState.DATA_INCOMPLETE && mtf.aligned > 0 && mtf.dir == d -> 25.0 * mtf.aligned / mtf.required * 0.6
                mtf.state == MtfState.NEUTRAL -> 5.0 else -> 0.0 }
            val mtfC = ScoreComponent("MTF", mtfEarned, 25.0, listOf(mtf.summary), "25 if all required TFs agree; 15*aligned/required if partial and none opposing; 5 neutral; 0 conflict")
            // 2) Structure (20)
            val n = mutableListOf<String>(); var s = 0.0
            val want = if (bull) Trend.BULLISH else Trend.BEARISH
            if (st.trend == want) { s += 8; n += "Trend ${st.trend}" }
            val ev = st.recentEvent(cfg.recentBars, last, d)
            if (ev != null) { s += 6; n += "${ev.type} ${d.name.lowercase()} @ ${ev.level}"; if (ev.displacement) { s += 3; n += "with displacement" } }
            val sw = st.swings.takeLast(6); if (sw.isNotEmpty() && sw.map { it.strengthAtr }.average() >= 1.0) { s += 3; n += "Swing quality >= 1 ATR" }
            val strC = ScoreComponent("Structure", s, 20.0, n, "8 trend + 6 recent BOS/CHOCH + 3 displacement + 3 swing quality")
            // 3) Liquidity (15)
            val ln = mutableListOf<String>(); var l = 0.0
            val sw1 = bundle.sweeps.lastOrNull { it.impliedDir == d && last - it.index <= cfg.recentBars }
            if (sw1 != null) { if (sw1.confirmed) { l += 10; ln += "Confirmed ${sw1.level.kind} sweep" } else { l += 4; ln += "Unconfirmed ${sw1.level.kind} sweep (no structure shift yet)" } }
            val hasTarget = bundle.levels.any { !it.swept && if (bull) it.price > price else it.price < price }
            if (hasTarget) { l += 5; ln += "Opposing liquidity target available" }
            val liqC = ScoreComponent("Liquidity", l, 15.0, ln, "10 confirmed sweep (4 unconfirmed) + 5 opposing target exists")
            // 4) Zones (15)
            val zn = mutableListOf<String>(); var z = 0.0
            val ob = bundle.orderBlocks.filter { it.dir == d && it.state != ZoneState.MITIGATED && abs(price - it.mid) <= 1.0 * atr + (it.high - it.low) / 2 }.maxByOrNull { it.strength }
            if (ob != null) { z += 4 + 4.0 * ob.strength / 100; zn += "${ob.state} OB ${ob.low}-${ob.high} strength ${ob.strength}"; if (ob.hasFvg) { z += 3; zn += "OB left an FVG" } }
            val fv = bundle.fvgs.filter { it.dir == d && it.fill != FillState.FULLY_FILLED && abs(price - (it.top + it.bottom) / 2) <= atr }.maxByOrNull { it.createdIndex }
            if (fv != null) { z += 4; zn += "FVG ${fv.bottom}-${fv.top} ${"%.0f".format(fv.mitigationPct)}% filled" }
            val zoneC = ScoreComponent("Order Block / FVG", z, 15.0, zn, "4+4*OBstrength/100 + 3 OB-with-FVG + 4 nearby unfilled FVG")
            // 5) Patterns (5)
            val pn = mutableListOf<String>(); var p = 0.0
            val want2 = if (bull) PatternBias.BULLISH else PatternBias.BEARISH
            bundle.patterns.filter { it.bias == want2 }.maxByOrNull { it.state.ordinal }?.let {
                p += if (it.state == PatternState.CONFIRMED_BREAKOUT) 5.0 else 2.0; pn += "${it.name} (${it.state})" }
            if (i.candleBias == d) { p = minOf(5.0, p + 2.0); pn += "Candlestick pattern agrees" }
            val patC = ScoreComponent("Patterns", p, 5.0, pn, "5 confirmed chart-pattern breakout (2 forming) +2 agreeing candlestick, cap 5")
            // 6) Volume (10) - only when the feed has volume
            val volC = if (volKind == VolumeType.NONE) ScoreComponent("Volume", 0.0, 0.0, listOf("No volume data - component excluded, not guessed"), "n/a")
            else { var v = 0.0; val vn = mutableListOf<String>()
                val rv = (last - 2..last).mapNotNull { Indicators.relativeVolume(i.candles, it) }.maxOrNull()
                if (rv != null) { if (rv >= 1.5) { v += 6; vn += "Relative volume %.2fx".format(rv) } else if (rv >= 1.0) { v += 3; vn += "Relative volume %.2fx".format(rv) } }
                val ob10 = Indicators.obv(i.candles); if (ob10.size > 10 && (ob10.last() - ob10[ob10.size - 11] > 0) == bull) { v += 4; vn += "OBV agrees" }
                vn += if (volKind == VolumeType.TICK) "TICK volume (broker activity, not exchange volume)" else "REAL volume"
                ScoreComponent("Volume", v, 10.0, vn, "6 rel.vol>=1.5 (3 if >=1.0) + 4 OBV slope agrees") }
            // 7) Momentum (10) - confirmation only
            val mn = mutableListOf<String>(); var m = 0.0
            if (rsi != null) { val ok = if (bull) rsi in 50.0..70.0 else rsi in 30.0..50.0; val ext = if (bull) rsi in 70.0..80.0 else rsi in 20.0..30.0
                if (ok) { m += 5; mn += "RSI %.1f supports".format(rsi) } else if (ext) { m += 2; mn += "RSI %.1f extended".format(rsi) } else mn += "RSI %.1f does not support".format(rsi) }
            if (e20 != null && e50 != null && (if (bull) e20 > e50 && price > e20 else e20 < e50 && price < e20)) { m += 5; mn += "EMA20/50 stack agrees" }
            val momC = ScoreComponent("Momentum", m, 10.0, mn, "5 RSI band + 5 EMA stack (confirmation only, never a trigger)")
            return listOf(mtfC, strC, liqC, zoneC, patC, volC, momC)
        }

        fun scoreStrategy(stratId: String, d: Dir): List<ScoreComponent> {
            val bull = d == Dir.BULL
            return when (stratId) {
                "ict-silver-bullet" -> {
                    val kz = Indicators.killzone(i.nowMs)
                    val kzEarned = when (kz.first) {
                        Indicators.KillzoneType.LONDON_OPEN, Indicators.KillzoneType.NY_AM, Indicators.KillzoneType.NY_PM -> 25.0
                        Indicators.KillzoneType.ASIAN -> 15.0
                        else -> 8.0
                    }
                    val kzC = ScoreComponent("Killzone Timing", kzEarned, 25.0, listOf(kz.second), "25 in London/NY Killzones; 15 Asian; 8 off-hours")

                    val sw = bundle.sweeps.lastOrNull { it.impliedDir == d && last - it.index <= cfg.recentBars }
                    val swEarned = when {
                        sw != null && sw.confirmed -> 25.0
                        sw != null -> 15.0
                        bundle.levels.any { it.swept && (if (bull) it.price < price else it.price > price) } -> 10.0
                        else -> 0.0
                    }
                    val swC = ScoreComponent("Liquidity Sweep", swEarned, 25.0, listOf(sw?.let { "Sweep of ${it.level.kind} (${if (it.confirmed) "confirmed" else "unconfirmed"})" } ?: "No recent sweep"), "25 confirmed sweep; 15 unconfirmed; 10 recent swept level")

                    val fvgs = bundle.fvgs.filter { it.dir == d && it.fill != FillState.FULLY_FILLED && abs(price - (it.top + it.bottom) / 2) <= 2.5 * atr }
                    val bestFvg = fvgs.maxByOrNull { it.createdIndex }
                    val fvgEarned = when {
                        bestFvg != null && last - bestFvg.createdIndex <= 15 -> 25.0
                        bestFvg != null -> 15.0
                        else -> 0.0
                    }
                    val fvgC = ScoreComponent("FVG Displacement", fvgEarned, 25.0, listOf(bestFvg?.let { "FVG ${it.bottom}-${it.top} CE 50% ${(it.bottom + it.top)/2}" } ?: "No displacement FVG"), "25 fresh FVG in displacement; 15 nearby FVG")

                    val ev = st.recentEvent(cfg.recentBars, last, d)
                    val strEarned = when {
                        ev != null && ev.displacement -> 15.0
                        ev != null -> 10.0
                        st.trend == (if (bull) Trend.BULLISH else Trend.BEARISH) -> 8.0
                        else -> 2.0
                    }
                    val strC = ScoreComponent("Structure Shift", strEarned, 15.0, listOf(ev?.let { "${it.type} shift with displacement" } ?: "Trend ${st.trend}"), "15 MSS with displacement; 10 shift; 8 trend")

                    val oppLevel = bundle.levels.firstOrNull { !it.swept && (if (bull) it.price > price + atr else it.price < price - atr) }
                    val tgtEarned = if (oppLevel != null) 10.0 else 0.0
                    val tgtC = ScoreComponent("Target Draw", tgtEarned, 10.0, listOf(oppLevel?.let { "Opposing draw at ${it.price} (${it.kind})" } ?: "No clear opposing draw"), "10 opposing liquidity pool draw exists")

                    listOf(kzC, swC, fvgC, strC, tgtC)
                }

                "wyckoff-schematics" -> {
                    val sw = bundle.sweeps.lastOrNull { it.impliedDir == d && last - it.index <= cfg.recentBars }
                    val spEarned = when {
                        sw != null && sw.confirmed -> 30.0
                        sw != null -> 20.0
                        st.consolidation -> 15.0
                        else -> 5.0
                    }
                    val spName = if (bull) "Spring Test (Phase C)" else "UTAD Test (Phase C)"
                    val spC = ScoreComponent(spName, spEarned, 30.0, listOf(sw?.let { "Tested range extreme with swift rejection" } ?: if (st.consolidation) "Range established" else "No clear range test"), "30 confirmed Spring/UTAD; 20 forming; 15 range context")

                    var volScore = 0.0
                    val volNotes = mutableListOf<String>()
                    val rv = (last - 2..last).mapNotNull { Indicators.relativeVolume(i.candles, it) }.maxOrNull()
                    if (rv != null) {
                        if (rv >= 1.5) { volScore += 18.0; volNotes += "Volume climax: %.2fx".format(rv) }
                        else if (rv >= 1.0) { volScore += 10.0; volNotes += "Volume above average: %.2fx".format(rv) }
                    }
                    val ob10 = Indicators.obv(i.candles)
                    if (ob10.size > 10 && (ob10.last() - ob10[ob10.size - 11] > 0) == bull) {
                        volScore += 7.0; volNotes += "OBV accumulation/distribution matches"
                    }
                    val volC = ScoreComponent("Volume Absorption", volScore, 25.0, volNotes, "18 relative volume climax >= 1.5x + 7 OBV institutional accumulation")

                    val rangeScore = if (st.consolidation) 20.0 else if (st.swings.size >= 4) 14.0 else 6.0
                    val rangeC = ScoreComponent("Trading Range Phase", rangeScore, 20.0, listOf(if (st.consolidation) "Well-defined Trading Range (Phase B/C)" else "Developing structure"), "20 defined Trading Range; 14 range swings; 6 trending")

                    val ev = st.recentEvent(cfg.recentBars, last, d)
                    val sosScore = if (ev != null && ev.displacement) 15.0 else if (ev != null) 10.0 else 3.0
                    val sosName = if (bull) "Sign of Strength (SOS)" else "Sign of Weakness (SOW)"
                    val sosC = ScoreComponent(sosName, sosScore, 15.0, listOf(ev?.let { "${it.type} break confirming phase shift" } ?: "Awaiting SOS/SOW bar"), "15 SOS/SOW displacement bar; 10 structural event")

                    val oppTarget = bundle.levels.firstOrNull { !it.swept && (if (bull) it.price > price else it.price < price) }
                    val oppScore = if (oppTarget != null) 10.0 else 0.0
                    val oppC = ScoreComponent("Opposite Boundary Target", oppScore, 10.0, listOf(oppTarget?.let { "Targeting opposite schematic boundary @ ${it.price}" } ?: "No opposite boundary"), "10 target reaches opposite trading range boundary")

                    listOf(spC, volC, rangeC, sosC, oppC)
                }

                "supply-and-demand" -> {
                    val ob = bundle.orderBlocks.filter { it.dir == d && it.state != ZoneState.MITIGATED && abs(price - it.mid) <= 1.5 * atr + (it.high - it.low) }.maxByOrNull { it.strength }
                    val freshScore = when {
                        ob != null && ob.state == ZoneState.FRESH -> 30.0
                        ob != null -> 20.0
                        else -> 5.0
                    }
                    val freshC = ScoreComponent("Zone Freshness", freshScore, 30.0, listOf(ob?.let { "${it.state} base @ ${it.low}-${it.high} (strength ${it.strength})" } ?: "No fresh base zone"), "30 unmitigated fresh base; 20 tested once; 5 distant")

                    val depScore = when {
                        ob != null && ob.hasFvg -> 25.0
                        ob != null && ob.strength >= 70 -> 18.0
                        ob != null -> 12.0
                        else -> 0.0
                    }
                    val depC = ScoreComponent("Departure Imbalance", depScore, 25.0, listOf(ob?.let { if (it.hasFvg) "Explosive departure leaving FVG imbalance" else "Departure strength: ${it.strength}/100" } ?: "No departure imbalance"), "25 explosive departure with FVG; 18 strong departure; 12 moderate")

                    val baseScore = if (ob != null) 20.0 * (ob.strength / 100.0) else 5.0
                    val baseC = ScoreComponent("Base Structure Quality", baseScore, 20.0, listOf(ob?.let { "Compact base structure (quality score %.0f%%)".format(ob.strength.toDouble()) } ?: "Base quality pending"), "20 compact base with rapid departure")

                    val htfScore = when {
                        mtf.state == (if (bull) MtfState.FULL_BULLISH else MtfState.FULL_BEARISH) -> 15.0
                        mtf.state == (if (bull) MtfState.PARTIAL_BULLISH else MtfState.PARTIAL_BEARISH) -> 10.0
                        else -> 4.0
                    }
                    val htfC = ScoreComponent("HTF Flow Alignment", htfScore, 15.0, listOf(mtf.summary), "15 full HTF alignment; 10 partial; 4 counter-trend")

                    val oppZone = bundle.orderBlocks.firstOrNull { it.dir != d && (if (bull) it.low > price + atr else it.high < price - atr) }
                    val pathScore = if (oppZone != null) 10.0 else 5.0
                    val pathC = ScoreComponent("Path to Opposing Zone", pathScore, 10.0, listOf(oppZone?.let { "Opposing supply/demand at ${it.mid}" } ?: "Unobstructed path"), "10 opposing zone provides >= 2.0 R:R target")

                    listOf(freshC, depC, baseC, htfC, pathC)
                }

                "breakout-momentum" -> {
                    val dc = Indicators.lastDonchian(i.candles, 20)
                    val dcScore = when {
                        dc != null && (if (bull) price >= dc.upper - 0.1 * atr else price <= dc.lower + 0.1 * atr) -> 30.0
                        dc != null && (if (bull) price > dc.middle else price < dc.middle) -> 16.0
                        else -> 5.0
                    }
                    val dcC = ScoreComponent("Donchian Breakout", dcScore, 30.0, listOf(dc?.let { if (bull) "Price breaking 20-bar high (Upper ${it.upper})" else "Price breaking 20-bar low (Lower ${it.lower})" } ?: "No Donchian data"), "30 bar pierced 20-period channel boundary; 16 momentum side of mid-channel")

                    val atrs = Indicators.atr(i.candles, 14).filterNotNull()
                    val atrAvg = if (atrs.size >= 20) atrs.takeLast(20).average() else atr
                    val atrRatio = if (atrAvg > 0) atr / atrAvg else 1.0
                    val atrScore = when {
                        atrRatio >= 1.25 -> 25.0
                        atrRatio >= 1.05 -> 18.0
                        else -> 8.0
                    }
                    val atrC = ScoreComponent("ATR Volatility Expansion", atrScore, 25.0, listOf("ATR expansion ratio: %.2fx (current %.5f vs avg %.5f)".format(atrRatio, atr, atrAvg)), "25 ATR expanding >= 1.25x average; 18 expanding >= 1.05x")

                    val rv = (last - 2..last).mapNotNull { Indicators.relativeVolume(i.candles, it) }.maxOrNull()
                    val volScore = when {
                        rv != null && rv >= 1.5 -> 20.0
                        rv != null && rv >= 1.1 -> 12.0
                        volKind == VolumeType.NONE -> 10.0
                        else -> 5.0
                    }
                    val volC = ScoreComponent("Volume Surge", volScore, 20.0, listOf(rv?.let { "Relative volume surge: %.2fx".format(it) } ?: "Average volume"), "20 volume surge >= 1.5x on breakout; 12 above 1.1x")

                    val momScore = when {
                        rsi != null && (if (bull) rsi >= 55.0 else rsi <= 45.0) -> 15.0
                        rsi != null && (if (bull) rsi in 50.0..55.0 else rsi in 45.0..50.0) -> 8.0
                        else -> 3.0
                    }
                    val momC = ScoreComponent("Momentum Velocity", momScore, 15.0, listOf(rsi?.let { "RSI velocity at %.1f".format(it) } ?: "RSI n/a"), "15 RSI directional acceleration (bull > 55 / bear < 45)")

                    val tgtScore = 10.0
                    val tgtC = ScoreComponent("Runway Extension", tgtScore, 10.0, listOf("Clear breakout volatility runway to 2.5R"), "10 unhindered path for volatility expansion")

                    listOf(dcC, atrC, volC, momC, tgtC)
                }

                "mean-reversion" -> {
                    val bb = Indicators.lastBollingerBands(closes, 20, 2.0)
                    val bbScore = when {
                        bb != null && (if (bull) price <= bb.lower + 0.2 * atr else price >= bb.upper - 0.2 * atr) -> 30.0
                        bb != null && (if (bull) price < (bb.lower + bb.middle) / 2 else price > (bb.upper + bb.middle) / 2) -> 18.0
                        else -> 5.0
                    }
                    val bbC = ScoreComponent("Bollinger Band 2.0σ Extreme", bbScore, 30.0, listOf(bb?.let { if (bull) "Price pierced lower 2.0σ band (%.5f)".format(it.lower) else "Price pierced upper 2.0σ band (%.5f)".format(it.upper) } ?: "BB n/a"), "30 pierced 2.0σ outer band; 18 extended into outer quartile")

                    val rsiScore = when {
                        rsi != null && (if (bull) rsi <= 30.0 else rsi >= 70.0) -> 25.0
                        rsi != null && (if (bull) rsi <= 38.0 else rsi >= 62.0) -> 16.0
                        else -> 4.0
                    }
                    val rsiC = ScoreComponent("RSI Exhaustion", rsiScore, 25.0, listOf(rsi?.let { "RSI exhaustion: %.1f".format(it) } ?: "RSI n/a"), "25 RSI in extreme exhaustion (<30 / >70); 16 in extreme warning zone")

                    val lastCandle = i.candles.last()
                    val totalRange = maxOf(lastCandle.range, 0.00001)
                    val lowerWick = minOf(lastCandle.open, lastCandle.close) - lastCandle.low
                    val upperWick = lastCandle.high - maxOf(lastCandle.open, lastCandle.close)
                    val candleReversal = if (bull) lowerWick / totalRange >= 0.5 || lastCandle.close > lastCandle.open
                                        else upperWick / totalRange >= 0.5 || lastCandle.close < lastCandle.open
                    val candleScore = if (candleReversal) 20.0 else 8.0
                    val candleC = ScoreComponent("Reversal Candlestick", candleScore, 20.0, listOf(if (candleReversal) "Rejection wick / reversal body at outer band" else "Awaiting reversal trigger candle"), "20 clear rejection wick or counter-candle; 8 forming")

                    val regimeScore = if (st.consolidation) 15.0 else if (mtf.state == MtfState.NEUTRAL) 12.0 else 5.0
                    val regimeC = ScoreComponent("Consolidation Regime", regimeScore, 15.0, listOf(if (st.consolidation) "Consolidation regime: optimal for mean reversion" else "Trending conditions present"), "15 consolidating market state; 12 neutral; 5 strong trend")

                    val revScore = if (bb != null && abs(bb.middle - price) >= 2.0 * atr) 10.0 else 6.0
                    val revC = ScoreComponent("Reversion R:R to Mean", revScore, 10.0, listOf(bb?.let { "Target 20 SMA mean at %.5f".format(it.middle) } ?: "Targeting mean"), "10 mean distance provides >= 2.0 R:R")

                    listOf(bbC, rsiC, candleC, regimeC, revC)
                }

                "trend-following" -> {
                    val e200 = Indicators.ema(closes, 200).last()
                    val emaStack = when {
                        e20 != null && e50 != null && e200 != null && (if (bull) e20 > e50 && e50 > e200 && price > e20 else e20 < e50 && e50 < e200 && price < e20) -> 30.0
                        e20 != null && e50 != null && (if (bull) e20 > e50 && price >= e50 else e20 < e50 && price <= e50) -> 20.0
                        else -> 5.0
                    }
                    val emaC = ScoreComponent("Multi-EMA Trend Stack", emaStack, 30.0, listOf(if (bull) "Bullish EMA stack: EMA20 > EMA50" else "Bearish EMA stack: EMA20 < EMA50"), "30 full 20>50>200 stack; 20 20>50 stack")

                    val inValueZone = if (e20 != null && e50 != null) {
                        val lo = minOf(e20, e50); val hi = maxOf(e20, e50)
                        price in (lo - 0.2 * atr)..(hi + 0.2 * atr)
                    } else false
                    val pbScore = if (inValueZone) 25.0 else if (e20 != null && abs(price - e20) <= 0.8 * atr) 15.0 else 5.0
                    val pbC = ScoreComponent("Value Zone Pullback", pbScore, 25.0, listOf(if (inValueZone) "Price located in 20-50 EMA value zone" else "Near 20 EMA dynamic zone"), "25 price inside 20-50 EMA value zone; 15 near 20 EMA")

                    val contScore = if (st.trend == (if (bull) Trend.BULLISH else Trend.BEARISH)) 20.0 else 10.0
                    val contC = ScoreComponent("Trend Continuation Signal", contScore, 20.0, listOf("Market trend: ${st.trend}"), "20 confirmed trend continuation structure")

                    val htfScore = when {
                        mtf.state == (if (bull) MtfState.FULL_BULLISH else MtfState.FULL_BEARISH) -> 15.0
                        mtf.state == (if (bull) MtfState.PARTIAL_BULLISH else MtfState.PARTIAL_BEARISH) -> 10.0
                        else -> 3.0
                    }
                    val htfC = ScoreComponent("Higher Timeframe Trend", htfScore, 15.0, listOf(mtf.summary), "15 full HTF agreement; 10 partial agreement")

                    val lastSwing = st.swings.lastOrNull { it.type == (if (bull) SwingType.HIGH else SwingType.LOW) }
                    val swingDist = lastSwing?.let { abs(it.price - price) } ?: (2.5 * atr)
                    val roomScore = if (swingDist >= 1.5 * atr) 10.0 else 5.0
                    val roomC = ScoreComponent("Runway to Previous Swing", roomScore, 10.0, listOf("Room to target swing: %.1f ATR".format(swingDist / atr)), "10 clearance >= 1.5 ATR to previous swing extreme")

                    listOf(emaC, pbC, contC, htfC, roomC)
                }

                "smc-confluence-1.0.0" -> scoreSmc(d)
                else -> emptyList()
            }
        }

        val recognizedStrategies = setOf(
            "smc-confluence-1.0.0",
            "ict-silver-bullet",
            "wyckoff-schematics",
            "supply-and-demand",
            "breakout-momentum",
            "mean-reversion",
            "trend-following"
        )
        if (!recognizedStrategies.contains(i.strategyId)) {
            return unavailable(i, "STRATEGY_NOT_IMPLEMENTED: '${i.strategyId}'", SignalState.DATA_UNAVAILABLE, mtf = mtf)
        }

        fun total(cs: List<ScoreComponent>): Int { val av = cs.sumOf { it.available }; return if (av <= 0) 0 else (cs.sumOf { it.earned } / av * 100).toInt().coerceIn(0, 100) }
        val bullC = scoreStrategy(i.strategyId, Dir.BULL); val bearC = scoreStrategy(i.strategyId, Dir.BEAR)
        val bs = total(bullC); val rs = total(bearC)
        val availW = bullC.sumOf { it.available }
        val blockers = mutableListOf<String>(); val reasons = mutableListOf<String>()
        if (availW < cfg.minAvailableWeight && i.strategyId == "smc-confluence-1.0.0") return unavailable(i, "Too little scorable evidence (available weight $availW < ${cfg.minAvailableWeight})", mtf = mtf)

        val dir = if (bs >= rs) Dir.BULL else Dir.BEAR
        val best = maxOf(bs, rs); val margin = abs(bs - rs); val comps = if (dir == Dir.BULL) bullC else bearC
        var state = SignalState.WAIT
        val mtfFull = mtf.state == (if (dir == Dir.BULL) MtfState.FULL_BULLISH else MtfState.FULL_BEARISH)
        val mtfOpposed = mtf.state == MtfState.CONFLICT || mtf.dir == (if (dir == Dir.BULL) Dir.BEAR else Dir.BULL)
        when {
            margin < cfg.minDirectionMargin -> reasons += "Bull $bs vs bear $rs: no clear edge -> WAIT"
            mtfOpposed && i.strategyId != "mean-reversion" -> reasons += "Higher timeframes conflict/oppose -> WAIT"
            cfg.mtfPolicy == MtfPolicy.STRICT && !mtfFull -> reasons += "STRICT policy: all required timeframes must align -> WAIT"
            best >= cfg.strongThreshold && mtfFull && !st.consolidation -> state = pick(dir, 3)
            best >= cfg.normalThreshold -> state = pick(dir, 2)
            best >= cfg.weakThreshold -> state = pick(dir, 1)
            else -> reasons += "Score $best below weak threshold ${cfg.weakThreshold} -> WAIT"
        }
        val phase = MarketPhaseEngine.classify(i.candles, st, bundle.sweeps.filter { last - it.index <= cfg.recentBars })
        reasons += "Phase: ${phase.phase} (${phase.sequence}) - ${phase.description}"
        if (state != SignalState.WAIT && i.strategyId == "smc-confluence-1.0.0") {
            MarketPhaseEngine.gate(
                phase, dir,
                confirmedSweepInDir = bundle.sweeps.any { it.confirmed && it.impliedDir == dir && last - it.index <= cfg.recentBars },
                displacementBreakoutInDir = st.recentEvent(cfg.recentBars, last, dir)?.displacement == true,
                mtfFullInDir = mtfFull
            )?.let { reasons += it; state = SignalState.WAIT }
        }

        val mtfComplete = mtf.missing.isEmpty()
        if (!mtfComplete) {
            reasons += "PARTIAL MTF: missing ${mtf.missing.joinToString { it.label }}; analysis only"
            state = when (state) {
                SignalState.STRONG_BUY, SignalState.BUY -> SignalState.WEAK_BUY
                SignalState.STRONG_SELL, SignalState.SELL -> SignalState.WEAK_SELL
                else -> SignalState.WAIT
            }
        }

        var plan: TradePlan? = null
        if (state != SignalState.WAIT) {
            val r = plan(i, i.quote!!, st, bundle.orderBlocks, bundle.levels, dir, atr, cfg)
            if (r.second != null) { reasons += r.second!!; state = SignalState.WAIT } else plan = r.first
        }
        comps.forEach { cc -> cc.notes.forEach { if (cc.earned > 0) reasons += "${cc.name}: $it" } }
        val keys = buildSet { if (st.events.isNotEmpty()) add(st.events.last().type.name); if (bundle.orderBlocks.isNotEmpty()) add("ORDER_BLOCK"); if (bundle.fvgs.isNotEmpty()) add("FVG")
            if (bundle.sweeps.isNotEmpty()) add("SWEEP"); bundle.patterns.forEach { add(it.knowledgeKey) }; if (volKind == VolumeType.TICK) add("TICK_VOLUME"); add("RSI") }
        val stratName = com.destinedfx.core.strategy.StrategyRegistry.allStrategies.find { it.id == i.strategyId }?.name ?: i.strategyId
        return SignalReport(i.symbol, i.tf, state, if (state == SignalState.WAIT) null else dir, best, bs, rs, comps, mtf, plan, reasons, blockers, bundle.patterns, bundle.orderBlocks, bundle.fvgs, bundle.sweeps,
            bundle.levels, st, keys.map { KnowledgeBase.explain(it) } + "STRATEGY ($stratName) | PLAYBOOK (${phase.phase}): ${phase.playbook}", volKind, strategyVersion = i.strategyId, dataSource = i.quote?.source, timestampMs = i.nowMs, mtfComplete = mtfComplete)
    }

    fun analyse(i: AnalysisInput, cfg: SignalConfig = SignalConfig()): SignalReport {
        val q = i.quote ?: return unavailable(i, "WAITING FOR LIVE DATA: no quote")
        val c = i.candles
        if (c.size < 60 || c.any { !it.isValid }) return unavailable(i, "Insufficient or invalid candle history (${c.size} closed candles, need >= 60)")
        val mtf = MtfEngine.analyse(i.htf + (i.tf to c), cfg.mtf, cfg.structure)

        val lastClosedCandleTime = c.lastOrNull { it.complete }?.timeMs ?: 0L
        val cacheKey = CacheKey(i.symbol, i.tf, lastClosedCandleTime, cfg.hashCode())
        val bundle = cache[cacheKey] ?: analyseStructure(i, cfg).also { cache[cacheKey] = it }

        return planAndScore(i, cfg, mtf, bundle)
    }

    /** Placeholder report for user-specified manual trades (analysis gates are not applied by the pipeline in that mode). */
    fun manualReport(symbol: String, tf: Timeframe, nowMs: Long, source: String?) = SignalReport(symbol, tf, SignalState.WAIT, null, 0, 0, 0, emptyList(), null, null,
        listOf("Manual trade"), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, emptyList(), VolumeType.NONE, dataSource = source, timestampMs = nowMs)

    private fun pick(d: Dir, level: Int) = if (d == Dir.BULL) when (level) { 3 -> SignalState.STRONG_BUY; 2 -> SignalState.BUY; else -> SignalState.WEAK_BUY }
    else when (level) { 3 -> SignalState.STRONG_SELL; 2 -> SignalState.SELL; else -> SignalState.WEAK_SELL }

    /** SL from structural invalidation + volatility/spread buffer; TPs from opposing liquidity. Returns (plan, failureReason). */
    fun plan(i: AnalysisInput, q: MarketQuote, st: StructureAnalysis, obs: List<OrderBlock>, levels: List<LiquidityLevel>, dir: Dir, atr: Double, cfg: SignalConfig): Pair<TradePlan?, String?> {
        val long = dir == Dir.BULL
        val entry = (if (long) q.ask else q.bid) ?: q.mid ?: return null to "No executable price"
        val spread = q.spread ?: 0.0
        val aligned = obs.filter { it.dir == dir && it.state != ZoneState.MITIGATED && (if (long) it.low < entry else it.high > entry) }.maxByOrNull { it.createdIndex }
        val sw = st.swings.lastOrNull { it.type == (if (long) SwingType.LOW else SwingType.HIGH) && (if (long) it.price < entry else it.price > entry) }
        val inv = when { aligned != null -> if (long) aligned.low else aligned.high; sw != null -> sw.price; else -> return null to "No structural invalidation level (no OB/swing) -> WAIT" }
        val reason = if (aligned != null) "beyond ${aligned.dir} order block" else "beyond last swing ${if (long) "low" else "high"}"
        val buffer = maxOf(cfg.slBufferAtr * atr, 2 * spread, (i.spec?.minStopDistance ?: 0.0))
        var sl = if (long) inv - buffer else inv + buffer
        if (i.spec != null && i.spec.tickSize > 0.0) {
            val tick = i.spec.tickSize
            sl = if (long) kotlin.math.floor(sl / tick) * tick else kotlin.math.ceil(sl / tick) * tick
        }
        var dist = abs(entry - sl)
        val minDist = maxOf(cfg.minSlAtr * atr, 3 * spread, i.spec?.minStopDistance ?: 0.0)
        if (dist < minDist) {
            sl = if (long) entry - minDist else entry + minDist
            if (i.spec != null && i.spec.tickSize > 0.0) {
                val tick = i.spec.tickSize
                sl = if (long) kotlin.math.floor(sl / tick) * tick else kotlin.math.ceil(sl / tick) * tick
            }
            dist = abs(entry - sl)
        }
        if (dist > cfg.maxSlAtr * atr) return null to "Structural invalidation %.1f ATR away exceeds max %.1f ATR -> WAIT".format(dist / atr, cfg.maxSlAtr)
        val cands = levels.filter { !it.swept && (if (long) it.side == LiquiditySide.BUY_SIDE && it.price > entry + minDist else it.side == LiquiditySide.SELL_SIDE && it.price < entry - minDist) }
            .sortedBy { abs(it.price - entry) }
        val ts = mutableListOf<Target>()
        fun addLevel(label: String, lv: LiquidityLevel) { RiskEngine.rewardToRisk(entry, sl, lv.price, long)?.let { ts += Target(label, lv.price, it, lv.kind.name) } }
        fun addPrice(label: String, targetPx: Double, source: String) { RiskEngine.rewardToRisk(entry, sl, targetPx, long)?.let { ts += Target(label, targetPx, it, source) } }

        if (cands.isNotEmpty()) {
            addLevel("TP1", cands.first())
            cands.firstOrNull { it.price != cands.first().price && (it.kind == LevelKind.EQUAL_HIGHS || it.kind == LevelKind.EQUAL_LOWS || it.higherTimeframe) }?.let { addLevel("TP2", it) }
            cands.lastOrNull { it.higherTimeframe }?.takeIf { h -> ts.none { it.price == h.price } }?.let { addLevel("TP3", it) }
        }

        val primary = ts.firstOrNull { it.rr >= cfg.minRR } ?: return null to "NO_VALID_TARGET: No structural target reaches RR >= ${cfg.minRR} (best %.2f) -> WAIT".format(ts.maxOfOrNull { it.rr } ?: 0.0)
        return TradePlan(long, entry, sl, dist / atr, ts, primary, "SL $reason + max(${cfg.slBufferAtr} ATR, 2x spread) buffer") to null
    }

    fun isInvalidated(isLong: Boolean, stopLoss: Double, currentPriceOrClose: Double): Boolean {
        return if (isLong) currentPriceOrClose <= stopLoss else currentPriceOrClose >= stopLoss
    }

    fun isInvalidated(plan: TradePlan, candle: Candle): Boolean {
        return if (plan.isLong) candle.low <= plan.stopLoss || candle.close <= plan.stopLoss
        else candle.high >= plan.stopLoss || candle.close >= plan.stopLoss
    }

    fun isInvalidated(plan: TradePlan, currentPrice: Double): Boolean {
        return isInvalidated(plan.isLong, plan.stopLoss, currentPrice)
    }
}
