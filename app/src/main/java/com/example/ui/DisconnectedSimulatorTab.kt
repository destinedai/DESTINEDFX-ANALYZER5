package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CardBorder
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LossRed
import com.example.ui.theme.ProfitGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

/** Visual-only modes. Neither mode has credentials, transport, or broker connectivity. */
enum class SimulatorMode { DEMO, LIVE }

enum class SimulatedOrderState { IDLE, VALIDATED, SIMULATED_FILLED, BLOCKED_LIVE }

data class SimulatedAccountSnapshot(
    val identity: String,
    val broker: String,
    val server: String,
    val currency: String,
    val balance: Double,
    val equity: Double,
    val freeMargin: Double,
    val openPositions: Int,
    val orderState: SimulatedOrderState = SimulatedOrderState.IDLE,
    val activity: String = "No simulated request submitted."
)

/**
 * Deterministic local simulator. It deliberately contains no suspend functions, HTTP clients,
 * credentials, broker identifiers, or execution gateway calls.
 */
object DisconnectedExecutionSimulator {
    const val safetyNotice = "SIMULATION ONLY · NO BROKER · NO NETWORK · NO ORDERS"

    fun initialSnapshot(): SimulatedAccountSnapshot = SimulatedAccountSnapshot(
        identity = "SIM-ACCOUNT-042",
        broker = "Fictional Demo Markets",
        server = "simulator-local",
        currency = "USD",
        balance = 10_000.00,
        equity = 10_125.50,
        freeMargin = 9_640.25,
        openPositions = 1
    )

    fun refreshScenario(snapshot: SimulatedAccountSnapshot): SimulatedAccountSnapshot =
        snapshot.copy(
            equity = 10_125.50,
            freeMargin = 9_640.25,
            orderState = SimulatedOrderState.IDLE,
            activity = "Synthetic market scenario refreshed locally."
        )

    fun requestOrder(mode: SimulatorMode, snapshot: SimulatedAccountSnapshot): SimulatedAccountSnapshot =
        if (mode == SimulatorMode.LIVE) {
            snapshot.copy(
                orderState = SimulatedOrderState.BLOCKED_LIVE,
                activity = "Blocked: LIVE-labelled simulator state cannot authenticate or execute."
            )
        } else {
            snapshot.copy(
                orderState = SimulatedOrderState.SIMULATED_FILLED,
                openPositions = snapshot.openPositions + 1,
                activity = "Hypothetical order marked SIMULATED_FILLED locally; no request left the device."
            )
        }
}

@Composable
fun DisconnectedSimulatorTab() {
    var mode by remember { mutableStateOf(SimulatorMode.DEMO) }
    var snapshot by remember { mutableStateOf(DisconnectedExecutionSimulator.initialSnapshot()) }
    val stateColor = when (snapshot.orderState) {
        SimulatedOrderState.BLOCKED_LIVE -> LossRed
        SimulatedOrderState.SIMULATED_FILLED -> ProfitGreen
        else -> ElectricCyan
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DISCONNECTED EXECUTION SIMULATOR", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
        Text("Visual testing workspace", color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Black)
        Text(
            DisconnectedExecutionSimulator.safetyNotice,
            color = WarningAmber,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("simulator_safety_notice")
        )
        Text(
            "This screen uses fictional identities, balances, and market outcomes. It is completely separate from broker authentication, MT5, the bridge, market feeds, and real order execution.",
            color = TextSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )

        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("VISUAL ACCOUNT MODE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SimulatorMode.values().forEach { candidate ->
                        FilterChip(
                            selected = mode == candidate,
                            onClick = { mode = candidate },
                            label = { Text("${candidate.name} · VISUAL ONLY") },
                            modifier = Modifier.testTag("simulator_mode_${candidate.name.lowercase()}"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (candidate == SimulatorMode.LIVE) LossRed else ElectricCyan,
                                selectedLabelColor = DarkBackground,
                                containerColor = DarkSurfaceVariant,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }
                Text(
                    if (mode == SimulatorMode.LIVE) "LIVE-labelled state is intentionally locked: authentication and execution are unavailable."
                    else "DEMO-labelled state is synthetic only: the button below changes local simulator state and sends nothing.",
                    color = if (mode == SimulatorMode.LIVE) LossRed else WarningAmber,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SYNTHETIC ACCOUNT", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                SimulatorLine("Identity", snapshot.identity)
                SimulatorLine("Broker", snapshot.broker)
                SimulatorLine("Server", snapshot.server)
                SimulatorLine("Currency", snapshot.currency)
                SimulatorLine("Balance", "${snapshot.currency} %.2f · SIMULATED".format(snapshot.balance))
                SimulatorLine("Equity", "${snapshot.currency} %.2f · SIMULATED".format(snapshot.equity))
                SimulatorLine("Free margin", "${snapshot.currency} %.2f · SIMULATED".format(snapshot.freeMargin))
                SimulatorLine("Open positions", "${snapshot.openPositions} · HYPOTHETICAL")
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SIMULATED DECISION WORKFLOW", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                SimulatorLine("Scenario", "EUR/USD · M15 · synthetic quote 1.08420")
                SimulatorLine("Analysis", "Candidate signal · local rules only")
                SimulatorLine("Order state", snapshot.orderState.name, stateColor)
                HorizontalDivider(color = CardBorder)
                Text(snapshot.activity, color = TextSecondary, fontSize = 10.sp, lineHeight = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { snapshot = DisconnectedExecutionSimulator.refreshScenario(snapshot) },
                        modifier = Modifier.weight(1f).testTag("simulator_refresh")
                    ) { Text("REFRESH SCENARIO", fontSize = 10.sp) }
                    Button(
                        onClick = { snapshot = DisconnectedExecutionSimulator.requestOrder(mode, snapshot) },
                        enabled = mode == SimulatorMode.DEMO,
                        modifier = Modifier.weight(1f).testTag("simulator_order")
                    ) { Text("SIMULATE ORDER", fontSize = 10.sp) }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SimulatorLine(label: String, value: String, valueColor: Color = TextPrimary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 10.sp)
        Text(value, color = valueColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
