package com.example.ui.bot

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.broker.HostProfile
import com.example.security.SecretsManager
import com.example.ui.theme.DestinedFXTheme
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

    private fun openFlow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("broker_flow_ui_fixture", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val secrets = SecretsManager(context) { preferences }
        composeRule.setContent {
            DestinedFXTheme {
                MetaTraderBrokerFlow(
                    secrets = secrets,
                    context = context,
                    hostProfile = HostProfile.EPHEMERAL,
                    onGrantAccess = { _, _ -> "WAITING FOR SPOTWARE APPROVAL" },
                    onConnect = { _, _, _ -> ConnectOutcome.Failed("FIXTURE", "Fixture only") },
                    onRefreshAccount = { _, _ -> null },
                    onAccountCommand = { _, _, action, _ -> "Fixture action: $action" },
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
    fun accountsBrokersLoginAndSettingsScreensAreReachable() {
        openFlow()
        composeRule.onNodeWithText("Accounts").assertIsDisplayed()
        composeRule.onNodeWithTag("flow_info").performClick()
        composeRule.onNodeWithText("Account safety").assertIsDisplayed()
        composeRule.onNodeWithText("CLOSE").performClick()
        composeRule.onNodeWithTag("add_account").performClick()
        composeRule.onNodeWithTag("broker_FBS_MT5").performClick()
        composeRule.onNodeWithText("Login to an existing account").assertIsDisplayed()
        composeRule.onNodeWithTag("mt5_mode_hint_notice").assertIsDisplayed()
        composeRule.onNodeWithTag("mt5_login_verification_path").assertIsDisplayed()
        composeRule.onNodeWithText("TEST LOGIN & VERIFY").assertIsDisplayed()
        composeRule.onNodeWithTag("field_login").performTextInput("123456")
        composeRule.onNodeWithTag("field_password").performTextInput("fixture-password")
        composeRule.onNodeWithTag("server_field").performTextInput("Fixture-Demo")
        composeRule.onNodeWithTag("login_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("login_mode_demo").performClick()
        composeRule.onNodeWithTag("login_button").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Forgot password?").performScrollTo().assertIsDisplayed()
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
            composeRule.onNodeWithText("Login to an existing account").assertIsDisplayed()
            composeRule.onNodeWithTag("mt5_login_fields_help").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("field_login").performScrollTo().performTextInput("123456")
            composeRule.onNodeWithTag("field_password").performScrollTo().performTextInput("fixture-password")
            composeRule.onNodeWithTag("server_field").performScrollTo().performTextInput("Fixture-Demo")
            composeRule.onNodeWithTag("login_mode_demo").performScrollTo().performClick()
            composeRule.onNodeWithTag("login_button").performScrollTo().assertIsEnabled()
            composeRule.onNodeWithTag("broker_back").performClick()
        }
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
            leverage = 100,
            retrievedAtMs = now - 5_000L,
        )
        assertEquals(1_250.0, displayableVerifiedAccountMetric(details.balance, details, now)!!, 0.0)
        assertNull(displayableVerifiedAccountMetric(details.balance, details, now + 16_000L))
        assertNull(displayableVerifiedAccountMetric(details.balance, details.copy(retrievedAtMs = now + 1L), now))
        assertNull(displayableVerifiedAccountMetric(Double.NaN, details, now))
        assertNull(displayableVerifiedAccountMetric(-1.0, details, now))
    }
}
