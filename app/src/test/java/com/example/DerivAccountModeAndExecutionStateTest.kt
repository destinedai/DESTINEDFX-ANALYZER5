package com.example

import com.example.data.deriv.DerivExecutionFailure
import com.example.data.deriv.DerivExecutionFailureKind
import com.example.data.deriv.DerivSubmissionStage
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
        assertTrue(manager.saveDerivAccountMode("real"))
        assertEquals("real", manager.getDerivAccountMode())
        assertFalse(manager.enableDerivRealExecution(true))
        assertTrue(manager.enableDerivRealExecution(true, "R456", "real", "USD", "session-a", "credential-a"))
        assertTrue(manager.isDerivRealExecutionAuthorized("R456", "real", "USD", "session-a", "credential-a"))
        assertFalse(manager.isDerivRealExecutionAuthorized("R999", "real", "USD", "session-a", "credential-a"))
        assertFalse(manager.isDerivRealExecutionAuthorized("R456", "real", "USD", "session-b", "credential-a"))
        assertFalse(manager.isDerivRealExecutionAuthorized("R456", "real", "USD", "session-a", "credential-b"))
        assertEquals("real", manager.getDerivAccountMode())
        manager.disableDerivRealExecution()
        assertEquals("demo", manager.getDerivAccountMode())
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
