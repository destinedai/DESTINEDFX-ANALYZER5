package com.example.domain.engine.impl

import com.example.domain.engine.BOSDetector
import com.example.domain.engine.CHOCHDetector
import com.example.domain.engine.MSSDetector
import com.example.domain.model.Candle
import com.example.domain.model.MarketStructure
import com.example.domain.model.StructureType

data class StructureCalibrationParams(
    val pivotBars: Int = 3,
    val requireBodyClose: Boolean = true
)

class StandardStructureShiftDetectors(
    private val p: StructureCalibrationParams = StructureCalibrationParams()
) : BOSDetector, CHOCHDetector, MSSDetector {

    private fun swingHighs(candles: List<Candle>): List<Pair<Int,Double>> {
        val out = mutableListOf<Pair<Int,Double>>()
        for (i in p.pivotBars until candles.size - p.pivotBars) {
            val h = candles[i].high
            if ((i-p.pivotBars until i).all{ candles[it].high < h } &&
                (i+1..i+p.pivotBars).all{ candles[it].high < h }) out.add(i to h)
        }
        return out
    }

    private fun swingLows(candles: List<Candle>): List<Pair<Int,Double>> {
        val out = mutableListOf<Pair<Int,Double>>()
        for (i in p.pivotBars until candles.size - p.pivotBars) {
            val l = candles[i].low
            if ((i-p.pivotBars until i).all{ candles[it].low > l } &&
                (i+1..i+p.pivotBars).all{ candles[it].low > l }) out.add(i to l)
        }
        return out
    }

    // BOS = trend-continuation break of the most recent confirmed pivot
    override suspend fun detectBOS(candles: List<Candle>): List<MarketStructure> {
        val res = mutableListOf<MarketStructure>()
        val highs = swingHighs(candles); val lows = swingLows(candles)
        if (highs.size < 2 || lows.size < 2) return res
        var lastBrokenHLine = Double.MAX_VALUE
        var lastBrokenLLine = 0.0

        for (i in 1 until candles.size) {
            val c = candles[i]
            val ph = highs.filter{it.first < i}.maxByOrNull{it.first}
            val pl = lows.filter{it.first < i}.maxByOrNull{it.first}
            
            if (ph != null && ph.second < lastBrokenHLine) {
                // we have a new lower high or valid high to break
                val broke = if(p.requireBodyClose) c.close > ph.second else c.high > ph.second
                if (broke) { 
                    res.add(MarketStructure(StructureType.BOS, c.timestamp, ph.second, true))
                    lastBrokenHLine = c.high 
                }
            } else if (ph != null && c.high > lastBrokenHLine) {
                lastBrokenHLine = c.high
            }
            
            if (pl != null && pl.second > lastBrokenLLine) {
                val broke = if(p.requireBodyClose) c.close < pl.second else c.low < pl.second
                if (broke) { 
                    res.add(MarketStructure(StructureType.BOS, c.timestamp, pl.second, true))
                    lastBrokenLLine = c.low 
                }
            } else if (pl != null && c.low < lastBrokenLLine) {
                lastBrokenLLine = c.low
            }
        }
        return res
    }

    // CHOCH = counter-trend break after confirmed sequence of opposing pivots
    override suspend fun detectCHOCH(candles: List<Candle>): List<MarketStructure> {
        val res = mutableListOf<MarketStructure>()
        val highs = swingHighs(candles); val lows = swingLows(candles)
        if (highs.size < 3 || lows.size < 3) return res
        // Bearish-to-bullish: lower high sequence then break up
        for (k in 2 until highs.size) {
            if (highs[k-1].second < highs[k-2].second) {
                val lvl = highs[k-1].second; val after = highs[k-1].first
                for (i in after+1 until candles.size) {
                    if (candles[i].close > lvl) { res.add(MarketStructure(StructureType.CHOCH, candles[i].timestamp, lvl, true)); break }
                }
            }
        }
        // Bullish-to-bearish: higher low sequence then break down
        for (k in 2 until lows.size) {
            if (lows[k-1].second > lows[k-2].second) {
                val lvl = lows[k-1].second; val after = lows[k-1].first
                for (i in after+1 until candles.size) {
                    if (candles[i].close < lvl) { res.add(MarketStructure(StructureType.CHOCH, candles[i].timestamp, lvl, true)); break }
                }
            }
        }
        return res.sortedBy { it.timestamp }
    }

    // MSS = first significant CHOCH confirming macro trend reversal
    override suspend fun detectMSS(candles: List<Candle>): List<MarketStructure> =
        StandardStructureShiftDetectors(StructureCalibrationParams(p.pivotBars+2, true))
            .detectCHOCH(candles).map { it.copy(type = StructureType.MSS) }.take(3)
}
