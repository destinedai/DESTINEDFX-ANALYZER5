package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

private data class AssistantMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val fromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun ChatWorkstationTab(viewModel: DestinedFXViewModel) {
    val symbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val timeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val tickers by viewModel.tickers.collectAsStateWithLifecycle()
    val analysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val messages = remember {
        mutableStateListOf(
            AssistantMessage(
                text = "Welcome to DestinedFX. This chat currently provides deterministic, on-device guidance from the app's verified market state. No conversational AI provider is connected, so I will not pretend that responses are generated intelligence. Ask about the Analyzer, risk rules, chart controls, or app settings.",
                fromUser = false
            )
        )
    }

    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val quickQuestions = listOf(
        "📊 Current $symbol Price & Bias",
        "📱 How to connect Telegram?",
        "🤖 How does the Demo Bot work?",
        "🧠 Explain SMC & Order Blocks",
        "🛡️ What are the Risk Rules?",
        "📈 How to use Analyzer?"
    )

    fun generateDeterministicResponse(rawPrompt: String): String {
        val q = rawPrompt.lowercase(Locale.ROOT).trim()
        val ticker = tickers[symbol]
        val freshPrice = ticker?.price?.takeIf { it > 0.0 }
        val priceStr = freshPrice?.let { formatPrice(it, symbol) } ?: "fetching..."

        return when {
            // Greetings and pleasantries
            q in listOf("hi", "hello", "hey", "good morning", "good evening", "howdy", "sup") || q.startsWith("hello") || q.startsWith("hi ") ->
                "Hello. I can provide deterministic help from the app's current analysis and explain its controls. Current market: $symbol on $timeframe (quote: $priceStr; verify freshness in Analyzer). What would you like to know?"

            q.contains("how are you") || q.contains("how're you") ->
                "I'm ready to help. Market-feed status depends on the selected source and its latest verified timestamp; the embedded TradingView chart is for visualization and is not treated as an OHLC API. How can I help with your trading today?"

            q.contains("who are you") || q.contains("what are you") || q.contains("what can you do") ->
                "I am DestinedFX's deterministic, on-device app-help chat. No conversational AI service is connected, and keyword matches are not AI-generated answers. I can explain the currently visible Analyzer state and app controls; for market data, check the reported source and freshness."

            q.contains("thank") || q.contains("great") || q.contains("cool") || q.contains("awesome") ->
                "You're welcome. I can help with supported app controls and currently visible, source-labelled analysis."

            // Current pair & price & analysis
            listOf("price", "cmp", "quote", "cost", "current market price", "ticker").any(q::contains) -> {
                if (freshPrice != null) {
                    val bias = ticker?.bias ?: "NEUTRAL"
                    val change = ticker?.changePercent?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "not supplied"
                    "Latest available price for $symbol is $priceStr (daily change: $change). Source: ${ticker?.source?.ifBlank { "unavailable" } ?: "unavailable"}; status: ${if (ticker?.isLive == true) "reported live by feed" else "not verified live"}. Feed bias: $bias. Check Analyzer's quote timestamp/status before relying on it."
                } else {
                    "A current verified quote for $symbol is unavailable. Switch to the Analyzer tab or tap REFRESH to request the selected market feed; do not treat the embedded TradingView chart as an OHLC API."
                }
            }

            listOf("signal", "setup", "direction", "buy or sell", "analyze", "analysis", "trade plan").any(q::contains) -> {
                val current = analysis
                if (current != null) {
                    val conf = current.confidencePct.coerceIn(0, 100)
                    val rr = validatedDisplayRewardRisk(current.signalState, current.direction, current.entry, current.stopLoss, current.tp1)
                    val status = "Analysis for ${current.symbol} ($timeframe): state=${current.signalState}; direction=${current.direction}; confluence=$conf/100 (not a win probability)."
                    if (rr != null) {
                        status + "\nValidated displayed plan: entry ${formatPrice(current.entry, current.symbol)}, stop ${formatPrice(current.stopLoss, current.symbol)}, TP1 ${formatPrice(current.tp1, current.symbol)}, geometric R:R 1:${String.format(Locale.US, "%.2f", rr)}. Verify feed freshness and broker constraints before acting."
                    } else {
                        status + "\nNo geometrically valid >=2R plan is available. ${current.blockReasons.firstOrNull() ?: "Use the Analyzer blockers for the reason; do not treat incomplete levels as a trade plan."}"
                    }
                } else {
                    "No completed scan report is active for $symbol ($timeframe). Go to Analyzer and run a scan; until it completes, there is no current analysis to report."
                }
            }

            // Telegram setup
            listOf("telegram", "botfather", "alert", "notification", "chat id").any(q::contains) ->
                "To connect Telegram for instant trade alerts:\n" +
                "1. Open Telegram and search for the verified @BotFather.\n" +
                "2. Send '/newbot' to create your bot and copy the HTTP API Token.\n" +
                "3. In Telegram, search for your new bot and tap START (send /start).\n" +
                "4. In DestinedFX, go to More → Settings → Telegram Notifications, enter your Bot Token, and tap 'Auto-Detect'!\n" +
                "5. Tap 'Connect Telegram' and test with 'Test Message' to verify instant delivery."

            // Execution settings are explicitly outside this non-Bot chat scope.
            listOf("bot", "demo", "paper", "automate", "automated", "metaapi", "mt5", "account").any(q::contains) ->
                "This deterministic chat does not inspect broker accounts or Bot/execution state, which is outside this non-Bot scope. Check the relevant workspace for its current, verified status."

            // Strategies (SMC, ICT, Order Blocks, FVGs)
            listOf("smc", "ict", "order block", "fvg", "liquidity", "bos", "choch", "silver bullet").any(q::contains) ->
                "Common SMC terminology (heuristic labels, not proof of institutional orders or predictive edge):\n" +
                "• Order Block (OB): Often described as the last opposing candle before a displacement move; it does not identify who placed orders.\n" +
                "• Fair Value Gap (FVG): A three-candle price imbalance sometimes monitored as a possible revisit area; a revisit is not assured.\n" +
                "• BOS / CHoCH: Heuristic labels for selected swing-level breaks or shifts; neither confirms continuation nor predicts a reversal on its own.\n" +
                "• Liquidity sweep: Price trades beyond a prior level; OHLC alone cannot prove orders were triggered or that price will reverse.\n" +
                "Use the Strategies workspace to inspect implemented rules and analysis evidence; labels do not prove an edge."

            // Risk management
            listOf("risk", "lot", "sizing", "stop loss", "sl", "tp", "reward", "drawdown").any(q::contains) ->
                "DestinedFX Core Risk Rules:\n" +
                "1. Maximum 1% risk per trade on your account balance.\n" +
                "2. Minimum 1:2.0 Risk-to-Reward ratio on all algorithmic signals.\n" +
                "3. Invalidation Stop Loss must be placed structurally behind the identifying Order Block or swing pivot.\n" +
                "4. Dynamic Lot Sizing: Use the Risk tab to automatically compute position volume based on your broker's contract size and stop distance in points."

            // BTC / Crypto queries
            listOf("btc", "bitcoin", "crypto", "ethereum", "eth", "solana", "sol", "xrp").any(q::contains) ->
                "This chat does not monitor crypto markets continuously. Select a supported instrument in Analyzer and verify the separate feed's source, timestamp and status before relying on its analysis."

            // SMC Liquidity & Stop Hunts Strategy queries
            listOf("liquidity", "stop hunt", "hunt", "sweep", "eqh", "eql", "trendline", "pool", "raid").any(q::contains) ->
                "A liquidity-sweep label is an OHLC-derived heuristic, not evidence of actual stop orders or a guaranteed reversal. Review the selected strategy's evidence, invalidation and risk blockers; no fixed reward-to-risk outcome is promised."

            // Balance & MT5 Account queries
            listOf("balance", "capital", "equity", "dollar", "$", "deposit", "funds", "3k", "100k").any(q::contains) ->
                "Chat cannot verify broker balances. Review the displayed account source and sizing assumptions in the Risk workspace. Bot and execution features are outside this non-Bot scope."

            // How to use the Analyzer
            listOf("analyzer", "chart", "tradingview", "timeframe", "pairs", "markets").any(q::contains) ->
                "Using the DestinedFX Analyzer:\n" +
                "1. Select your market category (Forex Majors, Forex Minors, Crypto, Commodities, Indices).\n" +
                "2. Pick your symbol and timeframe (M1, M5, M15, M30, H1, H4, D1, W1).\n" +
                "3. TradingView is an interactive chart embed; it is not the Analyzer OHLC API. Check the independent candle feed's source and freshness.\n" +
                "4. Run analysis and review its state, evidence and blockers; incomplete levels are not a trade plan.\n" +
                "5. Export only after reviewing the data source, status, blockers and actual price levels."

            // General Knowledge & Trivia & Non-trade questions
            q.contains("joke") || q.contains("funny") ->
                "Why do traders prefer graph paper? Because they love to plot! 😄 (Also, remember: markets go up and down, but good humor always trends higher!)"

            q.contains("weather") || q.contains("temperature") ->
                "This deterministic app-help chat has no weather or volatility-service provider, so it cannot report either value."

            q.contains("capital of france") || q.contains("paris") ->
                "The capital of France is Paris. It's famous for art, fashion, gastronomy, and culture (and great cafes to watch the morning Forex session open!)."

            q.contains("capital of") ->
                "No conversational AI provider is connected, so I cannot answer arbitrary general-knowledge questions beyond the small set of built-in responses."

            q.contains("math") || q.contains("calculate") || q.contains("+") || q.contains("-") || q.contains("*") || q.contains("/") ->
                "This deterministic app-help chat does not evaluate arbitrary calculations. Use the Risk workspace for instrument-aware sizing and review its assumptions."

            q.contains("name") || q.contains("who made you") || q.contains("creator") ->
                "I am DestinedFX's on-device deterministic guidance chat. No conversational AI provider is connected."

            // General fallthrough
            else ->
                "This on-device chat has no conversational AI provider and cannot safely answer that request. It can report visible Analyzer data, explain app controls, or summarize deterministic rules."
        }
    }

