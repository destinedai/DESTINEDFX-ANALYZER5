package com.example

import com.example.service.AccountSessionRegistry
import com.example.service.verifiedManagedMt5AccountSnapshot
import com.example.data.broker.HostProfile
import com.example.data.trade.MT5RemoteBridgeClient
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MT5AccountSessionRegistryTest {
    @Test fun brokerAndModeAreParsedAsIndependentProfileDimensions() {
        assertEquals("FBS" to "DEMO", AccountSessionRegistry.brandAndMode("FBS_DEMO"))
        assertEquals("EXNESS" to "DEMO", AccountSessionRegistry.brandAndMode("EXNESS_DEMO"))
        assertEquals("HFM" to "LIVE", AccountSessionRegistry.brandAndMode("HFM_LIVE"))
        assertEquals("DERIV" to "DEMO", AccountSessionRegistry.brandAndMode("DERIV_DEMO"))
    }

    @Test fun managedAccountProjectionRequiresExactProfileAndFreshTerminalHeartbeat() {
        val now = 1_800_000_000_000L
        val snapshot = MT5RemoteBridgeClient.Snapshot(
            hostProfile = HostProfile.PERSISTENT,
            managedState = "READY",
            workerStatus = "READY",
            accessType = "MASTER",
            profileMode = "DEMO",
            connected = true,
            terminalOnline = true,
            heartbeatFresh = true,
            state = "READY",
            account = "123 @ FBS-Demo",
            actualLogin = "123",
            actualServer = "FBS-Demo",
            actualAccountMode = "DEMO",
            liveAuthorized = false,
            tradeAllowed = true,
            terminalConnected = true,
            accountVerified = true,
            brokerIdentityVerified = true,
            currency = "USD",
            balance = 1_000.0,
            equity = 1_025.0,
            margin = 100.0,
            freeMargin = 925.0,
            marginLevel = 1_025.0,
            leverage = 100,
            positions = emptyList(),
            activeOrders = emptyList(),
            closedDeals = emptyList(),
            snapshotsValid = true,
            lastSignal = null,
            newsVerdict = null,
            raw = JSONObject().put("heartbeat_age_seconds", 2).put("heartbeat_stale_seconds", 15)
        )
        val verified = verifiedManagedMt5AccountSnapshot(
            "FBS_DEMO", "123", "FBS-Demo", "DEMO", snapshot, now
        )
        assertNotNull(verified)
        assertTrue(verified!!.isDisplayable(nowMs = now))
        assertEquals(1_000.0, verified.balance!!, 0.0)
        assertEquals(1_025.0, verified.equity!!, 0.0)
        assertEquals(100, verified.leverage)

        assertNull(verifiedManagedMt5AccountSnapshot("FBS_DEMO", "999", "FBS-Demo", "DEMO", snapshot, now))
        assertNull(verifiedManagedMt5AccountSnapshot("FBS_DEMO", "123", "Other-Server", "DEMO", snapshot, now))
        assertNull(verifiedManagedMt5AccountSnapshot("FBS_LIVE", "123", "FBS-Demo", "LIVE", snapshot, now))
    }

    @Test fun staleOrUnknownBrokerIdentityNeverBecomesDisplayableAccountData() {
        val now = 1_800_000_000_000L
        val snapshot = MT5RemoteBridgeClient.Snapshot(
            hostProfile = HostProfile.PERSISTENT,
            managedState = "READY", workerStatus = "READY", accessType = "MASTER",
            profileMode = "DEMO", connected = false, terminalOnline = false, heartbeatFresh = false,
            state = "STALE", account = "123 @ FBS-Demo", actualLogin = "123", actualServer = "FBS-Demo",
            actualAccountMode = "DEMO", liveAuthorized = false, tradeAllowed = false, terminalConnected = false,
            accountVerified = false, brokerIdentityVerified = false, currency = "USD",
            balance = 1_000.0, equity = 1_000.0, margin = 0.0, freeMargin = 1_000.0,
            marginLevel = 0.0, leverage = 100, positions = emptyList(), activeOrders = emptyList(),
            closedDeals = emptyList(), snapshotsValid = true, lastSignal = null, newsVerdict = null,
            raw = JSONObject().put("heartbeat_age_seconds", 90).put("heartbeat_stale_seconds", 15)
        )
        assertNull(verifiedManagedMt5AccountSnapshot("FBS_DEMO", "123", "FBS-Demo", "DEMO", snapshot, now))
        assertNull(verifiedManagedMt5AccountSnapshot(
            "FBS_DEMO", "123", "FBS-Demo", "DEMO",
            snapshot.copy(
                connected = true, terminalOnline = true, heartbeatFresh = true,
                terminalConnected = true, accountVerified = true, brokerIdentityVerified = false,
                raw = JSONObject().put("heartbeat_age_seconds", 1).put("heartbeat_stale_seconds", 15)
            ),
            now
        ))
        assertNull(verifiedManagedMt5AccountSnapshot(
            "FBS_DEMO", "123", "FBS-Demo", "DEMO",
            snapshot.copy(leverage = null, connected = true, terminalOnline = true, heartbeatFresh = true,
                terminalConnected = true, accountVerified = true, brokerIdentityVerified = true,
                raw = JSONObject().put("heartbeat_age_seconds", 1).put("heartbeat_stale_seconds", 15)),
            now
        ))
    }

    @Test fun unknownOrIncompleteProfilesAreRejected() {
        assertNull(AccountSessionRegistry.brandAndMode("UNKNOWN_DEMO"))
        assertNull(AccountSessionRegistry.brandAndMode("FBS"))
        assertNull(AccountSessionRegistry.brandAndMode("FBS_PAPER"))
    }
}
