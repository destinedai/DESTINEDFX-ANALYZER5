package com.example.data.deriv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.domain.risk.TradingSafetyLimits
import com.example.domain.model.Position
import com.example.domain.repository.AccountMetrics
import kotlinx.coroutines.flow.StateFlow

/** Narrow broker contract consumed by the execution authority; production uses DerivBrokerGateway. */
interface DerivMultiplierGateway {
    val accountInfo: StateFlow<DerivAccountInfo?>
    val accountMetrics: StateFlow<AccountMetrics?>
    val accountMetricsUpdatedAtMs: StateFlow<Long>
    val activeContracts: StateFlow<Map<Long, DerivContractInfo>>
    val portfolioSnapshot: StateFlow<DerivPortfolioSnapshot?>
    val connectionState: StateFlow<DerivConnectionState>
    suspend fun fetchPortfolio(): List<Position>
    suspend fun lookupHistoricalContract(contractId: Long, sinceMs: Long): DerivContractInfo?
    suspend fun executeMultiplier(
        intent: DerivMultiplierIntent,
        allowPurchase: suspend () -> Boolean,
        onStage: suspend (requestId: Int?, proposalId: String?, stage: DerivSubmissionStage) -> Unit
    ): Result<DerivBuyResult>
    suspend fun sellContract(contractId: Long): Result<DerivSellResult>
}

