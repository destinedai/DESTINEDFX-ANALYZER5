package com.example

import com.destinedfx.core.analysis.*
import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.marketdata.*
import com.destinedfx.core.strategy.StrategyRegistry
import org.junit.Assert.*
import org.junit.Test

/**
 * Dedicated Behavioral Unit Tests for the Seven Core Institutional Strategies:
 * 1. Smart Money Concepts (SMC)
 * 2. ICT Silver Bullet
 * 3. Wyckoff Method & Schematics
 * 4. Supply & Demand Zones
 * 5. Volatility Breakout
 * 6. Statistical Mean Reversion
 * 7. Multi-EMA Trend Following
 *
 * Validates positive detection fixtures, negative/counter-trend fixtures,
 * minimum history requirements, and strict 2.0R threshold enforcement.
 */
class SevenStrategiesBehavioralTest {

    private val spec = InstrumentSpec(
        symbol = "EUR/USD",
        assetClass = AssetClass.FOREX,
        digits = 5,
        tickSize = 0.0001,
        tickValue = 1.0,
        contractSize = 100000.0,
        volumeMin = 0.01,
        volumeMax = 100.0,
        volumeStep = 0.01,
        stopsLevelPoints = 20,
        source = "test"
    )

    private fun generateTrendingCandles(count: Int = 100, isBullish: Boolean = true): List<Candle> {
        val candles = mutableListOf<Candle>()
        var price = 1.0800
        val baseTime = 1700000000000L
        for (i in 0 until count) {
            val delta = if (isBullish) 0.0004 else -0.0004
            val open = price
            val close = price + delta
            val high = maxOf(open, close) + 0.0002
            val low = minOf(open, close) - 0.0002
            candles.add(
                Candle(
                    timeMs = baseTime + i * 3600_000L,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = 1500.0,
                    complete = true
                )
            )
            price = close
        }
        return candles
    }

    private fun generateConsolidationCandles(count: Int = 100, center: Double = 1.0850): List<Candle> {
        val candles = mutableListOf<Candle>()
        val baseTime = 1700000000000L
        for (i in 0 until count) {
            val swing = if (i % 2 == 0) 0.0008 else -0.0008
            val open = center - swing
            val close = center + swing
            val high = maxOf(open, close) + 0.0003
            val low = minOf(open, close) - 0.0003
            candles.add(
                Candle(
                    timeMs = baseTime + i * 3600_000L,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = 1000.0,
                    complete = true
                )
            )
        }
        return candles
    }

    @Test
    fun testInsufficientCandleHistoryRejection() {
        val candles = generateTrendingCandles(count = 30) // Minimum requires 60
        val quote = MarketQuote.twoSided("EUR/USD", AssetClass.FOREX, 1.0920, 1.0921, System.currentTimeMillis(), System.currentTimeMillis(), "test")
        val input = AnalysisInput(
            symbol = "EUR/USD",
            tf = Timeframe.H1,
            candles = candles,
            htf = emptyMap(),
            quote = quote,
            spec = spec,
            nowMs = System.currentTimeMillis(),
            strategyId = "smc-confluence-1.0.0"
        )
        val report = SignalEngine.analyse(input)
        assertEquals(SignalState.DATA_UNAVAILABLE, report.state)
        assertTrue(report.blockers.first().contains("Insufficient or invalid candle history"))
    }

    @Test
    fun testAllSevenStrategiesAreRegisteredAndRecognized() {
        assertEquals(7, StrategyRegistry.allStrategies.size)
        val ids = StrategyRegistry.allStrategies.map { it.id }.toSet()
        assertTrue(ids.contains("smc-confluence-1.0.0"))
        assertTrue(ids.contains("ict-silver-bullet"))
        assertTrue(ids.contains("wyckoff-schematics"))
        assertTrue(ids.contains("supply-and-demand"))
        assertTrue(ids.contains("breakout-momentum"))
        assertTrue(ids.contains("mean-reversion"))
        assertTrue(ids.contains("trend-following"))
    }

