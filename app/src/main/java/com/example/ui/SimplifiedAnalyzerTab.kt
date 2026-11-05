package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinedfx.core.strategy.StrategyRegistry
import com.example.ui.chart.TradingChartContainer
import com.example.ui.components.GoldGradientButton
import com.example.ui.components.QuoteFreshnessIndicator
import com.example.ui.theme.CardBorder
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LossRed
import com.example.ui.theme.ProfitGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import java.util.Locale

@Composable
fun SimplifiedAnalyzerTab(viewModel: DestinedFXViewModel, onExportAnalysis: () -> Unit = {}) {
    val symbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val timeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val ticker by viewModel.tickers.collectAsStateWithLifecycle()
    val signal by viewModel.currentTradeSignal.collectAsStateWithLifecycle()
    val analysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
    val scanning by viewModel.isSignalScanning.collectAsStateWithLifecycle()
    val scanStage by viewModel.scanProgressStage.collectAsStateWithLifecycle()
    val marketDataStatus by viewModel.marketDataStatus.collectAsStateWithLifecycle()
    val quoteDiagnostics by viewModel.quoteDiagnostics.collectAsStateWithLifecycle()
    val strategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    var advancedOpen by remember { mutableStateOf(false) }
    // TradingView's cross-origin widget does not expose verifiable candle-render evidence.
    // Never present its WebView or iframe being attached as proof that a chart rendered.
    var chartDisplayed by remember { mutableStateOf(false) }

    val activeTicker = ticker[symbol]
    val currentSignal = signal
    val currentAnalysis = analysis
    val strategyName = StrategyRegistry.allStrategies.firstOrNull { it.id == strategyId }?.name ?: strategyId
    val displayState = userFacingSignalLabel(currentSignal?.signalState, currentSignal?.direction)
    val rr = currentAnalysis?.let { validatedDisplayRewardRisk(it.signalState, it.direction, it.entry, it.stopLoss, it.tp1) }
    val hasPlan = rr != null && currentSignal?.entryPrice?.let { it > 0.0 } == true
    val readiness = deriveMarketReadiness(
        ticker = activeTicker,
        candleSource = viewModel.marketDataSource(symbol, timeframe),
        candlesAvailable = viewModel.candlesForChart(symbol, timeframe).isNotEmpty(),
        chartDisplayed = chartDisplayed,
        signal = currentSignal
    )
    val stateColor = when (displayState) {
        "BUY" -> ProfitGreen
        "SELL" -> LossRed
        "DATA UNAVAILABLE" -> LossRed
        "NO VALID SETUP" -> WarningAmber
        else -> ElectricCyan
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DESTINEDFX ANALYZER", color = GoldAccent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(symbol, color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Black)
                    Text("· $timeframe", color = ElectricCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                val price = activeTicker?.price?.takeIf { it > 0.0 }
                Text(price?.let { formatPrice(it, symbol) } ?: "Price unavailable", color = if (price != null) TextPrimary else WarningAmber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Analysis is based on available market data. Chart candle rendering is not verified.", color = TextSecondary, fontSize = 10.sp)
            }
        }
        item {
            ReadinessCard(readiness, marketDataStatus, quoteDiagnostics[symbol])
        }
        item {
            SelectionBar(viewModel, symbol, timeframe)
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface), border = BorderStroke(1.dp, stateColor.copy(alpha = 0.55f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("CURRENT SIGNAL", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            Text(displayState, color = stateColor, fontSize = 30.sp, fontWeight = FontWeight.Black)
                        }
                        if (scanning) CircularProgressIndicator(modifier = Modifier.size(28.dp), color = ElectricCyan, strokeWidth = 3.dp)
                        else Icon(Icons.Default.ShowChart, contentDescription = null, tint = stateColor, modifier = Modifier.size(30.dp))
                    }
                    if (scanning) {
                        Text("Analysing market structure…", color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(plainTradingReason(scanStage), color = TextSecondary, fontSize = 11.sp)
                    } else if (currentSignal == null) {
                        Text("Run analysis to check for a qualified opportunity.", color = TextSecondary, fontSize = 12.sp)
                    } else {
                        Text("Strategy: $strategyName", color = TextSecondary, fontSize = 11.sp)
                        if (hasPlan) {
                            TradePlanSummary(currentAnalysis!!, symbol, rr!!)
                        } else {
                            Text(conciseReasons(currentSignal!!).first(), color = WarningAmber, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
        }
        item {
            WhyThisTrade(signal, strategyName)
        }
        item {
            GoldGradientButton(onClick = { viewModel.runSignalScan(symbol, timeframe) }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                if (scanning) CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = DarkBackground)
                else Text("ANALYSE MARKET", fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().height(330.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkBackground), border = BorderStroke(1.dp, CardBorder)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("MARKET CHART", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        IconButton(onClick = { viewModel.refreshMarketData(symbol, timeframe) }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh chart", tint = ElectricCyan) }
                    }
                    TradingChartContainer(
                        activeSymbol = symbol,
                        selectedTimeframe = timeframe,
                        currentSpotPrice = activeTicker?.takeIf { readiness.quoteLive }?.price,
                        activeStrategy = strategyId,
                        dynamicAnalysis = analysis ?: DynamicAnalysisInfo(symbol = symbol),
                        candles = viewModel.candlesForChart(symbol, timeframe),
                        candleSource = viewModel.marketDataSource(symbol, timeframe),
                        isRealTradingViewEnabled = true,
                        onToggleChartType = { chartDisplayed = false },
                        onPriceUpdate = { _, _ -> },
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CardBorder)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Advanced Analysis", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    IconButton(onClick = { advancedOpen = !advancedOpen }) { Icon(if (advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = "Toggle advanced analysis", tint = ElectricCyan) }
                }
                if (advancedOpen) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineInsightsPanel(viewModel)
                        OutlinedButton(onClick = onExportAnalysis, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricCyan)) { Text("EXPORT FULL ANALYSIS") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(viewModel: DestinedFXViewModel, symbol: String, timeframe: String) {
    val symbols = listOf("EUR/USD", "GBP/USD", "USD/JPY", "XAU/USD", "BTC/USD", "ETH/USD", "NAS100", "USOIL")
    val timeframes = listOf("M5", "M15", "M30", "H1", "H4", "D1")
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CardBorder)) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("MARKET", color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Black)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { items(symbols) { item -> FilterChip(selected = item == symbol, onClick = { viewModel.selectTicker(item) }, label = { Text(item, fontSize = 10.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ElectricCyan, selectedLabelColor = DarkBackground, containerColor = DarkSurfaceVariant, labelColor = TextSecondary)) } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { items(timeframes) { item -> FilterChip(selected = item == timeframe, onClick = { viewModel.selectTimeframe(item) }, label = { Text(item, fontSize = 10.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ElectricCyan, selectedLabelColor = DarkBackground, containerColor = DarkSurfaceVariant, labelColor = TextSecondary)) } }
        }
    }
}

@Composable
private fun ReadinessCard(readiness: MarketReadiness, marketDataStatus: String, diagnostic: String?) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CardBorder)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("MARKET STATUS", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
            StatusLine("Quote", readiness.quoteLabel, readiness.quoteLive)
            StatusLine("Candles", readiness.candleLabel, readiness.candlesConfirmed)
            StatusLine("Chart", readiness.chartLabel, readiness.chartDisplayed)
            StatusLine("Analysis", readiness.analysisLabel, readiness.analysisCompleted)
            StatusLine("Trading", readiness.eligibilityLabel, readiness.tradingEligible)
            if (!diagnostic.isNullOrBlank()) Text(plainTradingReason(diagnostic), color = WarningAmber, fontSize = 10.sp)
            if (marketDataStatus.isNotBlank()) Text(plainTradingReason(marketDataStatus), color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, positive: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 10.sp)
        Text(value, color = if (positive) ProfitGreen else WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TradePlanSummary(analysis: DynamicAnalysisInfo, symbol: String, rr: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlanValue("ENTRY", formatPrice(analysis.entry, symbol), TextPrimary, Modifier.weight(1f))
            PlanValue("STOP LOSS", formatPrice(analysis.stopLoss, symbol), LossRed, Modifier.weight(1f))
            PlanValue("TAKE PROFIT", formatPrice(analysis.tp1, symbol), ProfitGreen, Modifier.weight(1f))
        }
        Text("Risk / reward: 1:${String.format(Locale.US, "%.2f", rr)}", color = ProfitGreen, fontSize = 13.sp, fontWeight = FontWeight.Black)
        Text("Entry: ${plainTradingReason(analysis.entryReason)}", color = TextSecondary, fontSize = 10.sp)
        Text("Stop loss: ${plainTradingReason(analysis.slReason)}", color = TextSecondary, fontSize = 10.sp)
        Text("Take profit: ${plainTradingReason(analysis.tpReason)}", color = TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun PlanValue(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier = modifier.background(DarkSurfaceVariant, RoundedCornerShape(9.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = TextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Black)
        Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun WhyThisTrade(signal: com.example.domain.model.TradeSignal?, strategyName: String) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CardBorder)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("WHY THIS TRADE?", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Text("Strategy: $strategyName", color = TextSecondary, fontSize = 10.sp)
            if (signal == null) Text("Run analysis to see the strategy's evidence.", color = TextSecondary, fontSize = 11.sp)
            else conciseReasons(signal).forEach { Text("• $it", color = TextPrimary, fontSize = 11.sp, lineHeight = 16.sp) }
        }
    }
}

private val GoldAccent = Color(0xFFE5B85C)
