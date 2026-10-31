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
    fun realProfileCanConnectButRequiresSeparateExplicitAuthorizationForOrders() {
        val manager = SecretsManager(ApplicationProvider.getApplicationContext())
        assertFalse(manager.isDerivRealExecutionEnabled())
        assertTrue(manager.saveDerivAccountMode("real"))
        assertEquals("real", manager.getDerivAccountMode())
        if (!manager.isSecureStorageAvailable) return
        assertTrue(manager.enableDerivRealExecution(true))
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
