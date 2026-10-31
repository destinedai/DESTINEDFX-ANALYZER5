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
        for (state in listOf("CONNECTING", "RECONCILING", "RUNNING")) {
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
    }

    private fun shouldRecover(
        action: String,
        mode: String,
        enabled: Boolean,
        credentials: Boolean,
        symbol: String,
        strategy: String,
        timeframe: String
    ) = DerivBotRecoveryPolicy.shouldRecover(action, mode, enabled, credentials, symbol, strategy, timeframe)
}
