package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LossRed
import com.example.ui.theme.ProfitGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@Composable
fun SimplifiedAnalyzerTab(viewModel: DestinedFXViewModel, onExportAnalysis: () -> Unit = {}) {
    val snapshot by viewModel.managedMt5BridgeSnapshot.collectAsStateWithLifecycle()
    val mode by viewModel.selectedMt5Mode.collectAsStateWithLifecycle()
    val brand by viewModel.selectedMt5BrokerBrand.collectAsStateWithLifecycle()

    LaunchedEffect(brand, mode) { viewModel.startSelectedMt5AnalyzerBridgeMonitor() }

    val connected = snapshot?.let {
        it.connected && it.terminalConnected && it.terminalOnline && it.heartbeatFresh &&
            it.accountVerified && it.brokerIdentityVerified && it.actualAccountMode == mode
    } == true
    val symbol = snapshot?.activeChartSymbol?.takeIf { it.isNotBlank() }
        ?: snapshot?.brokerQuotes?.firstOrNull()?.symbol.orEmpty()
    val timeframe = snapshot?.activeChartTimeframe?.takeIf { it.isNotBlank() } ?: "M15"
    val quote = if (connected && symbol.isNotBlank()) snapshot?.brokerQuotes?.firstOrNull {
        it.symbol.equals(symbol, true) && it.source == "MT5_TERMINAL"
    } else null
    val candles = if (connected && symbol.isNotBlank()) snapshot?.brokerCandles.orEmpty().filter {
        it.symbol.equals(symbol, true) && it.timeframe.equals(timeframe, true) &&
            it.source == "MT5_TERMINAL" && it.complete
    } else emptyList()
    val insight = remember(quote, candles) { MT5AnalyzerMath.analyze(quote, candles) }
    val price = quote?.let { (it.bid + it.ask) / 2.0 }
    val spread = quote?.let { it.ask - it.bid }

    if (!connected || quote == null) {
        Box(Modifier.fillMaxSize().background(DarkBackground)) {
            Text("Waiting for MT5...", color = TextSecondary, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center))
        }
    } else {
        Column(Modifier.fillMaxSize().background(DarkBackground).verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MT5 MARKET", color = TextSecondary, fontSize = 10.sp)
                        Text(symbol.ifBlank { "Waiting for MT5..." }, color = TextPrimary, fontSize = 21.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(9.dp).background(if (connected) ProfitGreen else TextSecondary, CircleShape))
                        Text(if (connected) "MT5 CONNECTED · ${snapshot?.actualAccountMode}" else "Waiting for MT5...",
                            color = if (connected) ProfitGreen else TextSecondary, fontSize = 10.sp)
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("LIVE PRICE · MT5", color = TextSecondary, fontSize = 9.sp)
                        Text(price?.let { formatPrice(it, symbol) } ?: "—", color = TextPrimary, fontSize = 29.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("SPREAD", color = TextSecondary, fontSize = 9.sp)
                        Text(spread?.let { formatPrice(it, symbol) } ?: "—", color = TextPrimary, fontSize = 15.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TRADINGVIEW CHART", color = ElectricCyan, fontSize = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    Text(if (connected) "$symbol · $timeframe" else "Waiting for MT5...", color = TextSecondary, fontSize = 9.sp)
                }
                TradingViewEmbedChart(symbol.takeIf { connected && it.isNotBlank() }, timeframe,
                    Modifier.fillMaxWidth().height(270.dp))
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("SIGNAL", color = ElectricCyan, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (connected) insight.trend else "NEUTRAL",
                        color = when { !connected -> TextSecondary; insight.trend=="BUY" -> ProfitGreen
                            insight.trend=="SELL" -> LossRed; else -> WarningAmber },
                        fontSize = 27.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    Text("${if (connected) insight.confidence else 0}%", color = TextPrimary, fontSize = 21.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                Text(if (connected) insight.reason else "Waiting for MT5...", color = TextSecondary, fontSize = 12.sp)
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("LEVELS · MT5 CANDLES", color = ElectricCyan, fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                AnalyzerLevelValue("Support", insight.support, symbol)
                AnalyzerLevelValue("Resistance", insight.resistance, symbol)
                AnalyzerLevelValue("SL suggestion", insight.stopLoss, symbol)
                AnalyzerLevelValue("TP suggestion", insight.takeProfit, symbol)
            }
        }
    }
    }
}

@Composable
private fun AnalyzerLevelValue(label: String, value: Double?, symbol: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(value?.let { formatPrice(it, symbol) } ?: "—", color = TextPrimary, fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}
