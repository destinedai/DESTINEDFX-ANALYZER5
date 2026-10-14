package com.example.domain.model

/**
 * Single authoritative trade plan model across the entire application:
 * UI, Journal, Bot Center, Execution Engine, Backtest Report, and Optional AI Review.
 * No UI component or subsystem may invent or alter these values.
 */
data class CanonicalTradePlan(
    val symbol: String,
    val broker: String,
    val provider: String,
    val timeframe: String,
    val strategy: String,
    val direction: SignalDirection,
    val status: String,
    val entry: Double,
    val bid: Double,
    val ask: Double,
    val spread: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val rr: Double,
    val riskPercent: Double,
    val riskAmount: Double,
    val positionSize: Double,
    val invalidation: Double,
    val timestamp: Long,
    val marketDataTimestamp: Long,
    val dataHealth: String,
    val reason: String,
    val strategyVersion: String,
    val confluenceScore: Int,
    val targets: List<CanonicalTarget> = emptyList(),
    val newsStatus: String = "CLEAR"
)

data class CanonicalTarget(
    val label: String,
    val price: Double,
    val rr: Double,
    val source: String
)
