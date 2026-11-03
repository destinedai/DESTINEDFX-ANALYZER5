package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.example.ui.StrategyWorkbench
import com.example.ui.theme.DestinedFXTheme
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Rule
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StrategyWorkbenchResponsiveTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactHandsetShowsCandidateOnlyAndBlocksAnalyzerRouting() {
        composeRule.setContent {
            DestinedFXTheme {
                Box(Modifier.width(320.dp).height(760.dp)) {
                    StrategyWorkbench(
                        selectedStrategyId = "volatility-breakout",
                        onSelect = {},
                        onOpenAnalyzer = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("strategy_open_analyzer").assertIsNotEnabled()
        composeRule.onNodeWithTag("strategy_list")
            .performScrollToNode(hasTestTag("strategy_card_volatility-breakout"))
        composeRule.onNodeWithTag("strategy_card_volatility-breakout").assertIsDisplayed()
        composeRule.onNodeWithText("CANDIDATE ONLY · NOT EXECUTABLE").assertIsDisplayed()
        composeRule.onNodeWithText("NOT ELIGIBLE").assertIsDisplayed()
        composeRule.onNodeWithText("Volatility Breakout").assertIsDisplayed()
    }

    @Test
    fun standardHandsetFiltersWithoutLosingCanonicalStrategyIdentity() {
        composeRule.setContent {
            DestinedFXTheme {
                Box(Modifier.width(380.dp).height(800.dp)) {
                    StrategyWorkbench(
                        selectedStrategyId = "smc-confluence-1.0.0",
                        onSelect = {},
                        onOpenAnalyzer = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("strategy_open_analyzer").assertIsEnabled()
        composeRule.onNodeWithTag("strategy_list")
            .performScrollToNode(hasTestTag("strategy_search"))
        composeRule.onNodeWithTag("strategy_search").performTextInput("mean-reversion")
        composeRule.onNodeWithTag("strategy_list")
            .performScrollToNode(hasTestTag("strategy_card_mean-reversion"))
        composeRule.onNodeWithTag("strategy_card_mean-reversion").assertIsDisplayed()
        composeRule.onNodeWithText("Statistical Mean Reversion").assertIsDisplayed()
        composeRule.onNodeWithTag("strategy_list")
            .performScrollToNode(hasTestTag("strategy_selected_id"))
        composeRule.onNodeWithTag("strategy_selected_id").assertTextEquals("smc-confluence-1.0.0")
        composeRule.onNodeWithTag("strategy_open_analyzer").assertIsEnabled()
    }
}
