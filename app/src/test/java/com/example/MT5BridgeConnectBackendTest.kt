package com.example

import com.example.data.broker.BrokerLoginSpecs
import com.example.data.broker.HostProfile
import com.example.data.trade.MT5ManagedAccountGateway
import com.example.data.trade.MT5ManagedAccountLogin
import com.example.data.trade.MT5RemoteBridgeClient
import com.example.ui.bot.AccessType
import com.example.ui.bot.LoginForm
import com.example.ui.bot.LoginMode
import com.example.ui.bot.MT5BridgeConnectBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MT5BridgeConnectBackendTest {
    private class FakeGateway(
        var configuredHostProfile: HostProfile = HostProfile.EPHEMERAL,
    ) : MT5ManagedAccountGateway {
        var submitted: MT5ManagedAccountLogin? = null
        var hostProfileCalls = 0
        var stateCalls = 0

        override suspend fun registerManagedAccount(login: MT5ManagedAccountLogin): MT5RemoteBridgeClient.Snapshot {
            submitted = login
            return snapshot()
        }

        override suspend fun hostProfile(): HostProfile {
            hostProfileCalls++
            return configuredHostProfile
        }

        override suspend fun state(): MT5RemoteBridgeClient.Snapshot {
            stateCalls++
            return snapshot()
        }
    }

    @Test
    fun firstPairingForAllFourMt5BrandsForwardsOneTimeBootstrapWithoutLoggingIt() = runBlocking {
        listOf("FBS", "EXNESS", "HFM", "DERIV").forEach { brand ->
            val gateway = FakeGateway()
            val backend = MT5BridgeConnectBackend(gateway)
            val spec = requireNotNull(BrokerLoginSpecs.byId("${brand}_MT5"))
            val form = LoginForm(
                type = AccessType.TRADE,
                mode = LoginMode.DEMO,
                login = "123456",
                password = "fixture-broker-password",
                ownerBootstrapCode = "fixture-one-time-bootstrap",
                server = "Fixture-Demo",
            )

            assertNull(backend.preflight(spec, form))
            backend.submit(spec, form)

            assertEquals(brand, gateway.submitted?.brokerBrand)
            assertEquals("DEMO", gateway.submitted?.accountMode)
            assertEquals("fixture-one-time-bootstrap", gateway.submitted?.ownerBootstrapCode)
            assertEquals("123456", gateway.submitted?.login)
            assertEquals("Fixture-Demo", gateway.submitted?.server)
            assertTrue(form.toString().contains("secrets=<redacted>"))
            assertTrue(!form.toString().contains("fixture-one-time-bootstrap"))
            assertTrue(gateway.submitted.toString().contains("<redacted>"))
            assertTrue(!gateway.submitted.toString().contains("fixture-broker-password"))
        }
    }

    @Test
    fun liveTradePreflightReadsPublicHostMetadataWithoutAppPairingState() = runBlocking {
        val gateway = FakeGateway(HostProfile.EPHEMERAL)
        val backend = MT5BridgeConnectBackend(gateway)
        val spec = requireNotNull(BrokerLoginSpecs.byId("FBS_MT5"))
        val form = LoginForm(
            type = AccessType.TRADE,
            mode = LoginMode.LIVE,
            login = "123456",
            password = "fixture-broker-password",
            server = "Fixture-Live",
        )

        assertEquals("EPHEMERAL_HOST_LIVE_BLOCKED", backend.preflight(spec, form))
        assertEquals(1, gateway.hostProfileCalls)
        assertEquals(0, gateway.stateCalls)
        assertNull(gateway.submitted)
    }

    companion object {
        private fun snapshot() = MT5RemoteBridgeClient.Snapshot(
            managedState = "QUEUED",
            accessType = "MASTER",
            profileMode = "DEMO",
            connected = false,
            terminalOnline = false,
            heartbeatFresh = false,
            state = "OFFLINE",
            account = "",
            actualLogin = "",
            actualServer = "",
            actualAccountMode = "",
            liveAuthorized = false,
            tradeAllowed = false,
            terminalConnected = false,
            accountVerified = false,
            brokerIdentityVerified = false,
            currency = "",
            balance = null,
            equity = null,
            margin = null,
            freeMargin = null,
            marginLevel = null,
            leverage = null,
            positions = null,
            activeOrders = null,
            closedDeals = null,
            snapshotsValid = false,
            lastSignal = null,
            newsVerdict = null,
            raw = JSONObject(),
        )
    }
}
