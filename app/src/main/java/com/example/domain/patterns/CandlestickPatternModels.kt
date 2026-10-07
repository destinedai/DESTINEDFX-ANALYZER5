package com.example.domain.patterns

import com.example.domain.model.Candle

enum class CandlestickPatternType(val displayName: String) {
    HAMMER("Hammer"),
    HANGING_MAN("Hanging Man"),
    SHOOTING_STAR("Shooting Star"),
    INVERTED_HAMMER("Inverted Hammer"),
    BULLISH_ENGULFING("Bullish Engulfing"),
    BEARISH_ENGULFING("Bearish Engulfing"),
    MORNING_STAR("Morning Star"),
    EVENING_STAR("Evening Star"),
    DOJI("Doji"),
    NONE("No Major Pattern")
}

data class CandlestickPatternResult(
    val type: CandlestickPatternType,
    val candle: Candle,
    val bias: String, // "BULLISH", "BEARISH", "NEUTRAL"
    val confidence: Int, // 0-100
    val rationale: String,
    val classicalFraming: String
)
