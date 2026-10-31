package com.example

import com.example.data.deriv.DerivExecutionFailure
import com.example.data.deriv.DerivExecutionFailureKind
import com.example.data.deriv.DerivSubmissionStage
import com.example.data.deriv.DerivAccountInfo
import com.example.data.deriv.DerivAccountReadiness
import com.example.data.deriv.DerivConnectionState
import com.example.data.deriv.DerivPortfolioSnapshot
import com.example.data.deriv.DerivRealAuthorizationLease
import com.example.data.deriv.selectDerivOptionsAccount
import com.example.security.SecretsManager
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DerivAccountModeAndExecutionStateTest {
    @Test
    fun accountSelectionRequiresRequestedBrokerMode() {
        val accounts = JSONArray("""
            [{"account_id":"D123","status":"active","account_type":"demo"},
             {"account_id":"R456","status":"active","account_type":"real"}]
        """.trimIndent())
        assertEquals("D123", selectDerivOptionsAccount(accounts, "demo")?.optString("account_id"))
        assertEquals("R456", selectDerivOptionsAccount(accounts, "real")?.optString("account_id"))
        assertTrue(selectDerivOptionsAccount(accounts, "demo")?.optString("account_type") == "demo")
    }

    @Test
    fun multipleAccountsRequireExplicitAccountId() {
        val accounts = JSONArray("""
            [{"account_id":"D123","status":"active","account_type":"demo","currency":"USD","balance":10},
             {"account_id":"D456","status":"active","account_type":"demo","currency":"EUR","balance":20},
             {"account_id":"R456","status":"active","account_type":"real","currency":"USD","balance":30}]
        """.trimIndent())
        assertEquals(null, selectDerivOptionsAccount(accounts, "demo"))
        assertEquals("D456", selectDerivOptionsAccount(accounts, "demo", "D456")?.optString("account_id"))
        assertEquals(null, selectDerivOptionsAccount(accounts, "demo", "R456"))
    }

    @Test
    fun realProfileCanConnectButRequiresSeparateExplicitAuthorizationForOrders() {
        val manager = SecretsManager(ApplicationProvider.getApplicationContext())
        assertFalse(manager.isDerivRealExecutionEnabled())
        if (!manager.isSecureStorageAvailable) return
        assertTrue(manager.saveDerivAccountMode("demo"))
        assertFalse(manager.enableDerivRealExecution(true, "R456", "real", "USD", "session-a", "credential-a"))
        assertTrue(manager.saveDerivAccountMode("real"))
        assertEquals("real", manager.getDerivAccountMode())
        assertFalse(manager.enableDerivRealExecution(true))
        assertTrue(manager.enableDerivRealExecution(true, "R456", "real", "USD", "session-a", "credential-a"))
        assertTrue(manager.isDerivRealExecutionAuthorized("R456", "real", "USD", "session-a", "credential-a"))
        assertFalse(manager.isDerivRealExecutionAuthorized("R999", "real", "USD", "session-a", "credential-a"))
        assertFalse(manager.isDerivRealExecutionAuthorized("R456", "real", "EUR", "session-a", "credential-a"))
        assertFalse(manager.isDerivRealExecutionAuthorized("R456", "real", "USD", "session-b", "credential-a"))
        assertFalse(manager.isDerivRealExecutionAuthorized("R456", "real", "USD", "session-a", "credential-b"))
        assertEquals("real", manager.getDerivAccountMode())
        manager.disableDerivRealExecution()
        assertEquals("demo", manager.getDerivAccountMode())
    }

    @Test
    fun expiredRealAuthorizationIsInvalidatedAndCannotBeSilentlyRenewed() {
        val manager = SecretsManager(ApplicationProvider.getApplicationContext())
        if (!manager.isSecureStorageAvailable) return
        assertTrue(manager.saveDerivAccountMode("real"))
        assertTrue(manager.enableDerivRealExecution(true, "R456", "real", "USD", "session-a", "credential-a"))
        val lease = manager.getDerivRealAuthorizationLease() ?: error("armed lease missing")
        val expiresAt = lease.armedAtMs + DerivRealAuthorizationLease.DEFAULT_VALIDITY_MS
        assertTrue(manager.isDerivRealExecutionAuthorized(
            "R456", "real", "USD", "session-a", "credential-a",
            nowMs = expiresAt - 1L
        ))
        assertFalse(manager.isDerivRealExecutionAuthorized(
            "R456", "real", "USD", "session-a", "credential-a",
            nowMs = expiresAt
        ))
        assertFalse(manager.isDerivRealExecutionEnabled(expiresAt))
        assertEquals(
            DerivRealAuthorizationLease.State.INVALIDATED,
            manager.getDerivRealAuthorizationLease(expiresAt)?.state
        )
        assertTrue(manager.enableDerivRealExecution(true, "R456", "real", "USD", "session-a", "credential-a"))
        assertTrue(manager.isDerivRealExecutionAuthorized("R456", "real", "USD", "session-a", "credential-a"))
    }

    @Test
    fun credentialGenerationChangesWhenDerivAppIdChanges() {
        val manager = SecretsManager(ApplicationProvider.getApplicationContext())
        if (!manager.isSecureStorageAvailable) return
        assertTrue(manager.saveDerivApiToken("unit-test-pat"))
        assertTrue(manager.saveDerivAppId("123456"))
        val firstGeneration = manager.getDerivCredentialGeneration()
        assertTrue(firstGeneration.isNotBlank())
        assertTrue(manager.saveDerivAppId("654321"))
        assertTrue(manager.getDerivCredentialGeneration() != firstGeneration)
        assertTrue(manager.clearDerivCredentials())
    }

    @Test
    fun liveArmingReadinessRequiresReadyFreshMatchingAccountAndNoUnresolvedExecution() {
        fun ready(
            connection: DerivConnectionState = DerivConnectionState.READY,
            balanceAt: Long = 98_000L,
            portfolioAt: Long = 98_000L,
            portfolioAccountId: String = "R456",
            balanceCurrency: String = "USD",
            portfolioCurrency: String = "USD",
            portfolioMode: String = "REAL",
            sessionId: String = "session-a",
            credentialGeneration: String = "credential-a",
            unresolvedCount: Int = 0
        ): Boolean {
            val account = DerivAccountInfo(
                loginId = "R456",
                currency = "USD",
                balance = 100.0,
                email = "",
                accountType = "real",
                mode = "real",
                authenticatedSessionId = "session-a",
                credentialGeneration = "credential-a"
            )
            return DerivAccountReadiness.isReadyForTrading(
                connectionState = connection,
                account = account,
                currentSessionId = sessionId,
                currentCredentialGeneration = credentialGeneration,
                balance = 100.0,
                balanceCurrency = balanceCurrency,
                balanceUpdatedAtMs = balanceAt,
                portfolio = DerivPortfolioSnapshot(
                    successful = true,
                    accountId = portfolioAccountId,
                    accountMode = portfolioMode,
                    retrievedAtMs = portfolioAt,
                    currency = portfolioCurrency
                ),
                unresolvedExecutionCount = unresolvedCount,
                nowMs = 100_000L
            )
        }

        assertTrue(ready())
        assertFalse(ready(connection = DerivConnectionState.SYNCHRONIZING))
        assertFalse(ready(balanceAt = 90_000L))
        assertFalse(ready(portfolioAt = 90_000L))
        assertFalse(ready(portfolioAccountId = "R999"))
        assertFalse(ready(balanceCurrency = "EUR"))
        assertFalse(ready(portfolioCurrency = "EUR"))
        assertFalse(ready(portfolioMode = "DEMO"))
        assertFalse(ready(sessionId = "session-b"))
        assertFalse(ready(credentialGeneration = "credential-b"))
        assertFalse(ready(unresolvedCount = 1))
    }

    @Test
    fun onlyPurchaseTransmissionWithoutConfirmationIsAmbiguous() {
        val rejectedProposal = DerivExecutionFailure(DerivExecutionFailureKind.PROPOSAL_REJECTED, "rejected")
        val ambiguousPurchase = DerivExecutionFailure(DerivExecutionFailureKind.PURCHASE_AMBIGUOUS, "timeout", 12, "proposal-1", DerivSubmissionStage.PURCHASE_SENT)
        assertFalse(rejectedProposal.kind == DerivExecutionFailureKind.PURCHASE_AMBIGUOUS)
        assertTrue(ambiguousPurchase.kind == DerivExecutionFailureKind.PURCHASE_AMBIGUOUS)
        assertEquals(DerivSubmissionStage.PURCHASE_SENT, ambiguousPurchase.submissionStage)
    }
}
