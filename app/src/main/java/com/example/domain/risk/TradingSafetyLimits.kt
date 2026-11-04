package com.example.domain.risk

/**
 * Single application-side source of non-negotiable trading limits.
 * The MT5 EA independently enforces the same numeric ceilings in MQL5.
 */
object TradingSafetyLimits {
    const val MAX_TRADE_RISK_PERCENT = 1.0
    const val MIN_REWARD_TO_RISK = 2.0
    const val MAX_DAILY_LOSS_PERCENT = 2.0
    const val MAX_WEEKLY_LOSS_PERCENT = 6.0
    const val DAILY_WARNING_80_PERCENT = 80.0
    const val DAILY_WARNING_90_PERCENT = 90.0
    const val MAX_CONCURRENT_POSITIONS = 2
}
