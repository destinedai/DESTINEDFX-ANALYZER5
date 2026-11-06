package com.example

import com.example.data.deriv.DerivDemoVerificationResult
import com.example.data.deriv.DerivDemoVerificationState
import com.example.data.deriv.DerivPublicTickEvidence
import com.example.data.deriv.derivDemoVerificationLabel
import com.example.data.deriv.redactedDerivAccountPrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DerivDemoVerificationTest {
    private val startedAt = 100_000L
    private val checkedAt = 102_000L

    private fun validEvidence(
        tick: DerivPublicTickEvidence? = DerivPublicTickEvidence("R_100", 123.45, 101_000L, 101_100L, startedAt),
    ) = DerivDemoVerificationResult(
        selectedAccountId = "VRTC123456",
        accountId = "VRTC123456",
        accountType = "DEMO",
        currency = "USD",
        balanceValue = 100.0,
        balanceReceived = true,
        balanceAccountId = "VRTC123456",
        balanceCurrency = "USD",
        balanceUpdatedAtMs = 101_000L,
        portfolioReceived = true,
        portfolioAccountId = "VRTC123456",
        portfolioMode = "DEMO",
        portfolioCurrency = "USD",
        portfolioRetrievedAtMs = 101_000L,
        tickEvidence = tick,
        checkedAtMs = checkedAt,
    )

    @Test
    fun completeMatchingFreshReadOnlyEvidenceIsPass() {
        val result = validEvidence()
        assertEquals(DerivDemoVerificationState.PASS, result.state)
        assertTrue(derivDemoVerificationLabel(result).contains("READ-ONLY DEMO"))
    }

    @Test
    fun invalidTickEvidenceCannotBecomePass() {
        val invalidTicks = listOf(
            null,
            DerivPublicTickEvidence("WRONG_SYMBOL", 123.45, 101_000L, 101_100L, startedAt),
            DerivPublicTickEvidence("R_100", 0.0, 101_000L, 101_100L, startedAt),
            DerivPublicTickEvidence("R_100", -1.0, 101_000L, 101_100L, startedAt),
            DerivPublicTickEvidence("R_100", Double.NaN, 101_000L, 101_100L, startedAt),
            DerivPublicTickEvidence("R_100", Double.POSITIVE_INFINITY, 101_000L, 101_100L, startedAt),
            DerivPublicTickEvidence("R_100", 123.45, 90_000L, 101_100L, startedAt), // stale/provider before start
            DerivPublicTickEvidence("R_100", 123.45, 101_000L, 111_001L, startedAt), // stale
            DerivPublicTickEvidence("R_100", 123.45, 103_000L, 103_000L, startedAt), // future provider time
            DerivPublicTickEvidence("R_100", 123.45, 101_000L, 103_000L, startedAt), // future receive time
            DerivPublicTickEvidence("R_100", 123.45, 101_000L, 101_100L, 103_000L), // start after provider
        )
        invalidTicks.forEach { tick ->
            assertEquals("invalid tick should fail: $tick", DerivDemoVerificationState.FAILED, validEvidence(tick).state)
        }
    }

    @Test
    fun mismatchedAccountAndCurrencyEvidenceCannotBecomePass() {
        val valid = validEvidence()
        listOf(
            valid.copy(selectedAccountId = "VRTC654321"),
            valid.copy(accountId = "VRTC654321"),
            valid.copy(currency = "EUR"),
            valid.copy(balanceCurrency = "EUR"),
            valid.copy(portfolioCurrency = "EUR"),
        ).forEach { result -> assertEquals(DerivDemoVerificationState.FAILED, result.state) }
    }

    @Test
    fun invalidBalanceValuesCannotBecomePassButZeroIsValidEvidence() {
        val valid = validEvidence()
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01).forEach { balance ->
            assertEquals(DerivDemoVerificationState.FAILED, valid.copy(balanceValue = balance).state)
        }
        assertEquals(DerivDemoVerificationState.PASS, valid.copy(balanceValue = 0.0).state)
    }

    @Test
    fun nonDemoAccountAndPortfolioEvidenceCannotBecomePass() {
        val valid = validEvidence()
        listOf(valid.copy(accountType = "REAL"), valid.copy(portfolioMode = "REAL")).forEach { result ->
            assertEquals(DerivDemoVerificationState.FAILED, result.state)
        }
    }

    @Test
    fun staleOrWrongAccountBalanceCannotBecomePass() {
        val valid = validEvidence()
        listOf(
            valid.copy(balanceUpdatedAtMs = checkedAt - 8_001L),
            valid.copy(balanceAccountId = "VRTC654321"),
        ).forEach { result -> assertEquals(DerivDemoVerificationState.FAILED, result.state) }
    }

    @Test
    fun staleOrWrongAccountOrWrongModePortfolioCannotBecomePass() {
        val valid = validEvidence()
        listOf(
            valid.copy(portfolioRetrievedAtMs = checkedAt - 8_001L),
            valid.copy(portfolioAccountId = "VRTC654321"),
            valid.copy(portfolioMode = "REAL"),
        ).forEach { result -> assertEquals(DerivDemoVerificationState.FAILED, result.state) }
    }

    @Test
    fun explicitFailureMessageCannotBecomePass() {
        assertEquals(DerivDemoVerificationState.FAILED, validEvidence().copy(failureMessage = "network").state)
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