class DerivMultiplierExecutor(
    private val gateway: DerivMultiplierGateway,
    private val dao: DerivExecutionDao,
    private val sensitiveActionAuthorized: () -> Boolean = { false },
    private val dailyRiskEntryAllowed: suspend (String, String) -> Boolean = { _, _ -> false }
) {
    companion object {
        private const val PORTFOLIO_MAX_AGE_MS = 8_000L
        private const val MAX_OPEN_RISK_PERCENT = TradingSafetyLimits.MAX_TRADE_RISK_PERCENT
    }
    private val account get() = gateway.accountInfo.value
    private val accountId get() = account?.loginId.orEmpty()
    private val accountMode get() = account?.mode?.uppercase().orEmpty()

    suspend fun hasActiveContracts(): Boolean = withContext(Dispatchers.IO) {
        val current = account ?: return@withContext true
        val snapshot = gateway.portfolioSnapshot.value ?: return@withContext true
        val age = System.currentTimeMillis() - snapshot.retrievedAtMs
        if (!snapshot.successful || snapshot.accountId != current.loginId ||
            snapshot.accountMode != current.mode.uppercase() ||
            !snapshot.currency.equals(current.currency, ignoreCase = true) ||
            age !in 0..PORTFOLIO_MAX_AGE_MS || !snapshot.exposure.isFinite()) {
            return@withContext true
        }
        snapshot.contracts.isNotEmpty() ||
            dao.knownContracts(current.loginId, current.mode.uppercase()).isNotEmpty()
    }

    suspend fun reconcileBeforeNewEntriesResult(): DerivReconciliationResult = withContext(Dispatchers.IO) {
        val current = account
            ?: return@withContext DerivReconciliationResult(DerivReconciliationStatus.RETRYABLE_FAILURE, "Selected Deriv account is not authenticated")
        // v5 rows were migrated without an account identity. Never silently ignore a possible
        // previously-open or ambiguous purchase while reconciling only the selected account.
        if (dao.legacyUnknown().isNotEmpty()) {
            return@withContext DerivReconciliationResult(
                DerivReconciliationStatus.MANUAL_RECONCILIATION_REQUIRED,
                "A migrated execution has no verified account identity"
            )
        }
        if (gateway.connectionState.value != DerivConnectionState.READY) {
            return@withContext DerivReconciliationResult(DerivReconciliationStatus.RETRYABLE_FAILURE, "Deriv WebSocket is disconnected")
        }
        gateway.fetchPortfolio()
        val now = System.currentTimeMillis()
        val snapshot = gateway.portfolioSnapshot.value
        val snapshotAge = snapshot?.let { now - it.retrievedAtMs } ?: Long.MAX_VALUE
        if (snapshot == null || !snapshot.successful || snapshot.accountId != current.loginId ||
            snapshot.accountMode != current.mode.uppercase() ||
            !snapshot.currency.equals(current.currency, ignoreCase = true) ||
            snapshotAge !in 0..PORTFOLIO_MAX_AGE_MS || !snapshot.exposure.isFinite()) {
            return@withContext DerivReconciliationResult(
                DerivReconciliationStatus.RETRYABLE_FAILURE,
                snapshot?.error ?: "A fresh, valid portfolio for the selected account and currency is unavailable"
            )
        }
        val unresolved = dao.unresolved(current.loginId, current.mode.uppercase())
        unresolved.forEach { record ->
            val contractId = record.contractId
            if (contractId == null) {
                dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "No broker contract ID; manual reconciliation is required.", updatedAtMs = now))
            } else {
                val currentContract = gateway.activeContracts.value[contractId]
                val historical = if (currentContract == null) gateway.lookupHistoricalContract(contractId, record.createdAtMs) else null
                val currentEvidenceMatches = currentContract != null && matchesPersistedBuy(record, currentContract)
                val historicalEvidenceMatches = historical != null && matchesPersistedBuy(record, historical)
                when {
                    currentContract != null && currentEvidenceMatches && currentContract.isSold &&
                        currentContract.profit.isFinite() && currentContract.bidPrice.isFinite() && currentContract.bidPrice >= 0.0 ->
                        updateSettled(record, currentContract)
                    currentContract != null && !currentEvidenceMatches ->
                        dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "Broker portfolio contract evidence did not match the persisted buy transaction ID.", updatedAtMs = now))
                    currentContract != null && currentContract.isSold ->
                        dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "Broker contract is sold but settlement profit/value is missing or invalid.", updatedAtMs = now))
                    currentEvidenceMatches -> dao.update(record.copy(status = DerivExecutionStatus.CONTRACT_OPEN.name, updatedAtMs = now))
                    historicalEvidenceMatches && historical!!.isSold && historical.profit.isFinite() && historical.bidPrice.isFinite() && historical.bidPrice >= 0.0 ->
                        updateSettled(record, historical)
                    historical != null && !historicalEvidenceMatches ->
                        dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "Broker history contract evidence did not match the persisted buy transaction ID.", updatedAtMs = now))
                    historicalEvidenceMatches && historical != null && !historical.isSold ->
                        dao.update(record.copy(status = DerivExecutionStatus.CONTRACT_OPEN.name, updatedAtMs = now))
                    else -> dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "Broker history did not establish the contract outcome; manual reconciliation is required.", updatedAtMs = now))
                }
            }
        }
        val stillUnresolved = dao.unresolved(current.loginId, current.mode.uppercase())
        if (stillUnresolved.isNotEmpty()) {
            DerivReconciliationResult(
                DerivReconciliationStatus.MANUAL_RECONCILIATION_REQUIRED,
                "One or more previous execution outcomes remain unconfirmed"
            )
        } else {
            DerivReconciliationResult(DerivReconciliationStatus.READY, "Fresh portfolio and all previous execution outcomes reconciled")
        }
    }

    /** Compatibility adapter for callers that only need a fail-closed yes/no decision. */
    suspend fun reconcileBeforeNewEntries(): Boolean = reconcileBeforeNewEntriesResult().ready

    suspend fun syncKnownContracts(): Boolean = withContext(Dispatchers.IO) {
        val current = account ?: return@withContext false
        gateway.fetchPortfolio()
        val now = System.currentTimeMillis()
        val snapshot = gateway.portfolioSnapshot.value
        val age = snapshot?.let { now - it.retrievedAtMs } ?: Long.MAX_VALUE
        if (snapshot == null || !snapshot.successful || snapshot.accountId != current.loginId ||
            snapshot.accountMode != current.mode.uppercase() ||
            !snapshot.currency.equals(current.currency, ignoreCase = true) ||
            age !in 0..PORTFOLIO_MAX_AGE_MS || !snapshot.exposure.isFinite()) {
            return@withContext false
        }
        var allMatched = true
        dao.knownContracts(current.loginId, current.mode.uppercase()).forEach { record ->
            val id = record.contractId
            if (id == null || id <= 0L || record.buyTransactionId == null || record.buyTransactionId <= 0L) {
                allMatched = false
                dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "Persisted contract or buy transaction ID is missing; manual reconciliation is required.", updatedAtMs = now))
                return@forEach
            }
            val contract = gateway.activeContracts.value[id] ?: gateway.lookupHistoricalContract(id, record.createdAtMs)
            when {
                contract == null -> {
                    allMatched = false
                    dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "Contract is not open and supported broker history has not established its outcome.", updatedAtMs = now))
                }
                !matchesPersistedBuy(record, contract) -> {
                    allMatched = false
                    dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "Broker contract ID or buy transaction ID did not exactly match persisted execution evidence.", updatedAtMs = now))
                }
                contract.isSold || contract.status.lowercase() in setOf("sold", "won", "lost", "expired") -> {
                    if (contract.profit.isFinite() && contract.bidPrice.isFinite() && contract.bidPrice >= 0.0) updateSettled(record, contract)
                    else {
                        allMatched = false
                        dao.update(record.copy(status = DerivExecutionStatus.RECONCILIATION_REQUIRED.name, lastError = "Broker settlement response omitted a valid profit or settlement value.", updatedAtMs = now))
                    }
                }
                else -> dao.update(record.copy(status = DerivExecutionStatus.CONTRACT_OPEN.name, updatedAtMs = now))
            }
        }
        allMatched
    }

    private fun matchesPersistedBuy(record: DerivExecutionEntity, broker: DerivContractInfo): Boolean =
        record.contractId != null && record.contractId > 0L &&
            record.buyTransactionId != null && record.buyTransactionId > 0L &&
            broker.contractId == record.contractId &&
            broker.transactionId > 0L && broker.transactionId == record.buyTransactionId

    private suspend fun updateSettled(record: DerivExecutionEntity, contract: DerivContractInfo) {
        dao.update(record.copy(
            status = DerivExecutionStatus.CONTRACT_SETTLED.name,
            settlementValue = contract.bidPrice,
            settlementProfit = contract.profit,
            lastError = null,
            updatedAtMs = System.currentTimeMillis()
        ))
    }

    /**
     * Attempt to sell only contracts created and persisted by this app for the selected account.
     * Returns true only after broker portfolio/history reconciliation proves all are settled.
     */
    suspend fun closeBotOwnedContracts(): Boolean = withContext(Dispatchers.IO) {
        val current = account ?: return@withContext false
        if (gateway.connectionState.value != DerivConnectionState.READY) return@withContext false
        if (!syncKnownContracts()) return@withContext false
        val records = dao.knownContracts(current.loginId, current.mode.uppercase())
        for (record in records) {
            val id = record.contractId ?: return@withContext false
            val contract = gateway.activeContracts.value[id] ?: return@withContext false
            if (contract.isSold) {
                updateSettled(record, contract)
                continue
            }
            if (!contract.isValidToSell) return@withContext false
            val sold = gateway.sellContract(id)
            if (sold.isFailure) return@withContext false
        }
        if (!syncKnownContracts()) return@withContext false
        dao.knownContracts(current.loginId, current.mode.uppercase()).isEmpty() &&
            dao.unresolved(current.loginId, current.mode.uppercase()).isEmpty()
    }

    suspend fun submit(intent: DerivMultiplierIntent): Result<DerivBuyResult> = withContext(Dispatchers.IO) {
        if (!sensitiveActionAuthorized()) {
            return@withContext Result.failure(Exception("REAUTHENTICATION_REQUIRED: authenticate again before authorizing or submitting a purchase"))
        }
        val current = account ?: return@withContext Result.failure(Exception("ACCOUNT_UNVERIFIED: verify the selected Deriv account before submitting"))
        if (current.loginId.isBlank() || current.mode !in setOf("demo", "real")) return@withContext Result.failure(Exception("ACCOUNT_INVALID: broker account identity is unavailable"))
        if (!dailyRiskEntryAllowed(current.loginId, current.mode.uppercase())) {
            return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.PURCHASE_NOT_SENT, "DAILY_RISK_RECHECK_FAILED: the persisted Deriv daily loss limit is locked or unavailable for this account and mode; no purchase was sent"))
        }
        val existing = dao.findBySignalId(current.loginId, current.mode.uppercase(), intent.signalId)
        if (existing != null) return@withContext Result.failure(Exception("DUPLICATE_SIGNAL: ${intent.signalId} already exists for ${current.mode.uppercase()} account ${current.loginId} in ${existing.status}"))
        val reconciliation = reconcileBeforeNewEntriesResult()
        if (!reconciliation.ready) {
            val prefix = if (reconciliation.status == DerivReconciliationStatus.MANUAL_RECONCILIATION_REQUIRED)
                "RECONCILIATION_REQUIRED" else "PORTFOLIO_RETRYABLE"
            return@withContext Result.failure(Exception("$prefix: ${reconciliation.reason}; no purchase was sent"))
        }
        // Refresh active-contract state, then enforce single-contract exposure at the
        // executor boundary so a future UI/service caller cannot bypass this guard.
        if (!syncKnownContracts()) return@withContext Result.failure(Exception("PORTFOLIO_REQUIRED: fresh broker portfolio refresh failed; no purchase was sent"))
        val activeCount = dao.knownContracts(current.loginId, current.mode.uppercase()).size
        val unresolvedCount = dao.unresolved(current.loginId, current.mode.uppercase()).size
        val snapshot = gateway.portfolioSnapshot.value
        if (snapshot == null || snapshot.contracts.isNotEmpty() ||
            !DerivMultiplierRules.canOpenNewEntry(activeCount, unresolvedCount)) {
            return@withContext Result.failure(Exception("ACTIVE_CONTRACT_LIMIT: the broker reports an open contract or unresolved execution exists; no additional purchase was sent"))
        }
        val now = System.currentTimeMillis()
        val snapshotAge = snapshot?.let { now - it.retrievedAtMs } ?: Long.MAX_VALUE
        if (snapshot == null || !snapshot.successful || snapshot.accountId != current.loginId ||
            snapshot.accountMode != current.mode.uppercase() ||
            !snapshot.currency.equals(current.currency, ignoreCase = true) ||
            snapshotAge !in 0..PORTFOLIO_MAX_AGE_MS || !snapshot.exposure.isFinite()) {
            return@withContext Result.failure(Exception("PORTFOLIO_REQUIRED: fresh account-, currency- and contract-validated broker portfolio is required; no purchase was sent"))
        }
        val metrics = gateway.accountMetrics.value
        val balanceAge = now - gateway.accountMetricsUpdatedAtMs.value
        if (metrics == null || !metrics.balance.isFinite() || metrics.balance <= 0.0 ||
            !metrics.currency.equals(current.currency, ignoreCase = true) ||
            balanceAge !in 0..PORTFOLIO_MAX_AGE_MS) {
            return@withContext Result.failure(Exception("BALANCE_REQUIRED: fresh positive broker balance in the verified account currency is required; no purchase was sent"))
        }
        val balance = metrics.balance
        val currentExposure = if (balance.isFinite() && balance > 0.0) snapshot.exposure / balance * 100.0 else Double.POSITIVE_INFINITY
        val candidateExposure = if (balance.isFinite() && balance > 0.0) intent.stake / balance * 100.0 else Double.POSITIVE_INFINITY
        if (!currentExposure.isFinite() || !candidateExposure.isFinite() || currentExposure + candidateExposure > MAX_OPEN_RISK_PERCENT + 1e-9) {
            return@withContext Result.failure(Exception("PORTFOLIO_RISK_LIMIT: aggregate open risk would exceed ${MAX_OPEN_RISK_PERCENT}% of the verified account balance; no purchase was sent"))
        }
        val validation = DerivMultiplierRules.validate(intent, balance, current.currency)
        if (!validation.valid) return@withContext Result.failure(DerivExecutionFailure(DerivExecutionFailureKind.VALIDATION_REJECTED, validation.reason ?: "Multiplier intent rejected"))

        val initial = intent.toEntity(DerivExecutionStatus.ORDER_SUBMITTING.name, DerivSubmissionStage.VALIDATED.name)
        if (dao.insertIfAbsent(initial) == -1L) return@withContext Result.failure(Exception("DUPLICATE_SIGNAL: persisted execution intent already exists"))
        val result = gateway.executeMultiplier(
            intent,
            onStage = { requestId, proposalId, stage ->
                val saved = dao.findBySignalId(current.loginId, current.mode.uppercase(), intent.signalId) ?: initial
                dao.update(saved.copy(requestId = requestId ?: saved.requestId, proposalId = proposalId ?: saved.proposalId, submissionStage = stage.name, status = if (stage == DerivSubmissionStage.PURCHASE_SENT) DerivExecutionStatus.ORDER_PENDING.name else DerivExecutionStatus.ORDER_SUBMITTING.name, updatedAtMs = System.currentTimeMillis()))
            },
            allowPurchase = { dailyRiskEntryAllowed(current.loginId, current.mode.uppercase()) }
        )
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
            dao.update(saved.copy(
                status = status.name,
                requestId = typed?.requestId ?: saved.requestId,
                proposalId = typed?.proposalId ?: saved.proposalId,
                submissionStage = typed?.submissionStage?.name ?: saved.submissionStage,
                contractId = typed?.contractId ?: saved.contractId,
                buyTransactionId = typed?.buyTransactionId ?: saved.buyTransactionId,
                purchasePrice = typed?.purchasePrice ?: saved.purchasePrice,
                lastError = failure?.message?.take(500),
                updatedAtMs = System.currentTimeMillis()
            ))
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
