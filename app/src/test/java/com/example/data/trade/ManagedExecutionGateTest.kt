package com.example.data.trade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedExecutionGateTest {
    private fun allowed(
        profile: String = "LIVE",
        state: String = "READY",
        access: String = "MASTER",
        mode: String = profile,
        expectedLogin: String = "123456",
        actualLogin: String = expectedLogin,
        expectedServer: String = "Broker-Exact",
        actualServer: String = expectedServer,
        eaState: String = "READY",
        fresh: Boolean = true,
        terminalConnected: Boolean = true,
        tradeAllowed: Boolean = true,
        accountVerified: Boolean = true,
        liveAuthorized: Boolean = true,
        newsClear: Boolean = true
    ) = MT5RemoteBridgeClient.canStart(
        profile, state, access, mode, expectedLogin, actualLogin, expectedServer, actualServer, eaState,
        fresh, terminalConnected, tradeAllowed, accountVerified, liveAuthorized, newsClear
    )

    @Test fun liveMasterWithVerifiedLiveIdentityFreshHeartbeatAndCurrentAuthorizationPasses() {
        assertTrue(allowed())
    }

    @Test fun liveInvestorIsRejected() {
        assertFalse(allowed(access = "INVESTOR"))
    }

    @Test fun liveMasterWithoutServerVerifiedIdentityIsRejected() {
        assertFalse(allowed(accountVerified = false))
        assertFalse(allowed(expectedLogin = "", actualLogin = "123456"))
        assertFalse(allowed(expectedServer = "", actualServer = "Broker-Exact"))
    }

    @Test fun liveRejectsDemoOrUnverifiedAccountMode() {
        assertFalse(allowed(mode = "DEMO"))
        assertFalse(allowed(mode = "UNVERIFIED"))
    }

    @Test fun liveRejectsStaleHeartbeatOrDisconnectedTerminal() {
        assertFalse(allowed(fresh = false))
        assertFalse(allowed(terminalConnected = false))
    }

    @Test fun liveRejectsMismatchedLoginOrExactBrokerServer() {
        assertFalse(allowed(actualLogin = "999999"))
        assertFalse(allowed(actualServer = "Broker-Other"))
    }

    @Test fun liveWithoutExplicitCurrentAuthorizationIsRejected() {
        assertFalse(allowed(liveAuthorized = false))
    }

    @Test fun nonReadyOrNoTradePermissionIsRejected() {
        assertFalse(allowed(state = "PROVISIONING"))
        assertFalse(allowed(tradeAllowed = false))
        assertFalse(allowed(eaState = "ERROR"))
    }

    @Test fun unknownNewsIsRejectedBeforeExecution() {
        assertFalse(allowed(newsClear = false))
    }

    @Test fun demoHasItsOwnModeAndDoesNotNeedOrInheritLiveAuthorization() {
        assertTrue(allowed(profile = "DEMO", mode = "DEMO", liveAuthorized = false))
        assertFalse(allowed(profile = "DEMO", mode = "LIVE", liveAuthorized = true))
    }
}
