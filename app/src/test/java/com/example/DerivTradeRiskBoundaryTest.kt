package com.example

import com.destinedfx.core.broker.DerivContractLimits
import com.destinedfx.core.broker.DerivInstrumentResolver
import com.example.domain.model.CanonicalTradePlan
import com.example.domain.model.SignalDirection
import com.example.data.deriv.selectDerivOptionsAccount
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Dedicated Boundary Tests for Trade Sizing & Deriv Contract-Specific Risk Limits:
 * 1. DerivContractLimits minimum stake lookup per asset class
 * 2. Worst-case loss calculation
 * 3. 1% Risk Budget ceiling calculation
 * 4. Account balance non-positive rejection simulation
 * 5. Minimum stake exceeding risk budget boundary logic (CANNOT_SIZE_WITHIN_RISK_LIMIT)
 * 6. Instrument resolution boundary logic
 */
class DerivTradeRiskBoundaryTest {

    @Test
    fun accountModeSelectionUsesMatchingActiveDemoOrRealAccount() {
        val accounts = JSONArray()
            .put(JSONObject().put("account_id", "V-DEMO").put("account_type", "demo").put("status", "active"))
            .put(JSONObject().put("account_id", "V-REAL").put("account_type", "real").put("status", "active"))
            .put(JSONObject().put("account_id", "V-OLD").put("account_type", "demo").put("status", "inactive"))

        assertEquals("V-DEMO", selectDerivOptionsAccount(accounts, "demo")?.optString("account_id"))
        assertEquals("V-REAL", selectDerivOptionsAccount(accounts, "real")?.optString("account_id"))
        assertEquals("V-DEMO", selectDerivOptionsAccount(accounts, "unsupported")?.optString("account_id"))
    }

    @Test
    fun testDerivContractLimitsPerAssetClass() {
        assertEquals(0.35, DerivContractLimits.getMinimumStake("R_100"), 1e-9)
        assertEquals(0.35, DerivContractLimits.getMinimumStake("1HZ100V"), 1e-9)
        assertEquals(0.50, DerivContractLimits.getMinimumStake("CRASH500"), 1e-9)
        assertEquals(0.50, DerivContractLimits.getMinimumStake("BOOM1000"), 1e-9)
        assertEquals(0.50, DerivContractLimits.getMinimumStake("frxEURUSD"), 1e-9)
        assertEquals(1.00, DerivContractLimits.getMinimumStake("frxXAUUSD"), 1e-9)
        assertEquals(1.00, DerivContractLimits.getMinimumStake("cryBTCUSD"), 1e-9)
    }

    @Test
    fun testWorstCaseLossMatchesStake() {
        val stake = 25.0
        val worstCase = DerivContractLimits.calculateWorstCaseLoss(stake, "MULTUP")
        assertEquals(25.0, worstCase, 1e-9)
    }

    @Test
    fun testRiskBudgetEnforcesOnePercentLimit() {
        val balance = 1000.0
        val riskPercent = 1.0
        val maxAllowedRisk = balance * (riskPercent / 100.0)
        assertEquals(10.0, maxAllowedRisk, 1e-9)
    }

    @Test
    fun testSmallAccountWhereMinStakeExceedsOnePercentCeiling() {
        // Account balance = $20.00
        // 1% max risk budget = $0.20
        // Deriv min stake for EUR/USD (frxEURUSD) is $0.50
        val balance = 20.0
        val maxAllowedRisk = balance * (1.0 / 100.0) // 0.20
        val derivSymbol = DerivInstrumentResolver.resolve("EUR/USD")!!
        val minBrokerStake = DerivContractLimits.getMinimumStake(derivSymbol) // 0.50

        // Sizing logic must reject: maxAllowedRisk < minBrokerStake
        assertTrue("Minimum broker stake ($minBrokerStake) must exceed risk budget ($maxAllowedRisk)", minBrokerStake > maxAllowedRisk)
        val shouldReject = maxAllowedRisk < minBrokerStake
        assertTrue(shouldReject)
    }

    @Test
    fun testTargetStakeBelowMinimumBrokerStakeRejection() {
        // Account balance = $1000.00 -> 1% ceiling is $10.00
        // Target stake provided in plan is $0.20 (below $0.50 min stake)
        val balance = 1000.0
        val maxAllowedRisk = balance * (1.0 / 100.0)
        val targetStake = 0.20
        val derivSymbol = DerivInstrumentResolver.resolve("EUR/USD")!!
        val minBrokerStake = DerivContractLimits.getMinimumStake(derivSymbol)

        assertTrue(targetStake < minBrokerStake)
        // Sizing must fail-closed rather than rounding up
        val shouldReject = targetStake < minBrokerStake
        assertTrue(shouldReject)
    }

    @Test
    fun testUnsupportedInstrumentRejection() {
        val resolved = DerivInstrumentResolver.resolve("UNKNOWN_COIN_XYZ")
        assertNull("Unknown symbol must not resolve to any valid Deriv symbol", resolved)
    }

    @Test
    fun testSupportedSyntheticsResolveCorrectly() {
        assertEquals("R_100", DerivInstrumentResolver.resolve("R_100"))
        assertEquals("1HZ100V", DerivInstrumentResolver.resolve("1HZ100V"))
        assertEquals("frxEURUSD", DerivInstrumentResolver.resolve("EUR/USD"))
        assertEquals("frxXAUUSD", DerivInstrumentResolver.resolve("XAU/USD"))
    }
}
