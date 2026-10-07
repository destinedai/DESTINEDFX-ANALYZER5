package com.example.domain.model

data class BacktestTrade(
    val dateString: String,
    val type: String, // "BUY" or "SELL"
    val entry: Double,
    val sl: Double,
    val tp: Double,
    val profit: Double,
    val pips: Double,
    val result: String // "TP HIT" or "SL HIT"
)

data class BacktestReport(
    val strategyName: String,
    val winRate: Double,
    val maxDrawdown: Double,
    val totalTrades: Int,
    val profitFactor: Double,
    val netProfitPercent: Double,
    val trades: List<BacktestTrade>
)

data class AIBotOpinion(
    val botName: String,
    val signal: String, // "BULLISH", "BEARISH", "NEUTRAL"
    val entry: Double,
    val sl: Double,
    val tp: Double,
    val confidence: Int,
    val coreRationale: String
)
