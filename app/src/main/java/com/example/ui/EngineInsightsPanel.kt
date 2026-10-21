package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.*

/**
 * Transparently exposes deterministic engine state, live-feed health, strategy scoring, MTF
 * output and execution blockers. It intentionally does not present legacy "AI consensus" claims.
 */
@Composable
fun EngineInsightsPanel(viewModel: DestinedFXViewModel) {
    val bias by viewModel.smcBiasConfidence.collectAsStateWithLifecycle()
    val alignment by viewModel.structureAlignment.collectAsStateWithLifecycle()
    val signal by viewModel.currentTradeSignal.collectAsStateWithLifecycle()
    val analysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
    val feedStatus by viewModel.marketDataStatus.collectAsStateWithLifecycle()
    val quote by viewModel.brokerQuote.collectAsStateWithLifecycle()
    val errors by viewModel.candleFetchErrors.collectAsStateWithLifecycle()
    val brokerUp by viewModel.brokerConnected.collectAsStateWithLifecycle()
    val isAnalyzing by viewModel.isAnalyzing.collectAsStateWithLifecycle()
    val isScanning by viewModel.isSignalScanning.collectAsStateWithLifecycle()
    val alertMessage by viewModel.actionAlertMessage.collectAsStateWithLifecycle()
    val symbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val timeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val mtfTimeframes by viewModel.selectedMtfTimeframes.collectAsStateWithLifecycle()

    if (!alertMessage.isNullOrBlank()) {
        InsightCard("ACTION ALERT") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Alert", tint = WarningAmber, modifier = Modifier.size(16.dp))
                    Text(alertMessage ?: "", fontSize = 11.sp, color = WarningAmber, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { viewModel.actionAlertMessage.value = null }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss Alert", tint = TextSecondary, modifier = Modifier.size(14.dp))
                }
            }
        }
    }

    InsightCard("ALGORITHMIC ENGINE STATE") {
        InsightLine("Engine mode", "Deterministic / rule-based")
        InsightLine("Active market", "$symbol · $timeframe")
        InsightLine("Strategy structure score", "$bias / 100")
        InsightLine("Structure alignment", alignment.ifBlank { "NOT AVAILABLE" })
        InsightLine("Analysis process", when {
            isAnalyzing || isScanning -> "SCANNING"
            analysis == null -> "WAITING FOR FIRST SCAN"
            else -> "IDLE · LAST RESULT RETAINED"
        })
        InsightLine("Market feed", feedStatus.ifBlank { "STATUS NOT REPORTED" })
        InsightLine("Broker connection", if (brokerUp) "CONNECTED" else "NOT CONNECTED")
        InsightLine("MTF contexts selected", mtfTimeframes.sorted().joinToString(", ").ifBlank { "NONE" })
        val q = quote
        if (q != null) {
            HorizontalDivider(color = TabBorder.copy(alpha = 0.7f))
            InsightLine("Quote instrument", q.symbol)
            InsightLine("Bid", q.bid?.toString() ?: "NOT SUPPLIED")
            InsightLine("Ask", q.ask?.toString() ?: "NOT SUPPLIED")
            InsightLine("Quote source", q.source.ifBlank { "UNKNOWN" })
        }
        signal?.let {
            HorizontalDivider(color = TabBorder.copy(alpha = 0.7f))
            InsightLine("Last engine signal", "${it.direction} · ${it.signalState}")
            InsightLine("Signal timeframe", it.timeframe)
            InsightLine("Signal confidence", "${it.confidenceScore}%")
            InsightLine("Execution status", if (it.executable) "ENGINE SAYS ELIGIBLE — BROKER/RISK GATES STILL APPLY" else "ANALYSIS ONLY / NOT EXECUTABLE")
            if (it.rationale.isNotBlank()) {
                Text(it.rationale, fontSize = 10.sp, color = TextSecondary, lineHeight = 15.sp)
            }
        }
    }

    InsightCard("DETERMINISTIC MULTI-TIMEFRAME ANALYSIS") {
        val current = analysis
        if (current == null) {
            Text("No timeframe report has completed yet.", fontSize = 10.sp, color = TextSecondary)
        } else {
            InsightLine("Overall MTF state", current.mtfStatus.ifBlank { "WAIT" })
            InsightLine("Summary", current.mtfSummary.ifBlank { "No summary supplied" })
            val timeframes = listOf("M5", "M15", "M30", "H1", "H4", "D1", "W1")
            timeframes.forEach { tf ->
                val biasValue = current.getTfBias(tf)
                val tint = when (biasValue.uppercase()) {
                    "BULLISH", "BUY", "LONG" -> ProfitGreen
                    "BEARISH", "SELL", "SHORT" -> LossRed
                    "N/A", "UNKNOWN", "OFF" -> TextMuted
                    else -> WarningAmber
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tf, fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(biasValue, fontSize = 10.sp, color = tint, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    InsightCard("STRATEGY SCORES / EVIDENCE") {
        val scores = analysis?.strategyScores.orEmpty()
        if (scores.isEmpty()) {
            Text("No separate strategy scores were returned. The screen will not insert illustrative scores.", fontSize = 10.sp, color = TextSecondary)
        } else {
            scores.entries.sortedByDescending { it.value }.forEach { (name, score) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, fontSize = 10.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                    Text("$score / 100", fontSize = 10.sp, color = ElectricCyan, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
            }
        }
        val reasons = analysis?.blockReasons.orEmpty()
        if (reasons.isNotEmpty()) {
            HorizontalDivider(color = TabBorder.copy(alpha = 0.7f))
            Text("BLOCKERS", color = WarningAmber, fontSize = 9.sp, fontWeight = FontWeight.Black)
            reasons.forEach { Text("• $it", color = WarningAmber, fontSize = 10.sp, lineHeight = 15.sp) }
        }
    }

    InsightCard("DATA INTEGRITY / FETCH DIAGNOSTICS") {
        if (errors.isEmpty()) {
            Text(
                "No per-symbol fetch error is currently recorded. This is not, by itself, proof that the feed is live; check the feed status and quote timestamp above.",
                fontSize = 10.sp, color = TextSecondary, lineHeight = 15.sp
            )
        } else {
            errors.entries.sortedBy { it.key }.forEach { (market, detail) ->
                Text(market, fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                Text(detail, fontSize = 10.sp, color = LossRed, lineHeight = 15.sp)
            }
        }
        Text(
            "Analysis uses broker/public-feed candles and deterministic rules. TradingView is the chart display; it is not treated as an OHLC API.",
            fontSize = 10.sp, color = TextSecondary, lineHeight = 15.sp
        )
    }
}

@Composable
fun InsightCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, TabBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = ElectricCyan)
            content()
        }
    }
}

@Composable
fun InsightLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 10.sp, color = TextSecondary, modifier = Modifier.weight(0.42f))
        Text(value, fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.58f))
    }
}
