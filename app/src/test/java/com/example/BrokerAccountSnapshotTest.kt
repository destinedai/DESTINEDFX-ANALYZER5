package com.example

import com.example.data.broker.BrokerAccountSnapshot
import com.example.data.broker.BrokerConnectionStatus
import com.example.data.broker.DataOrigin
import com.example.data.broker.DataValidity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerAccountSnapshotTest {
    private fun freshSnapshot(retrievedAtMs: Long = 1_000L) = BrokerAccountSnapshot(
        providerId = "DERIV",
        origin = DataOrigin.BROKER_API,
        status = BrokerConnectionStatus.CONNECTED,
        retrievedAtMs = retrievedAtMs,
        environment = "demo",
        currency = "USD",
        balance = 100.0,
        equity = 100.0,
        margin = 0.0,
        freeMargin = 100.0,
        validity = DataValidity.FRESH
    )

    @Test
    fun freshBrokerSnapshotIsDisplayable() {
        assertTrue(freshSnapshot().isDisplayable(nowMs = 5_000L, maxAgeMs = 5_000L))
    }

    @Test
    fun cachedOrDisconnectedSnapshotIsNotDisplayable() {
        assertFalse(freshSnapshot().copy(validity = DataValidity.CACHED).isDisplayable(nowMs = 2_000L))
        assertFalse(freshSnapshot().copy(status = BrokerConnectionStatus.DISCONNECTED).isDisplayable(nowMs = 2_000L))
        assertFalse(freshSnapshot().copy(origin = DataOrigin.LOCAL_DEMO_DATA).isDisplayable(nowMs = 2_000L))
    }

    @Test
    fun staleAndMissingValuesRemainUnavailable() {
        assertFalse(freshSnapshot(retrievedAtMs = 1_000L).isDisplayable(nowMs = 20_001L, maxAgeMs = 15_000L))
        assertFalse(freshSnapshot().copy(balance = null).isDisplayable(nowMs = 2_000L))
        assertFalse(freshSnapshot().copy(currency = "").isDisplayable(nowMs = 2_000L))
    }

    @Test
    fun unavailableFactoryDoesNotInventAccountValues() {
        val snapshot = BrokerAccountSnapshot.unavailable("MT5", BrokerConnectionStatus.ERROR, "NOT_CONNECTED")
        assertTrue(snapshot.origin == DataOrigin.UNAVAILABLE)
        assertTrue(snapshot.validity == DataValidity.UNAVAILABLE)
        assertTrue(snapshot.balance == null)
        assertTrue(snapshot.equity == null)
        assertFalse(snapshot.isDisplayable())
    }
}
