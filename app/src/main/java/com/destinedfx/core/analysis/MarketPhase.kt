package com.destinedfx.core.analysis

import com.destinedfx.core.marketdata.Candle
import kotlin.math.abs

/**
 * Market phase = what the swing sequence says the market is doing right now. Deterministic rules on confirmed swings only.
 * HH+HL = UPTREND, LH+LL = DOWNTREND, flat highs+flat lows = RANGE, LH+HL = CONTRACTING (coil/triangle),
 * HH+LL = EXPANDING (broadening, unreliable). RANGE/CONTRACTING after a prior trend with a confirmed sweep of a range edge
 * is reported as ACCUMULATION (after a down move) or DISTRIBUTION (after an up move).
 */
enum class MarketPhase { UPTREND, DOWNTREND, RANGE, CONTRACTING, EXPANDING, ACCUMULATION, DISTRIBUTION, UNDEFINED }
enum class SwingMove { UP, DOWN, FLAT }

data class PhaseReport(
    val phase: MarketPhase, val highMove: SwingMove?, val lowMove: SwingMove?, val sequence: String,
    val rangeHigh: Double?, val rangeLow: Double?, val rangePositionPct: Double?,
    val description: String, val playbook: String, val bias: Dir?
)

object MarketPhaseEngine {
    private fun move(a: Double, b: Double, tol: Double) = when { b > a + tol -> SwingMove.UP; b < a - tol -> SwingMove.DOWN; else -> SwingMove.FLAT }

    fun classify(c: List<Candle>, a: StructureAnalysis, recentSweeps: List<LiquiditySweep> = emptyList(), flatToleranceAtr: Double = 0.25): PhaseReport {
        val atr = a.atr
        val hs = a.swings.filter { it.type == SwingType.HIGH }.takeLast(3); val ls = a.swings.filter { it.type == SwingType.LOW }.takeLast(3)
        if (atr == null || hs.size < 2 || ls.size < 2 || c.isEmpty())
            return PhaseReport(MarketPhase.UNDEFINED, null, null, "", null, null, null, "Not enough confirmed swings to classify the market.", "No playbook: stand aside until structure forms.", null)
        val tol = atr * flatToleranceAtr
        val hMove = move(hs[hs.size - 2].price, hs.last().price, tol); val lMove = move(ls[ls.size - 2].price, ls.last().price, tol)
        val seq = (a.swings.takeLast(6)).joinToString(" ") { if (it.label == SwingLabel.NONE) (if (it.type == SwingType.HIGH) "H" else "L") else it.label.name }
        val rH = hs.last().price; val rL = ls.last().price
        val pos = if (rH > rL) ((c.last().close - rL) / (rH - rL) * 100.0).coerceIn(-50.0, 150.0) else null

        var phase = when {
            hMove == SwingMove.UP && lMove == SwingMove.UP -> MarketPhase.UPTREND
            hMove == SwingMove.DOWN && lMove == SwingMove.DOWN -> MarketPhase.DOWNTREND
            hMove == SwingMove.FLAT && lMove == SwingMove.FLAT -> MarketPhase.RANGE
            (hMove == SwingMove.DOWN && lMove == SwingMove.UP) || (hMove == SwingMove.FLAT && lMove == SwingMove.UP) || (hMove == SwingMove.DOWN && lMove == SwingMove.FLAT) -> MarketPhase.CONTRACTING
            else -> MarketPhase.EXPANDING            // HH+LL, HH+flat low, flat high+LL
        }
        var bias: Dir? = when (phase) { MarketPhase.UPTREND -> Dir.BULL; MarketPhase.DOWNTREND -> Dir.BEAR; else -> null }
        if (phase == MarketPhase.CONTRACTING) bias = if (lMove == SwingMove.UP && hMove == SwingMove.FLAT) Dir.BULL else if (hMove == SwingMove.DOWN && lMove == SwingMove.FLAT) Dir.BEAR else null

        // Wyckoff-style context: a range/coil that followed a trend and had a confirmed edge sweep.
        if ((phase == MarketPhase.RANGE || phase == MarketPhase.CONTRACTING) && c.size > 60) {
            val first = minOf(hs.first().index, ls.first().index).coerceIn(30, c.size - 1)
            val before = c[first].close - c[(first - 30).coerceAtLeast(0)].close
            val bullSweep = recentSweeps.any { it.confirmed && it.impliedDir == Dir.BULL }; val bearSweep = recentSweeps.any { it.confirmed && it.impliedDir == Dir.BEAR }
            if (before < -3 * atr && bullSweep) { phase = MarketPhase.ACCUMULATION; bias = Dir.BULL }
            else if (before > 3 * atr && bearSweep) { phase = MarketPhase.DISTRIBUTION; bias = Dir.BEAR }
        }

        val (desc, play) = when (phase) {
            MarketPhase.UPTREND -> "Higher highs and higher lows: buyers control structure." to "Look for pullbacks into a fresh bullish order block/FVG above the last higher low; stop below that low. Do not short without a bearish CHOCH."
            MarketPhase.DOWNTREND -> "Lower highs and lower lows: sellers control structure." to "Look for rallies into a fresh bearish order block/FVG below the last lower high; stop above that high. Do not buy without a bullish CHOCH."
            MarketPhase.RANGE -> "Flat highs and flat lows: price is consolidating between support and resistance." to "Do not trade the middle. Act only after a confirmed liquidity sweep of a range edge with a structure shift back inside, or after a displacement close beyond the range followed by a retest."
            MarketPhase.CONTRACTING -> "Lower highs with higher lows (or one side flat): volatility is compressing into a coil/triangle." to "Wait for a displacement close beyond the coil boundary and a retest. Breakouts against the prior trend fail more often; reduce confidence until confirmed."
            MarketPhase.EXPANDING -> "Higher highs with lower lows: a broadening, volatile structure." to "Unreliable for structure trades: stand aside unless the higher timeframes fully agree, and use wider stops with smaller size."
            MarketPhase.ACCUMULATION -> "A range after a decline, with a confirmed sell-side sweep (spring-like)." to "Bullish setup only after a bullish CHOCH/BOS with displacement; target the top of the range, then opposing liquidity above."
            MarketPhase.DISTRIBUTION -> "A range after an advance, with a confirmed buy-side sweep (upthrust-like)." to "Bearish setup only after a bearish CHOCH/BOS with displacement; target the bottom of the range, then liquidity below."
            MarketPhase.UNDEFINED -> "Undefined." to "Stand aside."
        }
        return PhaseReport(phase, hMove, lMove, seq, rH, rL, pos, desc, play, bias)
    }

