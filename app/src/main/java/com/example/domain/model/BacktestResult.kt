package com.example.domain.model

data class BacktestResult(
    val historicalReplayId: String,
    val totalTrades: Int,
    val winRatePercentage: Double,
    val maxDrawdownPercentage: Double,
    val profitFactor: Double,
    val sharpeRatio: Double,
    val expectancy: Double,
    val netProfit: Double,
    val verdict: String = "INSUFFICIENT_SAMPLE",
    val verdictText: String = "",
    val startingBalance: Double = 0.0,
    val strategyVersion: String = "",
    /** true = figures come from the out-of-sample segment only. In-sample numbers are never reported as evidence. */
    val outOfSample: Boolean = false
)
