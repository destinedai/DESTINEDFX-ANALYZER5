package com.example.domain.knowledge

import com.example.domain.patterns.CandlestickPatternType

/**
 * Classical price-action framing layer attached to detected candlestick patterns.
 * Explains pattern dynamics in probabilistic, structural language (liquidity distribution,
 * rejection of extremes, order absorption) rather than certainty promises.
 */
object ClassicalPriceAction {

    fun getFraming(pattern: CandlestickPatternType): String {
        return when (pattern) {
            CandlestickPatternType.HAMMER -> 
                "Historically read after a decline as an aggressive rejection of lower prices. " +
                "Sellers drove price lower to test liquidity, but strong responsive buyers absorbed " +
                "the float and pushed the close back near the highs. In SMC terms, this often signals a " +
                "liquidity sweep of previous swing lows."

            CandlestickPatternType.HANGING_MAN -> 
                "Occurs after an advance and carries identical geometry to a hammer, but opposite structural implication. " +
                "The long lower wick demonstrates that sellers were able to generate significant downward momentum during " +
                "the session. Even if buyers pushed price back up by the close, the structural vulnerability indicates supply " +
                "is entering the market at premium levels."

            CandlestickPatternType.SHOOTING_STAR -> 
                "Formed at the top of an uptrend when buyers attempt to probe higher liquidity pools but encounter heavy " +
                "broker resting sell limit orders. Price is driven back down to close near the lows, demonstrating that " +
                "the higher prices failed to find market acceptance."

            CandlestickPatternType.INVERTED_HAMMER -> 
                "Appears after a decline. Buyers test higher prices, and while supply pushes the price back down, the upward probe " +
                "reveals diminishing selling conviction. Confirmation requires follow-through buying in subsequent candles."

            CandlestickPatternType.BULLISH_ENGULFING -> 
                "A decisive expansion candle whose body completely covers the preceding bearish body. Reflects a substantial " +
                "shift in orderflow where aggressive market buy orders overwhelm prevailing sell limits, often forming a fresh Fair Value Gap."

            CandlestickPatternType.BEARISH_ENGULFING -> 
                "A dominant downward displacement whose body completely engulfs the prior bullish candle. Classically signals " +
                "that broker participants have distributed long positions and are initiating short inventory, leaving an unmitigated supply order block."

            CandlestickPatternType.MORNING_STAR -> 
                "A three-session reversal sequence: an initial sharp drop, an indecision candle with contracted range, followed by " +
                "a strong bullish displacement closing well into the body of the first candle. Indicates selling exhaustion followed by decisive accumulation."

            CandlestickPatternType.EVENING_STAR -> 
                "A three-candle topping formation featuring a strong advance, a high-level pause or star, followed by a heavy bearish candle. " +
                "Demonstrates that upward momentum has completely decayed into distribution."

            CandlestickPatternType.DOJI -> 
                "Characterized by virtually identical open and close prices. Represents market equilibrium where neither bulls nor bears " +
                "possess directional conviction. Typically precedes high-volatility expansions once directional liquidity is cleared."

            CandlestickPatternType.NONE -> 
                "Standard structural price action without single-candle or multi-candle structural extremes."
        }
    }
}
