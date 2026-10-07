package com.destinedfx.core.analysis

/**
 * Market knowledge wired into the engine: every detected concept carries a plain-language explanation that is
 * attached to the signal report. Wording is probabilistic on purpose; none of this implies a guaranteed outcome.
 */
object KnowledgeBase {
    private val k = mapOf(
        "BOS" to "Break of Structure: a candle CLOSED beyond the last swing in the direction of the prevailing trend. It signals trend continuation.",
        "CHOCH" to "Change of Character: a close beyond the last opposite swing against the prevailing trend - the first sign the trend may be reversing.",
        "ORDER_BLOCK" to "Order Block: the last opposite candle before a displacement leg that broke structure. Price often reacts when it returns to the zone; fresh zones carry more weight than tested ones.",
        "FVG" to "Fair Value Gap: a 3-candle imbalance where candle 1 and 3 do not overlap. Price frequently revisits gaps; mitigation % shows how much has been filled.",
        "EQUAL_HIGHS" to "Equal highs: swing highs at nearly the same price. Stops cluster above them (buy-side liquidity) and price is often drawn there.",
        "EQUAL_LOWS" to "Equal lows: swing lows at nearly the same price. Stops cluster below them (sell-side liquidity).",
        "SWEEP" to "Liquidity sweep: price wicks through a level and closes back inside. Only treated as meaningful when a structure shift follows (confirmed).",
        "DISPLACEMENT" to "Displacement: an unusually large, full-bodied candle showing aggressive one-sided participation.",
        "DOUBLE_TOP" to "Double top: two similar highs with a pullback between; a close below the valley confirms. Measured move = pattern height projected from the neckline.",
        "DOUBLE_BOTTOM" to "Double bottom: two similar lows with a bounce between; a close above the peak confirms.",
        "HEAD_SHOULDERS" to "Head and shoulders: three peaks, the middle highest. A close below the neckline confirms a bearish reversal.",
        "INV_HEAD_SHOULDERS" to "Inverse head and shoulders: three troughs, the middle lowest. A close above the neckline confirms a bullish reversal.",
        "ASC_TRIANGLE" to "Ascending triangle: flat resistance with rising lows; historically resolves upward more often than not, but false breakouts are common.",
        "DESC_TRIANGLE" to "Descending triangle: flat support with falling highs; tends to resolve downward.",
        "SYM_TRIANGLE" to "Symmetrical triangle: converging highs and lows; direction is decided by the breakout.",
        "RISING_WEDGE" to "Rising wedge: both lines rising but converging; momentum fades, often resolves downward.",
        "FALLING_WEDGE" to "Falling wedge: both lines falling but converging; selling pressure fades, often resolves upward.",
        "RANGE" to "Range/rectangle: price oscillates between support and resistance; trade the breakout or fade the edges, not the middle.",
        "CHANNEL_UP" to "Ascending channel: parallel rising trendlines; trend-following longs from the lower line, break below is a warning.",
        "CHANNEL_DOWN" to "Descending channel: parallel falling trendlines.",
        "BULL_FLAG" to "Bull flag: sharp rally then shallow counter-drift; continuation if price breaks the flag high.",
        "BEAR_FLAG" to "Bear flag: sharp drop then shallow counter-rally; continuation if price breaks the flag low.",
        "TICK_VOLUME" to "Forex 'volume' is TICK volume (number of price changes) from this provider, not centralised exchange volume.",
        "RSI" to "RSI is used only as momentum confirmation, never as a standalone entry (overbought/oversold alone is not a signal in a strong trend)."
    )
    fun explain(key: String): String = k[key] ?: "No knowledge entry for $key"
    fun keys(): Set<String> = k.keys
}
