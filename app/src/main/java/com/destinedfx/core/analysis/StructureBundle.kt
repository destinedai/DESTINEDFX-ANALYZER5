package com.destinedfx.core.analysis

data class StructureBundle(
    val orderBlocks: List<OrderBlock>,
    val fvgs: List<FairValueGap>,
    val sweeps: List<LiquiditySweep>,
    val levels: List<LiquidityLevel>,
    val structure: StructureAnalysis?,
    val patterns: List<ChartPattern>
)
