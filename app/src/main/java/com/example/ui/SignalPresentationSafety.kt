package com.example.ui

import com.destinedfx.core.strategy.StrategyRegistry
import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal

/** Maps UI labels to strategy IDs accepted by the deterministic signal engine. */
internal fun resolveSelectedStrategyId(selection: String): String =
    StrategyRegistry.allStrategies.firstOrNull {
        it.id.equals(selection, ignoreCase = true) ||
            it.name.equals(selection, ignoreCase = true)
    }?.id ?: StrategyRegistry.SMC_CONFLUENCE.id

/** A non-directional result must stay WAIT/NEUTRAL in UI, reports, and history. */
internal fun signalHistoryLabel(direction: SignalDirection): String = when (direction) {
    SignalDirection.LONG -> "BUY"
    SignalDirection.SHORT -> "SELL"
    SignalDirection.WAIT -> "WAIT"
}

internal fun signalBiasLabel(direction: SignalDirection): String = when (direction) {
    SignalDirection.LONG -> "BULLISH"
    SignalDirection.SHORT -> "BEARISH"
    SignalDirection.WAIT -> "NEUTRAL"
}

/** Confidence is not sufficient: only a fully validated executable plan may trigger an alert. */
internal fun shouldSendHighConfidenceAlert(signal: TradeSignal): Boolean =
    signal.executable &&
        signal.confidenceScore >= 85 &&
        signal.direction != SignalDirection.WAIT &&
        signal.direction in setOf(SignalDirection.LONG, SignalDirection.SHORT) &&
        signal.entryPrice.isFinite() && signal.entryPrice > 0.0
