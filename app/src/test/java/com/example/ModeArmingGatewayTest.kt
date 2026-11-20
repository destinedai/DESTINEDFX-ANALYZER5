package com.example

import com.example.data.broker.AccountMode
import com.example.data.broker.ModeAccountIdentity
import com.example.data.broker.ModeArmingPolicy
import com.example.data.broker.ModePath
import com.example.data.broker.ModeReadOnlyVerification
import com.example.data.broker.executeWithModeArmingDecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeArmingGatewayTest {
    @Test
    fun genericOrderClosureIsNotInvokedWithoutDemoAcceptanceOrLiveLease() = runBlocking {
        val now = 1_000_000L
        val policy = ModeArmingPolicy(nowMs = { now })
        val demo = ModeAccountIdentity(
            ModePath.FBS_MT5, "FBS", "123456", "FBS-Demo", AccountMode.DEMO, "demo-generation"
        )
        val live = demo.copy(mode = AccountMode.LIVE, server = "FBS-Live", credentialGeneration = "live-generation")
        val demoVerification = ModeReadOnlyVerification(demo, now, true, true, true, true, true)
        val liveVerification = ModeReadOnlyVerification(live, now, true, true, true, true, true)
        var fakeBrokerSubmissions = 0

        val demoDecision = policy.demoDecision(
            identity = demo,
            verification = demoVerification,
            acceptance = null,
            currentCredentialGeneration = demo.credentialGeneration,
            now = now
        )
        val demoResult = executeWithModeArmingDecision(demoDecision) {
            fakeBrokerSubmissions++
            Result.success("fixture-order")
        }
        assertFalse(demoDecision.allowed)
        assertEquals("OWNER_DEMO_ACCEPTANCE_REQUIRED", demoDecision.reason)
        assertTrue(demoResult.isFailure)
        assertTrue(demoResult.exceptionOrNull()?.message.orEmpty().startsWith("MODE_ARMING_BLOCKED:"))
        assertEquals(0, fakeBrokerSubmissions)

        val liveDecision = policy.liveDecision(live, now)
        val liveResult = executeWithModeArmingDecision(liveDecision) {
            fakeBrokerSubmissions++
            Result.success("fixture-order")
        }
        assertFalse(liveDecision.allowed)
        assertTrue(liveResult.isFailure)
        assertTrue(liveResult.exceptionOrNull()?.message.orEmpty().startsWith("MODE_ARMING_BLOCKED:"))
        assertEquals(0, fakeBrokerSubmissions)
    }
}
