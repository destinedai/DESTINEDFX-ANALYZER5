package com.destinedfx.core.strategy

data class StrategyDefinition(
    val id: String,
    val name: String,
    val version: String,
    val isImplemented: Boolean,
    val description: String,
    val rulesSummary: String
)

object StrategyRegistry {
    val SMC_CONFLUENCE = StrategyDefinition(
        id = "smc-confluence-1.0.0",
        name = "Smart Money Concepts (SMC)",
        version = "1.0.0",
        isImplemented = true,
        description = "Institutional market structure (BOS/CHoCH), unmitigated Order Blocks, Fair Value Gaps, liquidity sweeps, and multi-timeframe alignment.",
        rulesSummary = "Deterministic confluence scoring: Min R:R 2.0, structural SL with ATR buffer, multi-timeframe confirmation, high-impact news filtering."
    )

    val ICT_SILVER_BULLET = StrategyDefinition(
        id = "ict-silver-bullet",
        name = "ICT Silver Bullet",
        version = "N/A",
        isImplemented = false,
        description = "Killzone-specific liquidity sweep and 1m-5m FVG displacement model.",
        rulesSummary = "Not implemented."
    )

    val WYCKOFF_METHOD = StrategyDefinition(
        id = "wyckoff-schematics",
        name = "Wyckoff Method & Schematics",
        version = "N/A",
        isImplemented = false,
        description = "Accumulation and distribution schematic phases with springs and upthrusts.",
        rulesSummary = "Not implemented."
    )

    val SUPPLY_AND_DEMAND = StrategyDefinition(
        id = "supply-and-demand",
        name = "Supply & Demand Zones",
        version = "N/A",
        isImplemented = false,
        description = "Rally-Base-Drop and Drop-Base-Rally institutional imbalance zones.",
        rulesSummary = "Not implemented."
    )

    val BREAKOUT_MOMENTUM = StrategyDefinition(
        id = "breakout-momentum",
        name = "Volatility Breakout",
        version = "N/A",
        isImplemented = false,
        description = "Donchian channel and ATR expansion breakouts.",
        rulesSummary = "Not implemented."
    )

    val MEAN_REVERSION = StrategyDefinition(
        id = "mean-reversion",
        name = "Statistical Mean Reversion",
        version = "N/A",
        isImplemented = false,
        description = "Bollinger Band & RSI extreme deviation reversion.",
        rulesSummary = "Not implemented."
    )

    val TREND_FOLLOWING = StrategyDefinition(
        id = "trend-following",
        name = "Multi-EMA Trend Following",
        version = "N/A",
        isImplemented = false,
        description = "Moving average pullback and continuation.",
        rulesSummary = "Not implemented."
    )

    val allStrategies = listOf(
        SMC_CONFLUENCE,
        ICT_SILVER_BULLET,
        WYCKOFF_METHOD,
        SUPPLY_AND_DEMAND,
        BREAKOUT_MOMENTUM,
        MEAN_REVERSION,
        TREND_FOLLOWING
    )
}
