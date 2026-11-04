package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.deriv.DerivAccountInfo
import com.example.data.deriv.DerivBuyResult
import com.example.data.deriv.DerivConnectionState
import com.example.data.deriv.DerivContractInfo
import com.example.data.deriv.DerivExecutionEntity
import com.example.data.deriv.DerivExecutionStatus
import com.example.data.deriv.DerivMultiplierExecutor
import com.example.data.deriv.DerivMultiplierGateway
import com.example.data.deriv.DerivMultiplierIntent
import com.example.data.deriv.DerivMultiplierProduct
import com.example.data.deriv.DerivPortfolioSnapshot
import com.example.data.deriv.DerivReconciliationStatus
import com.example.data.deriv.DerivSubmissionStage
import com.example.domain.model.Position
import com.example.domain.model.SignalDirection
import com.example.domain.repository.AccountMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DerivMultiplierExecutorPersistenceTest {
    private val databaseName = "deriv-executor-${UUID.randomUUID()}.db"
    private lateinit var database: AppDatabase
    private lateinit var gateway: FakeDerivGateway
    private lateinit var executor: DerivMultiplierExecutor

    @Before
    fun setUp() {
        database = openDatabase()
        gateway = FakeDerivGateway()
        executor = newExecutor(database, gateway)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized && database.isOpen) database.close()
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(databaseName)
    }

    private fun openDatabase() = Room.databaseBuilder(
        ApplicationProvider.getApplicationContext(), AppDatabase::class.java, databaseName
    ).allowMainThreadQueries().build()

    private fun newExecutor(db: AppDatabase, fake: FakeDerivGateway) = DerivMultiplierExecutor(
        gateway = fake,
        dao = db.derivExecutionDao(),
        sensitiveActionAuthorized = { true },
        dailyRiskEntryAllowed = { _, _ -> true }
    )

    private fun intent(signalId: String = "signal-persisted") = DerivMultiplierIntent(
        signalId = signalId,
        symbol = "1HZ100V",
        strategyId = "smc-confluence-1.0.0",
        strategyVersion = "1.0.0",
        direction = SignalDirection.LONG,
        product = DerivMultiplierProduct.MULTUP,
        stake = 1.0,
        currency = "USD",
        multiplier = 10,
        stopLossAmount = 1.0,
        takeProfitAmount = 2.0,
        signalTimestampMs = System.currentTimeMillis(),
        createdAtMs = System.currentTimeMillis()
    )

    private fun storedExecution(
        signalId: String,
        status: DerivExecutionStatus = DerivExecutionStatus.EXECUTION_UNKNOWN,
        contractId: Long? = 9001L,
        buyTransactionId: Long? = 7001L
    ) = DerivExecutionEntity(
        accountId = "D123", accountMode = "DEMO", signalId = signalId,
        product = "MULTUP", symbol = "1HZ100V", direction = "LONG", stake = 1.0,
        currency = "USD", multiplier = 10, stopLossAmount = 1.0, takeProfitAmount = 2.0,
        strategyId = "smc-confluence-1.0.0", strategyVersion = "1.0.0",
        signalTimestampMs = System.currentTimeMillis(), submissionStage = DerivSubmissionStage.PURCHASE_SENT.name,
        contractId = contractId, buyTransactionId = buyTransactionId, purchasePrice = 1.0,
        status = status.name, createdAtMs = System.currentTimeMillis(), updatedAtMs = System.currentTimeMillis()
    )

    private fun contract(
        contractId: Long = 9001L,
        transactionId: Long = 7001L,
        sold: Boolean = false,
        status: String = if (sold) "sold" else "open"
    ) = DerivContractInfo(
        contractId = contractId, transactionId = transactionId, symbol = "1HZ100V",
        contractType = "MULTUP", buyPrice = 1.0, bidPrice = if (sold) 1.5 else 0.0,
        profit = if (sold) 0.5 else 0.0, isSold = sold, isValidToSell = !sold,
        status = status, purchaseTime = 1_800_000_000L, expiryTime = null
    )

    @Test
    fun brokerConfirmedBuyIdentifiersAndPricePersistEvenBeforePortfolioShowsContract() = runBlocking {
        gateway.buyResult = Result.success(DerivBuyResult(9001L, 7001L, 1.25, "proposal-1", 88))
        gateway.portfolioContracts = emptyList()

        assertTrue(executor.submit(intent()).isSuccess)
        val row = database.derivExecutionDao().findBySignalId("D123", "DEMO", "signal-persisted")
        assertNotNull(row)
        assertEquals(9001L, row!!.contractId)
        assertEquals(7001L, row.buyTransactionId)
        assertEquals(1.25, row.purchasePrice!!, 0.0)
        assertEquals(DerivExecutionStatus.CONTRACT_CONFIRMED.name, row.status)

        // Close and reopen the on-disk Room database to exercise restart persistence.
        database.close()
        database = openDatabase()
        executor = newExecutor(database, gateway)
        val restored = database.derivExecutionDao().findBySignalId("D123", "DEMO", "signal-persisted")
        assertEquals(9001L, restored!!.contractId)
        assertEquals(7001L, restored.buyTransactionId)
        assertEquals(1.25, restored.purchasePrice!!, 0.0)
    }

    @Test
    fun buyTimeoutPersistsNoInventedBrokerIdentifiersAndRemainsUnresolvedAfterRestart() = runBlocking {
        gateway.buyResult = Result.failure(
            com.example.data.deriv.DerivExecutionFailure(
                com.example.data.deriv.DerivExecutionFailureKind.PURCHASE_AMBIGUOUS,
                "broker response timed out",
                requestId = 99,
                proposalId = "proposal-timeout",
                submissionStage = DerivSubmissionStage.PURCHASE_SENT
            )
        )

        assertTrue(executor.submit(intent("signal-timeout")).isFailure)
        var row = database.derivExecutionDao().findBySignalId("D123", "DEMO", "signal-timeout")!!
        assertNull(row.contractId)
        assertNull(row.buyTransactionId)
        assertNull(row.purchasePrice)
        assertEquals(DerivExecutionStatus.EXECUTION_UNKNOWN.name, row.status)

        database.close()
        database = openDatabase()
        executor = newExecutor(database, gateway)
        row = database.derivExecutionDao().findBySignalId("D123", "DEMO", "signal-timeout")!!
        assertEquals(DerivExecutionStatus.EXECUTION_UNKNOWN.name, row.status)
        assertEquals(1, database.derivExecutionDao().unresolved("D123", "DEMO").size)

        val before = gateway.purchaseAttempts
        assertTrue(executor.submit(intent("signal-timeout")).isFailure)
        assertEquals("Ambiguous existing signal must not be resubmitted", before, gateway.purchaseAttempts)
    }

    @Test
    fun missingOrMismatchedBuyTransactionCannotReconcileFromContractIdAlone() = runBlocking {
        val dao = database.derivExecutionDao()
        dao.insertIfAbsent(storedExecution("missing-txn", buyTransactionId = null))
        dao.insertIfAbsent(storedExecution("wrong-txn", buyTransactionId = 7999L))
        gateway.active.value = mapOf(9001L to contract(transactionId = 7001L))

        val result = executor.reconcileBeforeNewEntriesResult()
        assertEquals(DerivReconciliationStatus.MANUAL_RECONCILIATION_REQUIRED, result.status)
        assertEquals(DerivExecutionStatus.RECONCILIATION_REQUIRED.name, dao.findBySignalId("D123", "DEMO", "missing-txn")!!.status)
        assertEquals(DerivExecutionStatus.RECONCILIATION_REQUIRED.name, dao.findBySignalId("D123", "DEMO", "wrong-txn")!!.status)
    }

    @Test
    fun syncKnownContractsDoesNotPromoteOrSettleWrongTransactionEvidence() = runBlocking {
        val dao = database.derivExecutionDao()
        dao.insertIfAbsent(storedExecution("wrong-open", DerivExecutionStatus.CONTRACT_CONFIRMED, buyTransactionId = 7999L))
        dao.insertIfAbsent(storedExecution("wrong-settle", DerivExecutionStatus.CONTRACT_OPEN, buyTransactionId = 7998L))
        dao.insertIfAbsent(storedExecution("missing-open", DerivExecutionStatus.CONTRACT_CONFIRMED, buyTransactionId = null))
        gateway.active.value = mapOf(9001L to contract(transactionId = 7001L, sold = true))

        assertFalse(executor.syncKnownContracts())
        assertEquals(DerivExecutionStatus.RECONCILIATION_REQUIRED.name, dao.findBySignalId("D123", "DEMO", "wrong-open")!!.status)
        assertEquals(DerivExecutionStatus.RECONCILIATION_REQUIRED.name, dao.findBySignalId("D123", "DEMO", "wrong-settle")!!.status)
        assertEquals(DerivExecutionStatus.RECONCILIATION_REQUIRED.name, dao.findBySignalId("D123", "DEMO", "missing-open")!!.status)
        assertEquals(0, dao.knownContracts("D123", "DEMO").size)
    }

    @Test
    fun exactHistoricalAccountContractAndBuyTransactionEvidenceCanSettle() = runBlocking {
        val dao = database.derivExecutionDao()
        dao.insertIfAbsent(storedExecution("history-match"))
        gateway.historical = contract(sold = true)

        val result = executor.reconcileBeforeNewEntriesResult()
        val restored = dao.findBySignalId("D123", "DEMO", "history-match")!!
        assertEquals(DerivExecutionStatus.READY, result.status)
        assertEquals(DerivExecutionStatus.CONTRACT_SETTLED.name, restored.status)
        assertEquals(7001L, restored.buyTransactionId)
        assertEquals(0.5, restored.settlementProfit!!, 0.0)
    }

    private class FakeDerivGateway : DerivMultiplierGateway {
        private val now = System.currentTimeMillis()
        private val _account = MutableStateFlow(
            DerivAccountInfo("D123", "USD", 100.0, "", "demo", true, "demo")
        )
        override val accountInfo: StateFlow<DerivAccountInfo?> = _account
        override val accountMetrics: StateFlow<AccountMetrics?> = MutableStateFlow(
            AccountMetrics(100.0, 100.0, 0.0, 100.0, 0.0, "USD")
        )
        override val accountMetricsUpdatedAtMs: StateFlow<Long> = MutableStateFlow(now)
        val active = MutableStateFlow<Map<Long, DerivContractInfo>>(emptyMap())
        override val activeContracts: StateFlow<Map<Long, DerivContractInfo>> = active
        var portfolioContracts: List<DerivContractInfo> = emptyList()
        override val portfolioSnapshot: MutableStateFlow<DerivPortfolioSnapshot?> = MutableStateFlow(
            DerivPortfolioSnapshot(true, "D123", "DEMO", now, emptyList(), currency = "USD")
        )
        override val connectionState: StateFlow<DerivConnectionState> = MutableStateFlow(DerivConnectionState.READY)
        var historical: DerivContractInfo? = null
        var buyResult: Result<DerivBuyResult> = Result.success(DerivBuyResult(9001L, 7001L, 1.0, "proposal-1", 88))
        var purchaseAttempts = 0
            private set

        override suspend fun fetchPortfolio(): List<Position> {
            portfolioSnapshot.value = DerivPortfolioSnapshot(
                true, "D123", "DEMO", System.currentTimeMillis(), portfolioContracts, currency = "USD"
            )
            return emptyList()
        }

        override suspend fun lookupHistoricalContract(contractId: Long, sinceMs: Long): DerivContractInfo? = historical

        override suspend fun executeMultiplier(
            intent: DerivMultiplierIntent,
            allowPurchase: suspend () -> Boolean,
            onStage: suspend (requestId: Int?, proposalId: String?, stage: DerivSubmissionStage) -> Unit
        ): Result<DerivBuyResult> {
            if (!allowPurchase()) return Result.failure(IllegalStateException("purchase disallowed"))
            purchaseAttempts++
            onStage(88, "proposal-1", DerivSubmissionStage.PURCHASE_SENT)
            return buyResult
        }

        override suspend fun sellContract(contractId: Long): Result<com.example.data.deriv.DerivSellResult> =
            Result.failure(UnsupportedOperationException("not used by persistence tests"))
    }
}
