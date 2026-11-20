package com.example

import com.example.ui.BotUiState
import com.example.ui.canSelectMt5Mode
import com.example.ui.deriveBotUiState
import com.example.ui.isVerifiedMt5Connection
import com.example.ui.isValidDemoPassEvidence
import com.example.ui.liveLeaseStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class BotUiStateTest {
    @Test fun httpSuccessWithoutVerifiedHeartbeatIsNotConnected() {
        assertEquals(
            BotUiState.CONNECTING,
            deriveBotUiState(true, false, false, null, null, null, false),
        )
        assertEquals(
            BotUiState.NOT_CONFIGURED,
            deriveBotUiState(false, false, false, null, null, null, false),
        )
    }

    @Test fun pairingAndStaleHeartbeatAreDistinctFailClosedStates() {
        assertEquals(
            BotUiState.PAIRING_REQUIRED,
            deriveBotUiState(false, false, true, null, null, null, false),
        )
        assertEquals(
            BotUiState.EA_OFFLINE,
            deriveBotUiState(false, false, false, true, false, "READY", false),
        )
        assertEquals(
            BotUiState.TERMINAL_OFFLINE,
            deriveBotUiState(false, false, false, false, true, "READY", false),
        )
    }

    @Test fun identityMismatchAndLiveLeaseRemainBlocked() {
        assertEquals(
            BotUiState.BLOCKED,
            deriveBotUiState(false, true, false, true, true, "READY", true),
        )
        assertEquals(
            BotUiState.READY,
            deriveBotUiState(false, true, false, true, true, "READY", false),
        )
        assertEquals(
            BotUiState.RUNNING,
            deriveBotUiState(false, true, false, true, true, "RUNNING", false),
        )
    }

    @Test fun httpSuccessPairingStaleHeartbeatAndIdentityMismatchNeverConnect() {
        fun verified(
            connected: Boolean = true,
            heartbeatFresh: Boolean = true,
            actualLogin: String = "123456",
            actualServer: String = "Broker-Demo",
            actualMode: String = "DEMO",
            brokerIdentityVerified: Boolean = true,
        ) = isVerifiedMt5Connection(
            snapshotConnected = connected,
            workerReady = true,
            terminalOnline = true,
            heartbeatFresh = heartbeatFresh,
            accountVerified = true,
            brokerIdentityVerified = brokerIdentityVerified,
            actualMode = actualMode,
            expectedMode = "DEMO",
            actualLogin = actualLogin,
            expectedLogin = "123456",
            actualServer = actualServer,
            expectedServer = "Broker-Demo",
        )

        assertEquals(false, verified(connected = false)) // HTTP readiness or pairing alone
        assertEquals(false, verified(heartbeatFresh = false))
        assertEquals(false, verified(actualLogin = "999999"))
        assertEquals(false, verified(actualServer = "Broker-Live"))
        assertEquals(false, verified(actualMode = "LIVE"))
        assertEquals(false, verified(brokerIdentityVerified = false))
        assertEquals(true, verified())

        fun uiState(
            connected: Boolean,
            pairingRequired: Boolean = false,
            terminalOnline: Boolean? = true,
            heartbeatFresh: Boolean? = true,
        ) = deriveBotUiState(false, connected, pairingRequired, terminalOnline, heartbeatFresh, "READY", false)

        assertEquals(BotUiState.PAIRING_REQUIRED, uiState(connected = false, pairingRequired = true))
        assertEquals(BotUiState.EA_OFFLINE, uiState(connected = false, heartbeatFresh = false))
        assertEquals(BotUiState.TERMINAL_OFFLINE, uiState(connected = false, terminalOnline = false))
        assertEquals(false, uiState(connected = verified(connected = false)) == BotUiState.CONNECTED) // HTTP success alone
        assertEquals(false, uiState(connected = verified(actualLogin = "999999")) == BotUiState.CONNECTED)
        assertEquals(false, uiState(connected = verified(actualServer = "Broker-Live")) == BotUiState.CONNECTED)
        assertEquals(false, uiState(connected = verified(brokerIdentityVerified = false)) == BotUiState.CONNECTED)
    }

    @Test fun liveRequiresMatchingBrokerDemoPassEvidenceAndFullGateSet() {
        fun accepted(
            broker: String = "FBS",
            status: String = "PASS",
            soakDays: Int = 5,
            restartTestPass: Boolean = true,
            login: String = "123456",
            server: String = "FBS-Demo",
            evidencePathCount: Int = 1,
        ) = isValidDemoPassEvidence(
            expectedBroker = "FBS",
            expectedLogin = "123456",
            expectedServer = "FBS-Demo",
            broker = broker,
            mode = "DEMO",
            status = status,
            login = login,
            server = server,
            soakDays = soakDays,
            orderLifecyclePass = true,
            dailyReconciliationPass = true,
            restartTestPass = restartTestPass,
            riskControlsPass = true,
            evidencePathCount = evidencePathCount,
        )

        assertEquals(true, accepted())
        assertEquals(false, accepted(broker = "EXNESS"))
        assertEquals(false, accepted(status = "BLOCKED"))
        assertEquals(false, accepted(soakDays = 4))
        assertEquals(false, accepted(restartTestPass = false))
        assertEquals(false, accepted(login = "999999"))
        assertEquals(false, accepted(server = "FBS-Live"))
        assertEquals(false, accepted(evidencePathCount = 0))
    }

    @Test fun liveSelectionRequiresAcceptedBrokerSpecificDemoPassAndLeaseLabelIsExplicit() {
        assertEquals(true, canSelectMt5Mode("DEMO", "FBS", emptyMap()))
        assertEquals(false, canSelectMt5Mode("LIVE", "FBS", emptyMap()))
        assertEquals(false, canSelectMt5Mode("LIVE", "EXNESS", mapOf("FBS" to "accepted-demo-pass-sha256")))
        assertEquals(true, canSelectMt5Mode("LIVE", "FBS", mapOf("FBS" to "accepted-demo-pass-sha256")))

        assertEquals("LIVE NOT ARMED · SERVER LEASE INACTIVE", liveLeaseStatusLabel(false))
        assertEquals("LIVE ARMED · SERVER LEASE ACTIVE", liveLeaseStatusLabel(true))
    }
}
