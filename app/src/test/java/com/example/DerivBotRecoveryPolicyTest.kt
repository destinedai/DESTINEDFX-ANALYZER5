package com.example

import android.content.Intent
import com.example.service.DerivBotRecoveryPolicy
import com.example.service.DerivBotServiceStatePolicy
import com.destinedfx.core.strategy.StrategyRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivBotRecoveryPolicyTest {
    private val strategy = StrategyRegistry.managedStrategyIds.first()

    @Test
    fun connectingAndReconcilingAreActiveAndCannotStartAgain() {
        for (state in listOf("STARTING", "CONNECTING", "RECONCILING", "RUNNING")) {
            assertTrue(DerivBotServiceStatePolicy.isSwitchChecked(state))
            assertFalse(DerivBotServiceStatePolicy.canStart(state))
            assertTrue(DerivBotServiceStatePolicy.canStop(state))
        }
    }

    @Test
    fun pausedErrorAndStoppedAreNotReportedAsActive() {
        for (state in listOf("PAUSED", "ERROR", "STOPPED")) {
            assertFalse(DerivBotServiceStatePolicy.isSwitchChecked(state))
            assertTrue(DerivBotServiceStatePolicy.canStart(state))
        }
    }

    @Test
    fun onlyNormalBootDemoWithCredentialsAndValidConfigRecovers() {
        assertTrue(shouldRecover(Intent.ACTION_BOOT_COMPLETED, "demo", true, true, "R_100", strategy, "M5"))
        assertFalse(shouldRecover(Intent.ACTION_LOCKED_BOOT_COMPLETED, "demo", true, true, "R_100", strategy, "M5"))
        assertFalse(shouldRecover(Intent.ACTION_BOOT_COMPLETED, "real", true, true, "R_100", strategy, "M5"))
        assertFalse(shouldRecover(Intent.ACTION_BOOT_COMPLETED, "demo", true, false, "R_100", strategy, "M5"))
        assertFalse(shouldRecover(Intent.ACTION_BOOT_COMPLETED, "demo", false, true, "R_100", strategy, "M5"))
        assertFalse(shouldRecover(Intent.ACTION_BOOT_COMPLETED, "demo", true, true, "not-a-deriv-symbol", strategy, "M5"))
        assertFalse(shouldRecover(Intent.ACTION_BOOT_COMPLETED, "demo", true, true, "R_100", "invalid-strategy", "M5"))
        assertFalse(shouldRecover(Intent.ACTION_BOOT_COMPLETED, "demo", true, true, "R_100", strategy, ""))
        assertFalse(shouldRecover(Intent.ACTION_BOOT_COMPLETED, "demo", true, true, "R_100", strategy, "not-a-timeframe"))
    }

    @Test
    fun explicitServiceStartRequiresAcceptedDemoAndVisibleNotificationPermission() {
        assertTrue(DerivBotRecoveryPolicy.canStartExplicitDemo("demo", true, true))
        assertFalse(DerivBotRecoveryPolicy.canStartExplicitDemo("real", true, true))
        assertFalse(DerivBotRecoveryPolicy.canStartExplicitDemo("demo", false, true))
        assertFalse(DerivBotRecoveryPolicy.canStartExplicitDemo("demo", true, false))
        assertFalse(DerivBotRecoveryPolicy.isDemoAccount("real", "real"))
        assertFalse(DerivBotRecoveryPolicy.isDemoAccount("demo", "real"))
        assertTrue(DerivBotRecoveryPolicy.isDemoAccount("demo", "demo"))
    }

    @Test
    fun persistedRealConfigurationAndDeniedNotificationsNeverRecover() {
        assertFalse(shouldRecover(Intent.ACTION_BOOT_COMPLETED, "real", true, true, "R_100", strategy, "M5"))
        assertFalse(shouldRecover(Intent.ACTION_BOOT_COMPLETED, "demo", true, true, "R_100", strategy, "M5", notificationsAllowed = false))
        assertFalse(shouldRecover(Intent.ACTION_BOOT_COMPLETED, "demo", false, true, "R_100", strategy, "M5"))
    }

    @Test
    fun processDeathRecoveryRequiresAcceptedDemoAndCurrentOwnerVisibleNotifications() {
        fun allowed(mode: String = "demo", enabled: Boolean = true, credentials: Boolean = true,
                    accepted: Boolean = true, notifications: Boolean = true) =
            DerivBotRecoveryPolicy.canRecoverAfterProcessDeath(
                mode, enabled, credentials, accepted, "R_100", strategy, "M5", notifications
            )
        assertTrue(allowed())
        assertFalse(allowed(mode = "real"))
        assertFalse(allowed(accepted = false))
        assertFalse(allowed(credentials = false))
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(notifications = false))
    }

    private fun shouldRecover(
        action: String,
        mode: String,
        enabled: Boolean,
        credentials: Boolean,
        symbol: String,
        strategy: String,
        timeframe: String,
        notificationsAllowed: Boolean = true
    ) = DerivBotRecoveryPolicy.shouldRecover(action, mode, enabled, credentials, symbol, strategy, timeframe, notificationsAllowed)
}
