package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.usecase.impl.stableAnalysisSignalId
import com.example.data.AppDatabase
import com.example.data.deriv.DerivExecutionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DerivSignalIdentityPersistenceTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    private fun intent(account: String, mode: String, id: String, direction: String) = DerivExecutionEntity(
        accountId = account, accountMode = mode, signalId = id,
        product = if (direction == "LONG") "MULTUP" else "MULTDOWN",
        symbol = "1HZ100V", direction = direction, stake = 1.0, currency = "USD", multiplier = 10,
        stopLossAmount = 1.0, takeProfitAmount = 2.0, strategyId = "smc-confluence-1.0.0",
        strategyVersion = "1.0.0", signalTimestampMs = 1_800_000_000_000L,
        status = "ORDER_SUBMITTING"
    )

    @Test
    fun repeatedAndOppositeDirectionScanCannotInsertSecondIntentButOtherAccountIsIsolated() = runBlocking {
        val signalId = stableAnalysisSignalId("1HZ100V", "M5", "smc-confluence-1.0.0", 1_800_000_000_000L)
        val dao = database.derivExecutionDao()
        assertTrue(dao.insertIfAbsent(intent("10001", "DEMO", signalId, "LONG")) >= 0L)

        // A repeated scan or opposite-direction report for the same candle keeps the analyzer ID.
        assertEquals(-1L, dao.insertIfAbsent(intent("10001", "DEMO", signalId, "LONG")))
        assertEquals(-1L, dao.insertIfAbsent(intent("10001", "DEMO", signalId, "SHORT")))
        assertEquals("LONG", dao.findBySignalId("10001", "DEMO", signalId)?.direction)

        // The composite key deliberately permits a distinct record on another account or mode.
        assertNotEquals(-1L, dao.insertIfAbsent(intent("20002", "DEMO", signalId, "SHORT")))
        assertNotEquals(-1L, dao.insertIfAbsent(intent("10001", "LIVE", signalId, "SHORT")))
        assertNotNull(dao.findBySignalId("10001", "DEMO", signalId))
    }

    @Test
    fun migratedLegacyIdentityRemainsVisibleToTheGlobalSafetyBlocker() = runBlocking {
        val dao = database.derivExecutionDao()
        val legacyId = "legacy-signal-from-v5"
        dao.insertIfAbsent(intent("LEGACY_UNKNOWN", "DEMO", legacyId, "LONG"))
        assertEquals(1, dao.legacyUnknown().size)
        assertEquals(null, dao.findBySignalId("10001", "DEMO", legacyId))
    }
}
