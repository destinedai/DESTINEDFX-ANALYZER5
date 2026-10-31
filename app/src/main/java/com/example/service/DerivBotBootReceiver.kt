package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.security.SecretsManager

/**
 * Requests DEMO bot recovery after a normal device reboot. The service performs the final
 * secure-storage and account checks; REAL trading is never started by this receiver.
 */
class DerivBotBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("deriv_bot_config", Context.MODE_PRIVATE)
        val symbol = prefs.getString(DerivDemoBotService.EXTRA_SYMBOL, null)?.trim().orEmpty()
        val strategy = prefs.getString(DerivDemoBotService.EXTRA_STRATEGY, null)?.trim().orEmpty()
        val timeframe = prefs.getString(DerivDemoBotService.EXTRA_TIMEFRAME, null)?.trim().orEmpty()
        val secure = runCatching { SecretsManager(context) }.getOrNull() ?: return
        val hasCredentials = secure.isSecureStorageAvailable &&
            secure.getDerivApiToken().isNotBlank() && secure.getDerivAppId().isNotBlank()
        if (!DerivBotRecoveryPolicy.shouldRecover(
                intent?.action,
                secure.getDerivAccountMode(),
                prefs.getBoolean("enabled", false),
                hasCredentials,
                symbol,
                strategy,
                timeframe
            )) return

        val serviceIntent = Intent(context, DerivDemoBotService::class.java).apply {
            action = DerivDemoBotService.ACTION_START
            putExtra(DerivDemoBotService.EXTRA_SYMBOL, symbol)
            putExtra(DerivDemoBotService.EXTRA_STRATEGY, strategy)
            putExtra(DerivDemoBotService.EXTRA_TIMEFRAME, timeframe)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
