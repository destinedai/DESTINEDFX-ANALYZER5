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
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("deriv_bot_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        // A stale preference must not be enough to restart a live account after reboot.
        val isDemo = runCatching { SecretsManager(context).getDerivAccountMode().equals("demo", ignoreCase = true) }
            .getOrDefault(false)
        if (!isDemo) return

        val symbol = prefs.getString(DerivDemoBotService.EXTRA_SYMBOL, null)?.trim().orEmpty()
        val strategy = prefs.getString(DerivDemoBotService.EXTRA_STRATEGY, null)?.trim().orEmpty()
        val timeframe = prefs.getString(DerivDemoBotService.EXTRA_TIMEFRAME, null)?.trim().orEmpty()
        if (symbol.isBlank() || strategy.isBlank() || timeframe.isBlank()) return

        val serviceIntent = Intent(context, DerivDemoBotService::class.java).apply {
            action = DerivDemoBotService.ACTION_START
            putExtra(DerivDemoBotService.EXTRA_SYMBOL, symbol)
            putExtra(DerivDemoBotService.EXTRA_STRATEGY, strategy)
            putExtra(DerivDemoBotService.EXTRA_TIMEFRAME, timeframe)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
