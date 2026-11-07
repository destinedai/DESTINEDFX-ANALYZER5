package com.example

import com.example.data.broker.BrokerLoginSpecs
import com.example.data.broker.LoginConnector
import com.example.data.broker.LoginField
import com.example.data.broker.ServerSuggestionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerLoginSpecTest {
    @Test
    fun c1ContainsExactlyTheRequestedLoginPaths() {
        assertEquals(
            setOf("DERIV_OPTIONS", "DERIV_CTRADER", "DERIV_MT5", "FBS_MT5", "EXNESS_MT5", "HFM_MT5", "CTRADER"),
            BrokerLoginSpecs.all.map { it.id }.toSet(),
        )
        assertEquals(7, BrokerLoginSpecs.all.size)
    }

    @Test
    fun everySpecHasOneConnectorAndCompleteVisibleFields() {
        BrokerLoginSpecs.all.forEach { spec ->
            assertTrue(spec.connector in LoginConnector.values().toSet())
            assertFalse(spec.fields.isEmpty())
            assertTrue(spec.fields.all { spec.fieldLabels[it].orEmpty().isNotBlank() })
            assertTrue(spec.connectedEvidence.isNotEmpty())
            assertTrue(spec.sourceUrls.isNotEmpty())
            assertTrue(spec.demoSignupUrl.startsWith("https://"))
            assertTrue(spec.passwordRecoveryUrl.startsWith("https://"))
            assertTrue(spec.companyAliases.isNotEmpty())
            assertTrue(spec.capabilityRow.isNotBlank())
            assertTrue(spec.sourceUrls.all { it.startsWith("https://") })
        }
        assertEquals(listOf(LoginField.LOGIN, LoginField.PASSWORD, LoginField.SERVER), BrokerLoginSpecs.byId("FBS_MT5")!!.fields)
        assertEquals(listOf(LoginField.APP_ID, LoginField.TOKEN), BrokerLoginSpecs.byId("DERIV_OPTIONS")!!.fields)
        assertEquals(listOf(LoginField.GRANT_ACCESS), BrokerLoginSpecs.byId("CTRADER")!!.fields)
    }

    @Test
    fun connectedEvidenceDiffersByTransportAndDoesNotTrustSavedCredentials() {
        val mt5 = BrokerLoginSpecs.byId("EXNESS_MT5")!!
        assertTrue(mt5.connectedEvidence.contains("fresh EA heartbeat"))
        assertTrue(mt5.connectedEvidence.contains("exact server"))
        assertFalse(mt5.connectedEvidence.contains("saved password"))

        val ctrader = BrokerLoginSpecs.byId("DERIV_CTRADER")!!
        assertTrue(ctrader.connectedEvidence.contains("owner grant"))
        assertTrue(ctrader.connectedEvidence.contains("fresh quote"))
    }

    @Test
    fun connectorRoutingIsUnambiguous() {
        BrokerLoginSpecs.all.forEach { spec ->
            assertEquals(1, BrokerLoginSpecs.forConnector(spec.connector).count { it.id == spec.id })
        }
        assertEquals(4, BrokerLoginSpecs.forConnector(LoginConnector.MT5_TERMINAL).size)
        assertEquals(2, BrokerLoginSpecs.forConnector(LoginConnector.CTRADER).size)
    }

    @Test
    fun mt5ServerSuggestionsAreNeverInventedAndCarryProvenance() {
        BrokerLoginSpecs.all.filter { it.connector == LoginConnector.MT5_TERMINAL }.forEach { spec ->
            assertTrue(spec.serverSuggestions.any { it.source == ServerSuggestionSource.OWNER_HISTORY })
            assertTrue(spec.serverSuggestions.any { it.source == ServerSuggestionSource.HOST_REPORTED })
            assertTrue(spec.companyAliases.any { it.isNotBlank() })
        }
    }
}