    fun handleSend(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || sending) return
        messages.add(AssistantMessage(text = trimmed, fromUser = true))
        draft = ""
        sending = true
        scope.launch {
            val reply = generateDeterministicResponse(trimmed)
            messages.add(AssistantMessage(text = reply, fromUser = false))
            sending = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(ElectricCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("DESTINEDFX GUIDANCE", fontSize = 10.sp, color = ElectricCyan, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(WarningAmber))
                    }
                    Text("Deterministic Local Assistant", fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                    Text("Conversational AI provider unavailable • no fabricated AI replies", fontSize = 9.sp, color = WarningAmber)
                    Text("Live Context: $symbol • $timeframe • CMP: ${tickers[symbol]?.price?.let { formatPrice(it, symbol) } ?: "—"}", fontSize = 10.sp, color = TextSecondary)
                }
                IconButton(
                    onClick = {
                        messages.clear()
                        messages.add(
                            AssistantMessage(
                                text = "Chat refreshed. How can I help you analyze the market or configure your terminal today?",
                                fromUser = false
                            )
                        )
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Clear chat", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Quick Suggestion Chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(quickQuestions) { q ->
                Surface(
                    onClick = { handleSend(q.substringAfter(" ")) },
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurfaceVariant,
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Text(
                        text = q,
                        fontSize = 10.sp,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Message Thread
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Top
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.92f),
                        shape = RoundedCornerShape(
                            topStart = 14.dp,
                            topEnd = 14.dp,
                            bottomStart = if (message.fromUser) 14.dp else 2.dp,
                            bottomEnd = if (message.fromUser) 2.dp else 14.dp
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.fromUser) DarkSurfaceVariant else DarkSurface
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (message.fromUser) ElectricCyan.copy(alpha = 0.4f) else CardBorder
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    if (message.fromUser) Icons.Default.Person else Icons.AutoMirrored.Filled.ShowChart,
                                    contentDescription = null,
                                    tint = if (message.fromUser) ElectricCyan else ProfitGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    if (message.fromUser) "YOU" else "DESTINEDFX ASSISTANT",
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = ElectricCyan)
                        Text("Thinking…", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }
        }

        // Input bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(1000) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input_field"),
                placeholder = { Text("Ask about signals, risk, bot, or Telegram…", fontSize = 11.sp) },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { handleSend(draft) }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = CardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Button(
                onClick = { handleSend(draft) },
                enabled = draft.isNotBlank() && !sending,
                modifier = Modifier
                    .height(52.dp)
                    .testTag("chat_send_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message", modifier = Modifier.size(18.dp))
            }
        }
    }
}
