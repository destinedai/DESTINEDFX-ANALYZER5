package com.example.domain.engine.impl

import com.example.domain.engine.OrderBlockDetector
import com.example.domain.model.Candle
import com.example.domain.model.OBType
import com.example.domain.model.OrderBlock
import kotlin.math.abs

data class OBCalibrationParams(
    val minDisplacementMultiplier: Double = 1.8,
    val includeWicksInBlocks: Boolean = true,
    val checkMitigation: Boolean = true
)

class StandardOrderBlockDetector(
    private val params: OBCalibrationParams = OBCalibrationParams()
) : OrderBlockDetector {

    private data class TrackedOB(var block: OrderBlock, val spawnIndex: Int)

    override suspend fun detectOrderBlocks(candles: List<Candle>): List<OrderBlock> {
        val blocks = mutableListOf<TrackedOB>()
        if (candles.size < 2) return emptyList()

        val avgBodySize = candles.map { abs(it.close - it.open) }.average()

        for (i in 1 until candles.size) {
            val current = candles[i]
            val prev = candles[i - 1]
            val bodySize = abs(current.close - current.open)

            // 1. Detect momentum/displacement candle
            if (bodySize > avgBodySize * params.minDisplacementMultiplier) {
                val isBullishImbalance = current.close > current.open
                
                if (isBullishImbalance && prev.close <= prev.open) {
                    val topPrice = if (params.includeWicksInBlocks) prev.high else maxOf(prev.open, prev.close)
                    val bottomPrice = if (params.includeWicksInBlocks) prev.low else minOf(prev.open, prev.close)
                    val score = minOf(100, (bodySize / avgBodySize * 30).toInt())
                    blocks.add(TrackedOB(OrderBlock(OBType.BULLISH, topPrice, bottomPrice, score, false), i))
                } else if (!isBullishImbalance && prev.close >= prev.open) {
                    val topPrice = if (params.includeWicksInBlocks) prev.high else maxOf(prev.open, prev.close)
                    val bottomPrice = if (params.includeWicksInBlocks) prev.low else minOf(prev.open, prev.close)
                    val score = minOf(100, (bodySize / avgBodySize * 30).toInt())
                    blocks.add(TrackedOB(OrderBlock(OBType.BEARISH, topPrice, bottomPrice, score, false), i))
                }
            }

            // 2. Track Mitigation
            if (params.checkMitigation) {
                for (tracked in blocks) {
                    if (tracked.spawnIndex >= i) continue
                    if (tracked.block.mitigated) continue

                    val ob = tracked.block
                    if (ob.type == OBType.BULLISH && current.low <= ob.topPrice) {
                         tracked.block = ob.copy(mitigated = true)
                    } else if (ob.type == OBType.BEARISH && current.high >= ob.bottomPrice) {
                         tracked.block = ob.copy(mitigated = true)
                    }
                }
            }
        }
        return blocks.map { it.block }
    }
}
