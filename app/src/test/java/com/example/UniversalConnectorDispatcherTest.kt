package com.example

import com.example.data.broker.ConnectorEvidence
import com.example.data.broker.ConnectorKind
import com.example.data.broker.ConnectorVerification
import com.example.data.broker.UniversalConnectorCapabilities
import com.example.data.broker.UniversalConnectorDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalConnectorDispatcherTest {
    @Test
    fun eachProviderRoutesToItsOwnConnector() = runBlocking {
        val calls = mutableListOf<ConnectorKind>()
        val evidence = ConnectorEvidence(true, true, true, freshQuote = true, terminalIdentityMatched = true, exactLogin = true, exactServer = true, exactCompany = true, exactAccountMode = true)
        val dispatcher = UniversalConnectorDispatcher(ConnectorKind.values().associateWith { kind ->
            suspend { calls += kind; ConnectorVerification(kind, evidence, "verified") }
        })

        ConnectorKind.values().forEach { kind -> assertTrue(dispatcher.connectAndVerify(kind).isSuccess) }

        assertEquals(ConnectorKind.values().toSet(), calls.toSet())
        assertEquals(ConnectorKind.values().size, calls.size)
    }

    @Test
    fun httpSuccessOrSavedCredentialsAloneCannotBecomeConnected() = runBlocking {
        val dispatcher = UniversalConnectorDispatcher(mapOf(
            ConnectorKind.CTRADER_ACCOUNT to suspend {
                ConnectorVerification(ConnectorKind.CTRADER_ACCOUNT, ConnectorEvidence(true, true, true, freshQuote = false), "fresh matching quote is missing")
            },
            ConnectorKind.FBS_MT5 to suspend {
                ConnectorVerification(ConnectorKind.FBS_MT5, ConnectorEvidence(true, true, false, freshQuote = true), "terminal identity is not verified")
            },
        ))

        assertFalse(dispatcher.connectAndVerify(ConnectorKind.CTRADER_ACCOUNT).isSuccess)
        assertFalse(dispatcher.connectAndVerify(ConnectorKind.FBS_MT5).isSuccess)
    }

    @Test
    fun capabilityRowsAreExplicitAndManualOnlyForMt5Fallbacks() {
        val rows = UniversalConnectorCapabilities.rows
        assertEquals(6, rows.size)
        assertFalse(rows.first { it.kind == ConnectorKind.CTRADER_ACCOUNT }.manualSignalsAvailable)
        assertTrue(rows.filter { it.kind.name.endsWith("MT5") }.all { it.manualSignalsAvailable && it.automatedOnOwnerHost })
    }
}
