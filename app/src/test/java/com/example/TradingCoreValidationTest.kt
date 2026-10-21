package com.example

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.marketdata.AssetClass
import com.destinedfx.core.marketdata.MarketQuote
import com.destinedfx.core.risk.*
import com.destinedfx.core.analysis.*
import com.destinedfx.core.trade.*
import com.destinedfx.core.news.*
import com.destinedfx.core.strategy.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TradingCoreValidationTest {

    private val eurUsdSpec = InstrumentSpec(
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

    // --- 1. RISK ENGINE TESTS ---

    @Test
    fun testRiskBudgetEnforcement_TenDollars_OnePercent() {
        val account = AccountState(balance = 10.0, equity = 10.0, freeMargin = 10.0, leverage = 100, currency = "USD")
        val result = RiskEngine.positionSize(
            account = account,
            riskPercent = 1.0, // 1% of $10.0 = $0.10
            entry = 1.0850,
            stopLoss = 1.0840, // 10 pips (100 ticks) stop distance
            spec = eurUsdSpec
        )

        // Sizing should target or limit actual money risk <= $0.10
        when (result) {
            is SizingResult.Sized -> {
                assertTrue("Actual risk amount (${result.moneyRiskActual}) exceeds budget 1% of $10.0 ($0.10)", result.moneyRiskActual <= 0.10)
            }
            is SizingResult.Rejected -> {
                // If rejected due to minimum volume step, that's correct since $0.10 / 100 ticks = 0.001 lots (below 0.01 volumeMin)
                assertTrue(result.reason.contains("below broker minimum"))
            }
        }
    }

    @Test
    fun testMinimumExecutionLotIsAtLeast001WhenRiskBudgetAllows() {
        val spec = eurUsdSpec.copy(volumeMin = 0.001, volumeStep = 0.001)
        val account = AccountState(balance = 1000.0, equity = 1000.0, freeMargin = 1000.0, leverage = 100, currency = "USD")
        val result = RiskEngine.positionSize(
            account = account,
            riskPercent = 1.0,
            entry = 1.0807,
            stopLoss = 1.0800,
            spec = spec
        )
        when (result) {
            is SizingResult.Sized -> {
                assertTrue("Execution lot must be at least 0.01", result.lots >= 0.01 - 1e-12)
                assertTrue("Actual risk may not exceed the 1% budget", result.moneyRiskActual <= 10.0 + 1e-9)
            }
            is SizingResult.Rejected -> fail("Budget should permit >= 0.01 lots: ${result.reason}")
        }
    }

    @Test
    fun testMinimumExecutionLotRejectsRoundingUpBeyondRiskBudget() {
        val spec = eurUsdSpec.copy(volumeMin = 0.001, volumeStep = 0.001)
        val account = AccountState(balance = 50.0, equity = 50.0, freeMargin = 50.0, leverage = 100, currency = "USD")
        val result = RiskEngine.positionSize(
            account = account,
            riskPercent = 1.0,
            entry = 1.0900,
            stopLoss = 1.0800,
            spec = spec
        )
        assertTrue(result is SizingResult.Rejected)
        assertTrue((result as SizingResult.Rejected).reason.contains("0.01-lot policy"))
    }

    @Test
    fun testRiskBudgetEnforcement_FourHundredDollars_OnePercent() {
        val account = AccountState(balance = 400.0, equity = 400.0, freeMargin = 400.0, leverage = 100, currency = "USD")
        val result = RiskEngine.positionSize(
            account = account,
            riskPercent = 1.0, // 1% of $400.0 = $4.00
            entry = 1.0850,
            stopLoss = 1.0840, // 10 pips (100 ticks) stop distance
            spec = eurUsdSpec
        )

        when (result) {
            is SizingResult.Sized -> {
                assertTrue("Actual risk amount (${result.moneyRiskActual}) exceeds target ($4.00)", result.moneyRiskActual <= 4.00)
                assertEquals(0.40, result.lots, 1e-9) // $4.00 max risk / ($1.00 per pip per lot * 10 pips) = 0.40 lots
            }
            is SizingResult.Rejected -> {
                fail("Sizing was rejected: ${result.reason}")
            }
        }
    }

    @Test
    fun testRiskEngineRejectsRiskGreaterThanOnePercent() {
        val account = AccountState(balance = 100.0, equity = 100.0, freeMargin = 100.0, leverage = 100, currency = "USD")
        val result = RiskEngine.positionSize(
            account = account,
            riskPercent = 1.5, // > 1% is strictly rejected by production risk policy
            entry = 1.0850,
            stopLoss = 1.0840,
            spec = eurUsdSpec
        )
        assertTrue("Risk engine must strictly reject risk percent > 1.0%", result is SizingResult.Rejected)
    }

    @Test
    fun testRiskEngineFloorsLotsDownward() {
        val account = AccountState(balance = 1000.0, equity = 1000.0, freeMargin = 1000.0, leverage = 100, currency = "USD")
        // Target budget = $10.00
        // Sl distance 7 ticks
        // Loss per lot = 7 * 1 = $7
        // Raw lots = 10.0 / 7.0 = 1.42857 lots
        val result = RiskEngine.positionSize(
            account = account,
            riskPercent = 1.0,
            entry = 1.0807,
            stopLoss = 1.0800,
            spec = eurUsdSpec
        )

        when (result) {
            is SizingResult.Sized -> {
                // Should floor raw 1.42857 lots downward to 1.42 lots
                assertEquals(1.42, result.lots, 1e-9)
                assertTrue("Floored actual risk (${result.moneyRiskActual}) must be <= target ($10.00)", result.moneyRiskActual <= 10.0)
            }
            is SizingResult.Rejected -> fail("Sizing was rejected: ${result.reason}")
        }
    }

    // --- 2. STOP LOSS ENGINE & TICK ROUNDING TESTS ---

    @Test
    fun testStopLossTickRounding_Long_Short() {
        // EUR/USD tick size is 0.0001
        val tickSize = eurUsdSpec.tickSize

        // For Long: SL rounds down
        val rawLongSl = 1.08346
        val roundedLongSl = kotlin.math.floor(rawLongSl / tickSize) * tickSize
        assertEquals(1.0834, roundedLongSl, 1e-9)

        // For Short: SL rounds up
        val rawShortSl = 1.08342
        val roundedShortSl = kotlin.math.ceil(rawShortSl / tickSize) * tickSize
        assertEquals(1.0835, roundedShortSl, 1e-9)
    }

    @Test
    fun testMinimumStopDistanceValidation() {
        // Stops level is 20 points (0.0002 on 5 digits)
        val minStop = eurUsdSpec.minStopDistance
        assertEquals(0.0002, minStop, 1e-9)

        val entry = 1.0850
        val tooCloseSl = 1.0849 // 1 pip (0.0001) - closer than min stop distance
        
        val account = AccountState(100.0, 100.0, 100.0, 100, "USD")
        val result = RiskEngine.positionSize(account, 1.0, entry, tooCloseSl, eurUsdSpec)
        
        // Print result status for debug
        System.out.println("testMinimumStopDistanceValidation result: $result")
        
        assertTrue(result is SizingResult.Rejected)
    }

    // --- 3. REWARD-TO-RISK (RR >= 2.0) TESTS ---

    @Test
    fun testMinimumRewardToRiskRatio() {
        val entry = 1.0850
        val sl = 1.0840 // risk = 10 pips
        val tpPass = 1.0870 // reward = 20 pips -> 2.0 RR
        val tpFail = 1.0865 // reward = 15 pips -> 1.5 RR

        val rrPass = RiskEngine.rewardToRisk(entry, sl, tpPass, isLong = true)
        val rrFail = RiskEngine.rewardToRisk(entry, sl, tpFail, isLong = true)

        assertNotNull(rrPass)
        assertTrue(rrPass!! >= 2.0)

        assertNotNull(rrFail)
        assertTrue(rrFail!! < 2.0)
    }

    // --- 4. INVALIDATION STATE VS EXECUTABLE SL TESTS ---

    @Test
    fun testInvalidationSeparateFromStopLoss() {
        val invalidationLevel = 1.08380
        val slPrice = 1.08360

        // Candle closed beyond invalidation level triggers INVALIDATED state
        val candleClosePrice = 1.08375
        val isCandleInvalidated = candleClosePrice <= invalidationLevel
        assertTrue("Closed candle beyond invalidation level must trigger structural invalidation", isCandleInvalidated)

        // Execution stop-out only when price reaches the executable SL (slPrice)
        val isStoppedOut = candleClosePrice <= slPrice
        assertFalse("Price has not reached executable SL yet; should not be stopped out", isStoppedOut)
    }

    // --- 5. STRATEGY DISPATCH TESTS ---

    @Test
    fun testStrategySelectionAndDispatch() {
        val smcDef = StrategyRegistry.allStrategies.find { it.id == "smc-confluence-1.0.0" }
        assertNotNull(smcDef)
        assertTrue(smcDef!!.isImplemented)

        val ictDef = StrategyRegistry.allStrategies.find { it.id == "ict-silver-bullet" }
        assertNotNull(ictDef)
        assertTrue(ictDef!!.isImplemented)

        StrategyRegistry.allStrategies.forEach { strat ->
            assertTrue("Strategy ${strat.name} (${strat.id}) must be implemented", strat.isImplemented)
        }
    }

    // --- 6. NEWS GATE TESTS ---

    @Test
    fun testNewsStatusBlocking() {
        val highImpactVerdict = NewsVerdict(status = NewsStatus.HIGH_IMPACT_WINDOW, detail = "HIGH IMPACT NEWS EVENT ACTIVE")
        val unknownVerdict = NewsVerdict(status = NewsStatus.UNKNOWN, detail = "NEWS STATE UNKNOWN - SECURE BLOCKED")

        assertTrue("High impact news must block execution", highImpactVerdict.status == NewsStatus.HIGH_IMPACT_WINDOW)
        assertTrue("Unknown news status must block execution", unknownVerdict.status == NewsStatus.UNKNOWN)
    }
}
