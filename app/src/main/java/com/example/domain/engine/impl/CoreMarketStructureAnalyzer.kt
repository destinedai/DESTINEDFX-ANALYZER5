package com.example.domain.engine.impl

import com.example.domain.engine.BOSDetector
import com.example.domain.engine.CHOCHDetector
import com.example.domain.engine.MSSDetector
import com.example.domain.engine.FairValueGapDetector
import com.example.domain.engine.FractalDetector
import com.example.domain.engine.OrderBlockDetector
import com.example.domain.engine.LiquiditySweepDetector
import com.example.domain.engine.MarketStructureAnalyzer
import com.example.domain.model.Candle
import com.example.domain.model.MarketStructure
import com.example.domain.model.MarketAnalysisResult

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.example.domain.model.FVGType
import com.example.domain.model.OBType
import com.example.domain.model.LiquidityType

class CoreMarketStructureAnalyzer(
    private val fractalDetector: FractalDetector,
    private val bosDetector: BOSDetector,
    private val chochDetector: CHOCHDetector,
    private val mssDetector: MSSDetector,
    private val fvgDetector: FairValueGapDetector,
    private val obDetector: OrderBlockDetector,
    private val liquiditySweepDetector: LiquiditySweepDetector
) : MarketStructureAnalyzer {
    
    override suspend fun analyzeStructure(candles: List<Candle>): MarketAnalysisResult = coroutineScope {
        // Run all detectors in parallel using async
        val bosDeferred = async { bosDetector.detectBOS(candles) }
        val chochDeferred = async { chochDetector.detectCHOCH(candles) }
        val mssDeferred = async { mssDetector.detectMSS(candles) }
        val fractalsDeferred = async { fractalDetector.detectFractals(candles) }
        
        // SMC Core components
        val fvgsDeferred = async { fvgDetector.detectFVGs(candles) }
        val obDeferred = async { obDetector.detectOrderBlocks(candles) }
        val sweepsDeferred = async { liquiditySweepDetector.detectSweeps(candles) }

        val structures = mutableListOf<MarketStructure>()
        structures.addAll(bosDeferred.await())
        structures.addAll(chochDeferred.await())
        structures.addAll(mssDeferred.await())
        
        val fractals = fractalsDeferred.await()
        val fvgs = fvgsDeferred.await()
        val orderBlocks = obDeferred.await()
        val liquiditySweeps = sweepsDeferred.await()

        // Consolidated SMC Confluence Score Calculation
        // A simple algorithm scoring Bullish vs Bearish confluence
        var score = 0 // Positive = Bullish, Negative = Bearish
        var totalConfluenceWeight = 0

        // 1. Evaluate Liquidity Sweeps
        val activeSweeps = liquiditySweeps.takeLast(3) // Look at recent sweeps
        for (sweep in activeSweeps) {
            if (sweep.type == LiquidityType.SSL) {
                // Swept Sell-Side Liquidity -> Bullish
                score += 30
            } else if (sweep.type == LiquidityType.BSL) {
                // Swept Buy-Side Liquidity -> Bearish
                score -= 30
            }
            totalConfluenceWeight += 30
        }

        // 2. Evaluate Order Blocks
        val recentOBs = orderBlocks.filter { !it.mitigated }.takeLast(3)
        for (ob in recentOBs) {
            if (ob.type == OBType.BULLISH) {
                score += 25
            } else if (ob.type == OBType.BEARISH) {
                score -= 25
            }
            totalConfluenceWeight += 25
        }

        // 3. Evaluate FVGs
        val unmitigatedFVGs = fvgs.filter { !it.mitigated }.takeLast(4)
        for (fvg in unmitigatedFVGs) {
            if (fvg.type == FVGType.BULLISH) {
                score += 15
            } else if (fvg.type == FVGType.BEARISH) {
                score -= 15
            }
            totalConfluenceWeight += 15
        }

        // Normalize score between 0 and 100 for absolute confidence, and determine bias
        val normalizedConfidence = if (totalConfluenceWeight > 0) {
            (Math.abs(score).toDouble() / totalConfluenceWeight * 100).toInt()
        } else {
            0
        }

        val bias = when {
            score > 20 -> "BULLISH"
            score < -20 -> "BEARISH"
            else -> "NEUTRAL"
        }

        MarketAnalysisResult(
            structures = structures,
            fractals = fractals,
            fvgs = fvgs,
            orderBlocks = orderBlocks,
            liquiditySweeps = liquiditySweeps,
            confluenceScore = normalizedConfidence,
            bias = bias
        )
    }
}
