package com.example.data.deriv

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "deriv_execution_intents",
    primaryKeys = ["accountId", "accountMode", "signalId"]
)
data class DerivExecutionEntity(
    val accountId: String,
    val accountMode: String,
    val signalId: String,
    val product: String,
    val symbol: String,
    val direction: String,
    val stake: Double,
    val currency: String,
    val multiplier: Int,
    val stopLossAmount: Double,
    val takeProfitAmount: Double,
    val strategyId: String,
    val strategyVersion: String,
    val signalTimestampMs: Long,
    val requestId: Int? = null,
    val proposalId: String? = null,
    val submissionStage: String = DerivSubmissionStage.VALIDATED.name,
    val contractId: Long? = null,
    val buyTransactionId: Long? = null,
    val sellTransactionId: Long? = null,
    val purchasePrice: Double? = null,
    val settlementValue: Double? = null,
    val settlementProfit: Double? = null,
    val status: String,
    val lastError: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface DerivExecutionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: DerivExecutionEntity): Long

    @Update
    suspend fun update(entity: DerivExecutionEntity)

    @Query("SELECT * FROM deriv_execution_intents WHERE accountId = :accountId AND accountMode = :accountMode AND signalId = :signalId LIMIT 1")
    suspend fun findBySignalId(accountId: String, accountMode: String, signalId: String): DerivExecutionEntity?

    @Query("SELECT * FROM deriv_execution_intents WHERE accountId = :accountId AND accountMode = :accountMode AND status IN ('ORDER_SUBMITTING','ORDER_PENDING','EXECUTION_UNKNOWN','RECONCILIATION_REQUIRED') ORDER BY updatedAtMs ASC")
    suspend fun unresolved(accountId: String, accountMode: String): List<DerivExecutionEntity>

    @Query("SELECT * FROM deriv_execution_intents WHERE accountId = :accountId AND accountMode = :accountMode AND status IN ('CONTRACT_CONFIRMED','CONTRACT_OPEN') ORDER BY updatedAtMs ASC")
    suspend fun knownContracts(accountId: String, accountMode: String): List<DerivExecutionEntity>

    @Query("SELECT * FROM deriv_execution_intents ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<DerivExecutionEntity>>
}

enum class DerivSubmissionStage {
    VALIDATED, PROPOSAL_SENT, PROPOSAL_CONFIRMED, PURCHASE_SENT, CONTRACT_CONFIRMED
}

enum class DerivExecutionFailureKind {
    VALIDATION_REJECTED,
    PROPOSAL_REJECTED,
    PROPOSAL_TIMEOUT,
    PURCHASE_NOT_SENT,
    PURCHASE_REJECTED,
    PURCHASE_AMBIGUOUS,
    RECONCILIATION_REQUIRED
}

class DerivExecutionFailure(
    val kind: DerivExecutionFailureKind,
    message: String,
    val requestId: Int? = null,
    val proposalId: String? = null,
    val submissionStage: DerivSubmissionStage = DerivSubmissionStage.VALIDATED
) : Exception(message)

enum class DerivExecutionStatus {
    IDLE, DEMO_READY, REAL_READY, STARTING, RUNNING, STOPPING, STOPPED,
    ORDER_SUBMITTING, ORDER_PENDING, CONTRACT_CONFIRMED, CONTRACT_OPEN,
    CONTRACT_SETTLED, ORDER_REJECTED, VALIDATION_REJECTED, EXECUTION_UNKNOWN,
    RECONCILIATION_REQUIRED, CONNECTION_LOST, SAFETY_BLOCKED
}
