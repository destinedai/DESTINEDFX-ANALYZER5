package com.example.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.*
import com.example.ui.theme.*
import java.util.Locale

@Composable
fun RiskManagementTab(viewModel: DestinedFXViewModel) {
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val riskPercent by viewModel.riskPercent.collectAsStateWithLifecycle()
    val brokerType by viewModel.activeBrokerType.collectAsStateWithLifecycle()
    val gateway by viewModel.activeBrokerGateway.collectAsStateWithLifecycle()
    val connection by gateway.connectionStatus.collectAsStateWithLifecycle()
    val accountSnapshot by gateway.accountSnapshot.collectAsStateWithLifecycle()
    val positions by gateway.openPositions.collectAsStateWithLifecycle()
    val brokerConnected = connection == com.example.data.broker.BrokerConnectionStatus.CONNECTED
    val currentAccount = accountSnapshot?.takeIf { it.isDisplayable() }

    LazyColumn(modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TerminalPageHeader(
                eyebrow = "ACCOUNT DEFENCE",
                title = "Risk control",
                subtitle = "Sizing and plan validation remain subject to RiskEngine."
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface), border = BorderStroke(1.dp, CardBorder)) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("BROKER ACCOUNT · ${brokerType.name}", fontSize = 10.sp, color = ElectricCyan,
                            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        Text(if (brokerConnected) "CONNECTED" else "OFFLINE", fontSize = 9.sp,
                            color = if (brokerConnected) ProfitGreen else WarningAmber, fontWeight = FontWeight.Black)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        StatChip("BALANCE", currentAccount?.balance?.let { "${currentAccount.currency} ${String.format(Locale.US, "%.2f", it)}" } ?: "—", Modifier.weight(1f), TextPrimary)
                        StatChip("EQUITY", currentAccount?.equity?.let { "${currentAccount.currency} ${String.format(Locale.US, "%.2f", it)}" } ?: "—", Modifier.weight(1f), TextPrimary)
                        StatChip("FREE MARGIN", currentAccount?.freeMargin?.let { "${currentAccount.currency} ${String.format(Locale.US, "%.2f", it)}" } ?: "—", Modifier.weight(1f), TextPrimary)
                    }
                    val floatingPnl = positions.sumOf { it.currentProfit }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        StatChip("OPEN POSITIONS", if (currentAccount != null) positions.size.toString() else "—", Modifier.weight(1f), ElectricCyan)
                        StatChip("FLOATING P&L", if (currentAccount != null) String.format(Locale.US, "%.2f", floatingPnl) else "—", Modifier.weight(1f), if (floatingPnl >= 0.0) ProfitGreen else LossRed)
                        StatChip("DRAWDOWN", "UNAVAILABLE", Modifier.weight(1f), WarningAmber)
                    }
                    Text(
                        if (currentAccount != null) "DATA SOURCE: BROKER_API · ${currentAccount.providerId} · ${currentAccount.environment ?: "environment unreported"} · retrieved ${currentAccount.retrievedAtMs}"
                        else "DATA SOURCE: UNAVAILABLE · account values remain -- until a fresh broker snapshot is verified.",
                        fontSize = 9.sp, color = TextSecondary
                    )
                    Text("Account-specific drawdown is not supplied by this broker connection; it is not inferred from unavailable history.", fontSize = 9.sp, color = TextSecondary)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface), border = BorderStroke(1.dp, CardBorder)) {
                Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("CURRENT CONFIGURATION", fontSize = 9.sp, color = TextSecondary,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            Text("${riskPercent.coerceAtMost(1.0)}% risk configured", fontSize = 18.sp,
                                color = if (riskPercent <= 1.0) ProfitGreen else LossRed, fontWeight = FontWeight.Black)
                        }
                        Box(Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (riskPercent <= 1.0) ProfitGreen.copy(alpha = 0.12f) else LossRed.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 7.dp)) {
                            Text(if (riskPercent <= 1.0) "WITHIN CAP" else "BREACH", fontSize = 9.sp,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                                color = if (riskPercent <= 1.0) ProfitGreen else LossRed)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(5.dp)).background(DarkSurfaceVariant)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(riskPercent.coerceIn(0.0, 1.0).toFloat())
                            .clip(RoundedCornerShape(5.dp)).background(if (riskPercent <= 1.0) ElectricCyan else LossRed))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StatChip(label = "MAX PER TRADE", value = "1.0%", modifier = Modifier.weight(1f), color = WarningAmber)
                        StatChip(label = "MIN PLAN RR", value = "2.0R", modifier = Modifier.weight(1f), color = ProfitGreen)
                    }
                    HorizontalDivider(color = CardBorder)
                    Text("Hard limits", fontSize = 12.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                    Text("• Position sizing is calculated against configured equity.\n• Stops are not narrowed to force a valid trade.\n• Plans below 2.0R must remain WAIT / non-executable.\n• An invalidated setup cannot remain actionable.",
                        fontSize = 11.sp, color = TextSecondary, lineHeight = 18.sp)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.25f))) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("POSITION SIZING CALCULATOR", fontSize = 10.sp, color = ElectricCyan,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Text("Instrument: $activeSymbol", fontSize = 11.sp, color = TextSecondary)
                    RiskRewardCalculatorComponent(viewModel = viewModel, symbol = activeSymbol, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
