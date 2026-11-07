package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.MyApplication
import com.example.security.SecretsManager

/**
 * Requests DEMO bot recovery after a normal device reboot. The service performs the final
 * secure-storage and account checks; REAL trading is never started by this receiver.
 */
class DerivBotBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        // Resume telemetry-only monitoring for saved MT5 profiles after a reboot.
        // This never starts an MT5 terminal or authorizes a trade; the Windows worker/EA
        // remains the execution authority and every profile is independently verified.
        val secure = runCatching { SecretsManager(context) }.getOrNull() ?: return
        if (secure.isSecureStorageAvailable &&
            secure.getManagedMT5ServiceUrl().isNotBlank() &&
            secure.getConfiguredMT5AccountProfileKeys().isNotEmpty()
        ) {
            runCatching { MT5AccountSessionService.start(context) }
        }

        val prefs = context.getSharedPreferences("deriv_bot_config", Context.MODE_PRIVATE)
        val symbol = prefs.getString(DerivDemoBotService.EXTRA_SYMBOL, null)?.trim().orEmpty()
        val strategy = prefs.getString(DerivDemoBotService.EXTRA_STRATEGY, null)?.trim().orEmpty()
        val timeframe = prefs.getString(DerivDemoBotService.EXTRA_TIMEFRAME, null)?.trim().orEmpty()
        val enabled = prefs.getBoolean("enabled", false)
        if (!enabled) return
        val mode = secure.getDerivAccountMode()
        val hasCredentials = secure.isSecureStorageAvailable &&
            secure.getDerivApiToken("demo").isNotBlank() && secure.getDerivAppId().isNotBlank() &&
            secure.getDerivCredentialGeneration("demo").isNotBlank() &&
            (context.applicationContext as? MyApplication)?.let { runCatching { it.modeArmingPolicy }.isSuccess } == true
        if (!DerivDemoBotService.hasNotificationPermission(context)) {
            DerivDemoBotService.recordStartBlocked(context, "Notification permission is denied. Open DestinedFX, allow notifications, then tap Start again.")
            return
        }
        if (!mode.equals("demo", true)) {
            DerivDemoBotService.recordStartBlocked(context, "Persisted REAL mode is never restarted automatically. Select DEMO and explicitly start after verification.")
            return
        }
        if (!hasCredentials) {
            DerivDemoBotService.recordStartBlocked(context, "Secure storage, DEMO PAT, or registered App ID is unavailable after reboot. Re-enter and verify the DEMO account.")
            return
        }
        if (!secure.hasCurrentDerivDemoAcceptance()) {
            DerivDemoBotService.recordStartBlocked(context, "Current DEMO acceptance is missing or stale after reboot. Verify and explicitly accept the account again.")
            return
        }
        if (!DerivBotRecoveryPolicy.shouldRecover(
                intent?.action,
                mode,
                enabled,
                hasCredentials,
                symbol,
                strategy,
                timeframe,
                notificationsAllowed = true
            )) {
            DerivDemoBotService.recordStartBlocked(context, "Saved DEMO recovery configuration is incomplete or unsupported. Open DestinedFX and start again after checking the symbol, strategy, and timeframe.")
            return
        }
        DerivDemoBotService.start(context, symbol, strategy, timeframe)
    }
}
