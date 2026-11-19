package com.example.data.risk

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.RiskProfile
import com.example.domain.repository.RiskRepository
import com.example.security.SecretsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Legacy plaintext DataStore, used only as a one-time migration source. */
internal val Context.riskSettingsStore by preferencesDataStore("risk_settings")

class RiskRepositoryImpl(
    private val ctx: Context,
    private val secrets: SecretsManager = SecretsManager(ctx.applicationContext)
) : RiskRepository {

    companion object {
        val BAL = doublePreferencesKey("balance")
        val RISK = doublePreferencesKey("risk_pct")
        val DD_DAY = doublePreferencesKey("max_daily_dd")
        val DD_WK = doublePreferencesKey("max_weekly_dd")
        val CUR_DD = doublePreferencesKey("current_daily_dd")
        val CUR_WDD = doublePreferencesKey("current_weekly_dd")
        val RR_MIN = doublePreferencesKey("min_rr")
        val HALT = booleanPreferencesKey("trading_halted")
        val START_DAY_EQUITY = doublePreferencesKey("start_day_equity")
        val START_WEEK_EQUITY = doublePreferencesKey("start_week_equity")
        val LAST_ROLLOVER = longPreferencesKey("last_rollover")
    }

    private val migrationMutex = Mutex()

    private fun defaultProfile() = RiskProfile(
        accountBalance = 0.0,
        fixedRiskPercentage = 1.0,
        maxDailyDrawdownPercentage = 3.0,
        maxWeeklyDrawdownPercentage = 6.0,
        currentDailyDrawdown = 0.0,
        currentWeeklyDrawdown = 0.0,
        minimumRR = 2.0,
        isTradingHalted = false
    )

    /**
     * Migrate old risk settings once, only after their encrypted replacement and equity
     * baselines have both committed. When Keystore is unavailable the old file is preserved
     * untouched, and all risk/account reads return safe defaults.
     */
    private suspend fun ensureEncryptedMigration() {
        if (!secrets.isSecureStorageAvailable) return
        migrationMutex.withLock {
            val legacy = ctx.riskSettingsStore.data.first()
            if (legacy.isEmpty()) return@withLock

            if (secrets.getEncryptedRiskProfile() == null) {
                fun finiteNonNegative(key: Preferences.Key<Double>, fallback: Double): Double =
                    (legacy[key] ?: fallback).takeIf { it.isFinite() && it >= 0.0 } ?: fallback
                val migrated = RiskProfile(
                    accountBalance = finiteNonNegative(BAL, 0.0),
                    fixedRiskPercentage = (legacy[RISK] ?: 1.0).takeIf { it.isFinite() }?.coerceIn(0.1, 1.0) ?: 1.0,
                    maxDailyDrawdownPercentage = (legacy[DD_DAY] ?: 3.0).takeIf { it.isFinite() }?.coerceIn(0.1, 100.0) ?: 3.0,
                    maxWeeklyDrawdownPercentage = (legacy[DD_WK] ?: 6.0).takeIf { it.isFinite() }?.coerceIn(0.1, 100.0) ?: 6.0,
                    currentDailyDrawdown = finiteNonNegative(CUR_DD, 0.0),
                    currentWeeklyDrawdown = finiteNonNegative(CUR_WDD, 0.0),
                    minimumRR = maxOf(2.0, (legacy[RR_MIN] ?: 2.0).takeIf { it.isFinite() } ?: 2.0),
                    isTradingHalted = legacy[HALT] ?: false
                )
                check(secrets.saveEncryptedRiskProfile(migrated)) {
                    "Risk profile could not be copied to encrypted storage; legacy data was preserved."
                }
            }

            val encrypted = secrets.getEncryptedRiskProfile()
                ?: error("Encrypted risk profile is unavailable; legacy data was preserved.")
            val startDay = (legacy[START_DAY_EQUITY] ?: 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val startWeek = (legacy[START_WEEK_EQUITY] ?: 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val lastRollover = (legacy[LAST_ROLLOVER] ?: 0L).coerceAtLeast(0L)
            check(secrets.saveRiskEquityRollover(
                startDay, startWeek, lastRollover,
                encrypted.currentDailyDrawdown, encrypted.currentWeeklyDrawdown
            )) {
                "Risk equity baselines could not be copied to encrypted storage; legacy data was preserved."
            }

            ctx.riskSettingsStore.edit { it.clear() }
        }
    }

    override suspend fun getRiskProfile(): RiskProfile {
        if (!secrets.isSecureStorageAvailable) return defaultProfile()
        ensureEncryptedMigration()
        return secrets.getEncryptedRiskProfile() ?: defaultProfile()
    }

    override suspend fun updateRiskProfile(profile: RiskProfile) {
        if (!secrets.isSecureStorageAvailable) return
        ensureEncryptedMigration()
        check(secrets.saveEncryptedRiskProfile(profile)) {
            "Risk profile could not be saved in Android Keystore-backed encrypted storage."
        }
    }

    override suspend fun validateTradeRisk(riskAmount: Double): Boolean {
        // This legacy interface carries no broker identity, timestamp or provenance.
        // A stored risk-profile balance is not proof of current broker equity.
        return false
    }

    override suspend fun updateEquityMetrics(currentEquity: Double) {
        if (!secrets.isSecureStorageAvailable || !currentEquity.isFinite() || currentEquity <= 0.0) return
        ensureEncryptedMigration()
        if (secrets.getEncryptedRiskProfile() == null) {
            check(secrets.saveEncryptedRiskProfile(defaultProfile())) {
                "Default risk profile could not be initialized in encrypted storage."
            }
        }

        val now = System.currentTimeMillis()
        val lastRoll = secrets.getRiskLastRolloverMillis()
        val startDay = secrets.getRiskStartDayEquity()
        val startWeek = secrets.getRiskStartWeekEquity()
        val dayChanged = isNewDay(lastRoll, now)
        val weekChanged = isNewWeek(lastRoll, now)
        val finalStartDay = if (dayChanged || startDay <= 0.0) currentEquity else startDay
        val finalStartWeek = if (weekChanged || startWeek <= 0.0) currentEquity else startWeek
        val dailyDrawdown = if (finalStartDay > 0.0)
            ((finalStartDay - currentEquity) / finalStartDay * 100.0).coerceAtLeast(0.0) else 0.0
        val weeklyDrawdown = if (finalStartWeek > 0.0)
            ((finalStartWeek - currentEquity) / finalStartWeek * 100.0).coerceAtLeast(0.0) else 0.0

        check(secrets.saveRiskEquityRollover(
            finalStartDay, finalStartWeek, now, dailyDrawdown, weeklyDrawdown
        )) { "Risk equity baseline could not be saved to encrypted storage." }
    }

    override suspend fun getStartDayEquity(): Double {
        if (!secrets.isSecureStorageAvailable) return 0.0
        ensureEncryptedMigration()
        return secrets.getRiskStartDayEquity()
    }

    override suspend fun getStartWeekEquity(): Double {
        if (!secrets.isSecureStorageAvailable) return 0.0
        ensureEncryptedMigration()
        return secrets.getRiskStartWeekEquity()
    }

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
