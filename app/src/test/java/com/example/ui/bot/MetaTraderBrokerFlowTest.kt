package com.example.ui.bot

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
                    hostProfile = HostProfile.PERSISTENT,
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
        composeRule.onNodeWithTag("add_account").performClick()
        composeRule.onNodeWithTag("broker_FBS_MT5").performClick()
        composeRule.onNodeWithText("Login to an existing account").assertIsDisplayed()
        composeRule.onNodeWithTag("login_mode_demo").performClick()
        composeRule.onNodeWithTag("field_login").performTextInput("123456")
        composeRule.onNodeWithTag("field_password").performTextInput("fixture-password")
        composeRule.onNodeWithTag("server_field").performTextInput("Fixture-Demo")
        composeRule.onNodeWithTag("login_button").assertIsEnabled()
        composeRule.onNodeWithTag("broker_back").performClick()
        composeRule.onNodeWithTag("broker_back").performClick()
        composeRule.onNodeWithTag("settings").performClick()
        composeRule.onNodeWithTag("settings_advanced").performClick()
        composeRule.onNodeWithTag("legacy_bot_tools").assertIsDisplayed()
    }
}
