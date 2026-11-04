package com.example.domain.model

enum class SignalDirection { LONG, SHORT, WAIT }

/** Python-side research output only; it is never an execution or risk approval. */
data class PythonAnalysisReport(
    val state: String,
    val runtimeAvailable: Boolean,
    val direction: String,
    val symbol: String,
    val strategyId: String,
    val strategyVersion: String,
    val timeframe: String,
    val candleTimestampMs: Long,
    val source: String,
    val retrievedAtMs: Long,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val rewardToRisk: Double? = null,
    val scoreBasis: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    val rejectionReasons: List<String> = emptyList(),
    val reason: String = "",
    val conflict: Boolean = false,
    val executable: Boolean = false
)

data class ProfitZone(
    val title: String,
    val price: Double,
    val riskReward: Double,
    val pipsYield: Double,
    val probability: String,
    val algorithmicRationale: String
)

data class TradeSignal(
    val id: String,
    val symbol: String,
    val timeframe: String,
    val direction: SignalDirection,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val confidenceScore: Int, // 0 to 100
    val rationale: String,
    val timestamp: Long,
    val invalidationLevel: Double = 0.0,
    val entryReason: String = "",
    val stopLossReason: String = "",
    val takeProfitReason: String = "",
    val nearestHighYieldZone: String = "",
    val profitZones: List<ProfitZone> = emptyList(),
    val orderBlocks: List<OrderBlock> = emptyList(),
    val keyLevels: List<String> = emptyList(),
    val strategyScores: Map<String, Int> = emptyMap(),
    val fvgs: List<FairValueGap> = emptyList(),
    /** Explicit state: STRONG_BUY, BUY, WEAK_BUY, WAIT, WEAK_SELL, SELL, STRONG_SELL, BLOCKED, DATA_UNAVAILABLE */
    val signalState: String = "DATA_UNAVAILABLE",
    /** true only when data is LIVE with a real two-sided quote and no blocker applies. DELAYED/analysis-only signals are false. */
    val executable: Boolean = false,
    val blockReasons: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
    val dataSource: String = "",
    val dataHealth: String = "",
    val mtfSummary: String = "",
    val mtfState: String = "",
    val mtfPerTimeframe: Map<String, String> = emptyMap(),
    val spreadText: String = "",
    val sessionLabel: String = "",
    val newsStatus: String = "",
    val knowledge: List<String> = emptyList(),
    val strategyVersion: String = "",
    val scoreLabel: String = "CONFLUENCE SCORE (rules-based, NOT a win probability)",
    val analysisLoadMs: Long = 0L,
    val analysisComputeMs: Long = 0L,
    val analysisTotalMs: Long = 0L,
    val cacheHit: Boolean = false,
    val pythonAnalysis: PythonAnalysisReport? = null
)
