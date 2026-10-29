package com.example.data.deriv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DerivMultiplierExecutor(
    private val gateway: DerivBrokerGateway,
    private val dao: DerivExecutionDao
) {
    /** Returns false when the app must remain blocked because a prior purchase is unresolved. */
    suspend fun reconcileBeforeNewEntries(): Boolean = withContext(Dispatchers.IO) {
        val unresolved = dao.unresolved()
        if (unresolved.isEmpty()) return@withContext true
        if (gateway.connectionState.value != DerivConnectionState.CONNECTED) return@withContext false
        gateway.fetchPortfolio()
        unresolved.forEach { record ->
            val contractId = record.contractId
            if (contractId != null && gateway.activeContracts.value.containsKey(contractId)) {
                dao.update(record.copy(status = DerivExecutionStatus.CONTRACT_OPEN.name, updatedAtMs = System.currentTimeMillis()))
            } else {
                dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, updatedAtMs = System.currentTimeMillis()))
            }
        }
        dao.unresolved().isEmpty()
    }

    suspend fun syncKnownContracts() = withContext(Dispatchers.IO) {
        gateway.fetchPortfolio()
        dao.knownContracts().forEach { record ->
            val id = record.contractId ?: return@forEach
            val contract = gateway.activeContracts.value[id] ?: return@forEach
            val settled = contract.isSold || contract.status.lowercase() in setOf("sold", "won", "lost", "expired")
            dao.update(record.copy(
                status = if (settled) DerivExecutionStatus.CONTRACT_SETTLED.name else DerivExecutionStatus.CONTRACT_OPEN.name,
                settlementValue = if (settled) contract.bidPrice else record.settlementValue,
                settlementProfit = if (settled) contract.profit else record.settlementProfit,
                updatedAtMs = System.currentTimeMillis()
            ))
        }
    }

    suspend fun submit(intent: DerivMultiplierIntent): Result<DerivBuyResult> = withContext(Dispatchers.IO) {
        val existing = dao.findBySignalId(intent.signalId)
        if (existing != null) {
            return@withContext Result.failure(Exception("DUPLICATE_SIGNAL: ${intent.signalId} already has a persisted execution record in ${existing.status}"))
        }
        if (!reconcileBeforeNewEntries()) {
            return@withContext Result.failure(Exception("RECONCILIATION_REQUIRED: prior Deriv execution outcome is unresolved; no purchase was sent"))
        }
        val account = gateway.accountInfo.value
            ?: return@withContext Result.failure(Exception("DEMO_ACCOUNT_UNVERIFIED: verify the Demo account before submitting"))
        val validation = DerivMultiplierRules.validate(
            intent,
            gateway.accountMetrics.value?.balance ?: account.balance,
            account.currency
        )
        if (!validation.valid) return@withContext Result.failure(Exception(validation.reason ?: "Multiplier intent rejected"))
        val inserted = dao.insertIfAbsent(intent.toEntity(DerivExecutionStatus.ORDER_SUBMITTING.name))
        if (inserted == -1L) return@withContext Result.failure(Exception("DUPLICATE_SIGNAL: persisted execution intent already exists"))

        val result = gateway.executeMultiplier(intent)
        if (result.isSuccess) {
            val buy = result.getOrThrow()
            dao.update(intent.toEntity(
                status = DerivExecutionStatus.CONTRACT_CONFIRMED.name,
                contractId = buy.contractId,
                buyTransactionId = buy.transactionId,
                purchasePrice = buy.buyPrice,
                proposalId = buy.proposalId,
                requestId = buy.requestId
            ))
            Result.success(buy)
        } else {
            val error = result.exceptionOrNull()?.message?.take(240) ?: "Unknown broker outcome"
            dao.update(intent.toEntity(DerivExecutionStatus.EXECUTION_UNKNOWN.name, lastError = error))
            Result.failure(Exception("EXECUTION_UNKNOWN: $error; reconcile the broker before retrying"))
        }
    }

    private fun DerivMultiplierIntent.toEntity(
        status: String,
        contractId: Long? = null,
        buyTransactionId: Long? = null,
        purchasePrice: Double? = null,
        lastError: String? = null,
        proposalId: String? = null,
        requestId: Int? = null
    ) = DerivExecutionEntity(
        signalId = signalId,
        product = product.contractType,
        symbol = symbol,
        direction = direction.name,
        stake = stake,
        currency = currency,
        multiplier = multiplier,
        stopLossAmount = stopLossAmount,
        takeProfitAmount = takeProfitAmount,
        strategyId = strategyId,
        strategyVersion = strategyVersion,
        signalTimestampMs = signalTimestampMs,
        requestId = requestId,
        proposalId = proposalId,
        status = status,
        contractId = contractId,
        buyTransactionId = buyTransactionId,
        purchasePrice = purchasePrice,
        lastError = lastError,
        updatedAtMs = System.currentTimeMillis()
    )
}
