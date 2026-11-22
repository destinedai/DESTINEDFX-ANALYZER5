package com.example

import com.example.data.trade.MT5RemoteBridgeClient
import com.example.data.codespaces.isCodespacesForwardedBridgeOrigin
import com.example.data.trade.parseVerifiedBrokerQuotes
import com.example.data.trade.parseVerifiedBrokerCandles
import com.example.security.SecretsManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MT5RemoteBridgeClientTest {
    @Test fun brokerCandleParserAcceptsOnlyClosedFreshFiniteUniqueTerminalBars() {
        val now = 1_800_000_000_000L
        val rows = JSONArray()
            .put(JSONObject().put("symbol","EURUSD.a").put("timeframe","M1").put("timestamp_ms",now-60_000L)
                .put("open",1.1).put("high",1.2).put("low",1.0).put("close",1.15).put("volume",10.0).put("complete",true))
            .put(JSONObject().put("symbol","GBPUSD").put("timeframe","H2").put("timestamp_ms",now-60_000L)
                .put("open",1.1).put("high",1.2).put("low",1.0).put("close",1.15).put("volume",10.0).put("complete",true))
            .put(JSONObject().put("symbol","USDJPY").put("timeframe","M1").put("timestamp_ms",now-60_000L)
                .put("open",150.0).put("high",149.0).put("low",148.0).put("close",150.0).put("volume",10.0).put("complete",true))
            .put(JSONObject().put("symbol","AUDUSD").put("timeframe","M1").put("timestamp_ms",now+5_000L)
                .put("open",0.6).put("high",0.61).put("low",0.59).put("close",0.605).put("volume",10.0).put("complete",true))
            .put(JSONObject().put("symbol","NZDUSD").put("timeframe","M1").put("timestamp_ms",now-60_000L)
                .put("open",0.6).put("high",0.61).put("low",0.59).put("close",0.605).put("volume",10.0).put("complete",false))
            .put(JSONObject().put("symbol","EURUSD.A").put("timeframe","M1").put("timestamp_ms",now-60_000L)
                .put("open",1.1).put("high",1.2).put("low",1.0).put("close",1.15).put("volume",10.0).put("complete",true))
        val accepted = parseVerifiedBrokerCandles(rows, now)
        assertEquals(1, accepted?.size)
        val candle = accepted!!.single()
        assertEquals("EURUSD.a", candle.symbol)
        assertEquals("M1", candle.timeframe)
        assertEquals(1.1, candle.open, 0.0000001)
        assertTrue(candle.complete)
        assertEquals("MT5_TERMINAL", candle.source)
        assertEquals(null, parseVerifiedBrokerCandles(null, now))
    }

    @Test fun brokerQuoteParserAcceptsOnlyFreshFiniteTwoSidedUniqueTerminalPrices() {
        val now = 1_800_000_000_000L
        val rows = JSONArray()
            .put(JSONObject().put("symbol", "EURUSD.a").put("bid", 1.08231).put("ask", 1.08245).put("timestamp_ms", now - 1200L))
            .put(JSONObject().put("symbol", "GBPUSD").put("bid", 1.2700).put("ask", 1.2690).put("timestamp_ms", now))
            .put(JSONObject().put("symbol", "USDJPY").put("bid", 150.0).put("ask", 150.02).put("timestamp_ms", now - 20_000L))
            .put(JSONObject().put("symbol", "AUDUSD").put("bid", 0.6500).put("ask", 0.6502).put("timestamp_ms", now + 5_000L))
            .put(JSONObject().put("symbol", "EURUSD.A").put("bid", 1.08230).put("ask", 1.08244).put("timestamp_ms", now))
        val accepted = parseVerifiedBrokerQuotes(rows, now)
        assertEquals(1, accepted?.size)
        val quote = accepted!!.single()
        assertEquals("EURUSD.a", quote.symbol)
        assertEquals(1.08231, quote.bid, 0.0000001)
        assertEquals(1.08245, quote.ask, 0.0000001)
        assertEquals(1200L, quote.ageMs)
        assertEquals(5, quote.digits)
        assertEquals("MT5_TERMINAL", quote.source)
        assertEquals(null, parseVerifiedBrokerQuotes(null, now))
    }

    @Test fun brokerQuoteParserAllowsOnlySmallClockSkewAndClampsDisplayedAge() {
        val now = 1_800_000_000_000L
        val rows = JSONArray()
            .put(JSONObject().put("symbol", "EURUSD").put("bid", 1.1).put("ask", 1.1002).put("digits", 5).put("timestamp_ms", now + 1000L))
            .put(JSONObject().put("symbol", "GBPUSD").put("bid", 1.2).put("ask", 1.2002).put("digits", 5).put("timestamp_ms", now + 5000L))
        val accepted = parseVerifiedBrokerQuotes(rows, now)
        assertEquals(1, accepted?.size)
        assertEquals(0L, accepted!!.single().ageMs)
        assertEquals("EURUSD", accepted.single().symbol)
    }

    @Test fun unavailableEncryptedStorageBlocksManagedRegistrationBeforeNetwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val secrets = SecretsManager(context) { throw IllegalStateException("simulated keystore failure") }
        val client = MT5RemoteBridgeClient(secrets, "FBS_DEMO", accountMode = "DEMO", brokerBrand = "FBS")
        val failure = runCatching {
            client.registerManagedAccount(
                login = "12345", password = "test-password", server = "Broker-Demo",
                accessType = "MASTER", consent = true
            )
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Secure storage is unavailable"))
    }


    @Test fun managementIdentityHeaderIsLimitedToSignedProfileLifecycleRequests() {
        val validAssertion = "header.payload.signature_" + "x".repeat(120)
        val builder = Request.Builder().url("https://bridge.example.com")
        MT5RemoteBridgeClient.addManagementIdentityHeader(
            builder, "POST", "/v1/pairings", validAssertion
        )
        assertEquals(validAssertion, builder.build().header("X-Management-Identity"))
        val loginBuilder = Request.Builder().url("https://bridge.example.com")
        MT5RemoteBridgeClient.addManagementIdentityHeader(loginBuilder, "POST", "/v1/app/login", validAssertion)
        assertEquals(validAssertion, loginBuilder.build().header("X-Management-Identity"))
        val bootstrapBuilder = Request.Builder().url("https://bridge.example.com")
        MT5RemoteBridgeClient.addManagementIdentityHeader(
            bootstrapBuilder, "POST", "/v1/owner/bootstrap-code", validAssertion
        )
        assertEquals(validAssertion, bootstrapBuilder.build().header("X-Management-Identity"))
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.addManagementIdentityHeader(
                Request.Builder().url("https://bridge.example.com"),
                "GET", "/v1/app/state", validAssertion
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.addManagementIdentityHeader(
                Request.Builder().url("https://bridge.example.com"),
                "POST", "/v1/app/demo-acceptance", "not-a-signed-assertion"
            )
        }
    }

    @Test fun allFourRequestedBrokerBrandsAreValidForTheirMT5AccountRoute() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val secrets = SecretsManager(context) { throw IllegalStateException("simulated keystore failure") }
        listOf("FBS", "EXNESS", "DERIV", "HFM").forEach { brand ->
            val client = MT5RemoteBridgeClient(
                secrets, "${brand}_DEMO", { true }, accountMode = "DEMO", brokerBrand = brand
            )
            assertEquals(brand, client.brokerBrand)
            assertEquals("DEMO", client.accountMode)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient(secrets, "UNKNOWN_DEMO", { true }, accountMode = "DEMO", brokerBrand = "UNKNOWN")
        }
    }

    @Test fun brokerQualifiedProfileSeparatesStorageNamespaceFromVerifiedAccountMode() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val secrets = SecretsManager(context) { throw IllegalStateException("simulated keystore failure") }
        val client = MT5RemoteBridgeClient(
            secrets,
            "EXNESS_DEMO",
            { true },
            accountMode = "DEMO",
            brokerBrand = "EXNESS"
        )
        assertEquals("EXNESS_DEMO", client.profile)
        assertEquals("DEMO", client.accountMode)
        assertEquals("EXNESS", client.brokerBrand)

        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient(secrets, "EXNESS_DEMO", { true }, accountMode = "PAPER", brokerBrand = "EXNESS")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient(secrets, "DERIV_DEMO", { true }, accountMode = "DEMO", brokerBrand = "UNKNOWN")
        }
    }

    @Test fun allEightExplicitBrokerModeSlotsRemainIndependentAndAreRequired() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val secrets = SecretsManager(context) { throw IllegalStateException("simulated keystore failure") }
        listOf("FBS", "EXNESS", "HFM", "DERIV").forEach { brand ->
            listOf("DEMO", "LIVE").forEach { mode ->
                val slot = "${brand}_${mode}"
                val client = MT5RemoteBridgeClient(secrets, slot, accountMode = mode, brokerBrand = brand)
                assertEquals(slot, client.profile)
                assertEquals(brand, client.brokerBrand)
                assertEquals(mode, client.accountMode)
                assertThrows(IllegalArgumentException::class.java) {
                    MT5RemoteBridgeClient(secrets, slot, accountMode = if (mode == "DEMO") "LIVE" else "DEMO", brokerBrand = brand)
                }
            }
        }
    }
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

    @Test fun ownerBootstrapHeaderIsRestrictedToInitialPairingRequest() {
        val pairing = Request.Builder().url("https://bridge.realcop.com/v1/pairings")
        MT5RemoteBridgeClient.addOwnerBootstrapHeader(
            pairing, "POST", "/v1/pairings", "one-time-owner-code"
        )
        assertEquals("one-time-owner-code", pairing.build().header("X-Owner-Bootstrap"))

        val normal = Request.Builder().url("https://bridge.realcop.com/v1/app/state")
        MT5RemoteBridgeClient.addOwnerBootstrapHeader(
            normal, "GET", "/v1/app/state", null
        )
        assertEquals(null, normal.build().header("X-Owner-Bootstrap"))

        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.addOwnerBootstrapHeader(
                Request.Builder().url("https://bridge.realcop.com/v1/app/state"),
                "GET", "/v1/app/state", "one-time-owner-code"
            )
        }
    }

    private fun snapshot(orders: List<JSONObject>) = MT5RemoteBridgeClient.Snapshot(
        managedState = "READY", accessType = "MASTER", profileMode = "DEMO", connected = true, terminalOnline = true,
        heartbeatFresh = true, state = "READY", account = "123 @ Broker-Demo", actualLogin = "123", actualServer = "Broker-Demo",
        actualAccountMode = "DEMO", liveAuthorized = false, tradeAllowed = true, terminalConnected = true, accountVerified = true, brokerIdentityVerified = true,
        currency = "USD", balance = 1_000.0, equity = 1_000.0, margin = 0.0, freeMargin = 1_000.0, marginLevel = null, leverage = 100,
        positions = emptyList(), activeOrders = orders, closedDeals = emptyList(), snapshotsValid = true,
        lastSignal = null, newsVerdict = null, raw = JSONObject(), actualCompany = "FBS Markets Inc.", connectionWarning = ""
    )



    @Test fun managedPositionActionsRejectZeroBalanceAndIdentityMismatchSnapshots() {
        val valid = snapshot(emptyList())
        assertTrue(MT5RemoteBridgeClient.canManageSnapshot("DEMO", "123", "Broker-Demo", valid))
        assertFalse(MT5RemoteBridgeClient.canManageSnapshot("DEMO", "123", "Broker-Demo", valid.copy(balance = 0.0, equity = 0.0)))
        assertFalse(MT5RemoteBridgeClient.canManageSnapshot("DEMO", "999", "Broker-Demo", valid))
        assertFalse(MT5RemoteBridgeClient.canManageSnapshot("DEMO", "123", "Other-Server", valid))
        assertFalse(MT5RemoteBridgeClient.canManageSnapshot("DEMO", "123", "Broker-Demo", valid.copy(actualCompany = "")))
        assertFalse(MT5RemoteBridgeClient.canManageSnapshot("DEMO", "123", "Broker-Demo", valid.copy(brokerIdentityVerified = false)))
    }

    @Test fun phoneMarketOrderConfigIsBrokerQuoteBoundAndIncludesRequestedVolume() {
        val snapshot = phoneMarketOrderSnapshot()
        val config = MT5RemoteBridgeClient.marketOrderConfig(
            snapshot, side = "BUY", stopLoss = 1.09000, takeProfit = 1.12000, riskPercent = 0.25,
            requestedVolume = 0.01
        )
        assertEquals("123", config.getString("account_login"))
        assertEquals("Broker-Demo", config.getString("account_server"))
        assertEquals("DEMO", config.getString("account_mode"))
        assertEquals("EURUSD", config.getString("symbol"))
        assertEquals("BUY", config.getString("side"))
        assertEquals(1.10000, config.getDouble("expected_bid"), 0.0000001)
        assertEquals(1.10020, config.getDouble("expected_ask"), 0.0000001)
        assertEquals(0.01, config.getDouble("requested_volume"), 0.0000001)
        assertFalse(config.has("volume"))

        val requestId = "2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18"
        val payload = MT5RemoteBridgeClient.commandPayload(requestId, "PLACE_MARKET_ORDER", config)
        assertEquals("PLACE_MARKET_ORDER", payload.getString("action"))
        assertEquals(requestId, payload.getString("request_id"))
        assertEquals("EURUSD", payload.getJSONObject("config").getString("symbol"))
        assertFalse(payload.getJSONObject("config").has("tickets"))
    }

    @Test fun phoneMarketOrderConfigRejectsRiskAboveEaCapAndInvalidProtection() {
        val snapshot = phoneMarketOrderSnapshot()
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.marketOrderConfig(
                snapshot, side = "BUY", stopLoss = 1.09000, takeProfit = 1.12000, riskPercent = 0.30,
                requestedVolume = 0.01
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.marketOrderConfig(
                snapshot, side = "BUY", stopLoss = 1.10100, takeProfit = 1.12000, riskPercent = 0.20,
                requestedVolume = 0.01
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.marketOrderConfig(
                snapshot.copy(hostProfile = com.example.data.broker.HostProfile.EPHEMERAL),
                side = "BUY", stopLoss = 1.09000, takeProfit = 1.12000, riskPercent = 0.20,
                requestedVolume = 0.01
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.marketOrderConfig(
                snapshot, side = "BUY", stopLoss = 1.09000, takeProfit = 1.12000, riskPercent = 0.20,
                requestedVolume = 0.03
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.marketOrderConfig(
                snapshot, side = "BUY", stopLoss = 1.09000, takeProfit = 1.12000, riskPercent = 0.20,
                requestedVolume = 0.009
            )
        }
    }

    private fun phoneMarketOrderSnapshot(): MT5RemoteBridgeClient.Snapshot {
        val base = snapshot(emptyList())
        val now = System.currentTimeMillis()
        val raw = JSONObject()
            .put("configuration", JSONObject()
                .put("symbols", JSONArray().put("EURUSD"))
                .put("effective_max_volume", 0.02))
            .put("risk_controls", JSONObject()
                .put("risk_percent", 0.25)
                .put("max_concurrent_positions", 2)
                .put("authorized", true)
                .put("daily_loss_clear", true)
                .put("risk_caps_clear", true))
        return base.copy(
            hostProfile = com.example.data.broker.HostProfile.PERSISTENT,
            newsVerdict = JSONObject().put("status", "CLEAR"),
            raw = raw,
            brokerQuotes = listOf(com.example.data.trade.VerifiedBrokerQuote(
                symbol = "EURUSD", bid = 1.10000, ask = 1.10020, digits = 5,
                timestampMs = now, ageMs = 0L
            ))
        )
    }

    @Test fun serverReturnedTradeTargetListsMustBeCanonicalAndUnique() {
        assertEquals(
            listOf("1", "42"),
            MT5RemoteBridgeClient.exactIdentifierList(JSONArray().put("1").put("42"))
        )
        assertEquals(null, MT5RemoteBridgeClient.exactIdentifierList(JSONArray()))
        assertEquals(null, MT5RemoteBridgeClient.exactIdentifierList(JSONArray().put("1").put("1")))
        assertEquals(null, MT5RemoteBridgeClient.exactIdentifierList(JSONArray().put(1)))
        assertEquals(null, MT5RemoteBridgeClient.exactIdentifierList(JSONArray().put("0")))
    }

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

    @Test fun buildsRequireExplicitBridgeConfigurationAndSavedUrlOverridesIt() {
        assertEquals("__CONFIGURATION_REQUIRED__", BuildConfig.MT5_MANAGED_SERVICE_URL)
        assertEquals("", MT5RemoteBridgeClient.initialServiceUrl("", BuildConfig.MT5_MANAGED_SERVICE_URL))
        assertEquals("https://saved.example.org", MT5RemoteBridgeClient.initialServiceUrl(
            "https://saved.example.org", BuildConfig.MT5_MANAGED_SERVICE_URL
        ))
        assertEquals("", MT5RemoteBridgeClient.initialServiceUrl("", "__CONFIGURATION_REQUIRED__"))
        assertEquals("", MT5RemoteBridgeClient.resolveServiceOrigin("", BuildConfig.MT5_MANAGED_SERVICE_URL))
        assertEquals("https://new-tunnel.trycloudflare.com", MT5RemoteBridgeClient.resolveServiceOrigin(
            "https://new-tunnel.trycloudflare.com", BuildConfig.MT5_MANAGED_SERVICE_URL
        ))
    }

    @Test fun runtimeBridgeUrlOverridesBuildDefaultOnlyWhenItIsValid() {
        assertEquals("https://bridge.realcop.com", MT5RemoteBridgeClient.resolveServiceOrigin(
            "https://bridge.realcop.com", "__CONFIGURATION_REQUIRED__"))
        assertEquals("https://embedded.realcop.com", MT5RemoteBridgeClient.resolveServiceOrigin(
            "http://bridge.realcop.com", "https://embedded.realcop.com"))
        assertEquals("", MT5RemoteBridgeClient.resolveServiceOrigin("", "__CONFIGURATION_REQUIRED__"))
    }


    @Test fun lockedBuildUsesOnlyEmbeddedHostOrStrictCodespacesForwardedBridgeOrigin() {
        assertEquals("https://embedded.realcop.com", MT5RemoteBridgeClient.resolveServiceOrigin(
            "https://attacker.realcop.com", "https://embedded.realcop.com", locked = true
        ))
        assertEquals("", MT5RemoteBridgeClient.resolveServiceOrigin(
            "https://old.realcop.com", "__CONFIGURATION_REQUIRED__", locked = true
        ))
        assertEquals("https://embedded.realcop.com", MT5RemoteBridgeClient.initialServiceUrl(
            "https://old.realcop.com", "https://embedded.realcop.com", locked = true
        ))

        val codespaces = "https://destinedfx-host-abc123-8000.app.github.dev"
        assertTrue(isCodespacesForwardedBridgeOrigin(codespaces))
        assertEquals("", MT5RemoteBridgeClient.resolveServiceOrigin(
            codespaces, "__CONFIGURATION_REQUIRED__", locked = true
        ))
        assertEquals("", MT5RemoteBridgeClient.initialServiceUrl(
            codespaces, "__CONFIGURATION_REQUIRED__", locked = true
        ))
        assertEquals(codespaces, MT5RemoteBridgeClient.resolveServiceOrigin(
            codespaces, "__CONFIGURATION_REQUIRED__", locked = true, allowCodespacesDiscovery = true
        ))
        assertEquals(codespaces, MT5RemoteBridgeClient.initialServiceUrl(
            codespaces, "__CONFIGURATION_REQUIRED__", locked = true, allowCodespacesDiscovery = true
        ))
        assertEquals(
            "https://destinedfx-host-abc123-8000.preview.app.github.dev",
            MT5RemoteBridgeClient.resolveServiceOrigin(
                "https://destinedfx-host-abc123-8000.preview.app.github.dev",
                "__CONFIGURATION_REQUIRED__", locked = true, allowCodespacesDiscovery = true
            )
        )
        assertEquals(
            "https://destinedfx-host-abc123-8000.githubpreview.dev",
            MT5RemoteBridgeClient.resolveServiceOrigin(
                "https://destinedfx-host-abc123-8000.githubpreview.dev",
                "__CONFIGURATION_REQUIRED__", locked = true, allowCodespacesDiscovery = true
            )
        )
        assertEquals("", MT5RemoteBridgeClient.resolveServiceOrigin(
            "https://attacker-8000.github.com", "__CONFIGURATION_REQUIRED__", locked = true
        ))
        assertEquals("", MT5RemoteBridgeClient.resolveServiceOrigin(
            "https://destinedfx-host-abc123-8000.app.github.dev/path",
            "__CONFIGURATION_REQUIRED__", locked = true
        ))
        assertEquals("", MT5RemoteBridgeClient.resolveServiceOrigin(
            "http://destinedfx-host-abc123-8000.app.github.dev",
            "__CONFIGURATION_REQUIRED__", locked = true
        ))
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

    @Test fun phonePositionStopEditAllowsOnlyMonotonicTighteningAndKeepsTpOutOfPayload() {
        val base = phoneMarketOrderSnapshot()
        val position = JSONObject()
            .put("ticket", "920001")
            .put("position_id", "920001")
            .put("symbol", "EURUSD")
            .put("direction", "BUY")
            .put("stop_loss", 1.09000)
            .put("take_profit", 1.13000)
        val snapshot = base.copy(
            raw = JSONObject()
                .put("configuration", JSONObject().put("symbols", JSONArray().put("EURUSD")))
                .put("risk_controls", base.raw.optJSONObject("risk_controls"))
                .put("open_positions", JSONArray().put(position))
        )
        val config = MT5RemoteBridgeClient.positionStopTightenConfig(snapshot, "920001", 1.09500)
        assertEquals("920001", config.getString("ticket"))
        assertEquals(1.09000, config.getDouble("expected_stop_loss"), 0.0000001)
        assertEquals(1.09500, config.getDouble("new_stop_loss"), 0.0000001)
        assertFalse(config.has("take_profit"))
        val payload = MT5RemoteBridgeClient.commandPayload(
            "2e5bd51d-34a5-46e6-bffb-0fa5b3a4df18", "TIGHTEN_POSITION_SL", config
        )
        assertEquals("TIGHTEN_POSITION_SL", payload.getString("action"))

        val sellPosition = JSONObject()
            .put("ticket", "920002")
            .put("position_id", "920002")
            .put("symbol", "EURUSD")
            .put("direction", "SELL")
            .put("stop_loss", 1.11000)
            .put("take_profit", 1.07000)
        val sellSnapshot = snapshot.copy(raw = JSONObject()
            .put("configuration", JSONObject().put("symbols", JSONArray().put("EURUSD")))
            .put("risk_controls", base.raw.optJSONObject("risk_controls"))
            .put("open_positions", JSONArray().put(sellPosition)))
        val sellConfig = MT5RemoteBridgeClient.positionStopTightenConfig(sellSnapshot, "920002", 1.10500)
        assertEquals("SELL", sellConfig.getString("direction"))
        assertEquals(1.10500, sellConfig.getDouble("new_stop_loss"), 0.0000001)
    }

    @Test fun phonePositionStopEditRejectsLooseningWrongSideAndUnknownTicket() {
        val base = phoneMarketOrderSnapshot()
        val position = JSONObject()
            .put("ticket", "920001")
            .put("position_id", "920001")
            .put("symbol", "EURUSD")
            .put("direction", "BUY")
            .put("stop_loss", 1.09000)
            .put("take_profit", 1.13000)
        val snapshot = base.copy(
            raw = JSONObject()
                .put("configuration", JSONObject().put("symbols", JSONArray().put("EURUSD")))
                .put("risk_controls", base.raw.optJSONObject("risk_controls"))
                .put("open_positions", JSONArray().put(position))
        )
        assertThrows(IllegalArgumentException::class.java) {
            MT5RemoteBridgeClient.positionStopTightenConfig(snapshot, "920001", 1.08500)
        }
        assertThrows(IllegalStateException::class.java) {
            MT5RemoteBridgeClient.positionStopTightenConfig(snapshot, "920099", 1.09500)
        }
    }
}