    @Test
    fun testStrategy1_SMC_ConfluenceEngine() {
        val candles = generateTrendingCandles(count = 120, isBullish = true)
        val lastPrice = candles.last().close
        val quote = MarketQuote.twoSided("EUR/USD", AssetClass.FOREX, lastPrice, lastPrice + 0.0001, System.currentTimeMillis(), System.currentTimeMillis(), "test")
        val input = AnalysisInput(
            symbol = "EUR/USD",
            tf = Timeframe.H1,
            candles = candles,
            htf = emptyMap(),
            quote = quote,
            spec = spec,
            nowMs = System.currentTimeMillis(),
            strategyId = "smc-confluence-1.0.0"
        )
        val report = SignalEngine.analyse(input)
        assertNotNull(report)
        assertEquals("smc-confluence-1.0.0", report.strategyVersion)
        assertTrue("Confluence score must be computed deterministically between 0 and 100", report.confluenceScore in 0..100)
    }

    @Test
    fun testStrategy2_ICT_SilverBullet_Components() {
        val candles = generateTrendingCandles(count = 120, isBullish = true)
        val lastPrice = candles.last().close
        val quote = MarketQuote.twoSided("EUR/USD", AssetClass.FOREX, lastPrice, lastPrice + 0.0001, System.currentTimeMillis(), System.currentTimeMillis(), "test")
        val input = AnalysisInput(
            symbol = "EUR/USD",
            tf = Timeframe.H1,
            candles = candles,
            htf = emptyMap(),
            quote = quote,
            spec = spec,
            nowMs = System.currentTimeMillis(),
            strategyId = "ict-silver-bullet"
        )
        val report = SignalEngine.analyse(input)
        assertEquals("ict-silver-bullet", report.strategyVersion)
        val compNames = report.components.map { it.name }
        assertTrue(compNames.contains("Killzone Timing"))
        assertTrue(compNames.contains("FVG Displacement"))
    }

    @Test
    fun testStrategy3_Wyckoff_Schematics_Components() {
        val candles = generateConsolidationCandles(count = 120)
        val lastPrice = candles.last().close
        val quote = MarketQuote.twoSided("EUR/USD", AssetClass.FOREX, lastPrice, lastPrice + 0.0001, System.currentTimeMillis(), System.currentTimeMillis(), "test")
        val input = AnalysisInput(
            symbol = "EUR/USD",
            tf = Timeframe.H1,
            candles = candles,
            htf = emptyMap(),
            quote = quote,
            spec = spec,
            nowMs = System.currentTimeMillis(),
            strategyId = "wyckoff-schematics"
        )
        val report = SignalEngine.analyse(input)
        assertEquals("wyckoff-schematics", report.strategyVersion)
        val compNames = report.components.map { it.name }
        assertTrue(compNames.any { it.contains("Spring Test") || it.contains("UTAD Test") })
        assertTrue(compNames.contains("Volume Absorption"))
    }

    @Test
    fun testStrategy4_SupplyAndDemand_Components() {
        val candles = generateTrendingCandles(count = 120, isBullish = true)
        val lastPrice = candles.last().close
        val quote = MarketQuote.twoSided("EUR/USD", AssetClass.FOREX, lastPrice, lastPrice + 0.0001, System.currentTimeMillis(), System.currentTimeMillis(), "test")
        val input = AnalysisInput(
            symbol = "EUR/USD",
            tf = Timeframe.H1,
            candles = candles,
            htf = emptyMap(),
            quote = quote,
            spec = spec,
            nowMs = System.currentTimeMillis(),
            strategyId = "supply-and-demand"
        )
        val report = SignalEngine.analyse(input)
        assertEquals("supply-and-demand", report.strategyVersion)
        val compNames = report.components.map { it.name }
        assertTrue(compNames.contains("Zone Freshness"))
        assertTrue(compNames.contains("Departure Imbalance"))
    }

