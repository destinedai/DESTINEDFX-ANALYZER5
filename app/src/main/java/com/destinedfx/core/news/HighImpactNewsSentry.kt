package com.destinedfx.core.news

import com.example.data.broker.BrokerGateway
import com.destinedfx.core.instrument.AssetClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HighImpactNewsAlert(
    val eventName: String,
    val currency: String,
    val status: NewsStatus,
    val detailMessage: String,
    val autoExitExecuted: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

object HighImpactNewsSentry {

    private val _sentryAlert = MutableStateFlow<HighImpactNewsAlert?>(null)
    val sentryAlert: StateFlow<HighImpactNewsAlert?> = _sentryAlert

    suspend fun auditAndProtectPositions(
        symbol: String,
        gateway: BrokerGateway,
        calendarResult: CalendarResult?,
        autoExitEnabled: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): NewsVerdict {
        val currencies = AssetClassifier.newsCurrencies(symbol)
        val verdict = NewsFilter.evaluate(calendarResult, currencies, nowMs)

        if (verdict.status == NewsStatus.HIGH_IMPACT_WINDOW) {
            val event = verdict.event
            val eventName = event?.name ?: "High Impact News Release"
            val currency = event?.currency ?: currencies.firstOrNull() ?: "USD"

            var exited = false
            if (autoExitEnabled) {
                try {
                    val openPositions = gateway.openPositions.value
                    val symbolPositions = openPositions.filter { it.symbol.equals(symbol, ignoreCase = true) }
                    for (pos in symbolPositions) {
                        gateway.closePosition(pos.id)
                        exited = true
                    }
                } catch (_: Exception) {
                }
            }

            _sentryAlert.value = HighImpactNewsAlert(
                eventName = eventName,
                currency = currency,
                status = verdict.status,
                detailMessage = "HIGH IMPACT NEWS DETECTED: $eventName ($currency) • ${if (exited) "AUTO-EXITED OPEN POSITIONS TO PREVENT SLIPPAGE" else "NEWS GUARD ACTIVE"}",
                autoExitExecuted = exited
            )
        } else {
            if (_sentryAlert.value != null && _sentryAlert.value?.status == NewsStatus.HIGH_IMPACT_WINDOW) {
                // Clear old alert when window is clear
                _sentryAlert.value = null
            }
        }

        return verdict
    }

    fun dismissAlert() {
        _sentryAlert.value = null
    }
}
