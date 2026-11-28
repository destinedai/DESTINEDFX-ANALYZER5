package com.example.ui.bot

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.broker.HostProfile
import com.example.security.SecretsManager
import com.example.ui.theme.DestinedFXTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI coverage uses local fixture callbacks only; it does not connect to a broker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MetaTraderBrokerFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun openFlow(
        storageAvailable: Boolean = true,
        onConnect: suspend (
            com.example.data.broker.BrokerLoginSpec,
            LoginForm,
            (ConnectStage) -> Unit,
        ) -> ConnectOutcome = { _, _, _ -> ConnectOutcome.Failed("FIXTURE", "Fixture only") },
        onAccountCommand: suspend (
            com.example.data.broker.BrokerLoginSpec,
            LoginForm,
            String,
            String?,
        ) -> String = { _, _, action, _ -> "Fixture action: $action" },
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("broker_flow_ui_fixture", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        // Injected fixture preferences are test-only; production continues using EncryptedSharedPreferences.
        val secrets = if (storageAvailable) {
            SecretsManager(context) { preferences }
        } else {
            SecretsManager(context) { throw IllegalStateException("simulated secure-storage failure") }
        }
        composeRule.setContent {
            DestinedFXTheme {
                MetaTraderBrokerFlow(
                    secrets = secrets,
                    context = context,
                    hostProfile = HostProfile.EPHEMERAL,
                    onGrantAccess = { _, _ -> "WAITING FOR SPOTWARE APPROVAL" },
                    onConnect = onConnect,
                    onSearchServers = { spec, mode, query ->
                        val brand = spec.id.removeSuffix("_MT5")
                        val exact = "$brand-${if (mode == com.example.data.broker.LoginMode.DEMO) "Demo" else "Real"}"
                        listOf(exact).filter { it.contains(query.trim(), ignoreCase = true) }
                    },
                    onRefreshAccount = { _, _ -> null },
                    onAccountCommand = onAccountCommand,
                    onEmergencyStop = { _, _ -> "Fixture emergency stop requested" },
                    onAcceptDemo = { _, _ -> "Fixture DEMO acceptance recorded" },
                    onArmLive = { _, _, _, _ -> "Fixture LIVE arming blocked" },
                    onOpenAdvanced = {},
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun realLoginUsesRealCatalogServerAndStartRequiresRealMoneyConfirmation() {
        var submittedForm: LoginForm? = null
        var startCalls = 0
        openFlow(
            onConnect = { _, form, _ ->
                submittedForm = form
                ConnectOutcome.Verified(
                    accountMode = "LIVE",
                    modeMismatch = false,
                    account = VerifiedAccountDetails(
                        login = "123456",
                        server = "FBS-Real",
                        mode = "LIVE",
                        currency = "USD",
                        balance = 1000.0,
                        equity = 1000.0,
                        freeMargin = 1000.0,
                        marginLevel = 0.0,
                        leverage = 100,
                        retrievedAtMs = System.currentTimeMillis(),
                        positions = emptyList(),
                        pendingOrders = emptyList(),
                        deals = emptyList(),
                        hostProfile = HostProfile.EPHEMERAL,
                        engineState = "STOPPED",
                    ),
                )
            },
            onAccountCommand = { _, _, action, _ ->
                if (action == "START") startCalls += 1
                "Fixture action: $action"
            },
        )
        composeRule.onNodeWithTag("add_account").performClick()
        composeRule.onNodeWithTag("broker_FBS_MT5").performClick()
        composeRule.onNodeWithTag("login_mode_real").performScrollTo().performClick()
        composeRule.onNodeWithTag("field_login").assertIsEnabled().performScrollTo().performTextInput("123456")
        composeRule.onNodeWithTag("field_password").performScrollTo().performTextInput("fixture-password")
        composeRule.onNodeWithTag("server_field").performScrollTo().performTextReplacement("FBS-Real")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("server_result_FBS-Real").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("server_result_FBS-Real").performClick()
        composeRule.onNodeWithTag("save_mt5_server").performScrollTo().performClick()
        composeRule.onNodeWithText("Server saved").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("login_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("account_tab_bot").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("LIVE", submittedForm?.mode?.name)
        assertEquals("FBS-Real", submittedForm?.server)
        assertEquals(AccessType.TRADE, submittedForm?.type)

        composeRule.onNodeWithTag("account_tab_bot").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("account_start").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("account_start").assertIsEnabled().performClick()
        composeRule.onNodeWithText("You are about to trade REAL money").assertIsDisplayed()
        assertEquals("REAL START must not be sent before explicit confirmation", 0, startCalls)
        composeRule.onNodeWithTag("cancel_real_trade_start").performClick()
        assertEquals(0, startCalls)

        composeRule.onNodeWithTag("account_start").performClick()
        composeRule.onNodeWithTag("confirm_real_trade_start").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { startCalls == 1 }
        assertEquals(1, startCalls)
    }

    @Test
    fun accountsBrokersLoginAndSettingsScreensAreReachable() {
        openFlow()
        composeRule.onNodeWithText("Accounts").assertIsDisplayed()
        composeRule.onNodeWithTag("flow_info").performClick()
        composeRule.onNodeWithText("Account safety").assertIsDisplayed()
        composeRule.onNodeWithText("CLOSE").performClick()
        composeRule.onNodeWithTag("add_account").performClick()
        composeRule.onNodeWithTag("broker_FBS_MT5").performClick()
        composeRule.onNodeWithText("MT5 account sign-in").assertIsDisplayed()
        composeRule.onNodeWithTag("mt5_mode_hint_notice").assertIsDisplayed()
        composeRule.onNodeWithTag("mt5_server_field_help").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Search MT5 Server").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("mt5_login_verification_path").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("LOGIN").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("field_login").performScrollTo().performTextInput("123456")
        composeRule.onNodeWithTag("field_password").performScrollTo().performTextInput("fixture-password")
        composeRule.onNodeWithTag("server_field").performTextReplacement("no-match-server")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Server not found").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("login_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("login_mode_demo").performScrollTo().performClick()
        composeRule.onNodeWithTag("server_field").performScrollTo().performTextReplacement("FBS-Demo")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("server_result_FBS-Demo").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("server_result_FBS-Demo").performClick()
        composeRule.onNodeWithTag("server_field").assertTextEquals("Search MT5 Server", "FBS-Demo")
        composeRule.onNodeWithTag("login_button").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("save_mt5_server").performScrollTo().performClick()
        composeRule.onNodeWithText("Server saved").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("login_button").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Forgot password? Use your broker app").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("broker_back").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Brokers").assertIsDisplayed()
        composeRule.onNodeWithTag("broker_back").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Accounts").assertIsDisplayed()
        composeRule.onNodeWithTag("settings").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("settings_advanced").performClick()
        composeRule.onNodeWithTag("legacy_bot_tools").assertIsDisplayed()
    }

    @Test
    fun everySupportedMt5BrokerShowsStandardLoginFieldsWithoutManualBootstrap() {
        openFlow()
        composeRule.onNodeWithTag("add_account").performClick()
        listOf("FBS", "EXNESS", "HFM", "DERIV").forEach { brand ->
            composeRule.onNodeWithTag("broker_list").performScrollToNode(hasTestTag("broker_${brand}_MT5"))
            composeRule.onNodeWithTag("broker_${brand}_MT5").performClick()
            composeRule.onNodeWithText("MT5 account sign-in").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("mt5_login_fields_help").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("mt5_server_field_help").performScrollTo().assertIsDisplayed()
            val expectedServerHint = if (brand == "DERIV") {
                "Copy from Deriv Dashboard > MT5 > Server. Enter the exact account-issued value only; do NOT add :port."
            } else {
                "Copy from Broker Dashboard > MT5 account details > Server. Enter the exact account-issued value only; do NOT add :port."
            }
            composeRule.onNodeWithText(expectedServerHint).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("field_login").performScrollTo().performTextInput("123456")
            composeRule.onNodeWithTag("field_password").performScrollTo().performTextInput("fixture-password")
            composeRule.onNodeWithTag("login_mode_demo").performScrollTo().performClick()
            val exactServer = "$brand-Demo"
            composeRule.onNodeWithTag("server_field").performScrollTo().performTextReplacement(exactServer)
            composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag("server_result_$exactServer").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("server_result_$exactServer").performClick()
            composeRule.onNodeWithTag("save_mt5_server").performScrollTo().performClick()
            composeRule.onNodeWithText("Server saved").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("login_button").performScrollTo().assertIsEnabled()
            composeRule.onNodeWithTag("broker_back").performClick()
        }
    }

    @Test
    fun serverWithPortCannotBeSavedAndCannotStartBrokerLogin() {
        openFlow()
        composeRule.onNodeWithTag("add_account").performClick()
        composeRule.onNodeWithTag("broker_FBS_MT5").performClick()
        composeRule.onNodeWithTag("field_login").performScrollTo().performTextInput("123456")
        composeRule.onNodeWithTag("field_password").performScrollTo().performTextInput("fixture-password")
        composeRule.onNodeWithTag("login_mode_demo").performScrollTo().performClick()
        composeRule.onNodeWithTag("server_field").performScrollTo().performTextReplacement("FBS-Demo:443")
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithText("Server not found").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("save_mt5_server").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("login_button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun demoAndRealServerValuesRemainIndependentAcrossModeSwitches() {
        openFlow()
        composeRule.onNodeWithTag("add_account").performClick()
        composeRule.onNodeWithTag("broker_FBS_MT5").performClick()
        composeRule.onNodeWithTag("field_login").performScrollTo().performTextInput("123456")
        composeRule.onNodeWithTag("field_password").performScrollTo().performTextInput("fixture-password")
        composeRule.onNodeWithTag("login_mode_demo").performScrollTo().performClick()
        composeRule.onNodeWithTag("server_field").performScrollTo().performTextReplacement("FBS-Demo") 
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag("server_result_FBS-Demo").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("server_result_FBS-Demo").performClick()
        composeRule.onNodeWithTag("server_field").assertTextEquals("Search MT5 Server", "FBS-Demo")
        composeRule.onNodeWithTag("login_button").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("save_mt5_server").performScrollTo().performClick()
        composeRule.onNodeWithText("Server saved").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("mt5_type_trade_only").performScrollTo().assertIsDisplayed()
        // REAL MT5 credentials remain available on EPHEMERAL; account authentication and explicit REAL confirmation govern trading.
        composeRule.onNodeWithTag("login_mode_real").performScrollTo().performClick()
        composeRule.onNodeWithTag("field_login").assertIsEnabled()
        composeRule.onNodeWithTag("field_password").assertIsEnabled()
        composeRule.onNodeWithTag("server_field").assertIsEnabled()
        composeRule.onNodeWithTag("server_field").assertTextEquals("Search MT5 Server", "FBS")
        composeRule.onNodeWithTag("login_button").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("mt5_server_save_status").assertIsDisplayed()
        assertTrue(
            "The previous mode's success message must be cleared",
            composeRule.onAllNodesWithText("Server saved").fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithTag("server_field").performScrollTo().performTextReplacement("FBS-Real")
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag("server_result_FBS-Real").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("server_result_FBS-Real").performClick()
        composeRule.onNodeWithTag("save_mt5_server").performScrollTo().performClick()
        composeRule.onNodeWithText("Server saved").performScrollTo().assertIsDisplayed()

        // Returning to DEMO requires a fresh selection from the DEMO catalogue.
        composeRule.onNodeWithTag("login_mode_demo").performScrollTo().performClick()
        composeRule.onNodeWithTag("server_field").performScrollTo().performTextReplacement("FBS-Demo")
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag("server_result_FBS-Demo").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("server_result_FBS-Demo").performClick()
        composeRule.onNodeWithTag("server_field").assertTextEquals("Search MT5 Server", "FBS-Demo")
        composeRule.onNodeWithTag("login_button").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("login_mode_real").performScrollTo().performClick()
        composeRule.onNodeWithTag("server_field").performScrollTo().performTextReplacement("FBS-Real")
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag("server_result_FBS-Real").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("server_result_FBS-Real").performClick()
        composeRule.onNodeWithTag("server_field").assertTextEquals("Search MT5 Server", "FBS-Real")
        composeRule.onNodeWithTag("login_button").performScrollTo().assertIsEnabled()
    }

    @Test
    fun realTradeFieldsRemainEnabledOnEphemeralHostAndEndpointVerifierIsOnLoginScreen() {
        openFlow()
        composeRule.onNodeWithTag("add_account").performClick()
        composeRule.onNodeWithTag("broker_FBS_MT5").performClick()
        composeRule.onNodeWithTag("mt5_type_trade_only").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("login_mode_real").performScrollTo().performClick()
        composeRule.onNodeWithTag("field_login").assertIsEnabled()
        composeRule.onNodeWithTag("field_password").assertIsEnabled()
        composeRule.onNodeWithTag("server_field").assertIsEnabled()
        composeRule.onNodeWithTag("field_login").assertIsEnabled().performScrollTo().performTextInput("123456")
        composeRule.onNodeWithTag("field_password").assertIsEnabled().performScrollTo().performTextInput("fixture-password")
        composeRule.onNodeWithTag("server_field").assertIsEnabled().performTextReplacement("FBS-Real")
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag("server_result_FBS-Real").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("server_result_FBS-Real").performClick()
        composeRule.onNodeWithTag("save_mt5_server").performScrollTo().performClick()
        composeRule.onNodeWithText("Server saved").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("verify_broker_endpoint").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("broker_endpoint_result").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("broker_endpoint_result").performScrollTo()
            .assertTextEquals("RED · NOT PUBLICLY PUBLISHED")
        composeRule.onNodeWithTag("login_button").performScrollTo().assertIsEnabled()
    }

    @Test
    fun failedSecureServerPersistenceNeverShowsSuccessOrEnablesLogin() {
        openFlow(storageAvailable = false)
        composeRule.onNodeWithTag("add_account").performClick()
        composeRule.onNodeWithTag("broker_FBS_MT5").performClick()
        composeRule.onNodeWithTag("field_login").performScrollTo().performTextInput("123456")
        composeRule.onNodeWithTag("field_password").performScrollTo().performTextInput("fixture-password")
        composeRule.onNodeWithTag("login_mode_demo").performScrollTo().performClick()
        composeRule.onNodeWithTag("server_field").performScrollTo().performTextReplacement("FBS-Demo")
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTag("server_result_FBS-Demo").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("server_result_FBS-Demo").performClick()
        composeRule.onNodeWithTag("save_mt5_server").performScrollTo().performClick()

        composeRule.onNodeWithText("Server could not be saved to encrypted storage.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("login_button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun brokerMetricsAreUnavailableWhenTheLastVerifiedSnapshotIsStaleOrFutureDated() {
        val now = 1_800_000_000_000L
        val details = VerifiedAccountDetails(
            login = "123456",
            server = "Broker-Demo",
            mode = "DEMO",
            currency = "USD",
            balance = 1_250.0,
            equity = 1_240.0,
            freeMargin = 1_100.0,
            marginLevel = 250.0,
            margin = 140.0,
            leverage = 100,
            retrievedAtMs = now - 5_000L,
        )
        assertEquals(1_250.0, displayableVerifiedAccountMetric(details.balance, details, now)!!, 0.0)
        assertEquals(140.0, displayableVerifiedAccountMetric(details.margin, details, now)!!, 0.0)
        assertNull(displayableVerifiedAccountMetric(details.balance, details, now + 16_000L))
        assertNull(displayableVerifiedAccountMetric(details.balance, details.copy(retrievedAtMs = now + 1L), now))
        assertNull(displayableVerifiedAccountMetric(Double.NaN, details, now))
        assertNull(displayableVerifiedAccountMetric(-1.0, details, now))
    }
}
