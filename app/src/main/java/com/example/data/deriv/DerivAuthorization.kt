package com.example.data.deriv

/**
 * Process-memory-only authorization for one exact Deriv REAL account session.
 * No persisted preference can satisfy this contract.
 */
data class DerivRealAuthorizationLease(
    val brokerIdentity: String,
    val accountId: String,
    val accountType: String,
    val currency: String,
    val authenticatedSessionId: String,
    val credentialGeneration: String,
    val armedAtMs: Long,
    val state: State = State.ARMED
) {
    enum class State { ARMED, INVALIDATED }

    companion object {
        const val DEFAULT_VALIDITY_MS: Long = 60 * 60 * 1_000L
    }

    /** A lease is valid only in the same process-time interval in which it was explicitly armed. */
    fun isValidAt(nowMs: Long, validityMs: Long = DEFAULT_VALIDITY_MS): Boolean =
        state == State.ARMED && validityMs > 0L && armedAtMs > 0L &&
            nowMs >= armedAtMs && nowMs - armedAtMs < validityMs

    fun matches(
        broker: String,
        currentAccountId: String,
        currentAccountType: String,
        currentCurrency: String,
        currentSessionId: String,
        currentCredentialGeneration: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = isValidAt(nowMs) &&
        brokerIdentity == broker &&
        accountId == currentAccountId &&
        accountType.equals(currentAccountType, ignoreCase = true) &&
        currency.equals(currentCurrency, ignoreCase = true) &&
        authenticatedSessionId == currentSessionId &&
        credentialGeneration == currentCredentialGeneration
}

/** Shared fail-closed predicate for account readiness, including session and snapshot freshness. */
object DerivAccountReadiness {
    const val MAX_SNAPSHOT_AGE_MS: Long = 8_000L

    fun isReadyForTrading(
        connectionState: DerivConnectionState,
        account: DerivAccountInfo?,
        currentSessionId: String?,
        currentCredentialGeneration: String,
        balance: Double?,
        balanceCurrency: String?,
        balanceUpdatedAtMs: Long,
        portfolio: DerivPortfolioSnapshot?,
        unresolvedExecutionCount: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = unresolvedExecutionCount == 0 && isSynchronized(
        connectionState = connectionState,
        account = account,
        currentSessionId = currentSessionId,
        currentCredentialGeneration = currentCredentialGeneration,
        balance = balance,
        balanceCurrency = balanceCurrency,
        balanceUpdatedAtMs = balanceUpdatedAtMs,
        portfolio = portfolio,
        nowMs = nowMs
    )

    /** Synchronization predicate used again immediately before a broker purchase is transmitted. */
    fun isSynchronized(
        connectionState: DerivConnectionState,
        account: DerivAccountInfo?,
        currentSessionId: String?,
        currentCredentialGeneration: String,
        balance: Double?,
        balanceCurrency: String?,
        balanceUpdatedAtMs: Long,
        portfolio: DerivPortfolioSnapshot?,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val current = account ?: return false
        val mode = current.mode.trim().lowercase()
        if (connectionState != DerivConnectionState.READY ||
            current.loginId.isBlank() || mode !in setOf("demo", "real") ||
            !current.accountType.equals(mode, ignoreCase = true) ||
            (mode == "demo" && !current.demoVerified) ||
            current.currency.isBlank() || !current.currency.matches(Regex("[A-Za-z0-9]{2,20}")) ||
            current.authenticatedSessionId.isBlank() || current.authenticatedSessionId != currentSessionId ||
            current.credentialGeneration.isBlank() || current.credentialGeneration != currentCredentialGeneration
        ) return false

        val balanceAge = nowMs - balanceUpdatedAtMs
        if (balance == null || !balance.isFinite() || balance <= 0.0 ||
            balanceCurrency.isNullOrBlank() || !balanceCurrency.equals(current.currency, ignoreCase = true) ||
            balanceUpdatedAtMs <= 0L || balanceAge !in 0..MAX_SNAPSHOT_AGE_MS
        ) return false

        val snapshot = portfolio ?: return false
        val portfolioAge = nowMs - snapshot.retrievedAtMs
        return snapshot.successful &&
            snapshot.accountId == current.loginId &&
            snapshot.accountMode == mode.uppercase() &&
            snapshot.currency.equals(current.currency, ignoreCase = true) &&
            snapshot.retrievedAtMs > 0L && portfolioAge in 0..MAX_SNAPSHOT_AGE_MS &&
            snapshot.exposure.isFinite()
    }
}
