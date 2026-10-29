package com.example.data.deriv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DerivMultiplierExecutor(
    private val gateway: DerivBrokerGateway,
    private val dao: DerivExecutionDao
) {
    private val account get() = gateway.accountInfo.value
    private val accountId get() = account?.loginId.orEmpty()
    private val accountMode get() = account?.mode?.uppercase().orEmpty()

    suspend fun reconcileBeforeNewEntries(): Boolean = withContext(Dispatchers.IO) {
        val current = account ?: return@withContext false
        if (gateway.connectionState.value != DerivConnectionState.CONNECTED) return@withContext false
        gateway.fetchPortfolio()
        val unresolved = dao.unresolved(current.loginId, current.mode.uppercase())
        unresolved.forEach { record ->
            val contractId = record.contractId
            if (contractId == null) {
                dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "No broker contract ID; manual reconciliation is required.", updatedAtMs = System.currentTimeMillis()))
            } else {
                val currentContract = gateway.activeContracts.value[contractId]
                val historical = if (currentContract == null) gateway.lookupHistoricalContract(contractId, record.createdAtMs) else null
                when {
                    currentContract != null && currentContract.isSold -> updateSettled(record, currentContract)
                    currentContract != null -> dao.update(record.copy(status = DerivExecutionStatus.CONTRACT_OPEN.name, updatedAtMs = System.currentTimeMillis()))
                    historical != null && historical.isSold -> updateSettled(record, historical)
                    historical != null -> dao.update(record.copy(status = DerivExecutionStatus.CONTRACT_OPEN.name, updatedAtMs = System.currentTimeMillis()))
                    else -> dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "Broker history did not establish the contract outcome; manual reconciliation is required.", updatedAtMs = System.currentTimeMillis()))
                }
            }
        }
        dao.unresolved(current.loginId, current.mode.uppercase()).isEmpty()
    }

    suspend fun syncKnownContracts() = withContext(Dispatchers.IO) {
        val current = account ?: return@withContext
        gateway.fetchPortfolio()
        dao.knownContracts(current.loginId, current.mode.uppercase()).forEach { record ->
            val id = record.contractId ?: return@forEach
            val contract = gateway.activeContracts.value[id] ?: gateway.lookupHistoricalContract(id, record.createdAtMs)
            if (contract == null) {
                dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "Contract is not open and supported broker history has not established its outcome.", updatedAtMs = System.currentTimeMillis()))
            } else if (contract.isSold || contract.status.lowercase() in setOf("sold", "won", "lost", "expired")) {
                updateSettled(record, contract)
            } else {
                dao.update(record.copy(status = DerivExecutionStatus.CONTRACT_OPEN.name, updatedAtMs = System.currentTimeMillis()))
            }
        }
    }

    private suspend fun updateSettled(record: DerivExecutionEntity, contract: DerivContractInfo) {
        dao.update(record.copy(
            status = DerivExecutionStatus.CONTRACT_SETTLED.name,
            settlementValue = contract.bidPrice,
            settlementProfit = contract.profit,
            lastError = null,
            updatedAtMs = System.currentTimeMillis()
        ))
    }

    suspend fun submit(intent: DerivMultiplierIntent): Result<DerivBuyResult> = withContext(Dispatchers.IO) {
        val current = account ?: return@withContext Result.failure(Exception("ACCOUNT_UNVERIFIED: verify the selected Deriv account before submitting"))
        if (current.loginId.isBlank() || current.mode !in setOf("demo", "real")) return@withContext Result.failure(Exception("ACCOUNT_INVALID: broker account identity is unavailable"))
        val existing = dao.findBySignalId(current.loginId, current.mode.uppercase(), intent.signalId)
        if (existing != null) return@withContext Result.failure(Exception("DUPLICATE_SIGNAL: ${intent.signalId} already exists for ${current.mode.uppercase()} account ${current.loginId} in ${existing.status}"))
        if (!reconcileBeforeNewEntries()) return@withContext Result.failure(Exception("RECONCILIATION_REQUIRED: prior Deriv execution outcome is unresolved; no purchase was sent"))
        val balance = gateway.accountMetrics.value?.balance ?: current.balance
        val validation = DerivMultiplierRules.validate(intent, balance, current.currency)
        if (!validation.valid) return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.VALIDATION_REJECTED, validation.reason ?: "Multiplier intent rejected"))

        val initial = intent.toEntity(DerivExecutionStatus.ORDER_SUBMITTING.name, DerivSubmissionStage.VALIDATED.name)
        if (dao.insertIfAbsent(initial) == -1L) return@withContext Result.failure(Exception("DUPLICATE_SIGNAL: persisted execution intent already exists"))
        val result = gateway.executeMultiplier(intent) { requestId, proposalId, stage ->
            val saved = dao.findBySignalId(current.loginId, current.mode.uppercase(), intent.signalId) ?: initial
            dao.update(saved.copy(requestId = requestId ?: saved.requestId, proposalId = proposalId ?: saved.proposalId, submissionStage = stage.name, status = if (stage == DerivSubmissionStage.PURCHASE_SENT) DerivExecutionStatus.ORDER_PENDING.name else DerivExecutionStatus.ORDER_SUBMITTING.name, updatedAtMs = System.currentTimeMillis()))
        }
        if (result.isSuccess) {
            val buy = result.getOrThrow()
            val saved = dao.findBySignalId(current.loginId, current.mode.uppercase(), intent.signalId) ?: initial
            dao.update(saved.copy(status = DerivExecutionStatus.CONTRACT_CONFIRMED.name, submissionStage = DerivSubmissionStage.CONTRACT_CONFIRMED.name, contractId = buy.contractId, buyTransactionId = buy.transactionId, purchasePrice = buy.buyPrice, proposalId = buy.proposalId, requestId = buy.requestId, lastError = null, updatedAtMs = System.currentTimeMillis()))
            Result.success(buy)
        } else {
            val failure = result.exceptionOrNull()
            val typed = failure as? DerivExecutionFailure
            val status = when (typed?.kind) {
                DerivExecutionFailureKind.VALIDATION_REJECTED -> DerivExecutionStatus.VALIDATION_REJECTED
                DerivExecutionFailureKind.PROPOSAL_REJECTED, DerivExecutionFailureKind.PROPOSAL_TIMEOUT, DerivExecutionFailureKind.PURCHASE_NOT_SENT, DerivExecutionFailureKind.PURCHASE_REJECTED -> DerivExecutionStatus.ORDER_REJECTED
                DerivExecutionFailureKind.PURCHASE_AMBIGUOUS, DerivExecutionFailureKind.RECONCILIATION_REQUIRED, null -> DerivExecutionStatus.EXECUTION_UNKNOWN
            }
            val saved = dao.findBySignalId(current.loginId, current.mode.uppercase(), intent.signalId) ?: initial
            dao.update(saved.copy(status = status.name, requestId = typed?.requestId ?: saved.requestId, proposalId = typed?.proposalId ?: saved.proposalId, submissionStage = typed?.submissionStage?.name ?: saved.submissionStage, lastError = failure?.message?.take(500), updatedAtMs = System.currentTimeMillis()))
            Result.failure(Exception("${status.name}: ${failure?.message ?: "Broker execution failed"}"))
        }
    }

    private fun DerivMultiplierIntent.toEntity(status: String, stage: String) = DerivExecutionEntity(
        accountId = accountId, accountMode = accountMode, signalId = signalId, product = product.contractType,
        symbol = symbol, direction = direction.name, stake = stake, currency = currency, multiplier = multiplier,
        stopLossAmount = stopLossAmount, takeProfitAmount = takeProfitAmount, strategyId = strategyId,
        strategyVersion = strategyVersion, signalTimestampMs = signalTimestampMs, submissionStage = stage,
        status = status, createdAtMs = createdAtMs, updatedAtMs = System.currentTimeMillis()
    )
}
