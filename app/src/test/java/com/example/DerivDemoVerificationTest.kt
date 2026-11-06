package com.example

import com.example.data.deriv.DerivDemoVerificationResult
import com.example.data.deriv.DerivDemoVerificationState
import com.example.data.deriv.derivDemoVerificationLabel
import com.example.data.deriv.redactedDerivAccountPrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DerivDemoVerificationTest {
    @Test
    fun completeReadOnlyEvidenceIsPass() {
        val result = DerivDemoVerificationResult("VRTC", "DEMO", "USD", true, true, true, 1L)
        assertEquals(DerivDemoVerificationState.PASS, result.state)
        assertTrue(derivDemoVerificationLabel(result).contains("READ-ONLY DEMO"))
    }

    @Test
    fun missingTickOrFailureCannotBecomePass() {
        val missingTick = DerivDemoVerificationResult("VRTC", "DEMO", "USD", true, true, false, 1L)
        val failed = DerivDemoVerificationResult("VRTC", "DEMO", "USD", true, true, true, 1L, "network")
        assertEquals(DerivDemoVerificationState.FAILED, missingTick.state)
        assertEquals(DerivDemoVerificationState.FAILED, failed.state)
    }

    @Test
    fun realEvidenceCannotBecomeDemoPass() {
        val real = DerivDemoVerificationResult("REAL", "REAL", "USD", true, true, true, 1L)
        assertEquals(DerivDemoVerificationState.FAILED, real.state)
    }

    @Test
    fun accountRedactionKeepsOnlyFirstFourCharacters() {
        assertEquals("VRTC", redactedDerivAccountPrefix("VRTC123456"))
        assertEquals("unverified", redactedDerivAccountPrefix(""))
    }

    @Test
    fun verificationResultModelContainsNoOrderEndpointCalls() {
        val source = File("src/main/java/com/example/data/deriv/DerivDemoVerification.kt").readText()
        listOf("/proposal", "/buy", "/sell", "/cancel", "/orders").forEach { endpoint ->
            assertTrue("unexpected order endpoint in read-only verification model: $endpoint", !source.contains(endpoint))
        }
    }
}
