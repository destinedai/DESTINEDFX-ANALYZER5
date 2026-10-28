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
import kotlinx.coroutines.delay
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
                text = "Welcome to the DestinedFX Intelligence Terminal! I'm your interactive trading co-pilot. I can explain live market analysis, chart structures, ICT/SMC strategies, risk management, Telegram alert setup, and our 1-click Demo Bot. What would you like to explore today?",
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

    fun generateIntelligentResponse(rawPrompt: String): String {
        val q = rawPrompt.lowercase(Locale.ROOT).trim()
        val ticker = tickers[symbol]
        val freshPrice = ticker?.price?.takeIf { it > 0.0 }
        val priceStr = freshPrice?.let { formatPrice(it, symbol) } ?: "fetching..."

        return when {
            // Greetings and pleasantries
            q in listOf("hi", "hello", "hey", "good morning", "good evening", "howdy", "sup") || q.startsWith("hello") || q.startsWith("hi ") ->
                "Hello! Welcome to DestinedFX. I'm ready to assist with real-time market data, institutional trade setups, strategy mechanics, or app configurations. Currently monitoring $symbol on $timeframe (CMP: $priceStr). How can I assist you today?"

            q.contains("how are you") || q.contains("how're you") ->
                "I'm ready to help. Market-feed status depends on the selected source and its latest verified timestamp; the embedded TradingView chart is for visualization and is not treated as an OHLC API. How can I help with your trading today?"

            q.contains("who are you") || q.contains("what are you") || q.contains("what can you do") ->
                "I am the DestinedFX Interactive Terminal Assistant. I provide instant guidance on:\n" +
                "• Live Market Analysis & Structures (BOS, CHoCH, Order Blocks, FVGs)\n" +
                "• Chart navigation and the selected market-feed status\n" +
                "• 1-Click Institutional Bot & MT5 Terminal ($3,000 / custom balance)\n" +
                "• Telegram automated alerts setup via BotFather with Auto-Detect Chat ID\n" +
                "• Mathematical Risk & Position sizing (1% rule, 2R minimum)\n" +
                "Ask me anything about the app or trading concepts!"

            q.contains("thank") || q.contains("great") || q.contains("cool") || q.contains("awesome") ->
                "You're very welcome! If you need any more insights on market setups, strategy confluences, or terminal features, just ask!"

            // Current pair & price & analysis
            listOf("price", "cmp", "quote", "cost", "current market price", "ticker").any(q::contains) -> {
                if (freshPrice != null) {
                    val bias = ticker?.bias ?: "NEUTRAL"
                    val change = String.format(Locale.US, "%+.2f%%", ticker?.changePercent ?: 0.0)
                    "Current Market Price (CMP) for $symbol is $priceStr ($change today). Source: ${ticker?.source?.ifBlank { "unavailable" } ?: "unavailable"}; status: ${if (ticker?.isLive == true) "reported live" else "not verified live"}. Directional bias: $bias. You can view the interactive chart in Analyzer or Home."
                } else {
                    "A current verified quote for $symbol is unavailable. Switch to the Analyzer tab or tap REFRESH to request the selected market feed; do not treat the embedded TradingView chart as an OHLC API."
                }
            }

            listOf("signal", "setup", "direction", "buy or sell", "analyze", "analysis", "trade plan").any(q::contains) -> {
                if (analysis != null) {
                    val dir = analysis?.direction ?: "WAIT"
                    val conf = analysis?.confidencePct ?: 0
                    val state = analysis?.signalState ?: "ACTIVE"
                    val entry = formatPrice(analysis?.entry ?: 0.0, symbol)
                    val tp = formatPrice(analysis?.tp1 ?: 0.0, symbol)
                    val sl = formatPrice(analysis?.stopLoss ?: 0.0, symbol)
                    val obs = analysis?.orderBlocks?.firstOrNull()?.let { "${it.type} Order Block" } ?: "Market Structure Shift"
                    "Deterministic analysis for $symbol ($timeframe):\n" +
                    "• Direction: $dir ($conf% confidence)\n" +
                    "• State: $state\n" +
                    "• Recommended Entry: $entry\n" +
                    "• Target TP1: $tp (+${String.format(Locale.US, "%.1f", analysis?.tp1YieldPips ?: 0.0)} pips)\n" +
                    "• Invalidation SL: $sl\n" +
                    "• Structure Focus: $obs\n" +
                    "Remember to always verify the setup on the TradingView chart before executing!"
                } else {
                    "No completed scan report is active for $symbol ($timeframe). Go to Analyzer and tap 'RUN LIVE ANALYSIS' to run our deterministic institutional scan."
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

            // Bot Tab & Demo Account
            listOf("bot", "demo", "paper", "automate", "automated", "metaapi", "mt5", "account").any(q::contains) ->
                "About the DestinedFX Bot Tab & MT5 Architecture:\n" +
                "• MT5 Account Management: Select 'Demo' or 'Real' mode and choose your starting balance ($3,000 preset, $5,000, $10,000 or custom deposit). It validates real broker credentials and session health.\n" +
                "• Real MT5 Broker: Connect your live broker account by entering your MT5 Login, Password, and Broker Server.\n" +
                "• Instant 1-Tap Execution: Use the 1-Tap BUY / SELL buttons with live Bid/Ask quotes, lot volume selector (0.01-1.00), and automated 1:2.5 RR stop-loss/take-profit."

            // Strategies (SMC, ICT, Order Blocks, FVGs)
            listOf("smc", "ict", "order block", "fvg", "liquidity", "bos", "choch", "silver bullet").any(q::contains) ->
                "Institutional Smart Money Concepts (SMC) & ICT breakdown:\n" +
                "• Order Block (OB): The last opposing candle before an aggressive displacement. Indicates institutional footprints where banks placed heavy orders.\n" +
                "• Fair Value Gap (FVG): A 3-candle imbalance where price moved so violently that liquidity was skipped. Price tends to retrace and fill this pocket.\n" +
                "• BOS (Break of Structure): Confirms continuation in the prevailing trend direction.\n" +
                "• CHoCH (Change of Character): The first structural shift signaling a potential trend reversal.\n" +
                "• Liquidity Sweeps: When price probes beyond previous highs/lows to take stop orders before reversing.\n" +
                "Visit the Strategies tab in the bottom bar to inspect full blueprints for all 6 built-in institutional strategies!"

            // Risk management
            listOf("risk", "lot", "sizing", "stop loss", "sl", "tp", "reward", "drawdown").any(q::contains) ->
                "DestinedFX Core Risk Rules:\n" +
                "1. Maximum 1% risk per trade on your account balance.\n" +
                "2. Minimum 1:2.0 Risk-to-Reward ratio on all algorithmic signals.\n" +
                "3. Invalidation Stop Loss must be placed structurally behind the identifying Order Block or swing pivot.\n" +
                "4. Dynamic Lot Sizing: Use the Risk tab to automatically compute position volume based on your broker's contract size and stop distance in points."

            // BTC / Crypto queries
            listOf("btc", "bitcoin", "crypto", "ethereum", "eth", "solana", "sol", "xrp").any(q::contains) ->
                "Bitcoin (BTC) is the premier digital asset monitored 24/7 on our TradingView live feed.\n" +
                "• In DestinedFX, BTC/USD is analyzed using institutional Smart Money Concepts (SMC).\n" +
                "• Strategy Focus: The engine detects Equal Highs/Lows (EQH/EQL), liquidity sweeps, and 3-candle Fair Value Gap (FVG) imbalances on 15m, 1h, and 4h timeframes.\n" +
                "• Risk Management: Due to BTC volatility, position volume is dynamically sized strictly under 1.0% account risk.\n" +
                "Select BTC/USD in the Analyzer or Home tab to view the live TradingView chart and run instant algorithmic scans!"

            // SMC Liquidity & Stop Hunts Strategy queries
            listOf("liquidity", "stop hunt", "hunt", "sweep", "eqh", "eql", "trendline", "pool", "raid").any(q::contains) ->
                "SMC Liquidity & Stop Hunts Strategy Blueprint:\n" +
                "• Hunt the Obvious: Pools of stop-loss orders sit directly above swing highs (Buy-Side Liquidity) and below swing lows (Sell-Side Liquidity).\n" +
                "• Highlighting: Identifies Equal Highs (EQH), Equal Lows (EQL), and trendline liquidity pools acting as liquidity traps.\n" +
                "• Sweep Anticipation: Anticipates a swift wick raid/sweep rather than chasing a clean breakout trap.\n" +
                "• Reversal Entry: Triggers immediate post-sweep reversal entries with tight invalidation SL placed just beyond the sweep wick extreme and targets opposite liquidity pools with minimum 1:2.5 R:R!"

            // Balance & MT5 Account queries
            listOf("balance", "capital", "equity", "dollar", "$", "deposit", "funds", "3k", "100k").any(q::contains) ->
                "Account Balance & Real Credential Synchronization:\n" +
                "• When you connect your MT5 demo or real account, DestinedFX reads and displays your exact account balance (e.g. $3,000.00) directly from your broker credentials.\n" +
                "• No Mock Figures: The terminal never hardcodes fake $100,000 balances for live/demo logins.\n" +
                "• Capital Protection: All lot sizing calculations in the Risk and Bot workstations dynamically adjust to your actual balance using the 1.0% max risk rule."

            // How to use the Analyzer
            listOf("analyzer", "chart", "tradingview", "timeframe", "pairs", "markets").any(q::contains) ->
                "Using the DestinedFX Live Analyzer:\n" +
                "1. Select your market category (Forex Majors, Forex Minors, Crypto, Commodities, Indices).\n" +
                "2. Pick your symbol and timeframe (M1, M5, M15, M30, H1, H4, D1, W1).\n" +
                "3. The embedded TradingView chart renders real-time price action with interactive drawing tools.\n" +
                "4. Tap 'RUN LIVE ANALYSIS' to let our deterministic engine compute order blocks, fair value gaps, and entry coordinates.\n" +
                "5. Tap 'EXPORT ANALYSIS' to generate PDF or PNG institutional reports to share via WhatsApp, Telegram, or Email!"

            // General Knowledge & Trivia & Non-trade questions
            q.contains("joke") || q.contains("funny") ->
                "Why do traders prefer graph paper? Because they love to plot! 😄 (Also, remember: markets go up and down, but good humor always trends higher!)"

            q.contains("weather") || q.contains("temperature") ->
                "I'm operating in the cloud terminal, but market temperature across $symbol right now is showing high volatility! Check your TradingView chart for precise price action."

            q.contains("capital of france") || q.contains("paris") ->
                "The capital of France is Paris. It's famous for art, fashion, gastronomy, and culture (and great cafes to watch the morning Forex session open!)."

            q.contains("capital of") ->
                "That's an interesting geography question! While I specialize in financial markets and terminal operations, I'm always happy to chat about world capitals, science, or technology."

            q.contains("math") || q.contains("calculate") || q.contains("+") || q.contains("-") || q.contains("*") || q.contains("/") ->
                "Mathematics is the foundation of trading! Whether calculating pip values, risk percentages (capped at 1%), or general equations, precision is key. How can I help you calculate today?"

            q.contains("name") || q.contains("who made you") || q.contains("creator") ->
                "I am the DestinedFX Intelligence Assistant, built into the DestinedFX Institutional Trading Terminal."

            // General fallthrough
            else ->
                "I hear you! You asked: \"$rawPrompt\".\n\n" +
                "As your DestinedFX assistant, I'm here to chat about anything you like—whether it's general knowledge, science, life, or market analysis, strategy confluences, and terminal tools. How can I help you further today?"
        }
    }

    fun handleSend(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || sending) return
        messages.add(AssistantMessage(text = trimmed, fromUser = true))
        draft = ""
        sending = true
        scope.launch {
            delay(150L) // smooth conversational feel
            val reply = generateIntelligentResponse(trimmed)
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
                        Text("DESTINEDFX INTELLIGENCE", fontSize = 10.sp, color = ElectricCyan, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(ProfitGreen))
                    }
                    Text("Interactive Terminal Assistant", fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Black)
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
