package com.example.domain.patterns

import com.example.domain.model.Candle

/** Classical candle formations. Confidence is pattern-strength evidence, never a win probability. */
enum class CandlestickPatternType(val displayName: String) {
    HAMMER("Hammer"),
    HANGING_MAN("Hanging Man"),
    SHOOTING_STAR("Shooting Star"),
    INVERTED_HAMMER("Inverted Hammer"),
    BULLISH_ENGULFING("Bullish Engulfing"),
    BEARISH_ENGULFING("Bearish Engulfing"),
    MORNING_STAR("Morning Star"),
    EVENING_STAR("Evening Star"),
    PIERCING_LINE("Piercing Line"),
    DARK_CLOUD_COVER("Dark Cloud Cover"),
    BULLISH_HARAMI("Bullish Harami"),
    BEARISH_HARAMI("Bearish Harami"),
    THREE_WHITE_SOLDIERS("Three White Soldiers"),
    THREE_BLACK_CROWS("Three Black Crows"),
    TWEEZER_TOP("Tweezer Top"),
    TWEEZER_BOTTOM("Tweezer Bottom"),
    DRAGONFLY_DOJI("Dragonfly Doji"),
    GRAVESTONE_DOJI("Gravestone Doji"),
    DOJI("Doji"),
    MARUBOZU("Marubozu"),
    SPINNING_TOP("Spinning Top"),
    NONE("No Major Pattern")
}

data class CandlestickPatternResult(
    val type: CandlestickPatternType,
    val candle: Candle,
    val bias: String,
    /** Heuristic pattern-strength score (0..100), not a probability of success. */
    val confidence: Int,
    val rationale: String,
    val classicalFraming: String,
    val bodyRangeRatio: Double = 0.0,
    val upperWickRatio: Double = 0.0,
    val lowerWickRatio: Double = 0.0,
    val priorTrend: String = "UNKNOWN",
    val volatilityContext: String = "UNAVAILABLE",
    val previousRelationship: String = "UNAVAILABLE",
    val locationContext: String = "UNAVAILABLE",
    val liquidityContext: String = "UNAVAILABLE",
    val confirmed: Boolean = false,
    val forming: Boolean = false,
    val dataValid: Boolean = true
)
