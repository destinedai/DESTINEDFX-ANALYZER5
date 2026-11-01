package com.example.domain.knowledge

import com.example.domain.patterns.CandlestickPatternType

/** Deterministic, descriptive Japanese-candlestick context; OHLC does not reveal participant intent. */
object ClassicalPriceAction {
    fun getFraming(pattern: CandlestickPatternType): String = when (pattern) {
        CandlestickPatternType.HAMMER -> "After a decline, a long lower shadow records rejection of lower prices; confirmation is required."
        CandlestickPatternType.HANGING_MAN -> "After an advance, the same lower-shadow geometry warns of vulnerability but is not a reversal alone."
        CandlestickPatternType.SHOOTING_STAR -> "At/after an advance, the upper shadow records rejected higher prices; seek follow-through."
        CandlestickPatternType.INVERTED_HAMMER -> "After a decline, an upper-price probe may indicate reduced selling pressure; confirmation is required."
        CandlestickPatternType.BULLISH_ENGULFING -> "A bullish body contains the prior bearish body, showing a shift in this two-candle balance, not certainty."
        CandlestickPatternType.BEARISH_ENGULFING -> "A bearish body contains the prior bullish body, showing a shift in this two-candle balance, not certainty."
        CandlestickPatternType.MORNING_STAR -> "A sharp decline, contracted middle candle and recovery beyond the first body's midpoint form a potential reversal sequence."
        CandlestickPatternType.EVENING_STAR -> "A sharp advance, contracted middle candle and decline beyond the first body's midpoint form a potential reversal sequence."
        CandlestickPatternType.PIERCING_LINE -> "The bullish close exceeds the prior bearish body's midpoint without engulfing it; location and follow-through matter."
        CandlestickPatternType.DARK_CLOUD_COVER -> "The bearish close falls below the prior bullish body's midpoint without engulfing it; location and follow-through matter."
        CandlestickPatternType.BULLISH_HARAMI -> "A small bullish body contained within a prior bearish body indicates contraction, not confirmation."
        CandlestickPatternType.BEARISH_HARAMI -> "A small bearish body contained within a prior bullish body indicates contraction, not confirmation."
        CandlestickPatternType.THREE_WHITE_SOLDIERS -> "Three advancing bullish bodies describe persistent buying pressure; assess extension and location."
        CandlestickPatternType.THREE_BLACK_CROWS -> "Three declining bearish bodies describe persistent selling pressure; assess extension and location."
        CandlestickPatternType.TWEEZER_TOP -> "Repeated tests of a similar high with rejection describe a resistance response; intent is not observable from OHLC."
        CandlestickPatternType.TWEEZER_BOTTOM -> "Repeated tests of a similar low with rejection describe a support response; intent is not observable from OHLC."
        CandlestickPatternType.DRAGONFLY_DOJI -> "Near-equal open/close near the high with a long lower shadow records lower-price rejection."
        CandlestickPatternType.GRAVESTONE_DOJI -> "Near-equal open/close near the low with a long upper shadow records higher-price rejection."
        CandlestickPatternType.DOJI -> "A near-equal open and close records temporary balance; direction requires subsequent evidence."
        CandlestickPatternType.MARUBOZU -> "A long body with minimal shadows records directional imbalance during the candle, not guaranteed continuation."
        CandlestickPatternType.SPINNING_TOP -> "A compact body with shadows on both sides records two-sided movement and temporary balance."
        CandlestickPatternType.NONE -> "No qualifying classical formation; candle color alone is not a reversal signal."
    }
}
