package com.example.domain.model

data class MarketAnalysisResult(
    val structures: List<MarketStructure>,
    val fractals: Map<Int, String>,
    val fvgs: List<FairValueGap>,
    val orderBlocks: List<OrderBlock>,
    val liquiditySweeps: List<LiquidityZone>,
    val confluenceScore: Int = 0,
    val bias: String = "NEUTRAL"
)
