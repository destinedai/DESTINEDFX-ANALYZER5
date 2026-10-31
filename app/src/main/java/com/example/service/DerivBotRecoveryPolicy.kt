package com.example.service

import android.content.Intent
import com.example.data.deriv.DerivMultiplierRules
import com.destinedfx.core.marketdata.Timeframe
import com.destinedfx.core.strategy.StrategyRegistry

/** Pure policy used by the receiver and tests; it never reads or writes secrets. */
internal object DerivBotRecoveryPolicy {
    fun shouldRecover(
        action: String?,
        accountMode: String,
        enabled: Boolean,
        hasCredentials: Boolean,
        symbol: String,
        strategy: String,
        timeframe: String
    ): Boolean = action == Intent.ACTION_BOOT_COMPLETED &&
        accountMode.equals("demo", ignoreCase = true) &&
        enabled && hasCredentials &&
        DerivMultiplierRules.isSupportedUnderlying(symbol) &&
        strategy in StrategyRegistry.managedStrategyIds &&
        Timeframe.parse(timeframe) != null
}

internal object DerivBotServiceStatePolicy {
    private val activeStates = setOf("CONNECTING", "RECONCILING", "RUNNING")

    fun isSwitchChecked(state: String): Boolean = state in activeStates
    fun canStart(state: String): Boolean = state !in activeStates
    fun canStop(state: String): Boolean = state in activeStates
}
