package com.example.data.risk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.security.SecretsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RiskRepositoryEncryptionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun legacyRiskProfileAndEquityBaselinesMigrateToEncryptedPreferences() = runBlocking {
        val legacy = context.riskSettingsStore
        legacy.edit { it.clear() }
        legacy.edit {
            it[RiskRepositoryImpl.BAL] = 2_500.0
            it[RiskRepositoryImpl.RISK] = 0.75
            it[RiskRepositoryImpl.DD_DAY] = 3.0
            it[RiskRepositoryImpl.DD_WK] = 6.0
            it[RiskRepositoryImpl.CUR_DD] = 0.5
            it[RiskRepositoryImpl.CUR_WDD] = 1.25
            it[RiskRepositoryImpl.RR_MIN] = 2.0
            it[RiskRepositoryImpl.HALT] = false
            it[RiskRepositoryImpl.START_DAY_EQUITY] = 2_450.0
            it[RiskRepositoryImpl.START_WEEK_EQUITY] = 2_400.0
            it[RiskRepositoryImpl.LAST_ROLLOVER] = 1_800_000_000_000L
        }

        val secrets = SecretsManager(context)
        if (!secrets.isSecureStorageAvailable) return@runBlocking
        val repository = RiskRepositoryImpl(context, secrets)
        val profile = repository.getRiskProfile()

        assertEquals(2_500.0, profile.accountBalance, 0.0)
        assertEquals(0.75, profile.fixedRiskPercentage, 0.0)
        assertEquals(3.0, profile.maxDailyDrawdownPercentage, 0.0)
        assertEquals(6.0, profile.maxWeeklyDrawdownPercentage, 0.0)
        assertEquals(0.5, profile.currentDailyDrawdown, 0.0)
        assertEquals(1.25, profile.currentWeeklyDrawdown, 0.0)
        assertEquals(2.0, profile.minimumRR, 0.0)
        assertFalse(profile.isTradingHalted)
        assertEquals(2_450.0, repository.getStartDayEquity(), 0.0)
        assertEquals(2_400.0, repository.getStartWeekEquity(), 0.0)
        assertTrue(secrets.getEncryptedRiskProfile() != null)
        assertTrue("Legacy risk DataStore must be cleared after encrypted migration", legacy.data.first().isEmpty())
        assertFalse("The legacy risk validation interface has no broker provenance", repository.validateTradeRisk(10.0))
    }

    @Test
    fun keystoreFailurePreservesLegacyRiskDataAndReturnsSafeDefaults() = runBlocking {
        val legacy = context.riskSettingsStore
        legacy.edit { it.clear() }
        legacy.edit { it[RiskRepositoryImpl.BAL] = 12_345.0 }
        val unavailable = SecretsManager(context) { throw IllegalStateException("simulated Android Keystore outage") }
        val repository = RiskRepositoryImpl(context, unavailable)

        val profile = repository.getRiskProfile()
        assertEquals(0.0, profile.accountBalance, 0.0)
        assertEquals(1.0, profile.fixedRiskPercentage, 0.0)
        assertFalse(repository.validateTradeRisk(10.0))
        assertTrue("Only plaintext copy must remain intact until encrypted migration succeeds", legacy.data.first().isNotEmpty())
        assertFalse(unavailable.saveEncryptedRiskProfile(profile))
    }
}
