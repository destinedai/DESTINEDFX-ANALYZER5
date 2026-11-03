package com.destinedfx.core.strategy

/** A status label is intentionally distinct from executable/bot eligibility. */
enum class StrategyCapabilityStatus {
    IMPLEMENTED,
    CANDIDATE_ONLY,
    NOT_IMPLEMENTED
}

/**
 * One source of truth for strategy identity and parity across Python candidates,
 * the Kotlin signal engine, final validation, and Bot routing.
 *
 * Python entries are analysis-only by contract; they never authorize or size orders.
 */
data class StrategyDefinition(
    val id: String,
    val name: String,
    val version: String,
    val pythonStatus: StrategyCapabilityStatus,
    val pythonVersion: String?,
    val kotlinStatus: StrategyCapabilityStatus,
    val kotlinFinalValidation: Boolean,
    val botExecutionEligible: Boolean,
    val description: String,
    val rulesSummary: String,
    val requiredEvidence: List<String>,
    val testCoverage: List<String>
) {
    /** Kept for older presentation/call sites; this means live Kotlin analysis is implemented. */
    val isImplemented: Boolean
        get() = kotlinStatus == StrategyCapabilityStatus.IMPLEMENTED && kotlinFinalValidation
}

object StrategyRegistry {
    /** IDs accepted by managed execution surfaces; must be subset of final-validated Kotlin rulesets. */
    val managedStrategyIds: Set<String>
        get() = allStrategies.filter { it.botExecutionEligible }.mapTo(linkedSetOf()) { it.id }

    val SMC_CONFLUENCE = StrategyDefinition(
        id = "smc-confluence-1.0.0", name = "Smart Money Concepts (SMC)", version = "1.0.0",
        pythonStatus = StrategyCapabilityStatus.CANDIDATE_ONLY, pythonVersion = "python-smc-confluence-1.0.0",
        kotlinStatus = StrategyCapabilityStatus.IMPLEMENTED, kotlinFinalValidation = true, botExecutionEligible = true,
        description = "Institutional market structure (BOS/CHoCH), unmitigated Order Blocks, Fair Value Gaps, liquidity sweeps, and multi-timeframe alignment.",
        rulesSummary = "Deterministic confluence scoring: Min R:R 2.0, structural SL with ATR buffer, multi-timeframe confirmation, high-impact news filtering.",
        requiredEvidence = listOf("Validated closed candles and structure", "Confirmed directional structure/sweep evidence", "Complete strict MTF context", "Valid quote, news/session gates and >= 2R plan"),
        testCoverage = listOf("testStrategy1_SMC_ConfluenceEngine", "SignalGenerationUseCaseImpl final-plan validation")
    )

    val LIQUIDITY_STOP_HUNTS = StrategyDefinition(
        id = "liquidity-stop-hunts", name = "SMC Liquidity & Stop Hunts", version = "1.0.0",
        pythonStatus = StrategyCapabilityStatus.NOT_IMPLEMENTED, pythonVersion = null,
        kotlinStatus = StrategyCapabilityStatus.IMPLEMENTED, kotlinFinalValidation = true, botExecutionEligible = true,
        description = "Identifies equal highs/lows (EQH/EQL) and trendline liquidity pools. Triggers reversal entries only after a confirmed sweep and rejection.",
        rulesSummary = "Equal highs/lows detection, confirmed wick raid and subsequent reversal structure, tight invalidation, minimum 1:2.5 R:R target.",
        requiredEvidence = listOf("Recent confirmed directional sweep", "Subsequent reversal-structure confirmation", "Structural stop and valid >= 2R target"),
        testCoverage = listOf("liquidityStopHuntWithoutSweepCannotEarnRejectionCreditOrProducePlan", "SignalGenerationUseCaseImpl final-plan validation")
    )