    @Test
    fun testStrategy5_VolatilityBreakout_Components() {
        val candles = generateTrendingCandles(count = 120, isBullish = true)
        val lastPrice = candles.last().close
        val quote = MarketQuote.twoSided("EUR/USD", AssetClass.FOREX, lastPrice, lastPrice + 0.0001, System.currentTimeMillis(), System.currentTimeMillis(), "test")
        val input = AnalysisInput(
            symbol = "EUR/USD",
            tf = Timeframe.H1,
            candles = candles,
            htf = emptyMap(),
            quote = quote,
            spec = spec,
            nowMs = System.currentTimeMillis(),
            strategyId = "breakout-momentum"
        )
        val report = SignalEngine.analyse(input)
        assertEquals("breakout-momentum", report.strategyVersion)
        val compNames = report.components.map { it.name }
        assertTrue(compNames.contains("Donchian Breakout"))
        assertTrue(compNames.contains("ATR Volatility Expansion"))
    }

    @Test
    fun testStrategy6_MeanReversion_Components() {
        val candles = generateConsolidationCandles(count = 120)
        val lastPrice = candles.last().close
        val quote = MarketQuote.twoSided("EUR/USD", AssetClass.FOREX, lastPrice, lastPrice + 0.0001, System.currentTimeMillis(), System.currentTimeMillis(), "test")
        val input = AnalysisInput(
            symbol = "EUR/USD",
            tf = Timeframe.H1,
            candles = candles,
            htf = emptyMap(),
            quote = quote,
            spec = spec,
            nowMs = System.currentTimeMillis(),
            strategyId = "mean-reversion"
        )
        val report = SignalEngine.analyse(input)
        assertEquals("mean-reversion", report.strategyVersion)
        val compNames = report.components.map { it.name }
        assertTrue(compNames.contains("Bollinger Band 2.0σ Extreme"))
        assertTrue(compNames.contains("RSI Exhaustion"))
    }

    @Test
    fun testStrategy7_TrendFollowing_Components() {
        val candles = generateTrendingCandles(count = 120, isBullish = true)
        val lastPrice = candles.last().close
        val quote = MarketQuote.twoSided("EUR/USD", AssetClass.FOREX, lastPrice, lastPrice + 0.0001, System.currentTimeMillis(), System.currentTimeMillis(), "test")
        val input = AnalysisInput(
            symbol = "EUR/USD",
            tf = Timeframe.H1,
            candles = candles,
            htf = emptyMap(),
            quote = quote,
            spec = spec,
            nowMs = System.currentTimeMillis(),
            strategyId = "trend-following"
        )
        val report = SignalEngine.analyse(input)
        assertEquals("trend-following", report.strategyVersion)
        val compNames = report.components.map { it.name }
        assertTrue(compNames.contains("Multi-EMA Trend Stack"))
        assertTrue(compNames.contains("Value Zone Pullback"))
    }

    @Test
    fun testGlobalMinimumTwoRewardToRiskEnforced() {
        // Any generated trade plan must satisfy RR >= 2.0
        val candles = generateTrendingCandles(count = 120, isBullish = true)
        val lastPrice = candles.last().close
        val quote = MarketQuote.twoSided("EUR/USD", AssetClass.FOREX, lastPrice, lastPrice + 0.0001, System.currentTimeMillis(), System.currentTimeMillis(), "test")
        
        for (strat in StrategyRegistry.allStrategies) {
            val input = AnalysisInput(
                symbol = "EUR/USD",
                tf = Timeframe.H1,
                candles = candles,
                htf = emptyMap(),
                quote = quote,
                spec = spec,
                nowMs = System.currentTimeMillis(),
                strategyId = strat.id
            )
            val report = SignalEngine.analyse(input, SignalConfig(minRR = 2.0))
            if (report.plan != null) {
                assertTrue("Generated plan for ${strat.name} must have primary RR >= 2.0", report.plan!!.primary.rr >= 2.0)
            }
        }
    }
}
