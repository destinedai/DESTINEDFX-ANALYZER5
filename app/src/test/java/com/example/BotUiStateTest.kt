package com.example

import com.example.ui.BotUiState
import com.example.ui.deriveBotUiState
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
}
