package com.example.data.mt5

/**
 * Non-secret account registry projection used by the Android UI. Secrets and bridge tokens
 * remain in encrypted storage and are never exposed by this model.
 */
data class MT5AccountSlot(
    val accountId: String,
    val broker: String,
    val platform: String,
    val accountMode: String,
    val login: String,
    val server: String,
    val accessType: String,
    val connected: Boolean,
    val passwordSaved: Boolean,
    val actualCompany: String? = null,
    val currency: String? = null,
    val terminalInstanceId: String? = null,
    val lastAuthenticatedAtMs: Long? = null,
    val lastHeartbeatAt: String? = null,
    val lastError: String? = null,
    val tradingAllowed: Boolean = false,
    val executionReady: Boolean = false
)
