package com.example.ui

import com.example.domain.model.OBType
import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal
import java.util.Locale
import kotlin.math.abs

data class OrderBlockUiModel(
    val type: String, // "BULLISH" or "BEARISH"
    val zone: String, // e.g. "1.08200 - 1.08350"
    val strength: String, // "HIGH", "MEDIUM", "LOW"
    val topPrice: Double = 0.0,
    val bottomPrice: Double = 0.0
)

data class DynamicAnalysisInfo(
    val symbol: String = "EUR/USD",
    val direction: String = "WAIT",
    val confidence: String = "NONE",
    val confidencePct: Int = 0,
    val entry: Double = 0.0,
    val stopLoss: Double = 0.0,
    val tp1: Double = 0.0,
    val tp2: Double = 0.0,
    val tp3: Double = 0.0,
    val smcAnalysis: String = "",
    val orderBlocks: List<OrderBlockUiModel> = emptyList(),
    val wyckoffPhase: String = "N/A (not implemented)",
    val elliottWave: String = "N/A (not implemented)",
    val marketStructureNote: String = "",
    val keyLevels: List<String> = emptyList(),
    val riskNote: String = "",
    val sessionBias: String = "",
    val mtf1M: String = "N/A",
    val mtf5M: String = "N/A",
    val mtf15M: String = "N/A",
    val mtf1H: String = "N/A",
    val mtf4H: String = "N/A",
    val mtf1D: String = "N/A",
    val strategyScores: Map<String, Int> = emptyMap(),
    val tp1YieldPips: Double = 0.0,
    val tp2YieldPips: Double = 0.0,
    val tp3YieldPips: Double = 0.0,
    val tp1DollarYield: Double = 0.0,
    val tp1RR: String = "N/A",
    val tp2RR: String = "N/A",
    val tp3RR: String = "N/A",
    val entryReason: String = "",
    val slReason: String = "",
    val tpReason: String = "",
    val dataSource: String = "",
    val strategyVersion: String = "",
    val signalState: String = "WAIT",
    val blockReasons: List<String> = emptyList()
)

fun formatPrice(price: Double, symbol: String = ""): String {
    if (price == 0.0) return "0.00000"
    val clean = symbol.uppercase()
    return when {
        clean.contains("JPY") -> String.format(Locale.US, "%.3f", price)
        clean.contains("BTC") || clean.contains("ETH") || clean.contains("XAU") || clean.contains("US30") || clean.contains("NAS") || clean.contains("SPX") -> String.format(Locale.US, "%.2f", price)
        else -> String.format(Locale.US, "%.5f", price)
    }
}

fun mapTradeSignalToUiModel(signal: TradeSignal): DynamicAnalysisInfo {
    val dirStr = when (signal.direction) {
        SignalDirection.LONG -> "BUY"
        SignalDirection.SHORT -> "SELL"
        SignalDirection.WAIT -> "WAIT"
    }

    val confLabel = when {
        signal.confidenceScore >= 80 -> "HIGH"
        signal.confidenceScore >= 60 -> "MODERATE"
        signal.confidenceScore >= 40 -> "NEUTRAL"
        else -> "LOW"
    }

    val isJpy = signal.symbol.contains("JPY", ignoreCase = true)
    val pipMultiplier = if (isJpy) 100.0 else 10000.0
    val riskDiff = abs(signal.entryPrice - signal.stopLoss)

    // TP comes ONLY from the deterministic engine. No synthetic 2R/3R/5R targets.
    val tp1Price = signal.takeProfit
    val tp2Price = 0.0
    val tp3Price = 0.0
    val tp1Pips = if (tp1Price != 0.0 && signal.entryPrice != 0.0) abs(tp1Price - signal.entryPrice) * pipMultiplier else 0.0
    val tp2Pips = 0.0
    val tp3Pips = 0.0
    val rrText = if (tp1Price != 0.0 && riskDiff > 0.0) String.format(Locale.US, "1:%.2f", abs(tp1Price - signal.entryPrice) / riskDiff) else "N/A"

    val obUiList = signal.orderBlocks.map { ob ->
        val obType = if (ob.type == OBType.BULLISH) "BULLISH" else "BEARISH"
        val strength = if (ob.validityScore >= 80) "HIGH" else if (ob.validityScore >= 60) "MEDIUM" else "MODERATE"
        OrderBlockUiModel(
            type = obType,
            zone = "${formatPrice(ob.bottomPrice, signal.symbol)} - ${formatPrice(ob.topPrice, signal.symbol)}",
            strength = strength,
            topPrice = ob.topPrice,
            bottomPrice = ob.bottomPrice
        )
    }

    return DynamicAnalysisInfo(
        symbol = signal.symbol,
        direction = dirStr,
        confidence = confLabel,
        confidencePct = signal.confidenceScore,
        entry = signal.entryPrice,
        stopLoss = signal.stopLoss,
        tp1 = tp1Price,
        tp2 = tp2Price,
        tp3 = tp3Price,
        smcAnalysis = signal.rationale,
        orderBlocks = obUiList,
        wyckoffPhase = "N/A (not implemented)",
        elliottWave = "N/A (not implemented)",
        marketStructureNote = signal.rationale,
        keyLevels = signal.keyLevels,
        riskNote = if (signal.executable) "Risk sized by RiskEngine (max 1% of equity)." else "Analysis only; trading blocked or quote unconfirmed.",
        sessionBias = signal.sessionLabel.ifBlank { "N/A" },
        mtf1M = "N/A",
        mtf5M = "N/A",
        mtf15M = "N/A",
        mtf1H = "N/A",
        mtf4H = "N/A",
        mtf1D = "N/A",
        strategyScores = signal.strategyScores,
        tp1YieldPips = tp1Pips,
        tp2YieldPips = tp2Pips,
        tp3YieldPips = tp3Pips,
        tp1DollarYield = 0.0,
        tp1RR = rrText,
        tp2RR = "N/A",
        tp3RR = "N/A",
        entryReason = signal.entryReason.ifBlank { "N/A" },
        slReason = signal.stopLossReason.ifBlank { "N/A" },
        tpReason = signal.takeProfitReason.ifBlank { "N/A" },
        dataSource = signal.dataSource.ifBlank { "UNKNOWN" },
        strategyVersion = signal.strategyVersion.ifBlank { "smc-confluence-1.0.0" },
        signalState = signal.signalState,
        blockReasons = signal.blockReasons
    )
}
