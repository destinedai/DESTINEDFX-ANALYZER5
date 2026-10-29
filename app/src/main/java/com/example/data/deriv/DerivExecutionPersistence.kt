package com.example.data.deriv

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "deriv_execution_intents")
data class DerivExecutionEntity(
    @androidx.room.PrimaryKey val signalId: String,
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
    val contractId: Long? = null,
    val buyTransactionId: Long? = null,
    val sellTransactionId: Long? = null,
    val purchasePrice: Double? = null,
    val settlementValue: Double? = null,
    val settlementProfit: Double? = null,
    val status: String,
    val lastError: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface DerivExecutionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: DerivExecutionEntity): Long

    @Update
    suspend fun update(entity: DerivExecutionEntity)

    @Query("SELECT * FROM deriv_execution_intents WHERE signalId = :signalId LIMIT 1")
    suspend fun findBySignalId(signalId: String): DerivExecutionEntity?

    @Query("SELECT * FROM deriv_execution_intents WHERE status IN ('EXECUTION_UNKNOWN','ORDER_PENDING','RECONCILIATION_REQUIRED') ORDER BY updatedAtMs ASC")
    suspend fun unresolved(): List<DerivExecutionEntity>

    @Query("SELECT * FROM deriv_execution_intents WHERE status IN ('CONTRACT_CONFIRMED','CONTRACT_OPEN') ORDER BY updatedAtMs ASC")
    suspend fun knownContracts(): List<DerivExecutionEntity>

    @Query("SELECT * FROM deriv_execution_intents ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<DerivExecutionEntity>>
}

enum class DerivExecutionStatus {
    IDLE, DEMO_READY, STARTING, RUNNING, STOPPING, STOPPED,
    ORDER_SUBMITTING, ORDER_PENDING, CONTRACT_CONFIRMED, CONTRACT_OPEN,
    CONTRACT_SETTLED, ORDER_REJECTED, EXECUTION_UNKNOWN,
    RECONCILIATION_REQUIRED, CONNECTION_LOST, SAFETY_BLOCKED
}
