package com.example

import com.example.data.trade.MT5RemoteBridgeClient
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MT5RemoteBridgeClientTest {
    @Test fun riskMustBeFiniteAndWithinTheExactSupportedInterval() {
        assertTrue(MT5RemoteBridgeClient.isValidRiskPercent(0.25))
        assertTrue(MT5RemoteBridgeClient.isValidRiskPercent(1.0))
        assertFalse(MT5RemoteBridgeClient.isValidRiskPercent(0.0))
        assertFalse(MT5RemoteBridgeClient.isValidRiskPercent(-0.1))
        assertFalse(MT5RemoteBridgeClient.isValidRiskPercent(1.01))
        assertFalse(MT5RemoteBridgeClient.isValidRiskPercent(Double.NaN))
        assertFalse(MT5RemoteBridgeClient.isValidRiskPercent(Double.POSITIVE_INFINITY))
    }

    @Test fun commandPayloadMatchesProtocolAndCarriesStableRequestId() {
        val id = "2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18"
        val config = JSONObject().put("strategy", "ict-silver-bullet").put("risk_percent", 0.25)
        val request = MT5RemoteBridgeClient.commandPayload(id, "START", config)
        assertEquals(id, request.getString("request_id"))
        assertEquals("START", request.getString("action"))
        assertEquals("ict-silver-bullet", request.getJSONObject("config").getString("strategy"))
        assertEquals(0.25, request.getJSONObject("config").getDouble("risk_percent"), 0.0)
    }

    @Test fun commandPayloadRejectsUnknownActionAndNonUuid() {
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.commandPayload("2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18", "CLOSE_ALL")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.commandPayload("not-a-uuid", "STOP")
        }
    }

    @Test fun payloadPreservesCallerGeneratedRequestIdExactly() {
        val id = "2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18"
        assertEquals(id, MT5RemoteBridgeClient.commandPayload(id, "STOP").getString("request_id"))
    }

    @Test fun closeAllUsesOneUseConfirmationAndCanonicalStringTickets() {
        val ticket = "18446744073709551615"
        val config = JSONObject().put("account_login", "123456").put("account_server", "Broker-Demo")
            .put("account_mode", "DEMO").put("tickets", JSONArray().put(ticket)).put("confirmation", "one-use-token")
        val payload = MT5RemoteBridgeClient.commandPayload("2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18", "CLOSE_ALL", config)
        assertEquals("CLOSE_ALL", payload.getString("action"))
        assertEquals(ticket, payload.getJSONObject("config").getJSONArray("tickets").getString(0))
        assertEquals("one-use-token", payload.getJSONObject("config").getString("confirmation"))
        assertFalse(payload.getJSONObject("config").has("confirmation_token"))
    }

    @Test fun selectedOrderAndPositionActionsUseAgreedActionsAndTicketsArray() {
        val id = "2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18"
        val config = JSONObject().put("account_login", "123").put("account_server", "Broker-Demo")
            .put("account_mode", "DEMO").put("tickets", JSONArray().put("9007199254740993"))
        listOf("CANCEL_PENDING_SELECTED", "CLOSE_POSITION_SELECTED").forEach { action ->
            val payload = MT5RemoteBridgeClient.commandPayload(id, action, config)
            assertEquals(action, payload.getString("action"))
            assertEquals("9007199254740993", payload.getJSONObject("config").getJSONArray("tickets").getString(0))
        }
        val bulk = MT5RemoteBridgeClient.commandPayload(id, "CANCEL_EA_PENDING", config = JSONObject()
            .put("account_login", "123").put("account_server", "Broker-Demo").put("account_mode", "DEMO"))
        assertFalse(bulk.getJSONObject("config").has("tickets"))
    }

    @Test fun identifiersAreLosslessAndOnlyCanonicalPositiveUint64ValuesAreAccepted() {
        assertEquals("9007199254740993", MT5RemoteBridgeClient.canonicalTicket("9007199254740993"))
        assertEquals("18446744073709551615", MT5RemoteBridgeClient.canonicalTicket("18446744073709551615"))
        assertEquals("9007199254740993", MT5RemoteBridgeClient.exactIdentifier(JSONObject().put("ticket", 9007199254740993L), "ticket"))
        assertEquals(null, MT5RemoteBridgeClient.canonicalTicket(9.007199254740993E15))
        assertEquals(null, MT5RemoteBridgeClient.canonicalTicket("018"))
        assertEquals(null, MT5RemoteBridgeClient.canonicalTicket("18446744073709551616"))
        assertEquals(null, MT5RemoteBridgeClient.canonicalTicket("0"))
    }

    @Test fun commandPayloadRejectsNumericTicketValuesInsteadOfSerializingLossyIdentifiers() {
        val config = JSONObject().put("account_login", "123").put("account_server", "Broker-Demo")
            .put("account_mode", "DEMO").put("tickets", JSONArray().put(123456789L))
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.commandPayload("2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18", "CANCEL_PENDING_SELECTED", config)
        }
    }

    @Test fun cancelEaPendingRequiresCompleteOwnershipMetadataAndTargetsOnlyEaOrders() {
        val orders = listOf(
            JSONObject().put("ticket", "1001").put("managed_by_ea", true),
            JSONObject().put("ticket", "1002").put("managed_by_ea", false)
        )
        assertEquals(listOf("1001"), MT5RemoteBridgeClient.managedOrderTicketList(snapshot(orders)))
        assertEquals(null, MT5RemoteBridgeClient.managedOrderTicketList(snapshot(listOf(JSONObject().put("ticket", "1001")))))
        assertEquals(null, MT5RemoteBridgeClient.managedOrderTicketList(snapshot(listOf(JSONObject().put("ticket", "bad").put("managed_by_ea", true)))))
    }

    @Test fun positionActionsUseStablePositionIdRatherThanPositionTicket() {
        val positionId = "9007199254740993"
        val open = snapshot(emptyList()).copy(positions = listOf(
            JSONObject().put("ticket", "9007199254740999").put("position_id", positionId)
        ))
        assertEquals(listOf(positionId), MT5RemoteBridgeClient.positionIdList(open))
        assertFalse(MT5RemoteBridgeClient.effectConfirmed("CLOSE_POSITION_SELECTED", open, listOf(positionId)))
        val closed = open.copy(positions = emptyList())
        assertTrue(MT5RemoteBridgeClient.effectConfirmed("CLOSE_POSITION_SELECTED", closed, listOf(positionId)))
    }

    private fun snapshot(orders: List<JSONObject>) = MT5RemoteBridgeClient.Snapshot(
        managedState = "READY", accessType = "MASTER", profileMode = "DEMO", connected = true, terminalOnline = true,
        heartbeatFresh = true, state = "READY", account = "123 @ Broker-Demo", actualLogin = "123", actualServer = "Broker-Demo",
        actualAccountMode = "DEMO", liveAuthorized = false, tradeAllowed = true, terminalConnected = true, accountVerified = true,
        currency = "USD", balance = null, equity = null, margin = null, freeMargin = null, marginLevel = null, leverage = null,
        positions = emptyList(), activeOrders = orders, closedDeals = emptyList(), snapshotsValid = true,
        lastSignal = null, newsVerdict = null, raw = JSONObject()
    )

    @Test fun recentClosedDealNetIncludesFeesAndRejectsUnknownFeeValues() {
        val now = Instant.parse("2026-10-29T12:00:00Z").toEpochMilli()
        val complete = JSONObject()
            .put("closed_at", "2026-10-29T11:00:00.123456Z")
            .put("profit", 10.0).put("commission", -2.0).put("swap", -1.0).put("fees", -0.5)
        assertEquals(6.5, MT5RemoteBridgeClient.recentClosedDealNetPnl(listOf(complete), now)!!, 0.0)

        val unknownFee = JSONObject()
            .put("closed_at", "2026-10-29T11:00:00Z")
            .put("profit", 10.0).put("commission", -2.0).put("swap", -1.0)
        assertEquals(null, MT5RemoteBridgeClient.recentClosedDealNetPnl(listOf(unknownFee), now))
        unknownFee.put("fees", JSONObject.NULL)
        assertEquals(null, MT5RemoteBridgeClient.recentClosedDealNetPnl(listOf(unknownFee), now))
    }

    @Test fun recentClosedDealNetRejectsInvalidTimestampsAndIgnoresOldDeals() {
        val now = Instant.parse("2026-10-29T12:00:00Z").toEpochMilli()
        val old = JSONObject().put("closed_at", "2026-10-20T11:00:00Z").put("profit", 10.0)
        assertEquals(0.0, MT5RemoteBridgeClient.recentClosedDealNetPnl(listOf(old), now)!!, 0.0)
        val invalidTime = JSONObject().put("closed_at", "unknown").put("profit", 1.0)
        assertEquals(null, MT5RemoteBridgeClient.recentClosedDealNetPnl(listOf(invalidTime), now))
    }

    @Test fun runtimeBridgeUrlOverridesBuildDefaultOnlyWhenItIsValid() {
        assertEquals("https://bridge.realcop.com", MT5RemoteBridgeClient.resolveServiceOrigin(
            "https://bridge.realcop.com", "__CONFIGURATION_REQUIRED__"))
        assertEquals("https://embedded.realcop.com", MT5RemoteBridgeClient.resolveServiceOrigin(
            "http://bridge.realcop.com", "https://embedded.realcop.com"))
        assertEquals("", MT5RemoteBridgeClient.resolveServiceOrigin("", "__CONFIGURATION_REQUIRED__"))
    }

    @Test fun serviceOriginValidationRejectsUnsafeOrigins() {
        listOf(
            "https://bridge.realcop.com:0",
            "https://bridge.realcop.com:99999",
            "https://10.0.0.4",
            "https://100.64.0.1",
            "https://192.0.2.1",
            "https://198.18.0.1",
            "https://203.0.113.1",
            "https://[::1]",
            "https://[::ffff:10.0.0.1]",
            "https://[fe80::1%25eth0]",
            "https://[2001:db8::1]",
            "https://user:pass@bridge.realcop.com",
            "https://bridge.realcop.com/path",
            "https://bridge.realcop.com/?query=1",
            "https://bridge.example"
        ).forEach { assertEquals(null, MT5RemoteBridgeClient.validServiceOrigin(it)) }
        assertEquals("https://bridge.realcop.com:443", MT5RemoteBridgeClient.validServiceOrigin(" HTTPS://Bridge.RealCop.com:443/ "))
    }
}
