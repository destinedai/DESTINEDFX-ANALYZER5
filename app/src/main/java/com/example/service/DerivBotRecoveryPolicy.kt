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
        timeframe: String,
        notificationsAllowed: Boolean = true
    ): Boolean = action == Intent.ACTION_BOOT_COMPLETED &&
        accountMode.equals("demo", ignoreCase = true) &&
        enabled && hasCredentials && notificationsAllowed &&
        DerivMultiplierRules.isSupportedUnderlying(symbol) &&
        strategy in StrategyRegistry.managedStrategyIds &&
        Timeframe.parse(timeframe) != null

    fun canStartExplicitDemo(accountMode: String, currentAcceptance: Boolean, notificationsAllowed: Boolean): Boolean =
        accountMode.equals("demo", ignoreCase = true) && currentAcceptance && notificationsAllowed

    fun canRecoverAfterProcessDeath(
        accountMode: String,
        enabled: Boolean,
        hasCredentials: Boolean,
        currentAcceptance: Boolean,
        symbol: String,
        strategy: String,
        timeframe: String,
        notificationsAllowed: Boolean
    ): Boolean = accountMode.equals("demo", ignoreCase = true) && enabled && hasCredentials && currentAcceptance &&
        notificationsAllowed && DerivMultiplierRules.isSupportedUnderlying(symbol) &&
        strategy in StrategyRegistry.managedStrategyIds && Timeframe.parse(timeframe) != null

    fun isDemoAccount(accountMode: String, accountType: String): Boolean =
        accountMode.equals("demo", ignoreCase = true) && accountType.equals("demo", ignoreCase = true)
}

internal object DerivBotServiceStatePolicy {
    private val activeStates = setOf("STARTING", "CONNECTING", "RECONCILING", "RUNNING")

    fun isSwitchChecked(state: String): Boolean = state in activeStates
    fun canStart(state: String): Boolean = state !in activeStates
    fun canStop(state: String): Boolean = state in activeStates
}
