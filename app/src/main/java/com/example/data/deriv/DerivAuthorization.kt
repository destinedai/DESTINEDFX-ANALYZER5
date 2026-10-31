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

    fun matches(
        broker: String,
        currentAccountId: String,
        currentAccountType: String,
        currentCurrency: String,
        currentSessionId: String,
        currentCredentialGeneration: String
    ): Boolean = state == State.ARMED &&
        brokerIdentity == broker &&
        accountId == currentAccountId &&
        accountType.equals(currentAccountType, ignoreCase = true) &&
        currency.equals(currentCurrency, ignoreCase = true) &&
        authenticatedSessionId == currentSessionId &&
        credentialGeneration == currentCredentialGeneration
}
