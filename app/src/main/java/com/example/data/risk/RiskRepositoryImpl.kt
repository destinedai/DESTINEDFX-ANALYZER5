package com.example.data.risk

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.RiskProfile
import com.example.domain.repository.RiskRepository
import kotlinx.coroutines.flow.first

private val Context.riskStore by preferencesDataStore("risk_settings")

class RiskRepositoryImpl(
    private val ctx: Context
) : RiskRepository {

    companion object {
        val BAL     = doublePreferencesKey("balance")
        val RISK    = doublePreferencesKey("risk_pct")
        val DD_DAY  = doublePreferencesKey("max_daily_dd")
        val DD_WK   = doublePreferencesKey("max_weekly_dd")
        val CUR_DD  = doublePreferencesKey("current_daily_dd")
        val CUR_WDD = doublePreferencesKey("current_weekly_dd")
        val RR_MIN  = doublePreferencesKey("min_rr")
        val HALT    = booleanPreferencesKey("trading_halted")
        val START_DAY_EQUITY = doublePreferencesKey("start_day_equity")
        val START_WEEK_EQUITY = doublePreferencesKey("start_week_equity")
        val LAST_ROLLOVER = longPreferencesKey("last_rollover")
    }

    override suspend fun getRiskProfile(): RiskProfile {
        val p = ctx.riskStore.data.first()
        return RiskProfile(
            accountBalance              = p[BAL]     ?: 0.0,
            fixedRiskPercentage         = p[RISK]    ?: 1.0,
            maxDailyDrawdownPercentage  = p[DD_DAY]  ?: 3.0,
            maxWeeklyDrawdownPercentage = p[DD_WK]   ?: 6.0,
            currentDailyDrawdown        = p[CUR_DD]  ?: 0.0,
            currentWeeklyDrawdown       = p[CUR_WDD] ?: 0.0,
            minimumRR                   = p[RR_MIN]  ?: 2.0,
            isTradingHalted             = p[HALT]    ?: false
        )
    }

    override suspend fun updateRiskProfile(profile: RiskProfile) {
        ctx.riskStore.edit { p ->
            p[BAL]=profile.accountBalance; p[RISK]=profile.fixedRiskPercentage
            p[DD_DAY]=profile.maxDailyDrawdownPercentage; p[DD_WK]=profile.maxWeeklyDrawdownPercentage
            p[CUR_DD]=profile.currentDailyDrawdown; p[CUR_WDD]=profile.currentWeeklyDrawdown
            p[RR_MIN]=profile.minimumRR; p[HALT]=profile.isTradingHalted
        }
    }

    override suspend fun validateTradeRisk(riskAmount: Double): Boolean {
        val p   = getRiskProfile()
        if (p.isTradingHalted) return false
        val pct = (riskAmount / p.accountBalance) * 100.0
        return pct <= p.fixedRiskPercentage &&
               p.currentDailyDrawdown  < p.maxDailyDrawdownPercentage &&
               p.currentWeeklyDrawdown < p.maxWeeklyDrawdownPercentage
    }

    override suspend fun updateEquityMetrics(currentEquity: Double) {
        val now = System.currentTimeMillis()
        val p = ctx.riskStore.data.first()
        val lastRoll = p[LAST_ROLLOVER] ?: 0L
        
        val startDay = p[START_DAY_EQUITY] ?: 0.0
        val startWeek = p[START_WEEK_EQUITY] ?: 0.0

        val dayChanged = isNewDay(lastRoll, now)
        val weekChanged = isNewWeek(lastRoll, now)

        ctx.riskStore.edit { prefs ->
            val finalStartDay = if (dayChanged || startDay <= 0.0) currentEquity else startDay
            val finalStartWeek = if (weekChanged || startWeek <= 0.0) currentEquity else startWeek
            
            prefs[START_DAY_EQUITY] = finalStartDay
            prefs[START_WEEK_EQUITY] = finalStartWeek
            prefs[LAST_ROLLOVER] = now
            
            prefs[CUR_DD] = if (finalStartDay > 0) ((finalStartDay - currentEquity) / finalStartDay * 100.0).coerceAtLeast(0.0) else 0.0
            prefs[CUR_WDD] = if (finalStartWeek > 0) ((finalStartWeek - currentEquity) / finalStartWeek * 100.0).coerceAtLeast(0.0) else 0.0
        }
    }

    override suspend fun getStartDayEquity(): Double = ctx.riskStore.data.first()[START_DAY_EQUITY] ?: 0.0
    override suspend fun getStartWeekEquity(): Double = ctx.riskStore.data.first()[START_WEEK_EQUITY] ?: 0.0

    private fun isNewDay(last: Long, now: Long): Boolean {
        if (last == 0L) return true
        val calLast = java.util.Calendar.getInstance().apply { timeInMillis = last }
        val calNow = java.util.Calendar.getInstance().apply { timeInMillis = now }
        return calLast.get(java.util.Calendar.DAY_OF_YEAR) != calNow.get(java.util.Calendar.DAY_OF_YEAR) ||
               calLast.get(java.util.Calendar.YEAR) != calNow.get(java.util.Calendar.YEAR)
    }

    private fun isNewWeek(last: Long, now: Long): Boolean {
        if (last == 0L) return true
        val calLast = java.util.Calendar.getInstance().apply { timeInMillis = last }
        val calNow = java.util.Calendar.getInstance().apply { timeInMillis = now }
        return calLast.get(java.util.Calendar.WEEK_OF_YEAR) != calNow.get(java.util.Calendar.WEEK_OF_YEAR) ||
               calLast.get(java.util.Calendar.YEAR) != calNow.get(java.util.Calendar.YEAR)
    }
}
