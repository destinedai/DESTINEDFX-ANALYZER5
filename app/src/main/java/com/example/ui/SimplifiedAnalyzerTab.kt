package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    val symbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val timeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val tickers by viewModel.tickers.collectAsStateWithLifecycle()
    val signal by viewModel.currentTradeSignal.collectAsStateWithLifecycle()
    val scanning by viewModel.isSignalScanning.collectAsStateWithLifecycle()
    val activeTicker = tickers[symbol]
    val quoteAgeMs = activeTicker?.lastUpdateMillis?.let { System.currentTimeMillis() - it }
    val livePrice = activeTicker?.takeIf {
        it.isLive && it.kind == "STREAM" && it.price.isFinite() && it.price > 0.0 &&
            quoteAgeMs != null && quoteAgeMs in 0L..15_000L &&
            it.source.equals("Deriv public WebSocket", ignoreCase = true)
    }?.price
    val signalText = if (scanning) "ANALYZING…" else
        signal?.let { userFacingSignalLabel(it.signalState, it.direction) } ?: "WAIT"
    val signalColor = when (signalText.uppercase()) {
        "BUY" -> ProfitGreen
        "SELL" -> LossRed
        "DATA UNAVAILABLE" -> LossRed
        "NO VALID SETUP" -> WarningAmber
        else -> ElectricCyan
    }

    LaunchedEffect(symbol, timeframe) {
        viewModel.refreshMarketData(symbol, timeframe)
        viewModel.runSignalScan(symbol, timeframe)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground)
            .verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("DESTINEDFX ANALYZER", color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Text("$symbol · $timeframe", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LIVE PRICE", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text(
                    livePrice?.let { formatPrice(it, symbol) } ?: "Price unavailable",
                    color = if (livePrice != null) TextPrimary else LossRed,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    if (livePrice != null) "PUBLIC MARKET FEED · NO LOGIN NEEDED"
                    else "PUBLIC FEED CONNECTING · NO LOGIN NEEDED",
                    color = if (livePrice != null) ProfitGreen else WarningAmber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SIGNAL", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text(signalText, color = signalColor, fontSize = 28.sp, fontWeight = FontWeight.Black)
                if (scanning) CircularProgressIndicator(color = ElectricCyan)
            }
        }
        Button(
            onClick = {
                viewModel.refreshMarketData(symbol, timeframe)
                viewModel.runSignalScan(symbol, timeframe)
            },
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)
        ) {
            Text("REFRESH PRICE & SIGNAL", fontWeight = FontWeight.Black)
        }
    }
}
