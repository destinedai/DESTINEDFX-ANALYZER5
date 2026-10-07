package com.example.domain.repository

import com.example.domain.model.RiskProfile

interface RiskRepository {
    suspend fun getRiskProfile(): RiskProfile
    suspend fun updateRiskProfile(profile: RiskProfile)
    suspend fun validateTradeRisk(riskAmount: Double): Boolean
    suspend fun updateEquityMetrics(currentEquity: Double)
    suspend fun getStartDayEquity(): Double
    suspend fun getStartWeekEquity(): Double
}
