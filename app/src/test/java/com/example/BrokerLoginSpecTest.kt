package com.example

import com.example.data.broker.BrokerLoginSpecs
import com.example.data.broker.LoginConnector
import com.example.data.broker.LoginField
import com.example.ui.bot.LoginForm
import com.example.ui.bot.ServerOption
import com.example.ui.bot.ServerOptions
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
            assertTrue(spec.realAccountUrl.startsWith("https://"))
            assertTrue(spec.passwordRecoveryUrl.startsWith("https://"))
            assertTrue(spec.companyAliases.isNotEmpty())
            assertTrue(spec.capabilityRow.isNotBlank())
            assertTrue(spec.sourceUrls.all { it.startsWith("https://") })
            assertTrue(spec.sourceUrls.contains(spec.realAccountUrl))
        }
        assertEquals(listOf(LoginField.LOGIN, LoginField.PASSWORD, LoginField.SERVER), BrokerLoginSpecs.byId("FBS_MT5")!!.fields)
        assertEquals(listOf(LoginField.APP_ID, LoginField.TOKEN), BrokerLoginSpecs.byId("DERIV_OPTIONS")!!.fields)
        assertEquals(listOf(LoginField.GRANT_ACCESS), BrokerLoginSpecs.byId("CTRADER")!!.fields)
    }

    @Test
    fun realAccountLinksDoNotReuseTheDemoSignupUrl() {
        BrokerLoginSpecs.all.forEach { spec ->
            assertTrue(spec.id, spec.realAccountUrl != spec.demoSignupUrl)
        }
        assertEquals("https://ctrader.com/brokers/", BrokerLoginSpecs.byId("CTRADER")!!.realAccountUrl)
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
    fun derivRequiresOwnerEnteredServerAndDoesNotSuggestGuessedNames() {
        val deriv = BrokerLoginSpecs.byId("DERIV_MT5")!!
        assertEquals("", LoginForm().server)
        assertTrue(deriv.serverSuggestions.isEmpty())
        assertEquals(
            listOf(ServerOption.TypeServerName),
            ServerOptions.build(deriv, emptyList(), emptyList()),
        )
        assertTrue(ServerOptions.fieldHint(deriv).startsWith("Copy from Deriv Dashboard > MT5 > Server"))
        BrokerLoginSpecs.all.filter { it.connector == LoginConnector.MT5_TERMINAL }.forEach { spec ->
            assertEquals(listOf(LoginField.LOGIN, LoginField.PASSWORD, LoginField.SERVER), spec.fields)
            assertFalse(spec.fields.contains(LoginField.APP_ID))
        }
    }

    @Test
    fun everyManagedMt5BrokerRequiresItsOwnVerifiedDemoAcceptance() {
        val paths = mapOf(
            "FBS_MT5" to com.example.data.broker.ModePath.FBS_MT5,
            "EXNESS_MT5" to com.example.data.broker.ModePath.EXNESS_MT5,
            "HFM_MT5" to com.example.data.broker.ModePath.HFM_MT5,
            "DERIV_MT5" to com.example.data.broker.ModePath.DERIV_MT5,
        )
        val now = 1_800_000_000_000L
        val policy = com.example.data.broker.ModeArmingPolicy(nowMs = { now })
        val mt5Specs = BrokerLoginSpecs.forConnector(LoginConnector.MT5_TERMINAL)
        assertEquals(paths.keys, mt5Specs.map { it.id }.toSet())
        mt5Specs.forEach { spec ->
            val identity = com.example.data.broker.ModeAccountIdentity(
                path = paths.getValue(spec.id),
                broker = spec.id.substringBefore("_MT5"),
                accountId = "12345678",
                server = "Broker-Demo",
                mode = com.example.data.broker.AccountMode.DEMO,
                credentialGeneration = "credential-generation",
            )
            val verification = com.example.data.broker.ModeReadOnlyVerification(
                identity = identity,
                checkedAtMs = now,
                authenticatedSession = true,
                freshAccountData = true,
                freshQuote = true,
                freshHeartbeat = true,
                accountIdentityMatched = true,
            )
            val decision = policy.demoDecision(
                identity = identity,
                verification = verification,
                acceptance = null,
                currentCredentialGeneration = identity.credentialGeneration,
                now = now,
            )
            assertFalse(spec.id, decision.allowed)
            assertEquals(spec.id, "OWNER_DEMO_ACCEPTANCE_REQUIRED", decision.reason)
        }
    }

    @Test
    fun mt5ServerSuggestionsAreNeverInventedAndCarryProvenance() {
        BrokerLoginSpecs.all.filter { it.connector == LoginConnector.MT5_TERMINAL }.forEach { spec ->
            assertTrue(spec.id, spec.serverSuggestions.isEmpty())
            assertEquals(listOf(ServerOption.TypeServerName), ServerOptions.build(spec, emptyList(), emptyList()))
            val dashboardSource = if (spec.id == "DERIV_MT5") "Copy from Deriv Dashboard" else "Copy from Broker Dashboard"
            assertTrue(spec.id, ServerOptions.fieldHint(spec).startsWith(dashboardSource))
            assertEquals(
                listOf(
                    ServerOption.Suggested("Owner-Exact", "Your history"),
                    ServerOption.Suggested("Host-Exact", "Reported by host"),
                    ServerOption.TypeServerName,
                ),
                ServerOptions.build(spec, listOf("Owner-Exact"), listOf("Host-Exact")),
            )
            assertTrue(spec.companyAliases.any { it.isNotBlank() })
        }
    }
}
