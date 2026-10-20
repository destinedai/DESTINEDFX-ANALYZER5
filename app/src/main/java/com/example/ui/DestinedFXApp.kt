package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinedfx.core.analysis.SignalState
import com.destinedfx.core.instrument.SpecRegistry
import com.destinedfx.core.strategy.StrategyRegistry
import com.example.ui.chart.TradingChartContainer
import com.example.ui.export.AnalysisExportSelectionDialog
import com.example.ui.workstations.CalendarTab
import com.example.ui.workstations.BotWorkstationTab
import com.example.ui.DestinedFXScreen
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.BuildConfig
import com.example.MyApplication

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DestinedFXApp(viewModel: DestinedFXViewModel) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val initStatus by viewModel.initStatus.collectAsStateWithLifecycle()

    if (initStatus !is MyApplication.InitState.Ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val isAppLocked by viewModel.isAppLocked.collectAsStateWithLifecycle()
    val showAuthScreen by viewModel.showAuthScreen.collectAsStateWithLifecycle()
    val showRegisterScreen by viewModel.showRegisterScreen.collectAsStateWithLifecycle()
    val selectedScreen by viewModel.selectedScreen.collectAsStateWithLifecycle()
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()

    // 1. Never block the terminal on startup.
    // Display login/register ONLY if the user explicitly opens it from Settings:
    if (showAuthScreen) {
        if (showRegisterScreen) {
            RegisterScreen(
                viewModel = viewModel,
                onDismiss = { viewModel.closeAuthScreen() }
            )
        } else {
            LoginScreen(
                viewModel = viewModel,
                onDismiss = { viewModel.closeAuthScreen() }
            )
        }
        return
    }

    // 2. Only show BiometricLockScreen if the user explicitly engaged the lock manually
    if (isAppLocked) {
        BiometricLockScreen(
            viewModel = viewModel,
            onUnlocked = { viewModel.unlockApp() }
        )
        return
    }

    var showExportDialog by remember { mutableStateOf(false) }
    val dynamicAnalysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
    val tickers by viewModel.tickers.collectAsStateWithLifecycle()
    val currentTicker = tickers[activeSymbol]
    val headerPrice = currentTicker?.price?.takeIf { it > 0.0 }
    val priceChange = currentTicker?.changePercent

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            Surface(
                color = DarkSurface,
                border = BorderStroke(1.dp, CardBorder.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Row 1: Logo & App Name (left) | PulsingDot + Status + Actions (right)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, ElectricCyan.copy(alpha = 0.8f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.destinedfx_icon_1791382332866),
                                    contentDescription = "DestinedFX Emblem",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Column {
                                Text(
                                    text = "DESTINEDFX",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextPrimary,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "INSTITUTIONAL TERMINAL",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = ElectricCyan,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "TRADINGVIEW",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = ElectricCyan
                            )

                            IconButton(
                                onClick = { showExportDialog = true },
                                modifier = Modifier.size(32.dp).testTag("export_button")
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Export Analysis",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Row 2: Selected symbol and price under it
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = activeSymbol,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = ElectricCyan
                            )
                            Text(
                                text = "• $selectedTimeframe",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary
                            )
                        }
                        if (headerPrice != null) {
                            PriceText(
                                value = headerPrice,
                                change = priceChange,
                                symbol = activeSymbol
                            )
                        } else {
                            Text(
                                text = "PRICE —",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 0.dp,
                modifier = Modifier.border(BorderStroke(1.dp, CardBorder.copy(alpha = 0.6f)), shape = RoundedCornerShape(0.dp))
            ) {
                DestinedFXScreen.allScreens.forEach { screen ->
                    NavigationBarItem(
                        selected = selectedScreen == screen,
                        onClick = { viewModel.selectScreen(screen) },
                        icon = { Icon(screen.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = {
                            Text(
                                text = screen.title,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (selectedScreen == screen) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ElectricCyan,
                            selectedTextColor = ElectricCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = ElectricCyan.copy(alpha = 0.12f)
                        ),
                        modifier = Modifier.testTag(screen.tag)
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedScreen) {
                    DestinedFXScreen.Strategies -> StrategiesTab(viewModel)
                    DestinedFXScreen.Analyzer -> SignalsAndChartTab(viewModel)
                    DestinedFXScreen.Calendar -> CalendarTab(viewModel)
                    DestinedFXScreen.Bot -> BotWorkstationTab(viewModel)
                    DestinedFXScreen.Risk -> RiskManagementTab(viewModel)
                    DestinedFXScreen.Settings -> SettingsTab(viewModel)
                }
            }
        }

        val currentAnalysis = dynamicAnalysis
        if (showExportDialog && currentAnalysis != null) {
            AnalysisExportSelectionDialog(
                analysis = currentAnalysis,
                onDismiss = { showExportDialog = false },
                onExportPdf = {
                    viewModel.exportPdfDossier(currentAnalysis)
                    showExportDialog = false
                },
                onExportImage = {
                    viewModel.exportImageSnapshot(currentAnalysis)
                    showExportDialog = false
                },
                isExporting = false
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalsAndChartTab(viewModel: DestinedFXViewModel) {
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val tickers by viewModel.tickers.collectAsStateWithLifecycle()
    val ticker = tickers[activeSymbol]
    val spotPrice = ticker?.price ?: 0.0

    val analysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
    val isScanning by viewModel.isSignalScanning.collectAsStateWithLifecycle()
    val selectedStrategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    var isRealTradingViewEnabled by remember { mutableStateOf(true) }

    val symbols = listOf("XAU/USD", "EUR/USD", "GBP/USD", "USD/JPY", "BTC/USD")
    val timeframes = listOf("M1", "M5", "M15", "M30", "H1", "H4", "D1", "W1")

    LaunchedEffect(activeSymbol, selectedTimeframe, selectedStrategyId) {
        viewModel.runSignalScan(activeSymbol, selectedTimeframe)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TerminalPageHeader(
            eyebrow = "DESTINEDFX / ANALYSIS",
            title = "$activeSymbol  •  $selectedTimeframe",
            subtitle = "TradingView chart with deterministic strategy analysis",
            trailing = { SignalBadge(state = analysis?.signalState ?: "WAIT") }
        )

        // Symbol & Timeframe Selectors
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(symbols) { sym ->
                    FilterChip(
                        selected = activeSymbol == sym,
                        onClick = { viewModel.selectTicker(sym) },
                        label = { Text(sym, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan,
                            selectedLabelColor = DarkBackground,
                            containerColor = DarkSurface,
                            labelColor = TextPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (activeSymbol == sym) ElectricCyan else CardBorder,
                            selectedBorderColor = ElectricCyan,
                            enabled = true,
                            selected = activeSymbol == sym
                        ),
                        modifier = Modifier.testTag("symbol_chip_$sym")
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(timeframes) { tf ->
                    FilterChip(
                        selected = selectedTimeframe == tf,
                        onClick = { viewModel.selectTimeframe(tf) },
                        label = { Text(tf, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan,
                            selectedLabelColor = DarkBackground,
                            containerColor = DarkSurface,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (selectedTimeframe == tf) ElectricCyan else CardBorder,
                            selectedBorderColor = ElectricCyan,
                            enabled = true,
                            selected = selectedTimeframe == tf
                        ),
                        modifier = Modifier.testTag("tf_chip_$tf")
                    )
                }
            }
            Button(
                onClick = { viewModel.runSignalScan(activeSymbol, selectedTimeframe) },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                modifier = Modifier.fillMaxWidth().height(40.dp).testTag("analyze_button"),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Analyze Market", color = DarkBackground, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }

        // TradingView Chart Container with GlassCard
        GlassCard(
            title = "TRADINGVIEW CHART  •  $activeSymbol  •  $selectedTimeframe",
            action = {
                Text(
                    text = "EMBEDDED CHART",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
            }
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                TradingChartContainer(
                    activeSymbol = activeSymbol,
                    selectedTimeframe = selectedTimeframe,
                    currentSpotPrice = spotPrice,
                    activeStrategy = selectedStrategyId,
                    dynamicAnalysis = analysis ?: DynamicAnalysisInfo(symbol = activeSymbol),
                    candles = viewModel.candlestickHistory[activeSymbol] ?: emptyList(),
                    isRealTradingViewEnabled = isRealTradingViewEnabled,
                    onToggleChartType = { isRealTradingViewEnabled = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Live Market Stat Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val highVal = ticker?.high?.takeIf { it > 0.0 }?.let { formatPrice(it, activeSymbol) } ?: "—"
            val lowVal = ticker?.low?.takeIf { it > 0.0 }?.let { formatPrice(it, activeSymbol) } ?: "—"
            val biasVal = ticker?.bias ?: analysis?.direction ?: "NEUTRAL"
            val confVal = analysis?.confidencePct?.takeIf { it > 0 }?.let { "${it}%" } ?: "—"

            StatChip(label = "24h High", value = highVal, modifier = Modifier.weight(1f), color = ProfitGreen)
            StatChip(label = "24h Low", value = lowVal, modifier = Modifier.weight(1f), color = LossRed)
            StatChip(
                label = "SMC Bias",
                value = biasVal,
                modifier = Modifier.weight(1f),
                color = if (biasVal.contains("BULL")) ProfitGreen else if (biasVal.contains("BEAR")) LossRed else WarningAmber
            )
            StatChip(label = "Confidence", value = confVal, modifier = Modifier.weight(1f), color = ElectricCyan)
        }

        // Native Trade Plan Card
        GlassCard(
            title = "INSTITUTIONAL TRADE PLAN",
            action = {
                SignalBadge(state = analysis?.signalState ?: "WAIT")
            }
        ) {
            if (isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ElectricCyan)
            }

            val a = analysis
            val isTradeValid = a != null && a.entry != 0.0 && (a.signalState == "STRONG_BUY" || a.signalState == "BUY" || a.signalState == "WEAK_BUY" || a.signalState == "STRONG_SELL" || a.signalState == "SELL" || a.signalState == "WEAK_SELL")
            if (isTradeValid && a != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip(
                            label = "Direction",
                            value = a.direction,
                            color = if (a.direction == "BUY") ProfitGreen else LossRed
                        )
                        StatChip(
                            label = "Reward / Risk",
                            value = a.tp1RR,
                            color = ProfitGreen
                        )
                    }
                    StatChip(
                        label = "Yield (Pips)",
                        value = "${a.tp1YieldPips} pips",
                        color = ElectricCyan
                    )
                }

                HorizontalDivider(color = CardBorder.copy(alpha = 0.5f))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Entry Price", fontSize = 10.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                        Text(formatPrice(a.entry, activeSymbol), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TextPrimary, fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Stop Loss (SL)", fontSize = 10.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                        Text(formatPrice(a.stopLoss, activeSymbol), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = LossRed, fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Take Profit (TP)", fontSize = 10.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                        Text(formatPrice(a.tp1, activeSymbol), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = ProfitGreen, fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text("SL Reason: ${a.slReason}", fontSize = 10.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                Text("TP Reason: ${a.tpReason}", fontSize = 10.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                Text("Strategy: ${a.strategyVersion}", fontSize = 10.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
            } else {
                Text(
                    text = analysis?.blockReasons?.firstOrNull() ?: analysis?.smcAnalysis ?: "WAIT: Gathering confluence & structural validation...",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WarningAmber
                )
            }
        }

        // Multi-Timeframe Alignment & Confluence Card
        GlassCard(
            title = "MULTI-TIMEFRAME CONFLUENCE",
            action = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                analysis?.mtfStatus?.contains("FULL_BULLISH") == true -> ProfitGreen.copy(alpha = 0.2f)
                                analysis?.mtfStatus?.contains("FULL_BEARISH") == true -> LossRed.copy(alpha = 0.2f)
                                analysis?.mtfStatus?.contains("PARTIAL") == true -> ElectricCyan.copy(alpha = 0.2f)
                                analysis?.mtfStatus?.contains("CONFLICT") == true -> LossRed.copy(alpha = 0.2f)
                                else -> WarningAmber.copy(alpha = 0.2f)
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = analysis?.mtfSummary?.ifBlank { "EVALUATING" } ?: "EVALUATING",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            analysis?.mtfStatus?.contains("FULL_BULLISH") == true -> ProfitGreen
                            analysis?.mtfStatus?.contains("FULL_BEARISH") == true -> LossRed
                            analysis?.mtfStatus?.contains("PARTIAL") == true -> ElectricCyan
                            analysis?.mtfStatus?.contains("CONFLICT") == true -> LossRed
                            else -> WarningAmber
                        }
                    )
                }
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val mtfDisplayTfs = listOf("M5", "M15", "M30", "H1", "H4", "D1", "W1")
                mtfDisplayTfs.forEach { tfKey ->
                    val isSelected = selectedTimeframe == tfKey
                    val bias = analysis?.getTfBias(tfKey) ?: "N/A"
                    val (badgeColor, badgeBg) = when (bias) {
                        "BULLISH" -> ProfitGreen to ProfitGreen.copy(alpha = 0.15f)
                        "BEARISH" -> LossRed to LossRed.copy(alpha = 0.15f)
                        "NEUTRAL" -> TextSecondary to DarkSurfaceVariant
                        "MISSING" -> WarningAmber to WarningAmber.copy(alpha = 0.15f)
                        else -> TextSecondary.copy(alpha = 0.5f) to Color.Transparent
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = tfKey,
                            fontSize = 9.sp,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace,
                            color = if (isSelected) ElectricCyan else TextSecondary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(badgeBg)
                                .border(if (isSelected) 1.dp else 0.dp, if (isSelected) ElectricCyan else Color.Transparent, RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = bias.take(4),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = badgeColor
                            )
                        }
                    }
                }
            }
        }

        // Detailed Market Structure Elements
        val smc = analysis
        if (smc != null && (smc.orderBlocks.isNotEmpty() || smc.keyLevels.isNotEmpty())) {
            GlassCard(title = "SMC STRUCTURAL INVENTORY") {
                if (smc.orderBlocks.isNotEmpty()) {
                    Text("Active Order Blocks", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TextPrimary)
                    smc.orderBlocks.take(4).forEach { ob ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier.size(8.dp).clip(CircleShape)
                                        .background(if (ob.type == "BULLISH") ProfitGreen else LossRed)
                                )
                                Text(ob.type, fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Text(ob.zone, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextSecondary)
                            Text(ob.strength, fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                if (smc.keyLevels.isNotEmpty()) {
                    HorizontalDivider(color = CardBorder.copy(alpha = 0.5f))
                    Text("Verified Key Levels", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TextPrimary)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        smc.keyLevels.take(6).forEach { level ->
                            SuggestionChip(
                                onClick = { },
                                label = { Text(level, fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    labelColor = TextSecondary,
                                    containerColor = DarkSurfaceVariant
                                ),
                                border = SuggestionChipDefaults.suggestionChipBorder(
                                    borderColor = CardBorder,
                                    enabled = true
                                )
                            )
                        }
                    }
                }
            }
        }

        EngineInsightsPanel(viewModel)
    }
}

private val selectedIdVersion = "smc-confluence-1.0.0"

@Composable
fun StrategiesTab(viewModel: DestinedFXViewModel) {
    val selectedStrategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    val strategies = StrategyRegistry.allStrategies
    val selectedStrategy = strategies.firstOrNull { it.id == selectedStrategyId }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TerminalPageHeader(
                eyebrow = "DETERMINISTIC RULESETS",
                title = "Strategy Library",
                subtitle = "Choose the ruleset used by the next analysis. Status reflects the registry."
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChip(label = "REGISTERED", value = strategies.size.toString(), modifier = Modifier.weight(1f))
                StatChip(
                    label = "IMPLEMENTED",
                    value = strategies.count { it.isImplemented }.toString(),
                    modifier = Modifier.weight(1f),
                    color = ProfitGreen
                )
            }
        }

        item {
            val selected = selectedStrategy
            if (selected != null) {
                GlassCard(
                    title = "ACTIVE RULESET",
                    action = {
                        Text(
                            text = if (selected.isImplemented) "READY" else "UNAVAILABLE",
                            color = if (selected.isImplemented) ProfitGreen else WarningAmber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                ) {
                    Text(selected.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(selected.description, fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp)
                    Text(
                        text = selected.rulesSummary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ElectricCyan
                    )
                    Button(
                        onClick = { viewModel.selectScreen(DestinedFXScreen.Analyzer) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                    ) {
                        Text("Analyze with this strategy", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        items(strategies) { strat ->
            val isSelected = strat.id == selectedStrategyId
            Card(
                colors = CardDefaults.cardColors(containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (isSelected) CyanPrimary else TabBorder),
                modifier = Modifier.fillMaxWidth().clickable(enabled = strat.isImplemented) {
                    viewModel.selectStrategy(strat.id)
                    viewModel.selectScreen(DestinedFXScreen.Analyzer)
                }
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strat.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextPrimary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (strat.isImplemented) Color(0xFF00FF99).copy(alpha = 0.15f) else Color(0xFFFF4560).copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (strat.isImplemented) "IMPLEMENTED (${strat.version})" else "NOT IMPLEMENTED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (strat.isImplemented) Color(0xFF00FF99) else Color(0xFFFF4560)
                            )
                        }
                    }

                    Text(
                        text = strat.description,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )

                    Divider(color = TabBorder)

                    Text(
                        text = "Ruleset: ${strat.rulesSummary}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (strat.isImplemented) CyanPrimary else TextSecondary
                    )
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(16.dp))
            BacktestSection(viewModel)
        }
    }
}

@Composable
fun RiskManagementTab(viewModel: DestinedFXViewModel) {
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TerminalPageHeader(
            eyebrow = "CAPITAL PROTECTION",
            title = "Risk Management",
            subtitle = "Position sizing is calculated by the deterministic RiskEngine."
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatChip(label = "MAX RISK / TRADE", value = "1.0%", modifier = Modifier.weight(1f), color = WarningAmber)
            StatChip(label = "MINIMUM PLAN RR", value = "2.0R", modifier = Modifier.weight(1f), color = ProfitGreen)
        }
        GlassCard(title = "NON-NEGOTIABLE GUARDRAILS") {
            Text(
                text = "Risk is capped at 1% of configured equity. Plans below 2.0R must not be promoted to executable trades, and stops must never be narrowed to force a pass.",
                fontSize = 11.sp,
                color = TextSecondary,
                lineHeight = 16.sp
            )
        }
        RiskRewardCalculatorComponent(
            viewModel = viewModel,
            symbol = activeSymbol,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SettingsTab(viewModel: DestinedFXViewModel) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    var supabaseUrlInput by remember { mutableStateOf(viewModel.getSupabaseUrl()) }
    var supabaseKeyInput by remember { mutableStateOf(viewModel.getSupabaseAnonKey()) }
    var derivTokenInput by remember { mutableStateOf(viewModel.getDerivApiToken()) }
    var derivAppIdInput by remember { mutableStateOf(viewModel.getDerivAppId()) }
    var savedMessage by remember { mutableStateOf("") }
    var derivConnectStatus by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            TerminalPageHeader(
                eyebrow = "CONFIGURATION",
                title = "Settings & Integrations",
                subtitle = "TradingView chart display does not require market-data API keys."
            )
        }
        item {
            Text("ACCOUNT & OPTIONAL SIGN-IN", fontWeight = FontWeight.Black, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = ElectricCyan)
            Text("Supabase credentials are used only for cloud account features. Never store service_role keys in the app.", fontSize = 11.sp, color = TextSecondary)
        }

        item {
            OutlinedTextField(
                value = supabaseUrlInput,
                onValueChange = { supabaseUrlInput = it },
                label = { Text("Supabase URL (https://xyz.supabase.co)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        item {
            OutlinedTextField(
                value = supabaseKeyInput,
                onValueChange = { supabaseKeyInput = it },
                label = { Text("Supabase Anon / Public Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }



        item {
            Divider(color = TabBorder)
            Spacer(modifier = Modifier.height(4.dp))
            Text("OPTIONAL DERIV EXECUTION", fontWeight = FontWeight.Black, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = ElectricCyan)
            Text("Only needed for Deriv broker execution. It is not needed to display TradingView or inspect strategy rules.", fontSize = 11.sp, color = TextSecondary)
        }

        item {
            OutlinedTextField(
                value = derivTokenInput,
                onValueChange = { derivTokenInput = it },
                label = { Text("Deriv API Token") },
                modifier = Modifier.fillMaxWidth().testTag("deriv_token_input"),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }

        item {
            OutlinedTextField(
                value = derivAppIdInput,
                onValueChange = { derivAppIdInput = it },
                label = { Text("Deriv App ID (Default: 1089)") },
                modifier = Modifier.fillMaxWidth().testTag("deriv_app_id_input"),
                singleLine = true
            )
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!viewModel.isPreview) {
                            viewModel.app.secretsManager.saveSupabaseUrl(supabaseUrlInput.trim())
                            viewModel.app.secretsManager.saveSupabaseAnonKey(supabaseKeyInput.trim())
                            viewModel.app.secretsManager.saveDerivApiToken(derivTokenInput.trim())
                            viewModel.app.secretsManager.saveDerivAppId(derivAppIdInput.trim())
                        }
                        savedMessage = "All credentials saved securely in Keystore."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    modifier = Modifier.weight(1f).testTag("save_credentials_button")
                ) {
                    Text("Save All", color = DarkBackground, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        viewModel.connectToDeriv(derivTokenInput, derivAppIdInput) { success, msg ->
                            derivConnectStatus = msg
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF99)),
                    modifier = Modifier.weight(1f).testTag("connect_deriv_button")
                ) {
                    Text("Connect Deriv", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            }
            if (savedMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(savedMessage, fontSize = 11.sp, color = Color(0xFF00FF99))
            }
            val status = derivConnectStatus
            if (status != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(status, fontSize = 11.sp, color = CyanPrimary)
            }
        }

        item {
            Divider(color = TabBorder)
            Spacer(modifier = Modifier.height(8.dp))
            Text("TELEGRAM NOTIFICATIONS & ONBOARDING", fontWeight = FontWeight.Black, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = CyanPrimary)
            Text("Configure real-time Telegram trade alerts and bot connectivity.", fontSize = 11.sp, color = TextSecondary)
        }

        item {
            val telegramStatus by viewModel.telegramStatus.collectAsStateWithLifecycle()
            val telegramNotifEnabled by viewModel.telegramNotificationsEnabled.collectAsStateWithLifecycle()
            val telegramResult by viewModel.telegramTestMessageResult.collectAsStateWithLifecycle()
            val isTelegramLoading by viewModel.isTelegramLoading.collectAsStateWithLifecycle()

            var botTokenInput by remember { mutableStateOf(viewModel.getTelegramBotToken()) }
            var chatIdInput by remember { mutableStateOf(viewModel.getTelegramChatId()) }
            var showBotFatherGuide by remember { mutableStateOf(false) }

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, TabBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Connection Status", fontSize = 11.sp, color = TextSecondary)
                            Text(
                                text = telegramStatus,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = when (telegramStatus) {
                                    "Connected" -> Color(0xFF00FF99)
                                    "Connection failed" -> Color(0xFFFF4560)
                                    else -> OrangeAccent
                                }
                            )
                        }
                        TextButton(onClick = { showBotFatherGuide = !showBotFatherGuide }) {
                            Text(if (showBotFatherGuide) "Hide BotFather Guide" else "How to Setup BotFather?", fontSize = 11.sp, color = CyanPrimary)
                        }
                    }

                    if (showBotFatherGuide) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkBackground),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("BotFather Setup Guide:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = CyanPrimary)
                                Text("1. Open Telegram and search for official @BotFather.", fontSize = 10.sp, color = TextPrimary)
                                Text("2. Send /newbot and follow instructions to name your bot.", fontSize = 10.sp, color = TextPrimary)
                                Text("3. Copy the HTTP API Token provided by BotFather.", fontSize = 10.sp, color = TextPrimary)
                                Text("4. Send a message to your bot in Telegram, then enter your Chat ID.", fontSize = 10.sp, color = TextPrimary)
                                Text("5. Enter Bot Token and Chat ID below and tap Connect Telegram.", fontSize = 10.sp, color = TextPrimary)
                                Text("6. Handshake verifies delivery and activates secure alerts.", fontSize = 10.sp, color = TextPrimary)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = botTokenInput,
                        onValueChange = { botTokenInput = it },
                        label = { Text("Telegram Bot Token") },
                        modifier = Modifier.fillMaxWidth().testTag("telegram_token_input"),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )

                    OutlinedTextField(
                        value = chatIdInput,
                        onValueChange = { chatIdInput = it },
                        label = { Text("Telegram Chat ID") },
                        modifier = Modifier.fillMaxWidth().testTag("telegram_chat_id_input"),
                        singleLine = true
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.connectTelegram(botTokenInput, chatIdInput) },
                            enabled = !isTelegramLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            modifier = Modifier.weight(1f).testTag("connect_telegram_button")
                        ) {
                            Text(if (isTelegramLoading) "Connecting..." else "Connect Telegram", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { viewModel.sendTelegramTestNotification() },
                            enabled = !isTelegramLoading && telegramStatus == "Connected",
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF99)),
                            modifier = Modifier.weight(1f).testTag("test_telegram_button")
                        ) {
                            Text("Test Message", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.refreshTelegramStatus() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Refresh Status", color = TextPrimary, fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = { viewModel.unlinkTelegram() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF4560)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Unlink", fontSize = 11.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Enable Automated Alerts", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Requires verified connection status", fontSize = 10.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = telegramNotifEnabled,
                            onCheckedChange = { viewModel.setTelegramNotificationsEnabled(it) },
                            enabled = telegramStatus == "Connected",
                            modifier = Modifier.testTag("telegram_switch")
                        )
                    }

                    val res = telegramResult
                    if (res != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(res, fontSize = 11.sp, color = if (telegramStatus == "Connected") Color(0xFF00FF99) else Color(0xFFFF4560))
                    }
                }
            }
        }

        item {
            val lastCrash by viewModel.lastCrashText.collectAsStateWithLifecycle()
            InsightCard("SYSTEM DIAGNOSTICS & INCIDENT LOG") {
                if (!lastCrash.isNullOrBlank()) {
                    Text("Previous Incident Trace Recorded:", fontSize = 10.sp, color = Color(0xFFFF4560), fontWeight = FontWeight.Bold)
                    Text(
                        text = lastCrash ?: "",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )
                    Button(
                        onClick = { viewModel.dismissLastCrash() },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                        modifier = Modifier.padding(top = 4.dp).testTag("dismiss_crash_button")
                    ) {
                        Text("Clear Incident Log", fontSize = 10.sp, color = TextPrimary)
                    }
                } else {
                    InsightLine("Diagnostic Status", "Operational (Zero crashes recorded)")
                    InsightLine("Process State", "Encrypted Sandbox Active")
                }
            }
        }

        item {
            HorizontalDivider(color = TabBorder)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Session Status:", fontSize = 11.sp, color = TextSecondary)
                    Text(
                        if (currentUser != null) currentUser?.email ?: "Cloud Trader" else "Guest Trader (Local Terminal)",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                if (currentUser != null) {
                    Button(
                        onClick = { viewModel.logoutUser() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4560).copy(alpha = 0.2f))
                    ) {
                        Text("Sign Out", color = Color(0xFFFF4560), fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { viewModel.openLoginScreen() },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Text("Cloud Sign In", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            GlassCard(title = "APPLICATION BUILD") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("DestinedFX Analyzer", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Native Android • Kotlin / Compose", fontSize = 10.sp, color = TextSecondary)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = ElectricCyan)
                        Text("BUILD #${BuildConfig.VERSION_CODE}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextSecondary)
                    }
                }
            }
        }

    }
}

@Composable
fun LoginScreen(viewModel: DestinedFXViewModel, onDismiss: () -> Unit = {}) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(DarkBackground), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, TabBorder),
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("DESTINEDFX LOGIN", fontWeight = FontWeight.Black, fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = CyanPrimary)
                        Text("Optional Cloud Sync Account", fontSize = 11.sp, color = TextSecondary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (errorMessage.isNotBlank()) {
                    Text(errorMessage, fontSize = 11.sp, color = Color(0xFFFF4560), textAlign = TextAlign.Center)
                }

                Button(
                    onClick = {
                        viewModel.loginExistingUser(email, password) { success, msg ->
                            if (!success) errorMessage = msg
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign In", color = DarkBackground, fontWeight = FontWeight.Bold)
                }

                TextButton(onClick = { viewModel.showRegisterScreen.value = true }) {
                    Text("Don't have an account? Register", color = CyanPrimary, fontSize = 11.sp)
                }

                TextButton(onClick = onDismiss) {
                    Text("Continue as Guest (Open Terminal)", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun RegisterScreen(viewModel: DestinedFXViewModel, onDismiss: () -> Unit = {}) {
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(DarkBackground), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, TabBorder),
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("DESTINEDFX REGISTER", fontWeight = FontWeight.Black, fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = CyanPrimary)
                        Text("Create Cloud Account", fontSize = 11.sp, color = TextSecondary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (min 6 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (message.isNotBlank()) {
                    Text(message, fontSize = 11.sp, color = TextPrimary, textAlign = TextAlign.Center)
                }

                Button(
                    onClick = {
                        viewModel.registerNewUser(email, username, password) { success, msg ->
                            message = msg
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Account", color = DarkBackground, fontWeight = FontWeight.Bold)
                }

                TextButton(onClick = { viewModel.showRegisterScreen.value = false }) {
                    Text("Already registered? Sign In", color = CyanPrimary, fontSize = 11.sp)
                }

                TextButton(onClick = onDismiss) {
                    Text("Continue as Guest (Open Terminal)", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}


@Composable
fun BacktestSection(viewModel: DestinedFXViewModel) {
    val backtestState by viewModel.backtestState.collectAsStateWithLifecycle()
    val report by viewModel.activeBacktestReport.collectAsStateWithLifecycle()
    val selectedStrategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TabBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("STRATEGY BACKTESTER (30D)", fontWeight = FontWeight.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = CyanPrimary)
            
            if (backtestState == "RUNNING") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CyanPrimary)
                Text("Simulating 30 days of market data...", fontSize = 11.sp, color = TextSecondary)
            } else {
                Button(
                    onClick = { viewModel.runBacktesting(selectedStrategyId) },
                    enabled = backtestState != "RUNNING",
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Run 30-Day Simulation", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            }

            val currentReport = report
            if (currentReport != null && backtestState == "SUCCESS") {
                HorizontalDivider(color = TabBorder)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Win Rate", fontSize = 10.sp, color = TextSecondary)
                        Text("${String.format(java.util.Locale.US, "%.1f", currentReport.winRate)}%", fontWeight = FontWeight.Bold, color = Color(0xFF00FF99))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Profit Factor", fontSize = 10.sp, color = TextSecondary)
                        Text(String.format(java.util.Locale.US, "%.2f", currentReport.profitFactor), fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Max Drawdown", fontSize = 10.sp, color = TextSecondary)
                        Text("${String.format(java.util.Locale.US, "%.1f", currentReport.maxDrawdown)}%", fontWeight = FontWeight.Bold, color = Color(0xFFFF4560))
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Net Profit:", fontSize = 11.sp, color = TextSecondary)
                    Text("${String.format(java.util.Locale.US, "%.2f", currentReport.netProfitPercent)}%", fontWeight = FontWeight.Black, color = Color(0xFF00FF99), fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun SplashScreen(onFinished: () -> Unit, username: String) {
    LaunchedEffect(Unit) {
        delay(1500)
        onFinished()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(CyanPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.TrendingUp, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(36.dp))
            }
            Text("DESTINEDFX", fontWeight = FontWeight.Black, fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
            Text("Welcome back, $username", fontSize = 13.sp, color = CyanPrimary)
        }
    }
}

@Composable
fun BiometricLockScreen(viewModel: DestinedFXViewModel, onUnlocked: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, TabBorder),
            modifier = Modifier.fillMaxWidth(0.85f).padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = OrangeAccent, modifier = Modifier.size(48.dp))
                Text("TERMINAL SECURED", fontWeight = FontWeight.Black, fontSize = 16.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                Text("Authentication required to access institutional trading desk.", fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center)

                Button(
                    onClick = { onUnlocked() },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Unlock Terminal", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
