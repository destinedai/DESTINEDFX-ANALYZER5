package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinedfx.core.strategy.StrategyRegistry
import com.example.ui.chart.TradingChartContainer
import com.example.ui.components.StatChip
import com.example.ui.components.TerminalStatusPill
import com.example.ui.theme.*

/** Website-inspired native landing page. All prices shown here come from the real ticker state. */
@Composable
fun HomeDashboardTab(viewModel: DestinedFXViewModel) {
    val tickers by viewModel.tickers.collectAsStateWithLifecycle()
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val selectedStrategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    val selectedMtfTimeframes by viewModel.selectedMtfTimeframes.collectAsStateWithLifecycle()
    val marketDataStatus by viewModel.marketDataStatus.collectAsStateWithLifecycle()
    val activeTicker = tickers[activeSymbol]
    val goldTicker = tickers["XAU/USD"]
    val implementedCount = StrategyRegistry.allStrategies.count { it.isImplemented }
    val feedReady = marketDataStatus.startsWith("READY", ignoreCase = true)
    val feedUnavailable = marketDataStatus.startsWith("UNAVAILABLE", ignoreCase = true)
    val feedColor = when {
        feedReady -> ProfitGreen
        feedUnavailable -> LossRed
        else -> WarningAmber
    }
    val feedLabel = when {
        feedReady -> "DATA READY"
        feedUnavailable -> "FEED UNAVAILABLE"
        else -> "FEED CHECKING"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF3B3427), DarkSurfaceVariant, DarkSurface)))
                    .border(1.dp, ElectricCyan.copy(alpha = 0.38f), RoundedCornerShape(22.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TerminalStatusPill("RULE-BASED ENGINE", ElectricCyan)
                    TerminalStatusPill(feedLabel, feedColor)
                }
                Text(
                    text = "Trade with the\ninstitutions, not against them.",
                    fontSize = 27.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary
                )
                Text(
                    text = "DestinedFX maps live market structure through deterministic SMC, ICT, Wyckoff and quantitative strategy rules—then surfaces the evidence, risk and trade plan.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = TextSecondary
                )
                Text(
                    text = "DETERMINISTIC ANALYSIS • NO PROVIDER KEY • NO FABRICATED MARKET PRICES",
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Black,
                    color = ElectricCyan
                )
                Button(
                    onClick = { viewModel.selectScreen(DestinedFXScreen.Analyzer) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)
                ) {
                    Icon(Icons.Default.QueryStats, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("RUN LIVE ANALYSIS", fontWeight = FontWeight.Black, fontSize = 12.sp)
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(17.dp))
                }
                OutlinedButton(
                    onClick = { viewModel.selectScreen(DestinedFXScreen.Strategies) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Text("EXPLORE STRATEGIES", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("LIVE MARKET WORKSPACE", color = ElectricCyan, fontSize = 9.sp,
                        fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text("XAU/USD · Gold · H1", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
                TextButton(onClick = {
                    viewModel.selectTicker("XAU/USD")
                    viewModel.selectTimeframe("H1")
                    viewModel.selectScreen(DestinedFXScreen.Analyzer)
                }) {
                    Text("Analyze pair →", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth().height(430.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.36f))
            ) {
                TradingChartContainer(
                    activeSymbol = "XAU/USD",
                    selectedTimeframe = "H1",
                    currentSpotPrice = goldTicker?.price?.takeIf { it > 0.0 } ?: 0.0,
                    activeStrategy = selectedStrategyId,
                    dynamicAnalysis = DynamicAnalysisInfo(symbol = "XAU/USD"),
                    candles = viewModel.candlestickHistoryByTimeframe["XAU/USD-H1"]
                        ?: viewModel.candlestickHistory["XAU/USD"] ?: emptyList(),
                    isRealTradingViewEnabled = true,
                    onToggleChartType = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
                Text(
                    "Chart visualisation: TradingView · Trade analysis: native deterministic engine",
                    fontSize = 9.sp, color = TextMuted
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("MARKET WATCH", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Black)
                val visibleTickers = tickers.values.filter { it.price > 0.0 }.sortedBy { it.symbol }.take(12)
                if (visibleTickers.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), border = BorderStroke(1.dp, CardBorder)) {
                        Text(
                            "No verified ticker quotes are currently available. Refresh the Analyzer after the feed connects.",
                            modifier = Modifier.padding(14.dp), color = WarningAmber, fontSize = 11.sp, lineHeight = 16.sp
                        )
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visibleTickers, key = { it.symbol }) { quote ->
                            Column(
                                modifier = Modifier.width(124.dp).clip(RoundedCornerShape(12.dp))
                                    .background(DarkSurface).border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                                    .clickable { viewModel.selectTicker(quote.symbol); viewModel.selectScreen(DestinedFXScreen.Analyzer) }
                                    .padding(11.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(quote.symbol, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                Text(formatPrice(quote.price, quote.symbol), color = ElectricCyan,
                                    fontSize = 13.sp, fontWeight = FontWeight.Black)
                                Text(
                                    if (quote.isLive) "LIVE · ${quote.source}" else "NOT LIVE · ${quote.source}",
                                    color = if (quote.isLive) ProfitGreen else TextMuted,
                                    fontSize = 8.sp, maxLines = 2, lineHeight = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TERMINAL SNAPSHOT", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatChip("IMPLEMENTED RULESETS", implementedCount.toString(), Modifier.weight(1f), ProfitGreen)
                    StatChip("RISK CAP", "≤ 1%", Modifier.weight(1f), ElectricCyan)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatChip("MINIMUM TARGET", "2.0R", Modifier.weight(1f), ProfitGreen)
                    StatChip("MTF CONTEXTS", selectedMtfTimeframes.size.toString(), Modifier.weight(1f), ElectricCyan)
                }
            }
        }

        item {
            Text("BUILT FOR A CLEAR DECISION", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Black)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FeatureRow(Icons.Default.ShowChart, "Live charting", "Responsive TradingView chart with symbol and timeframe controls.")
                FeatureRow(Icons.Default.Timeline, "Algorithmic strategy engine", "SMC, ICT Silver Bullet, Wyckoff, Supply & Demand, Volatility Breakout, Mean Reversion and Multi-EMA Trend Following.")
                FeatureRow(Icons.Default.Security, "Risk-first trade plans", "RiskEngine is the sizing authority; invalid or incomplete setups stay WAIT.")
                FeatureRow(Icons.Default.CalendarMonth, "News & session context", "Economic-calendar checks can block automation when the status is high-impact or unknown.")
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(DarkSurface).border(1.dp, CardBorder, RoundedCornerShape(13.dp)).padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                .background(ElectricCyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp)) }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Text(description, color = TextSecondary, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
