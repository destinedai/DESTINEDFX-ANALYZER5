package com.example.ui

import com.example.domain.model.MarketTicker
import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal
import java.util.Locale

/** Distinguishes quote, candles, chart display, analysis, and execution readiness. */
data class MarketReadiness(
    val quoteLive: Boolean,
    val candlesConfirmed: Boolean,
    val chartDisplayed: Boolean,
    val analysisCompleted: Boolean,
    val tradingEligible: Boolean,
    val quoteLabel: String,
    val candleLabel: String,
    val chartLabel: String,
    val analysisLabel: String,
    val eligibilityLabel: String
)

fun deriveMarketReadiness(
    ticker: MarketTicker?,
    candleSource: String,
    candlesAvailable: Boolean,
    chartDisplayed: Boolean,
    signal: TradeSignal?,
    nowMs: Long = System.currentTimeMillis()
): MarketReadiness {
    val quoteLive = ticker?.isLive == true && ticker.lastUpdateMillis > 0L &&
        nowMs - ticker.lastUpdateMillis in 0..15_000L
    val candlesConfirmed = candlesAvailable && candleSource.isNotBlank()
    val analysisCompleted = signal != null
    val tradingEligible = signal?.executable == true
    return MarketReadiness(
        quoteLive = quoteLive,
        candlesConfirmed = candlesConfirmed,
        chartDisplayed = chartDisplayed,
        analysisCompleted = analysisCompleted,
        tradingEligible = tradingEligible,
        quoteLabel = if (quoteLive) "Live quote received" else "Quote unavailable or delayed",
        candleLabel = if (candlesConfirmed) "Fresh OHLC candles confirmed" else "OHLC candles not confirmed",
        chartLabel = if (chartDisplayed) "Chart displayed" else "Chart not displayed",
        analysisLabel = when {
            signal == null -> "Analysis not completed"
            else -> "Analysis completed"
        },
        eligibilityLabel = if (tradingEligible) "Trading checks passed" else "Not eligible to trade"
    )
}

fun userFacingSignalLabel(signalState: String?, direction: SignalDirection? = null): String = when {
    signalState.orEmpty().uppercase(Locale.ROOT) in setOf("BUY", "STRONG_BUY", "WEAK_BUY") -> "BUY"
    signalState.orEmpty().uppercase(Locale.ROOT) in setOf("SELL", "STRONG_SELL", "WEAK_SELL") -> "SELL"
    signalState.orEmpty().uppercase(Locale.ROOT) == "DATA_UNAVAILABLE" -> "DATA UNAVAILABLE"
    signalState.orEmpty().uppercase(Locale.ROOT) in setOf("BLOCKED", "INVALIDATED") -> "NO VALID SETUP"
    direction == SignalDirection.LONG -> "BUY"
    direction == SignalDirection.SHORT -> "SELL"
    else -> "WAIT"
}

fun plainTradingReason(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) return "Waiting for a confirmed entry."
    val key = value.uppercase(Locale.ROOT)
    return when {
        key.contains("NO_CONFIRMED_BOS") || key.contains("NO CONFIRMED BOS") -> "Market structure has not confirmed a breakout yet."
        key.contains("MTF") && (key.contains("CONFLICT") || key.contains("OPPOSE")) -> "Higher timeframes disagree with this setup."
        key.contains("STALE") || key.contains("DELAYED") -> "Market data is outdated; waiting for a fresh update."
        key.contains("INSUFFICIENT_LIQUIDITY") || key.contains("LIQUIDITY") && key.contains("INSUFFICIENT") -> "Market liquidity is not sufficient for a reliable setup."
        key.contains("RISK") && (key.contains("REJECT") || key.contains("BLOCK")) -> "This setup does not meet the account risk requirements."
        key.contains("PYTHON") && (key.contains("PENDING") || key.contains("WAIT")) -> "Analysis is still being completed."
        key.contains("QUOTE") && key.contains("CANDLE") -> "The quote and candle feeds do not match, so the setup is blocked."
        key.contains("DATA") && (key.contains("ERROR") || key.contains("UNAVAILABLE")) -> "Reliable market data is not available right now."
        key.contains("NOT_IMPLEMENTED") -> "This strategy is not available for the selected market yet."
        else -> value.replace('_', ' ').replace(Regex("\\s+"), " ").trim().replaceFirstChar { it.uppercase() }
    }
}

fun conciseReasons(signal: TradeSignal, limit: Int = 3): List<String> {
    val source = (signal.reasons + signal.blockReasons + signal.knowledge)
        .map(::plainTradingReason)
        .filter { it.isNotBlank() }
        .distinct()
    return if (source.isEmpty()) listOf("Waiting for a valid entry confirmation.") else source.take(limit)
}
