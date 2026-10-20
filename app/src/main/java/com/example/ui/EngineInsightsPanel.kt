package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.*

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment

/**
 * Surfaces engine outputs the ViewModel computes:
 * SMC bias, structure alignment, trade signal, deterministic multi-timeframe analysis,
 * strategy sentiment, data correspondence, broker quote, fetch errors, analysis state,
 * action alerts, and the algorithmic analysis report.
 */
@Composable
fun EngineInsightsPanel(viewModel: DestinedFXViewModel) {
    val bias by viewModel.smcBiasConfidence.collectAsStateWithLifecycle()
    val alignment by viewModel.structureAlignment.collectAsStateWithLifecycle()
    val signal by viewModel.currentTradeSignal.collectAsStateWithLifecycle()
    val consensus by viewModel.multiAIConsensusStatus.collectAsStateWithLifecycle()
    val opinions by viewModel.aiBotOpinions.collectAsStateWithLifecycle()
    val sentiments by viewModel.modelSentimentScores.collectAsStateWithLifecycle()
    val rows by viewModel.correspondenceRows.collectAsStateWithLifecycle()
    val quote by viewModel.brokerQuote.collectAsStateWithLifecycle()
    val errors by viewModel.candleFetchErrors.collectAsStateWithLifecycle()
    val aiText by viewModel.aiAnalysisResult.collectAsStateWithLifecycle()
    val brokerUp by viewModel.brokerConnected.collectAsStateWithLifecycle()
    val isAnalyzing by viewModel.isAnalyzing.collectAsStateWithLifecycle()
    val isMultiScanning by viewModel.isMultiAIScanning.collectAsStateWithLifecycle()
    val alertMessage by viewModel.actionAlertMessage.collectAsStateWithLifecycle()

    // Action Alert Banner (if engine raised an alert)
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
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Alert",
                        tint = OrangeAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = alertMessage ?: "",
                        fontSize = 11.sp,
                        color = OrangeAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = { viewModel.actionAlertMessage.value = null },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss Alert",
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }

    InsightCard("ENGINE STATE") {
        InsightLine("SMC bias confidence", "$bias%")
        InsightLine("Structure", alignment)
        InsightLine("Analysis engine", if (isAnalyzing) "COMPUTING REALTIME SMC..." else "READY")
        InsightLine("Broker", if (brokerUp) "CONNECTED" else "NOT CONNECTED")
        val q = quote
        if (q != null) InsightLine("Broker quote", "${q.symbol} bid ${q.bid ?: "-"} / ask ${q.ask ?: "-"} (${q.source})")
        signal?.let {
            HorizontalDivider(color = TabBorder.copy(alpha = 0.5f))
            InsightLine("Signal", "${it.direction} ${it.symbol} ${it.timeframe} [${it.signalState}]")
            InsightLine("Entry / SL / TP", "${it.entryPrice} / ${it.stopLoss} / ${it.takeProfit}")
            InsightLine("Confidence", "${it.confidenceScore}%")
            if (it.rationale.isNotBlank()) Text(it.rationale, fontSize = 10.sp, color = TextSecondary)
            it.strategyScores.forEach { (name, score) -> InsightLine(name, "$score") }
        }
    }

    InsightCard("DETERMINISTIC MTF ANALYSIS") {
        InsightLine("Status", consensus)
        InsightLine("Timeframe scanner", if (isMultiScanning) "SCANNING ACTIVE (H1/M15/H4)..." else "IDLE")
        if (opinions.isEmpty()) Text("No timeframe results yet.", fontSize = 10.sp, color = TextSecondary)
        opinions.values.forEach { o ->
            InsightLine(o.botName, "${o.signal} ${o.confidence}%  E ${o.entry} SL ${o.sl} TP ${o.tp}")
            if (o.coreRationale.isNotBlank()) Text(o.coreRationale, fontSize = 9.sp, color = TextSecondary)
        }
        sentiments.forEach { (model, pair) -> InsightLine("$model sentiment", "${pair.first} ${pair.second}") }
    }

    InsightCard("DATA CORRESPONDENCE") {
        if (rows.isEmpty()) Text("No data rows yet.", fontSize = 10.sp, color = TextSecondary)
        rows.forEach { r ->
            InsightLine(r.symbol, "${r.status} | ${r.kind} | ${r.analysisSource}")
            val pips = r.diffPips
            if (pips != null) Text("diff ${"%.2f".format(pips)} pips", fontSize = 9.sp, color = TextSecondary)
        }
        errors.forEach { (sym, msg) -> InsightLine("$sym error", msg) }
    }

    if (!aiText.isNullOrBlank()) {
        InsightCard("ALGORITHMIC ENGINE ANALYSIS") {
            Text(aiText ?: "", fontSize = 10.sp, color = TextPrimary)
        }
    }
}

@Composable
fun InsightCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TabBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = CyanPrimary)
            content()
        }
    }
}

@Composable
fun InsightLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp, color = TextSecondary, modifier = Modifier.weight(0.4f))
        Text(value, fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.6f))
    }
}
