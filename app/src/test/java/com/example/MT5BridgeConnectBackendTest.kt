package com.example

import com.example.data.broker.BrokerLoginSpecs
import com.example.data.broker.HostProfile
import com.example.data.broker.LoginMode
import com.example.data.trade.MT5ManagedAccountGateway
import com.example.data.trade.MT5ManagedAccountLogin
import com.example.data.trade.MT5RemoteBridgeClient
import com.example.ui.bot.AccessType
import com.example.ui.bot.LoginForm
import com.example.ui.bot.MT5BridgeConnectBackend
import com.example.ui.bot.ConnectSubmissionFailure
import com.example.ui.bot.ConnectStage
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
        var pairingAvailable = false
        var currentSnapshot: MT5RemoteBridgeClient.Snapshot = snapshot()

        override fun hasPairing(): Boolean = pairingAvailable

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
            return currentSnapshot
        }
    }

    @Test
    fun engineWakeAndHealthVerificationFinishBeforePairingBegins() = runBlocking {
        val gateway = FakeGateway()
        val events = mutableListOf<String>()
        val backend = MT5BridgeConnectBackend(
            gateway,
            managementHealthCheck = { events += "MANAGEMENT_HEALTH" },
            wakeHost = { reportStage ->
                events += "HOST_WAKE"
                reportStage(ConnectStage.ENGINE_READY)
            },
        )
        val spec = requireNotNull(BrokerLoginSpecs.byId("FBS_MT5"))
        val form = LoginForm(
            type = AccessType.TRADE, mode = LoginMode.DEMO, login = "123456",
            password = "fixture-broker-password", server = "Fixture-Demo",
        )

        assertNull(backend.preflight(spec, form) { events += it.name })
        backend.submit(spec, form)

        assertTrue(events.indexOf("HOST_WAKE") >= 0)
        assertTrue(events.indexOf("HOST_WAKE") < events.indexOf("MANAGEMENT_HEALTH"))
        assertTrue(events.indexOf("MANAGEMENT_HEALTH") < events.indexOf(ConnectStage.PAIRING.name))
        assertEquals("123456", gateway.submitted?.login)
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
    fun retryResumesSameQueuedBrokerAccountWithoutResubmittingCredentials() = runBlocking {
        val gateway = FakeGateway()
        gateway.pairingAvailable = true
        gateway.currentSnapshot = snapshot().copy(
            workerStatus = "QUEUED",
            raw = JSONObject().put("managed_execution", JSONObject()
                .put("broker_brand", "FBS")
                .put("account_mode", "DEMO")
                .put("access_type", "MASTER")
                .put("requested_login", "123456")
                .put("requested_server", "Fixture-Demo")),
        )
        val backend = MT5BridgeConnectBackend(gateway)
        val spec = requireNotNull(BrokerLoginSpecs.byId("FBS_MT5"))
        val form = LoginForm(
            type = AccessType.TRADE, mode = LoginMode.DEMO, login = "123456",
            password = "fixture-broker-password", server = "Fixture-Demo",
        )

        backend.submit(spec, form)

        assertNull(gateway.submitted)
        assertEquals(1, gateway.stateCalls)
    }

    @Test
    fun retryCannotReplaceAnotherActiveAccountInTheSameBrokerPairing() = runBlocking {
        val gateway = FakeGateway()
        gateway.pairingAvailable = true
        gateway.currentSnapshot = snapshot().copy(
            workerStatus = "AUTHENTICATING",
            raw = JSONObject().put("managed_execution", JSONObject()
                .put("broker_brand", "FBS")
                .put("account_mode", "DEMO")
                .put("access_type", "MASTER")
                .put("requested_login", "654321")
                .put("requested_server", "Fixture-Demo")),
        )
        val backend = MT5BridgeConnectBackend(gateway)
        val spec = requireNotNull(BrokerLoginSpecs.byId("FBS_MT5"))
        val form = LoginForm(
            type = AccessType.TRADE, mode = LoginMode.DEMO, login = "123456",
            password = "fixture-broker-password", server = "Fixture-Demo",
        )

        try {
            backend.submit(spec, form)
            throw AssertionError("A different active broker account must not be overwritten.")
        } catch (failure: ConnectSubmissionFailure) {
            assertEquals("PAIRING_TERMINAL_ALREADY_ACTIVE", failure.code)
        }
        assertNull(gateway.submitted)
    }

    @Test
    fun readyTerminalWithoutFreshConnectionReportsMt5NotConnected() = runBlocking {
        val gateway = FakeGateway()
        gateway.currentSnapshot = snapshot().copy(
            managedState = "READY",
            workerStatus = "READY",
            terminalOnline = false,
            terminalConnected = false,
            heartbeatFresh = false,
        )

        val observation = MT5BridgeConnectBackend(gateway).observe()

        assertEquals("MT5_NOT_CONNECTED", observation.failureCode)
        assertEquals(
            com.example.ui.bot.ConnectFailures.sentence("MT5_NOT_CONNECTED"),
            observation.failureMessage,
        )
    }

    @Test
    fun verifiedAccountCarriesTheBrokerReportedMargin() = runBlocking {
        val gateway = FakeGateway()
        gateway.currentSnapshot = snapshot().copy(
            managedState = "READY",
            workerStatus = "READY",
            connected = true,
            terminalOnline = true,
            heartbeatFresh = true,
            state = "RUNNING",
            account = "123456 @ FBS-Live",
            actualLogin = "123456",
            actualServer = "FBS-Live",
            actualAccountMode = "LIVE",
            terminalConnected = true,
            accountVerified = true,
            brokerIdentityVerified = true,
            currency = "USD",
            balance = 1.0,
            equity = 1.0,
            margin = 0.35,
            freeMargin = 0.65,
            marginLevel = 285.71,
            raw = JSONObject().put("heartbeat_age_seconds", 1),
        )

        val observation = MT5BridgeConnectBackend(gateway).observe()

        assertEquals(0.35, requireNotNull(observation.account).margin!!, 0.0)
    }

    @Test
    fun liveTradeCredentialsCanBeSubmittedOnEphemeralHostForReadOnlyVerification() = runBlocking {
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

        assertNull(backend.preflight(spec, form))
        backend.submit(spec, form)
        assertEquals(0, gateway.hostProfileCalls)
        assertEquals(0, gateway.stateCalls)
        assertEquals("LIVE", gateway.submitted?.accountMode)
        assertEquals("MASTER", gateway.submitted?.accessType)
        assertEquals("123456", gateway.submitted?.login)
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
