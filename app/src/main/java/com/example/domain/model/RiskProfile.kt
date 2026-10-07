package com.example.domain.model

data class RiskProfile(
    val accountBalance: Double,
    val fixedRiskPercentage: Double,
    val maxDailyDrawdownPercentage: Double,
    val maxWeeklyDrawdownPercentage: Double,
    val currentDailyDrawdown: Double,
    val currentWeeklyDrawdown: Double,
    val minimumRR: Double,
    val isTradingHalted: Boolean
)
