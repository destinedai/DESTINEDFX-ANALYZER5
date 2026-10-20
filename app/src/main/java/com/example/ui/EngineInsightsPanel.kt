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

/**
 * Surfaces engine outputs the ViewModel already computes but no screen drew:
 * SMC bias, structure alignment, trade signal, multi-AI consensus, model sentiment,
 * data correspondence, broker quote, fetch errors and Gemini analysis text.
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

    InsightCard("ENGINE STATE") {
        InsightLine("SMC bias confidence", "$bias%")
        InsightLine("Structure", alignment)
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

    InsightCard("MULTI-AI CONSENSUS") {
        InsightLine("Status", consensus)
        if (opinions.isEmpty()) Text("No model opinions yet.", fontSize = 10.sp, color = TextSecondary)
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
        InsightCard("GEMINI ANALYSIS") {
            Text(aiText ?: "", fontSize = 10.sp, color = TextPrimary)
        }
    }
}

@Composable
private fun InsightCard(title: String, content: @Composable () -> Unit) {
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
private fun InsightLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp, color = TextSecondary, modifier = Modifier.weight(0.4f))
        Text(value, fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.6f))
    }
}