    /**
     * Returns a reason to WAIT, or null if the phase allows the entry.
     * RANGE / CONTRACTING need a confirmed edge sweep in the trade direction OR a displacement breakout in that direction; EXPANDING needs full MTF alignment.
     */
    fun gate(p: PhaseReport, dir: Dir, confirmedSweepInDir: Boolean, displacementBreakoutInDir: Boolean, mtfFullInDir: Boolean): String? = when (p.phase) {
        MarketPhase.RANGE, MarketPhase.CONTRACTING ->
            if (confirmedSweepInDir || displacementBreakoutInDir) null
            else "Phase ${p.phase}: wait for a confirmed edge sweep or a displacement breakout before entering"
        MarketPhase.EXPANDING -> if (mtfFullInDir) null else "Phase EXPANDING (broadening): requires full higher-timeframe alignment"
        MarketPhase.UPTREND -> if (dir == Dir.BEAR && !displacementBreakoutInDir) "Phase UPTREND: no short without bearish CHOCH/displacement" else null
        MarketPhase.DOWNTREND -> if (dir == Dir.BULL && !displacementBreakoutInDir) "Phase DOWNTREND: no long without bullish CHOCH/displacement" else null
        MarketPhase.ACCUMULATION -> if (dir == Dir.BEAR && !displacementBreakoutInDir) "Phase ACCUMULATION: no short without bearish displacement" else null
        MarketPhase.DISTRIBUTION -> if (dir == Dir.BULL && !displacementBreakoutInDir) "Phase DISTRIBUTION: no long without bullish displacement" else null
        MarketPhase.UNDEFINED -> "Market phase undefined: not enough structure"
    }
}
