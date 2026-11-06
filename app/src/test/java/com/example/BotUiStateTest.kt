package com.example

import com.example.ui.BotUiState
import com.example.ui.deriveBotUiState
import com.example.ui.isVerifiedMt5Connection
import com.example.ui.isValidDemoPassEvidence
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
        ) = isVerifiedMt5Connection(
            snapshotConnected = connected,
            workerReady = true,
            terminalOnline = true,
            heartbeatFresh = heartbeatFresh,
            accountVerified = true,
            brokerIdentityVerified = true,
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
        assertEquals(true, verified())
    }

    @Test fun liveRequiresMatchingBrokerDemoPassEvidenceAndFullGateSet() {
        fun accepted(
            broker: String = "FBS",
            status: String = "PASS",
            soakDays: Int = 5,
            restartTestPass: Boolean = true,
        ) = isValidDemoPassEvidence(
            expectedBroker = "FBS",
            expectedLogin = "123456",
            expectedServer = "FBS-Demo",
            broker = broker,
            mode = "DEMO",
            status = status,
            login = "123456",
            server = "FBS-Demo",
            soakDays = soakDays,
            orderLifecyclePass = true,
            dailyReconciliationPass = true,
            restartTestPass = restartTestPass,
            riskControlsPass = true,
            evidencePathCount = 1,
        )

        assertEquals(true, accepted())
        assertEquals(false, accepted(broker = "EXNESS"))
        assertEquals(false, accepted(status = "BLOCKED"))
        assertEquals(false, accepted(soakDays = 4))
        assertEquals(false, accepted(restartTestPass = false))
    }
}
