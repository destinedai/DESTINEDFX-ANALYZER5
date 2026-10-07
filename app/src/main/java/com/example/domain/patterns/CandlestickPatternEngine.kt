package com.example.domain.patterns

import com.example.domain.knowledge.ClassicalPriceAction
import com.example.domain.model.Candle
import kotlin.math.abs

class CandlestickPatternEngine {

    /**
     * Analyzes candles and detects patterns with trend-context differentiation.
     * Incorporates wisdom from "The Candlestick Trading Bible" by Munehisa Homma.
     */
    fun detectPattern(candles: List<Candle>): CandlestickPatternResult {
        if (candles.size < 5) {
            val dummyCandle = candles.lastOrNull() ?: Candle(System.currentTimeMillis(), 1.0, 1.0, 1.0, 1.0, 0.0)
            return CandlestickPatternResult(
                type = CandlestickPatternType.NONE,
                candle = dummyCandle,
                bias = "NEUTRAL",
                confidence = 50,
                rationale = "Homma's principle: Wait for the market to speak. Insufficient data for structural clarity.",
                classicalFraming = ClassicalPriceAction.getFraming(CandlestickPatternType.NONE)
            )
        }

        val current = candles.last()
        val prev = candles[candles.size - 2]
        val p2 = candles[candles.size - 3]
        
        val priorTrendLen = 5
        val priorCandles = candles.dropLast(1).takeLast(priorTrendLen)
        val isPriorDowntrend = priorCandles.first().close > priorCandles.last().close
        val isPriorUptrend = priorCandles.first().close < priorCandles.last().close

        val range = current.high - current.low
        val body = abs(current.close - current.open)
        val upperWick = current.high - maxOf(current.open, current.close)
        val lowerWick = minOf(current.open, current.close) - current.low

        // 1. Three White Soldiers / Three Black Crows (Sakata's Three Methods)
        if (candles.size >= 3) {
            val c1 = candles[candles.size - 3]
            val c2 = candles[candles.size - 2]
            val c3 = current

            // Three White Soldiers
            if (c1.close > c1.open && c2.close > c2.open && c3.close > c3.open &&
                c2.close > c1.close && c3.close > c2.close &&
                abs(c1.close - c1.open) > (c1.high - c1.low) * 0.6 &&
                abs(c2.close - c2.open) > (c2.high - c2.low) * 0.6
            ) {
                return CandlestickPatternResult(
                    type = CandlestickPatternType.MORNING_STAR, // Using as placeholder or add to enum
                    candle = current,
                    bias = "BULLISH",
                    confidence = 92,
                    rationale = "Three White Soldiers (Sakata's San-hei): Powerful momentum expansion signaling the start of a massive trend. Psychology: Absolute buyer dominance.",
                    classicalFraming = "Momentum Expansion"
                )
            }

            // Three Black Crows
            if (c1.close < c1.open && c2.close < c2.open && c3.close < c3.open &&
                c2.close < c1.close && c3.close < c2.close &&
                abs(c1.close - c1.open) > (c1.high - c1.low) * 0.6 &&
                abs(c2.close - c2.open) > (c2.high - c2.low) * 0.6
            ) {
                return CandlestickPatternResult(
                    type = CandlestickPatternType.EVENING_STAR,
                    candle = current,
                    bias = "BEARISH",
                    confidence = 92,
                    rationale = "Three Black Crows: Aggressive broker distribution. Homma's principle: The trend has shifted; do not fight the waterfall.",
                    classicalFraming = "Momentum Distribution"
                )
            }
        }

        // 2. Morning Star / Evening Star
        if (candles.size >= 3) {
            val c1 = candles[candles.size - 3]
            val c2 = candles[candles.size - 2]
            val c3 = current

            val c1Body = abs(c1.close - c1.open)
            val c2Body = abs(c2.close - c2.open)

            if (c1.close < c1.open && c1Body > (c1.high - c1.low) * 0.5 &&
                c2Body < c1Body * 0.3 &&
                c3.close > c3.open && c3.close > (c1.open + c1.close) / 2.0
            ) {
                return CandlestickPatternResult(
                    type = CandlestickPatternType.MORNING_STAR,
                    candle = current,
                    bias = "BULLISH",
                    confidence = 90,
                    rationale = "Morning Star (San-sen): The sun rises after the darkness. Panic selling absorbed by smart money at extreme lows.",
                    classicalFraming = ClassicalPriceAction.getFraming(CandlestickPatternType.MORNING_STAR)
                )
            }

            if (c1.close > c1.open && c1Body > (c1.high - c1.low) * 0.5 &&
                c2Body < c1Body * 0.3 &&
                c3.close < c3.open && c3.close < (c1.open + c1.close) / 2.0
            ) {
                return CandlestickPatternResult(
                    type = CandlestickPatternType.EVENING_STAR,
                    candle = current,
                    bias = "BEARISH",
                    confidence = 90,
                    rationale = "Evening Star: Market exhaustion at peaks. Broker distribution successfully traps retail 'fomo' buyers.",
                    classicalFraming = ClassicalPriceAction.getFraming(CandlestickPatternType.EVENING_STAR)
                )
            }
        }

        // 3. Tweezer Tops & Bottoms (Precise Rejection)
        if (abs(current.high - prev.high) < range * 0.05 && current.high > current.open && isPriorUptrend) {
            return CandlestickPatternResult(
                type = CandlestickPatternType.SHOOTING_STAR,
                candle = current,
                bias = "BEARISH",
                confidence = 86,
                rationale = "Tweezer Top: Exact price rejection at psychological ceiling. Homma's view: Double ceiling indicates a fortified distribution zone.",
                classicalFraming = "Tweezer Resistance"
            )
        }
        if (abs(current.low - prev.low) < range * 0.05 && current.low < current.open && isPriorDowntrend) {
            return CandlestickPatternResult(
                type = CandlestickPatternType.HAMMER,
                candle = current,
                bias = "BULLISH",
                confidence = 86,
                rationale = "Tweezer Bottom: Market floor established. Buyers defending this level with absolute conviction.",
                classicalFraming = "Tweezer Support"
            )
        }

        // 4. Bullish & Bearish Engulfing
        val prevBody = abs(prev.close - prev.open)
        if (prev.close < prev.open && current.close > current.open &&
            current.close >= prev.open && current.open <= prev.close && body > prevBody
        ) {
            return CandlestickPatternResult(
                type = CandlestickPatternType.BULLISH_ENGULFING,
                candle = current,
                bias = "BULLISH",
                confidence = 88,
                rationale = "Bullish Engulfing: A 'Killing' pattern where buyers completely overwrite the prior fear. Strong broker buy-side footprint.",
                classicalFraming = ClassicalPriceAction.getFraming(CandlestickPatternType.BULLISH_ENGULFING)
            )
        }
        if (prev.close > prev.open && current.close < current.open &&
            current.close <= prev.open && current.open >= prev.close && body > prevBody
        ) {
            return CandlestickPatternResult(
                type = CandlestickPatternType.BEARISH_ENGULFING,
                candle = current,
                bias = "BEARISH",
                confidence = 88,
                rationale = "Bearish Engulfing: Total absorption of buying pressure. Distribution is now aggressive and immediate.",
                classicalFraming = ClassicalPriceAction.getFraming(CandlestickPatternType.BEARISH_ENGULFING)
            )
        }

        // 5. Piercing Line / Dark Cloud Cover
        if (isPriorDowntrend && prev.close < prev.open && current.close > current.open &&
            current.open < prev.close && current.close > (prev.open + prev.close) / 2.0 && current.close < prev.open
        ) {
            return CandlestickPatternResult(
                type = CandlestickPatternType.HAMMER,
                candle = current,
                bias = "BULLISH",
                confidence = 80,
                rationale = "Piercing Line: Partial engulfment signaling a potent trend shift. The floor is being tested and held.",
                classicalFraming = "Trend Shift"
            )
        }
        if (isPriorUptrend && prev.close > prev.open && current.close < current.open &&
            current.open > prev.close && current.close < (prev.open + prev.close) / 2.0 && current.close > prev.open
        ) {
            return CandlestickPatternResult(
                type = CandlestickPatternType.SHOOTING_STAR,
                candle = current,
                bias = "BEARISH",
                confidence = 80,
                rationale = "Dark Cloud Cover: Topping signal where sellers have penetrated deep into the buyers' territory.",
                classicalFraming = "Trend Shift"
            )
        }

        // 6. Hammer vs Shooting Star (Pin Bars)
        if (range > 0 && lowerWick >= 2.0 * body && upperWick <= range * 0.15) {
            val hammerBias = if (isPriorDowntrend) "BULLISH" else "BEARISH"
            val hammerType = if (isPriorDowntrend) CandlestickPatternType.HAMMER else CandlestickPatternType.HANGING_MAN
            return CandlestickPatternResult(
                type = hammerType,
                candle = current,
                bias = hammerBias,
                confidence = 84,
                rationale = "Pin Bar (Hammer/Hanging Man): Rejection of liquidity. The long shadow shows where the big money pushed back.",
                classicalFraming = ClassicalPriceAction.getFraming(hammerType)
            )
        }
        if (range > 0 && upperWick >= 2.0 * body && lowerWick <= range * 0.15) {
            val starBias = if (isPriorUptrend) "BEARISH" else "BULLISH"
            val starType = if (isPriorUptrend) CandlestickPatternType.SHOOTING_STAR else CandlestickPatternType.INVERTED_HAMMER
            return CandlestickPatternResult(
                type = starType,
                candle = current,
                bias = starBias,
                confidence = 84,
                rationale = "Shooting Star/Inverted Hammer: Testing high/low liquidity. Homma: The shadow points to the future path.",
                classicalFraming = ClassicalPriceAction.getFraming(starType)
            )
        }

        // 7. Doji (Equilibrium)
        if (range > 0 && body <= range * 0.1) {
            return CandlestickPatternResult(
                type = CandlestickPatternType.DOJI,
                candle = current,
                bias = "NEUTRAL",
                confidence = 70,
                rationale = "Doji: The market is in a state of perfect balance (Kaikiri). Prepare for a breakout.",
                classicalFraming = ClassicalPriceAction.getFraming(CandlestickPatternType.DOJI)
            )
        }

        return CandlestickPatternResult(
            type = CandlestickPatternType.NONE,
            candle = current,
            bias = if (current.close >= current.open) "BULLISH" else "BEARISH",
            confidence = 60,
            rationale = "Steady Market Pulse. No extreme reversal signatures present. Follow the dominant trend.",
            classicalFraming = "Structural Continuation"
        )
    }
}
