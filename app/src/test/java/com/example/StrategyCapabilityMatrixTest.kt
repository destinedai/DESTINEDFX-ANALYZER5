package com.example

import com.destinedfx.core.analysis.SignalEngine
import com.destinedfx.core.strategy.StrategyCapabilityStatus
import com.destinedfx.core.strategy.StrategyRegistry
import com.example.domain.usecase.impl.OnDevicePythonAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyCapabilityMatrixTest {
    @Test
    fun strategyIdsAndDisplayNamesAreUnique() {
        val strategies = StrategyRegistry.allStrategies
        assertEquals(strategies.size, strategies.map { it.id }.toSet().size)
        assertEquals(strategies.size, strategies.map { it.name.lowercase() }.toSet().size)
    }

    @Test
    fun botEligibleStrategiesHaveKotlinFinalValidationAndSignalEnginePaths() {
        val matrixBotIds = StrategyRegistry.allStrategies
            .filter { it.botExecutionEligible }
            .map { it.id }
            .toSet()

        assertEquals(matrixBotIds, StrategyRegistry.managedStrategyIds)
        assertEquals(matrixBotIds, SignalEngine.finalValidatedStrategyIds)
        assertTrue(StrategyRegistry.allStrategies.filter { it.botExecutionEligible }.all {
            it.kotlinStatus == StrategyCapabilityStatus.IMPLEMENTED && it.kotlinFinalValidation
        })
    }

    @Test
    fun pythonStrategiesAreExplicitlyAnalysisOnlyAndCanonicalIdsMatchKotlin() {
        val pythonCandidates = StrategyRegistry.allStrategies.filter { it.pythonVersion != null }
        assertTrue(pythonCandidates.isNotEmpty())
        assertTrue(pythonCandidates.all { it.pythonStatus == StrategyCapabilityStatus.CANDIDATE_ONLY })
        assertTrue(pythonCandidates.all { it.testCoverage.isNotEmpty() && it.requiredEvidence.isNotEmpty() })

        assertNotNull(StrategyRegistry.allStrategies.singleOrNull { it.id == "mean-reversion" })
        assertNotNull(StrategyRegistry.allStrategies.singleOrNull { it.id == "trend-following" })
        assertEquals("python-mean-reversion-1.0.0", StrategyRegistry.MEAN_REVERSION.pythonVersion)
        assertEquals("python-trend-following-1.0.0", StrategyRegistry.TREND_FOLLOWING.pythonVersion)
        val registryPythonVersions = pythonCandidates.associate { it.id to requireNotNull(it.pythonVersion) }
        assertEquals(registryPythonVersions, OnDevicePythonAnalyzer.RULESET_VERSIONS)
    }

    @Test
    fun volatilityBreakoutIsNotSilentlyAliasedToKotlinBreakoutMomentum() {
        val momentum = StrategyRegistry.allStrategies.single { it.id == "breakout-momentum" }
        val volatility = StrategyRegistry.allStrategies.single { it.id == "volatility-breakout" }

        assertEquals("Breakout Momentum", momentum.name)
        assertTrue(momentum.botExecutionEligible)
        assertEquals("Volatility Breakout", volatility.name)
        assertEquals(StrategyCapabilityStatus.CANDIDATE_ONLY, volatility.pythonStatus)
        assertEquals(StrategyCapabilityStatus.CANDIDATE_ONLY, volatility.kotlinStatus)
        assertFalse(volatility.kotlinFinalValidation)
        assertFalse(volatility.botExecutionEligible)
        assertFalse(StrategyRegistry.managedStrategyIds.contains(volatility.id))
        assertFalse(SignalEngine.finalValidatedStrategyIds.contains(volatility.id))
        assertTrue(volatility.requiredEvidence.any { it.contains("Kotlin", ignoreCase = true) })
    }
}
