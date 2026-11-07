package com.example

import com.example.data.broker.AccountMode
import com.example.data.broker.ConnectorEvidence
import com.example.data.broker.ConnectorKind
import com.example.data.broker.ConnectorVerification
import com.example.data.broker.ModeAccountIdentity
import com.example.data.broker.ModeArmingPolicy
import com.example.data.broker.ModeDemoAcceptance
import com.example.data.broker.ModePath
import com.example.data.broker.ModeReadOnlyVerification
import com.example.data.broker.UniversalConnectorCapabilities
import com.example.data.broker.UniversalConnectorDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalConnectorDispatcherTest {
    private fun path(kind: ConnectorKind) = when (kind) {
        ConnectorKind.DERIV_OPTIONS -> ModePath.DERIV_OPTIONS
        ConnectorKind.CTRADER_ACCOUNT -> ModePath.CTRADER_ACCOUNT
        ConnectorKind.FBS_MT5 -> ModePath.FBS_MT5
        ConnectorKind.EXNESS_MT5 -> ModePath.EXNESS_MT5
        ConnectorKind.HFM_MT5 -> ModePath.HFM_MT5
        ConnectorKind.DERIV_MT5 -> ModePath.DERIV_MT5
    }

    private fun fixture(kind: ConnectorKind): ConnectorVerification {
        val identity = ModeAccountIdentity(
            path(kind), kind.name, "fixture-account-" + kind.name,
            if (kind.name.endsWith("MT5")) "Fixture-Demo-Server" else "",
            AccountMode.DEMO, "fixture-generation-" + kind.name
        )
        val now = System.currentTimeMillis()
        return ConnectorVerification(
            kind = kind,
            evidence = ConnectorEvidence(
                authenticatedSession = true, freshAccountData = true, accountIdentityMatched = true,
                freshQuote = true, terminalIdentityMatched = true, exactLogin = true,
                exactServer = true, exactCompany = true, exactAccountMode = true
            ),
            detail = "Fixture transport evidence only",
            identity = identity,
            readOnlyVerification = ModeReadOnlyVerification(identity, now, true, true, true, true, true),
            demoAcceptance = ModeDemoAcceptance(identity, now, "a".repeat(64)),
            currentDemoCredentialGeneration = identity.credentialGeneration
        )
    }

    @Test
    fun everyProviderUsesItsOwnConnectorAndTheSharedModePolicy() = runBlocking {
        val calls = mutableListOf<ConnectorKind>()
        val dispatcher = UniversalConnectorDispatcher(ConnectorKind.values().associateWith { kind ->
            suspend { calls += kind; fixture(kind) }
        }, ModeArmingPolicy())

        ConnectorKind.values().forEach { kind ->
            assertTrue(dispatcher.connectAndVerify(kind).isSuccess)
        }
        assertEquals(ConnectorKind.values().toSet(), calls.toSet())
        assertEquals(ConnectorKind.values().size, calls.size)
    }

    @Test
    fun missingQuoteOrTerminalEvidenceCannotBecomeConnected() {
        val dispatcher = UniversalConnectorDispatcher(emptyMap(), ModeArmingPolicy())
        val ctrader = fixture(ConnectorKind.CTRADER_ACCOUNT).copy(
            evidence = ConnectorEvidence(true, true, true, freshQuote = false)
        )
        val mt5 = fixture(ConnectorKind.FBS_MT5).copy(
            evidence = ConnectorEvidence(true, true, false, freshQuote = true)
        )
        assertFalse(dispatcher.validateVerification(ConnectorKind.CTRADER_ACCOUNT, ctrader).isSuccess)
        assertFalse(dispatcher.validateVerification(ConnectorKind.FBS_MT5, mt5).isSuccess)
    }

    @Test
    fun exactPathIdentityAndDemoAcceptanceMustMatch() {
        val policy = ModeArmingPolicy()
        val dispatcher = UniversalConnectorDispatcher(emptyMap(), policy)
        val good = fixture(ConnectorKind.DERIV_OPTIONS)
        assertTrue(dispatcher.validateVerification(ConnectorKind.DERIV_OPTIONS, good).isSuccess)
        assertFalse(dispatcher.validateVerification(
            ConnectorKind.FBS_MT5, good.copy(kind = ConnectorKind.FBS_MT5)
        ).isSuccess)
        assertFalse(dispatcher.validateVerification(
            ConnectorKind.DERIV_OPTIONS,
            good.copy(demoAcceptance = null)
        ).isSuccess)
    }

    @Test
    fun liveConnectorIsBlockedUntilAProcessLocalLeaseExists() {
        val dispatcher = UniversalConnectorDispatcher(emptyMap(), ModeArmingPolicy())
        val demo = fixture(ConnectorKind.FBS_MT5)
        val liveIdentity = demo.identity!!.copy(mode = AccountMode.LIVE, credentialGeneration = "live-generation")
        val live = demo.copy(
            identity = liveIdentity,
            readOnlyVerification = ModeReadOnlyVerification(liveIdentity, System.currentTimeMillis(), true, true, true, true, true),
            demoAcceptance = demo.demoAcceptance,
            currentDemoCredentialGeneration = demo.identity.credentialGeneration
        )
        assertFalse(dispatcher.validateVerification(ConnectorKind.FBS_MT5, live).isSuccess)
    }

    @Test
    fun capabilityRowsAreExplicitAndManualOnlyForMt5Fallbacks() {
        val rows = UniversalConnectorCapabilities.rows
        assertEquals(6, rows.size)
        assertFalse(rows.first { it.kind == ConnectorKind.CTRADER_ACCOUNT }.manualSignalsAvailable)
        assertTrue(rows.filter { it.kind.name.endsWith("MT5") }.all { it.manualSignalsAvailable && it.automatedOnOwnerHost })
    }
}