    val ICT_SILVER_BULLET = StrategyDefinition(
        id = "ict-silver-bullet", name = "ICT Silver Bullet", version = "1.0.0",
        pythonStatus = StrategyCapabilityStatus.CANDIDATE_ONLY, pythonVersion = "python-ict-silver-bullet-1.0.0",
        kotlinStatus = StrategyCapabilityStatus.IMPLEMENTED, kotlinFinalValidation = true, botExecutionEligible = true,
        description = "Killzone-specific liquidity sweep and 1m-5m FVG displacement model with Consequent Encroachment (CE 50%) entries.",
        rulesSummary = "Killzone timing validation, liquidity-pool sweep confirmation, FVG displacement trigger, minimum 1:2.0 R:R.",
        requiredEvidence = listOf("Confirmed liquidity sweep and displacement", "Recent unfilled directional FVG", "Price near CE 50% during a supported killzone", "Valid >= 2R plan"),
        testCoverage = listOf("testStrategy2_ICT_SilverBullet_Components", "ICT killzone/FVG plan gates", "SignalGenerationUseCaseImpl final-plan validation")
    )

    val WYCKOFF_METHOD = StrategyDefinition(
        id = "wyckoff-schematics", name = "Wyckoff Method & Schematics", version = "1.0.0",
        pythonStatus = StrategyCapabilityStatus.CANDIDATE_ONLY, pythonVersion = "python-wyckoff-schematics-1.0.0",
        kotlinStatus = StrategyCapabilityStatus.IMPLEMENTED, kotlinFinalValidation = true, botExecutionEligible = true,
        description = "Institutional Accumulation and Distribution schematics, Phase C Springs and UTADs with volume absorption confirmation.",
        rulesSummary = "Phase C Spring/UTAD false-breakout detection, volume-climax absorption, SOS/SOW breakout targeting the opposite range boundary.",
        requiredEvidence = listOf("Confirmed range/phase structure", "Spring or UTAD evidence with volume absorption", "Valid structural stop and >= 2R target"),
        testCoverage = listOf("testStrategy3_Wyckoff_Schematics_Components", "Wyckoff range-boundary target tests", "SignalGenerationUseCaseImpl final-plan validation")
    )

    val SUPPLY_AND_DEMAND = StrategyDefinition(
        id = "supply-and-demand", name = "Supply & Demand Zones", version = "1.0.0",
        pythonStatus = StrategyCapabilityStatus.CANDIDATE_ONLY, pythonVersion = "python-supply-and-demand-1.0.0",
        kotlinStatus = StrategyCapabilityStatus.IMPLEMENTED, kotlinFinalValidation = true, botExecutionEligible = true,
        description = "Rally-Base-Drop (RBD), Drop-Base-Rally (DBR), RBR, and DBD imbalances with unmitigated-zone tracking.",
        rulesSummary = "Freshness scoring, explosive departure ratio, proximal entry with distal SL + ATR buffer, and opposing fresh imbalance target.",
        requiredEvidence = listOf("Fresh, unmitigated zone", "Confirmed departure/imbalance", "Structural invalidation and valid >= 2R plan"),
        testCoverage = listOf("testStrategy4_SupplyAndDemand_Components", "Supply/demand target rules", "SignalGenerationUseCaseImpl final-plan validation")
    )

    val BREAKOUT_MOMENTUM = StrategyDefinition(
        id = "breakout-momentum", name = "Breakout Momentum", version = "1.0.0",
        pythonStatus = StrategyCapabilityStatus.CANDIDATE_ONLY, pythonVersion = "python-breakout-momentum-1.0.0",
        kotlinStatus = StrategyCapabilityStatus.IMPLEMENTED, kotlinFinalValidation = true, botExecutionEligible = true,
        description = "Closed-candle channel breakout with momentum confirmation, volatility-aware invalidation, and a defined extension target.",
        rulesSummary = "Donchian/channel breakout evidence, momentum confirmation, structural SL, 2.5R volatility-extension target.",
        requiredEvidence = listOf("Confirmed closed-candle breakout", "Momentum/volatility confirmation", "Valid structural stop and 2.5R extension plan"),
        testCoverage = listOf("testStrategy5_BreakoutMomentum_Components", "SignalGenerationUseCaseImpl final-plan validation")
    )

