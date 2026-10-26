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
        version = "1.0.0",
        isImplemented = true,
        description = "Killzone-specific liquidity sweep and 1m-5m FVG displacement model with Consequent Encroachment (CE 50%) entries.",
        rulesSummary = "Killzone timing validation (London/NY AM/PM), liquidity pool sweep confirmation, FVG displacement trigger, minimum 1:2.0 R:R."
    )

    val WYCKOFF_METHOD = StrategyDefinition(
        id = "wyckoff-schematics",
        name = "Wyckoff Method & Schematics",
        version = "1.0.0",
        isImplemented = true,
        description = "Institutional Accumulation and Distribution schematics, Phase C Springs and UTADs with volume absorption confirmation.",
        rulesSummary = "Phase C Spring/UTAD false breakout detection, institutional volume climax absorption, Sign of Strength (SOS)/Weakness (SOW) breakout targeting opposite TR."
    )

    val SUPPLY_AND_DEMAND = StrategyDefinition(
        id = "supply-and-demand",
        name = "Supply & Demand Zones",
        version = "1.0.0",
        isImplemented = true,
        description = "Rally-Base-Drop (RBD), Drop-Base-Rally (DBR), RBR, and DBD institutional imbalances with unmitigated zone mitigation tracking.",
        rulesSummary = "Freshness scoring (untouched base), explosive departure ratio (>= 1.5x ATR), proximal entry with distal SL + ATR buffer, targeting opposite fresh imbalance."
    )

    val BREAKOUT_MOMENTUM = StrategyDefinition(
        id = "breakout-momentum",
        name = "Volatility Breakout",
        version = "1.0.0",
        isImplemented = true,
        description = "20-period Donchian Channel volatility breakouts paired with ATR volatility expansion and relative volume surges.",
        rulesSummary = "Donchian 20-period channel breakout, ATR expansion (> 1.25x 20-bar average), relative volume surge (>= 1.5x), SL at channel median, 2.5R extension target."
    )

    val MEAN_REVERSION = StrategyDefinition(
        id = "mean-reversion",
        name = "Statistical Mean Reversion",
        version = "1.0.0",
        isImplemented = true,
        description = "Bollinger Bands (20, 2.0σ) extreme boundary piercing with RSI oversold (<30) / overbought (>70) and candlestick reversal triggers.",
        rulesSummary = "2.0 standard deviation outer band pierce, RSI extreme (<30 / >70), candlestick price rejection confirmation, targeting 20 SMA mean with minimum 2.0 R:R."
    )

    val TREND_FOLLOWING = StrategyDefinition(
        id = "trend-following",
        name = "Multi-EMA Trend Following",
        version = "1.0.0",
        isImplemented = true,
        description = "Triple EMA stack alignment (20 > 50 > 200) with dynamic pullbacks into the value zone and trend continuation momentum.",
        rulesSummary = "Triple EMA hierarchy (20/50/200), retracement into 20-50 EMA value zone, continuation rejection bar with RSI support, SL behind EMA 50, trailing trend target."
    )

    val LIQUIDITY_STOP_HUNTS = StrategyDefinition(
        id = "liquidity-stop-hunts",
        name = "SMC Liquidity & Stop Hunts",
        version = "1.0.0",
        isImplemented = true,
        description = "Identifies equal highs/lows (EQH/EQL) and trendline liquidity pools. Anticipates liquidity sweeps over breakout traps and triggers reversal entries with tight invalidation.",
        rulesSummary = "Equal highs/lows detection, liquidity pool sweep anticipation, wick raid rejection bar trigger, tight SL behind sweep wick, minimum 1:2.5 R:R target."
    )

    val allStrategies = listOf(
        SMC_CONFLUENCE,
        LIQUIDITY_STOP_HUNTS,
        ICT_SILVER_BULLET,
        WYCKOFF_METHOD,
        SUPPLY_AND_DEMAND,
        BREAKOUT_MOMENTUM,
        MEAN_REVERSION,
        TREND_FOLLOWING
    )
}
