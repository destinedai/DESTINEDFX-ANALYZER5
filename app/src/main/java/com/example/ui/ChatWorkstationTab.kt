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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.CardBorder
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LossRed
import com.example.ui.theme.ProfitGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

private data class AssistantMessage(val text: String, val fromUser: Boolean)

@Composable
fun ChatWorkstationTab(viewModel: DestinedFXViewModel) {
    val symbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val timeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val tickers by viewModel.tickers.collectAsStateWithLifecycle()
    val analysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
    val messages = remember {
        mutableStateListOf(
            AssistantMessage(
                "Welcome to DestinedFX Market Assistant. I can explain the current algorithmic scan, risk rules, strategies and app navigation. This assistant runs locally; it does not call a cloud AI or invent live prices.",
                false
            )
        )
    }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    fun answer(question: String): String {
        val q = question.lowercase()
        val ticker = tickers[symbol]
        val freshPrice = ticker?.price?.takeIf { it.isFinite() && it > 0.0 && viewModel.isDataFresh(symbol) }
        return when {
            listOf("price", "quote", "current value", "how much").any(q::contains) ->
                if (freshPrice != null) "Current $symbol quote: ${freshPrice}. Source: ${ticker?.source?.ifBlank { "market feed" }}. This quote is only as current as its feed timestamp; verify it on the chart before trading."
                else "A verified fresh quote for $symbol is not available right now. Open Analyzer and refresh market data. I will not guess a price."
            listOf("signal", "analysis", "bias", "setup", "trade plan").any(q::contains) -> {
                if (analysis == null) {
                    "No completed analysis is available for $symbol on $timeframe yet. Open Analyzer and tap RUN LIVE ANALYSIS. If data is missing, the engine should remain WAIT rather than fabricate a signal."
                } else {
                    val reasons = analysis!!.blockReasons.take(3).joinToString("; ").ifBlank { "No additional block reason reported." }
                    "Latest algorithmic result for ${analysis!!.symbol}: ${analysis!!.direction} / ${analysis!!.signalState}. Selected timeframe: $timeframe. ${if (analysis!!.blockReasons.isNotEmpty()) "Current blockers: $reasons" else "Review the Analyzer trade plan and validate the live quote before acting."} This is a rule-based result, not a promise of profit."
                }
            }
            listOf("risk", "lot size", "position size", "stop loss", "take profit", "rr", "reward").any(q::contains) ->
                "Risk controls: maximum configured risk is 1% per trade and a valid plan must offer at least 2.0R. The app should reject stale or missing prices, invalid stops and incomplete trade plans. Open Risk to review sizing; never increase risk to force a trade."
            listOf("strategy", "smc", "ict", "wyckoff", "mean reversion", "breakout").any(q::contains) ->
                "Open Strategies to inspect the available deterministic strategies, then choose one in Analyzer. The selected strategy must be passed into the actual scan; no strategy can guarantee a winning trade."
            listOf("chart", "timeframe", "pair", "symbol", "market").any(q::contains) ->
                "Open Analyzer to choose the market, symbol and timeframe. The TradingView chart is for visualization; the algorithmic engine must use its own validated market-data feed and must not pretend it reads private TradingView iframe data."
            listOf("bot", "connect", "broker", "deriv", "mt5", "telegram").any(q::contains) ->
                "Open More → Bot or Settings to review connection status. Deriv and MT5 use different connection paths. A missing token, App ID or required bridge must be shown as a setup error—not a false connected state. Test with a demo account first."
            listOf("help", "what can you", "hello", "hi", "start").any(q::contains) ->
                "Try asking: “Explain the latest signal”, “Why is the market data unavailable?”, “What are the risk rules?”, or “How do I connect the bot?”. I can explain app state and deterministic rules, but I am not a general-purpose cloud AI."
            else ->
                "I can help with current app state, market-data availability, algorithmic signals, risk controls, strategies and broker setup. I do not have a cloud language model connected, so I won't pretend to understand or answer unrelated questions. Try asking about the latest signal or risk rules."
        }
    }

    fun send() {
        val question = draft.trim()
        if (question.isEmpty() || sending) return
        messages.add(AssistantMessage(question, true))
        draft = ""
        sending = true
        // Local deterministic response: no network dependency, no indefinite loading spinner.
        messages.add(AssistantMessage(answer(question), false))
        sending = false
    }

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(ElectricCyan.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(23.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("MARKET ASSISTANT", fontSize = 10.sp, color = ElectricCyan, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Text("Ask about your terminal", fontSize = 20.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                    Text("Context: $symbol • $timeframe", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }

        Surface(
            color = WarningAmber.copy(alpha = 0.10f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "LOCAL RULE-BASED ASSISTANT • no cloud AI configured • no invented prices",
                modifier = Modifier.padding(10.dp),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = WarningAmber
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Top
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.92f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.fromUser) DarkSurfaceVariant else DarkSurface
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (message.fromUser) ElectricCyan.copy(alpha = 0.35f) else CardBorder
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    if (message.fromUser) Icons.Default.Person else Icons.Default.ShowChart,
                                    contentDescription = null,
                                    tint = if (message.fromUser) ElectricCyan else ProfitGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    if (message.fromUser) "YOU" else "DESTINEDFX",
                                    fontSize = 9.sp,
                                    color = if (message.fromUser) ElectricCyan else ProfitGreen,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(message.text, fontSize = 12.sp, color = TextPrimary, lineHeight = 18.sp)
                        }
                    }
                }
            }
            if (sending) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ElectricCyan)
                        Text("Preparing local response…", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(1000) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about signals, risk or data…", fontSize = 12.sp) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                shape = RoundedCornerShape(12.dp)
            )
            Button(
                onClick = { send() },
                enabled = draft.isNotBlank() && !sending,
                modifier = Modifier.height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send message", modifier = Modifier.size(18.dp))
            }
        }
    }
}