    val VOLATILITY_BREAKOUT = StrategyDefinition(
        id = "volatility-breakout", name = "Volatility Breakout", version = "1.0.0",
        pythonStatus = StrategyCapabilityStatus.CANDIDATE_ONLY, pythonVersion = "python-volatility-breakout-1.0.0",
        kotlinStatus = StrategyCapabilityStatus.CANDIDATE_ONLY, kotlinFinalValidation = false, botExecutionEligible = false,
        description = "Python analysis-only candidate using range expansion; it is not currently implemented in the Kotlin final-validation path.",
        rulesSummary = "Range expansion versus prior ATR with directional close; candidate only, not an order instruction.",
        requiredEvidence = listOf("Validated closed candles", "Range expansion versus prior ATR", "Independent Kotlin execution implementation and final-validation coverage are still required"),
        testCoverage = listOf("test_each_known_strategy_has_versioned_positive_evidence_and_is_non_executable", "test_each_known_strategy_negative_case_waits_with_rejection_reason")
    )

    val MEAN_REVERSION = StrategyDefinition(
        id = "mean-reversion", name = "Statistical Mean Reversion", version = "1.0.0",
        pythonStatus = StrategyCapabilityStatus.CANDIDATE_ONLY, pythonVersion = "python-mean-reversion-1.0.0",
        kotlinStatus = StrategyCapabilityStatus.IMPLEMENTED, kotlinFinalValidation = true, botExecutionEligible = true,
        description = "Bollinger Bands (20, 2.0σ) extreme boundary piercing with RSI extremes and closed-candle reversal evidence.",
        rulesSummary = "2σ outer-band evidence, RSI extreme, reversal confirmation, and target at the 20-period mean with minimum 2.0 R:R.",
        requiredEvidence = listOf("Closed-candle band extreme and RSI exhaustion", "Reversal evidence beyond candle colour alone", "20-period mean target meeting >= 2R"),
        testCoverage = listOf("testStrategy6_MeanReversion_Components", "meanReversionDoesNotAwardReversalCreditForCandleColorAlone", "testMeanReversionPlanTargetsTheTwentyPeriodMeanAndRequiresTwoR")
    )

    val TREND_FOLLOWING = StrategyDefinition(
        id = "trend-following", name = "Multi-EMA Trend Following", version = "1.0.0",
        pythonStatus = StrategyCapabilityStatus.CANDIDATE_ONLY, pythonVersion = "python-trend-following-1.0.0",
        kotlinStatus = StrategyCapabilityStatus.IMPLEMENTED, kotlinFinalValidation = true, botExecutionEligible = true,
        description = "Triple EMA stack alignment with pullbacks into the value zone and trend-continuation evidence.",
        rulesSummary = "EMA 20/50/200 hierarchy, pullback into the value zone, continuation rejection, structural SL, and trend target.",
        requiredEvidence = listOf("EMA stack and trend agreement", "Value-zone pullback", "Continuation evidence", "Valid structural stop and >= 2R plan"),
        testCoverage = listOf("testStrategy7_TrendFollowing_Components", "SignalGenerationUseCaseImpl final-plan validation")
    )

    /** Capability matrix. Python versions produce only non-executable candidates. */
    val allStrategies = listOf(
        SMC_CONFLUENCE,
        LIQUIDITY_STOP_HUNTS,
        ICT_SILVER_BULLET,
        WYCKOFF_METHOD,
        SUPPLY_AND_DEMAND,
        BREAKOUT_MOMENTUM,
        VOLATILITY_BREAKOUT,
        MEAN_REVERSION,
        TREND_FOLLOWING
    )
}
