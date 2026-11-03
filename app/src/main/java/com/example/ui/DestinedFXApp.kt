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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.platform.LocalContext
import com.example.R
import com.example.BuildConfig
import com.example.MyApplication

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DestinedFXApp(viewModel: DestinedFXViewModel) {

    val initStatus by viewModel.initStatus.collectAsStateWithLifecycle()

    if (initStatus !is MyApplication.InitState.Ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val isAppLocked by viewModel.isAppLocked.collectAsStateWithLifecycle()

    val selectedScreen by viewModel.selectedScreen.collectAsStateWithLifecycle()
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()



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
    val quoteDiagnostics by viewModel.quoteDiagnostics.collectAsStateWithLifecycle()
    val marketDataStatus by viewModel.marketDataStatus.collectAsStateWithLifecycle()
    val currentTicker = tickers[activeSymbol]
    val hasFreshQuote = currentTicker?.price?.let { it.isFinite() && it > 0.0 } == true &&
        viewModel.isDataFresh(activeSymbol)
    val headerPrice = currentTicker?.price?.takeIf { it.isFinite() && it > 0.0 && hasFreshQuote }
    val priceChange = currentTicker?.changePercent
    val candlesAvailable = marketDataStatus.startsWith("READY", ignoreCase = true)
    val feedStatusColor = when {
        hasFreshQuote -> ProfitGreen
        marketDataStatus.startsWith("UNAVAILABLE", ignoreCase = true) -> LossRed
        else -> WarningAmber
    }
    val feedStatusLabel = when {
        hasFreshQuote && candlesAvailable -> "Live quote • candle history available"
        hasFreshQuote && marketDataStatus.startsWith("UNAVAILABLE", ignoreCase = true) -> "Live quote • candle history unavailable"
        hasFreshQuote -> "Live quote • candle history pending"
        candlesAvailable -> quoteDiagnostics[activeSymbol]?.let { "Candle history available • $it" }
            ?: "Candle history available • no fresh quote"
        quoteDiagnostics[activeSymbol] != null -> quoteDiagnostics.getValue(activeSymbol)
        marketDataStatus.startsWith("UNAVAILABLE", ignoreCase = true) -> "No fresh quote • candle history unavailable"
        else -> "Checking quote • candle history pending"
    }

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
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF17243A), DarkSurface, DarkBackground)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 7.dp),
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
                                    text = "INSTITUTIONAL TERMINAL • v${BuildConfig.VERSION_NAME}",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = ElectricCyan,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            TerminalStatusPill(
                                label = feedStatusLabel,
                                tint = feedStatusColor,
                                modifier = Modifier.testTag("market_feed_status")
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "DATA FEED",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = ElectricCyan,
                                    letterSpacing = 0.4.sp
                                )
                                IconButton(
                                    onClick = { viewModel.selectScreen(DestinedFXScreen.More) },
                                    modifier = Modifier.size(30.dp).testTag("more_workspaces_button")
                                ) {
                                    Icon(
                                        Icons.Default.MoreHoriz,
                                        contentDescription = "More workspaces",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { showExportDialog = true },
                                    modifier = Modifier.size(30.dp).testTag("export_button")
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
                    }

                    // Workspace context keeps the six tabs understandable without opening a menu.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = selectedScreen.title,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = selectedScreen.purpose,
                                fontSize = 10.sp,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }
                        if (headerPrice != null) {
                            PriceText(
                                value = headerPrice,
                                change = priceChange,
                                symbol = activeSymbol
                            )
                        }
                    }
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
                        if (headerPrice == null) {
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
            Surface(
                color = DarkSurface,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .border(BorderStroke(1.dp, CardBorder.copy(alpha = 0.8f)))
            ) {
                // Keep the six daily-use trading workspaces visible without opening More.
                val bottomSelectedScreen = selectedScreen.takeIf { it in DestinedFXScreen.primaryScreens }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DestinedFXScreen.primaryScreens.forEach { screen ->
                        val isSelected = bottomSelectedScreen == screen
                        Column(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) GoldPrimary.copy(alpha = 0.10f) else Color.Transparent)
                                .border(
                                    BorderStroke(1.dp, if (isSelected) GoldPrimary.copy(alpha = 0.72f) else Color.Transparent),
                                    RoundedCornerShape(10.dp)
                                )
                                .selectable(
                                    selected = isSelected,
                                    role = Role.Tab,
                                    onClick = { viewModel.selectScreen(screen) }
                                )
                                .testTag(screen.tag)
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 2.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                screen.icon,
                                contentDescription = screen.title,
                                tint = if (isSelected) GoldPrimary else TextSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                            Text(
                                text = screen.title,
                                maxLines = 1,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                                color = if (isSelected) GoldPrimary else TextSecondary
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedScreen) {
                    DestinedFXScreen.Home -> HomeDashboardTab(viewModel)
                    DestinedFXScreen.Analyzer -> SignalsAndChartTab(viewModel, onExportAnalysis = { showExportDialog = true })
                    DestinedFXScreen.ImageAnalyzer -> ChartImageAnalyzerTab(viewModel)
                    DestinedFXScreen.Strategies -> StrategiesTab(viewModel)
                    DestinedFXScreen.Calendar -> CalendarTab(viewModel)
                    DestinedFXScreen.Bot -> BotWorkstationTab(viewModel)
                    DestinedFXScreen.Watchlist -> WatchlistTab(viewModel)
                    DestinedFXScreen.More -> MoreTab(viewModel)
                    DestinedFXScreen.Chat -> ChatWorkstationTab(viewModel)
                    DestinedFXScreen.Risk -> RiskManagementTab(viewModel)
                    DestinedFXScreen.Settings -> SettingsTab(viewModel)
                    DestinedFXScreen.About -> AboutDisclosureTab(viewModel)
                    DestinedFXScreen.Contact -> ContactWorkstationTab()
                }
            }
        }

        val currentAnalysis = dynamicAnalysis
        if (showExportDialog) {
            val exportAnalysis = currentAnalysis ?: DynamicAnalysisInfo(
                symbol = activeSymbol, direction = "WAIT", signalState = "DATA_UNAVAILABLE",
                blockReasons = listOf("No completed analysis is available yet. No prices are invented for this report.")
            )
            AnalysisExportSelectionDialog(
                analysis = exportAnalysis,
                onDismiss = { showExportDialog = false },
                onExportPdf = {
                    viewModel.exportPdfDossier(exportAnalysis)
                    showExportDialog = false
                },
                onExportImage = {
                    viewModel.exportImageSnapshot(exportAnalysis)
                    showExportDialog = false
                },
                isExporting = false
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalsAndChartTab(viewModel: DestinedFXViewModel, onExportAnalysis: () -> Unit = {}) {
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val tickers by viewModel.tickers.collectAsStateWithLifecycle()
    val quoteDiagnostics by viewModel.quoteDiagnostics.collectAsStateWithLifecycle()
    val analysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
    val isScanning by viewModel.isSignalScanning.collectAsStateWithLifecycle()
    val scanStage by viewModel.scanProgressStage.collectAsStateWithLifecycle()
    val selectedStrategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    val selectedMtfTimeframes by viewModel.selectedMtfTimeframes.collectAsStateWithLifecycle()
    val marketDataStatus by viewModel.marketDataStatus.collectAsStateWithLifecycle()
    val ticker = tickers[activeSymbol]
    // Group canonical symbols into the same asset families traders expect from the website.
    // Every selection is shared by the embedded chart and deterministic analysis pipeline.
    val symbolGroups = linkedMapOf(
        "FOREX MAJORS" to listOf(
            "EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "AUD/USD", "USD/CAD", "NZD/USD"
        ),
        "FOREX MINORS" to listOf(
            "EUR/GBP", "EUR/JPY", "GBP/JPY", "AUD/JPY", "EUR/AUD", "EUR/CAD", "EUR/CHF",
            "GBP/CHF", "GBP/AUD", "GBP/CAD", "CAD/JPY", "CHF/JPY", "USD/TRY", "USD/ZAR",
            "USD/MXN", "USD/SGD", "EUR/TRY", "GBP/NZD"
        ),
        "COMMODITIES" to listOf(
            "XAU/USD", "XAG/USD", "XPT/USD", "XPD/USD", "USOIL", "UKOIL", "NATGAS",
            "WHEAT", "CORN", "SOYBEANS", "COFFEE"
        ),
        "INDICES" to listOf("SPX500", "NAS100", "DJ30", "FTSE100", "DAX40"),
        "CRYPTO" to listOf("BTC/USD", "ETH/USD", "BNB/USD", "SOL/USD", "XRP/USD")
    )
    val allSymbols = symbolGroups.values.flatten().distinct()
    val categories = listOf("ALL MARKETS") + symbolGroups.keys.toList()
    var selectedAssetCategory by remember { mutableStateOf("ALL MARKETS") }
    val symbols = if (selectedAssetCategory == "ALL MARKETS") allSymbols
        else symbolGroups[selectedAssetCategory] ?: allSymbols
    val strategies = StrategyRegistry.allStrategies.filter { it.isImplemented }
    val activeStrategyName = strategies.firstOrNull { it.id == selectedStrategyId }?.name ?: selectedStrategyId
    val timeframes = listOf("M1", "M5", "M15", "M30", "H1", "H4", "D1", "W1")
    var chartSymbolMenuExpanded by remember { mutableStateOf(false) }
    var chartTimeframeMenuExpanded by remember { mutableStateOf(false) }
    var strategyMenuExpanded by remember { mutableStateOf(false) }
    // Keep chart mode owned by the Analyzer screen so TradingView failure fallback
    // and the explicit chart-mode control always switch the actual rendered chart.
    var useTradingViewChart by remember { mutableStateOf(true) }

    LaunchedEffect(selectedAssetCategory) {
        if (activeSymbol !in symbols) {
            symbols.firstOrNull()?.let { viewModel.selectTicker(it) }
        }
    }

    // Manual analysis control: Analysis is executed on-demand when the user taps 'RUN LIVE ANALYSIS' below.

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("LIVE ALGORITHMIC ANALYZER", fontSize = 9.sp, color = ElectricCyan,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Text(activeSymbol, fontSize = 23.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                    Text("TradingView chart · rule-based analysis", fontSize = 10.sp, color = TextSecondary)
                }
                SignalBadge(state = analysis?.signalState ?: "WAIT")
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, CardBorder)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("INSTRUMENT", fontSize = 9.sp, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, color = TextSecondary)
                        Spacer(Modifier.weight(1f))
                        Text("Selected: $selectedTimeframe", fontSize = 10.sp, color = ElectricCyan,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("MARKET CATEGORY", fontSize = 9.sp, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, color = TextSecondary)
                        Spacer(Modifier.weight(1f))
                        Text("ALL INSTRUMENTS AVAILABLE BELOW", fontSize = 8.sp, color = TextMuted)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(categories) { category ->
                            FilterChip(
                                selected = selectedAssetCategory == category,
                                onClick = { selectedAssetCategory = category },
                                label = { Text(category, fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan, selectedLabelColor = DarkBackground,
                                    containerColor = DarkSurfaceVariant, labelColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(
                                    "market_category_" + category.lowercase().replace(" ", "_")
                                )
                            )
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(symbols) { sym ->
                            FilterChip(
                                selected = activeSymbol == sym,
                                onClick = { viewModel.selectTicker(sym) },
                                label = { Text(sym, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan, selectedLabelColor = DarkBackground,
                                    containerColor = DarkSurfaceVariant, labelColor = TextPrimary
                                ),
                                modifier = Modifier.testTag("symbol_chip_$sym")
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("STRATEGY", fontSize = 9.sp, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, color = TextSecondary)
                        Spacer(Modifier.weight(1f))
                        Box {
                            TextButton(
                                onClick = { strategyMenuExpanded = true },
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("analyzer_strategy_selector")
                            ) {
                                Text(
                                    activeStrategyName,
                                    fontSize = 10.sp,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Select strategy",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = strategyMenuExpanded,
                                onDismissRequest = { strategyMenuExpanded = false }
                            ) {
                                strategies.forEach { strategy ->
                                    DropdownMenuItem(
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(strategy.name, fontWeight = FontWeight.Bold)
                                                Text(
                                                    strategy.rulesSummary,
                                                    fontSize = 10.sp,
                                                    color = TextSecondary,
                                                    maxLines = 2
                                                )
                                            }
                                        },
                                        onClick = {
                                            strategyMenuExpanded = false
                                            viewModel.selectStrategy(strategy.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TIMEFRAME", fontSize = 9.sp, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, color = TextSecondary)
                        Spacer(Modifier.weight(1f))
                        Text("M1 → W1", fontSize = 9.sp, color = TextMuted)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(timeframes) { tf ->
                            FilterChip(
                                selected = selectedTimeframe == tf,
                                onClick = { viewModel.selectTimeframe(tf) },
                                label = { Text(tf, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = if (selectedTimeframe == tf) FontWeight.Black else FontWeight.Medium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan, selectedLabelColor = DarkBackground,
                                    containerColor = DarkSurfaceVariant, labelColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("tf_chip_$tf")
                            )
                        }
                    }
                    GoldGradientButton(
                        onClick = { viewModel.runSignalScan(activeSymbol, selectedTimeframe, selectedMtfTimeframes) },
                        enabled = !isScanning,
                        modifier = Modifier.fillMaxWidth().testTag("analyze_button")
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = DarkBackground)
                            Spacer(Modifier.width(9.dp))
                            Text(scanStage.ifBlank { "ANALYZING $activeSymbol..." }, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, maxLines = 1)
                        } else {
                            Icon(Icons.Default.QueryStats, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("RUN LIVE ANALYSIS", fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.refreshMarketData(activeSymbol, selectedTimeframe) },
                        modifier = Modifier.fillMaxWidth().height(40.dp).testTag("refresh_market_data"),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricCyan),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("REFRESH MARKET DATA", fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = onExportAnalysis,
                        modifier = Modifier.fillMaxWidth().height(42.dp).testTag("export_analysis_button"),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricCyan),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("EXPORT ANALYSIS", fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().height(470.dp).testTag("tradingview_chart_panel"),
                shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = DarkBackground),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().background(DarkSurface).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (useTradingViewChart) "TRADINGVIEW" else "NATIVE CANDLE CHART",
                                fontSize = 11.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box {
                                    TextButton(onClick = { chartSymbolMenuExpanded = true }, contentPadding = PaddingValues(0.dp)) {
                                        Text(activeSymbol, fontSize = 10.sp, color = TextPrimary, fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold)
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select chart symbol",
                                            tint = ElectricCyan, modifier = Modifier.size(17.dp))
                                    }
                                    DropdownMenu(expanded = chartSymbolMenuExpanded, onDismissRequest = { chartSymbolMenuExpanded = false }) {
                                        symbols.forEach { sym ->
                                            DropdownMenuItem(
                                                text = { Text(sym, fontFamily = FontFamily.Monospace) },
                                                onClick = { viewModel.selectTicker(sym); chartSymbolMenuExpanded = false }
                                            )
                                        }
                                    }
                                }
                                Text("•", fontSize = 10.sp, color = TextMuted)
                                Box {
                                    TextButton(onClick = { chartTimeframeMenuExpanded = true }, contentPadding = PaddingValues(0.dp)) {
                                        Text(selectedTimeframe, fontSize = 10.sp, color = ElectricCyan,
                                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select chart timeframe",
                                            tint = ElectricCyan, modifier = Modifier.size(17.dp))
                                    }
                                    DropdownMenu(expanded = chartTimeframeMenuExpanded, onDismissRequest = { chartTimeframeMenuExpanded = false }) {
                                        timeframes.forEach { tf ->
                                            DropdownMenuItem(
                                                text = { Text(tf, fontFamily = FontFamily.Monospace) },
                                                onClick = { viewModel.selectTimeframe(tf); chartTimeframeMenuExpanded = false }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { useTradingViewChart = !useTradingViewChart },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("chart_mode_toggle")
                            ) {
                                Text(if (useTradingViewChart) "NATIVE" else "TRADINGVIEW",
                                    color = ElectricCyan, fontSize = 8.sp, fontWeight = FontWeight.Black)
                            }
                            TextButton(
                                onClick = { viewModel.refreshMarketData(activeSymbol, selectedTimeframe) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh market data",
                                    tint = ElectricCyan, modifier = Modifier.size(15.dp))
                                Text("REFRESH", color = ElectricCyan, fontSize = 8.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                    TradingChartContainer(
                        activeSymbol = activeSymbol,
                        selectedTimeframe = selectedTimeframe,
                        currentSpotPrice = ticker?.takeIf {
                            com.example.data.market.classifyQuoteFreshness(it, System.currentTimeMillis()) ==
                                com.example.data.market.QuoteFreshness.LIVE
                        }?.price,
                        activeStrategy = selectedStrategyId,
                        dynamicAnalysis = analysis ?: DynamicAnalysisInfo(symbol = activeSymbol),
                        candles = viewModel.candlesForChart(activeSymbol, selectedTimeframe),
                        candleSource = viewModel.marketDataSource(activeSymbol, selectedTimeframe),
                        isRealTradingViewEnabled = useTradingViewChart,
                        onToggleChartType = { enabled -> useTradingViewChart = enabled },
                        onPriceUpdate = { sym, price -> viewModel.updatePriceFromChart(sym, price) },
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                    // Keep status inside the bounded chart column so it cannot overflow the
                    // fixed-height panel and compress/clip the WebView's available space.
                    Text(
                        text = marketDataStatus,
                        fontSize = 9.sp,
                        color = when {
                            marketDataStatus.startsWith("READY") -> ProfitGreen
                            marketDataStatus.startsWith("UNAVAILABLE") -> LossRed
                            else -> WarningAmber
                        },
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip(label = "QUOTE",
                    value = ticker?.price?.takeIf { it > 0.0 }?.let { formatPrice(it, activeSymbol) } ?: "—",
                    modifier = Modifier.weight(1f), color = TextPrimary)
                StatChip(label = "STRUCTURE", value = ticker?.bias ?: analysis?.direction ?: "UNKNOWN",
                    modifier = Modifier.weight(1f),
                    color = when {
                        (ticker?.bias ?: analysis?.direction.orEmpty()).contains("BULL", true) || analysis?.direction == "BUY" -> ProfitGreen
                        (ticker?.bias ?: analysis?.direction.orEmpty()).contains("BEAR", true) || analysis?.direction == "SELL" -> LossRed
                        else -> WarningAmber
                    })
                StatChip(label = "CONFLUENCE",
                    value = analysis?.confidencePct?.takeIf { it > 0 }?.let { "$it/100" } ?: "—",
                    modifier = Modifier.weight(1f), color = ElectricCyan)
            }
        }
        item {
            QuoteFreshnessIndicator(ticker, connecting = marketDataStatus.startsWith("WAIT"),
                diagnostic = quoteDiagnostics[activeSymbol])
        }
        item {
            val result = analysis
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface), border = BorderStroke(1.dp, CardBorder)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("TRADE PLAN", fontSize = 10.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                            Text("Validated levels only", fontSize = 10.sp, color = TextSecondary)
                        }
                        SignalBadge(state = result?.signalState ?: "WAIT")
                    }
                    if (isScanning) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = ElectricCyan, strokeWidth = 2.dp)
                                    Text("MARKET DATA VALIDATION IN PROGRESS", fontSize = 10.sp, fontWeight = FontWeight.Black, color = ElectricCyan, fontFamily = FontFamily.Monospace)
                                }
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ElectricCyan)
                                Text(scanStage.ifBlank { "Multi-pass market structure scan in progress..." }, fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text("Validating closed OHLC candles, market structure, liquidity, strategy evidence, timeframe alignment and risk gates. A trade plan is shown only when every required check passes.", fontSize = 9.sp, color = TextSecondary, lineHeight = 13.sp)
                            }
                        }
                    }
                    val effectiveEntry = result?.entry?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    val effectiveSl = if (result != null && result.stopLoss > 0.0) result.stopLoss else 0.0
                    val effectiveTp1 = if (result != null && result.tp1 > 0.0) result.tp1 else 0.0
                    val effectiveTp2 = result?.tp2 ?: 0.0
                    val effectiveTp3 = result?.tp3 ?: 0.0
                    val actualRr = result?.let {
                        validatedDisplayRewardRisk(it.signalState, it.direction, it.entry, it.stopLoss, it.tp1)
                    }
                    val hasValidatedPlan = actualRr != null

                    if (hasValidatedPlan) {
                        if (effectiveSl > 0.0 && effectiveTp1 > 0.0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlanLevel("ENTRY", formatPrice(effectiveEntry, activeSymbol), TextPrimary, Modifier.weight(1f))
                                PlanLevel("STOP LOSS", formatPrice(effectiveSl, activeSymbol), LossRed, Modifier.weight(1f))
                                PlanLevel("TP1", formatPrice(effectiveTp1, activeSymbol), ProfitGreen, Modifier.weight(1f))
                            }
                        }
                        if (effectiveTp2 > 0.0 || effectiveTp3 > 0.0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (effectiveTp2 > 0.0) {
                                    PlanLevel("TP2 (EXTENDED)", formatPrice(effectiveTp2, activeSymbol), ProfitGreen, Modifier.weight(1f))
                                }
                                if (effectiveTp3 > 0.0) {
                                    PlanLevel("TP3 (RUNWAY)", formatPrice(effectiveTp3, activeSymbol), ProfitGreen, Modifier.weight(1f))
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatChip(label = "REWARD / RISK", value = actualRr?.let { "1:${String.format(java.util.Locale.US, "%.2f", it)}" } ?: "N/A", modifier = Modifier.weight(1f), color = ProfitGreen)
                            StatChip(label = "CONFLUENCE", value = result?.confidencePct?.takeIf { it > 0 }?.let { "$it/100" } ?: "N/A", modifier = Modifier.weight(1f), color = ElectricCyan)
                        }
                        HorizontalDivider(color = CardBorder)
                        Text("Signal state: ${result?.signalState ?: "WAIT"} • Source: ${result?.dataSource?.takeIf { it.isNotBlank() } ?: "unavailable"}", fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                        result?.slReason?.takeIf { it.isNotBlank() }?.let { Text("Stop rationale: $it", fontSize = 10.sp, color = TextSecondary) }
                        result?.tpReason?.takeIf { it.isNotBlank() }?.let { Text("Target rationale: $it", fontSize = 10.sp, color = TextSecondary) }
                    } else {
                        Text(result?.blockReasons?.firstOrNull() ?: result?.smcAnalysis?.takeIf { it.isNotBlank() }
                            ?: "WAIT — no validated setup is currently available for this symbol and timeframe.",
                            fontSize = 11.sp, color = WarningAmber, lineHeight = 17.sp)
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface), border = BorderStroke(1.dp, CardBorder)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MULTI-TIMEFRAME MAP", fontSize = 10.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(listOf("M5", "M15", "M30", "H1", "H4", "D1", "W1")) { tf ->
                            val enabled = tf in selectedMtfTimeframes
                            val bias = if (!enabled) "OFF" else if (tf == selectedTimeframe) {
                                when (analysis?.direction?.uppercase()) {
                                    "BUY", "LONG", "BULLISH", "STRONG_BUY", "WEAK_BUY" -> "BULLISH"
                                    "SELL", "SHORT", "BEARISH", "STRONG_SELL", "WEAK_SELL" -> "BEARISH"
                                    "WAIT" -> "WAIT"
                                    else -> "N/A"
                                }
                            } else analysis?.getTfBias(tf) ?: "N/A"
                            val tone = when (bias) {
                                "BULLISH" -> ProfitGreen
                                "BEARISH" -> LossRed
                                "NEUTRAL" -> TextSecondary
                                "OFF" -> TextMuted
                                else -> WarningAmber
                            }
                            Column(
                                modifier = Modifier.width(72.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (enabled) ElectricCyan.copy(alpha = 0.08f) else DarkSurfaceVariant)
                                    .border(1.dp, if (enabled) ElectricCyan.copy(alpha = 0.7f) else CardBorder, RoundedCornerShape(9.dp))
                                    .clickable { viewModel.toggleMtfTimeframe(tf) }
                                    .padding(vertical = 10.dp, horizontal = 6.dp)
                                    .testTag("mtf_toggle_" + tf),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(tf, fontSize = 10.sp, color = if (enabled) ElectricCyan else TextSecondary,
                                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
                                Text(bias, fontSize = 8.sp, color = tone, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                    Text("Tap timeframe cards to include/exclude them from MTF analysis. At least one context timeframe must remain enabled.",
                        fontSize = 9.sp, color = TextMuted, lineHeight = 14.sp)
                    Text(analysis?.mtfSummary?.takeIf { it.isNotBlank() } ?: "No multi-timeframe verdict available yet.",
                        fontSize = 11.sp, color = TextPrimary, lineHeight = 16.sp)
                }
            }
        }
        item { MarketStructureReport(analysis) }
        item { FullAnalysisReport(analysis) }
        item { PythonQuantitativeAnalysisPanel(analysis?.pythonAnalysis) }
        item { EngineInsightsPanel(viewModel) }
    }
}




@Composable
private fun MarketStructureReport(result: DynamicAnalysisInfo?) {
    InsightCard("MARKET STRUCTURE / KEY LEVELS") {
        if (result == null) {
            Text("Run live analysis to populate structure, order blocks and levels.", fontSize = 11.sp, color = TextSecondary)
        } else {
            Text(
                result.marketStructureNote.ifBlank { result.smcAnalysis.ifBlank { "No structural narrative was returned for this scan." } },
                fontSize = 11.sp, color = TextPrimary, lineHeight = 16.sp
            )
            HorizontalDivider(color = CardBorder)
            Text("ORDER BLOCKS", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black)
            if (result.orderBlocks.isEmpty()) {
                Text("No validated order blocks in the current analysis output.", fontSize = 10.sp, color = TextSecondary)
            } else {
                result.orderBlocks.take(8).forEach { block ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(block.type, color = if (block.type == "BULLISH") ProfitGreen else LossRed, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        Text(block.zone, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(block.strength, color = TextSecondary, fontSize = 9.sp)
                    }
                }
            }
            HorizontalDivider(color = CardBorder)
            Text("KEY LEVELS", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black)
            if (result.keyLevels.isEmpty()) {
                Text("No validated key levels returned.", fontSize = 10.sp, color = TextSecondary)
            } else {
                result.keyLevels.take(12).forEach { level ->
                    Text("• $level", color = TextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Wyckoff phase: ${result.wyckoffPhase}", color = TextSecondary, fontSize = 10.sp)
            Text("Elliott wave: ${result.elliottWave}", color = TextSecondary, fontSize = 10.sp)
            Text("Session bias: ${result.sessionBias.ifBlank { "Not supplied" }}", color = TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun FullAnalysisReport(result: DynamicAnalysisInfo?) {
    InsightCard("FULL ALGORITHMIC ANALYSIS REPORT") {
        if (result == null) {
            Text("No completed report yet. Run Live Analysis to populate every report field.", fontSize = 11.sp, color = TextSecondary)
            return@InsightCard
        }
        InsightLine("Instrument", result.symbol)
        InsightLine("Signal state", result.signalState.ifBlank { "UNKNOWN" })
        InsightLine("Direction", result.direction.ifBlank { "WAIT" })
        InsightLine("Confluence score (not a win probability)", result.confidencePct.takeIf { it > 0 }?.let { "$it/100" } ?: "Not scored")
        InsightLine("Data source", result.dataSource.ifBlank { "UNKNOWN" })
        InsightLine("Ruleset version", result.strategyVersion.ifBlank { "Not supplied" })
        InsightLine("MTF state", result.mtfStatus.ifBlank { "WAIT" })
        InsightLine("MTF summary", result.mtfSummary.ifBlank { "No summary returned" })
        InsightLine("Risk gate", result.riskNote.ifBlank { "No risk note returned" })
        HorizontalDivider(color = CardBorder)
        Text("SETUP RATIONALE", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black)
        Text(result.smcAnalysis.ifBlank { "No rationale returned for this scan." }, fontSize = 11.sp, color = TextPrimary, lineHeight = 16.sp)
        HorizontalDivider(color = CardBorder)
        Text("ENTRY / STOP / TARGET RATIONALE", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black)
        InsightLine("Entry reason", result.entryReason.ifBlank { "Not supplied" })
        InsightLine("Stop-loss reason", result.slReason.ifBlank { "Not supplied" })
        InsightLine("Take-profit reason", result.tpReason.ifBlank { "Not supplied" })
        HorizontalDivider(color = CardBorder)
        Text("DETERMINISTIC STRATEGY SCORES", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black)
        if (result.strategyScores.isEmpty()) {
            Text("No separate strategy scores returned for this scan.", fontSize = 10.sp, color = TextSecondary)
        } else {
            result.strategyScores.entries.sortedByDescending { it.value }.forEach { (name, score) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, color = TextPrimary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("$score / 100", color = ElectricCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        HorizontalDivider(color = CardBorder)
        Text("BLOCKERS / EXECUTION LIMITS", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black)
        if (result.blockReasons.isEmpty()) {
            Text(
                "No explicit blocker strings were supplied. Execution still depends on live market health, broker authentication and RiskEngine validation.",
                color = TextSecondary, fontSize = 10.sp, lineHeight = 15.sp
            )
        } else {
            result.blockReasons.forEach { reason ->
                Text("• $reason", color = if (reason.contains("invalid", true) || reason.contains("unavailable", true)) LossRed else WarningAmber,
                    fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}


@Composable
private fun PlanLevel(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(RoundedCornerShape(9.dp)).background(DarkSurfaceVariant).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(value, fontSize = 12.sp, color = valueColor, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun StrategiesTab(viewModel: DestinedFXViewModel) {
    val selectedStrategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    StrategyWorkbench(
        selectedStrategyId = selectedStrategyId,
        onSelect = viewModel::selectStrategy,
        onOpenAnalyzer = { viewModel.selectScreen(DestinedFXScreen.Analyzer) },
        backtestContent = { BacktestSection(viewModel) }
    )
}

@Composable
internal fun StrategyWorkbench(
    selectedStrategyId: String,
    onSelect: (String) -> Unit,
    onOpenAnalyzer: () -> Unit,
    backtestContent: @Composable () -> Unit = {}
) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }
    val strategies = StrategyRegistry.allStrategies
    val selected = strategies.firstOrNull { it.id == selectedStrategyId }
    val visible = strategies.filter { strategy ->
        val matchesSearch = search.isBlank() || strategy.name.contains(search, true) ||
            strategy.id.contains(search, true) || strategy.description.contains(search, true)
        val matchesFilter = when (filter) {
            "BOT READY" -> strategy.botExecutionEligible
            "PYTHON CANDIDATES" -> strategy.pythonStatus == com.destinedfx.core.strategy.StrategyCapabilityStatus.CANDIDATE_ONLY
            else -> true
        }
        matchesSearch && matchesFilter
    }

    LazyColumn(modifier = Modifier.fillMaxSize().background(DarkBackground).testTag("strategy_list"),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            TerminalPageHeader(
                eyebrow = "RULESET REGISTRY · EXECUTION PARITY",
                title = "Strategy workbench",
                subtitle = "Python outputs are candidates only. Bot routing requires a Kotlin strategy and final validation."
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip(label = "KOTLIN READY", value = strategies.count { it.kotlinStatus == com.destinedfx.core.strategy.StrategyCapabilityStatus.IMPLEMENTED && it.kotlinFinalValidation }.toString(), modifier = Modifier.weight(1f), color = ProfitGreen)
                StatChip(label = "PY CANDIDATES", value = strategies.count { it.pythonVersion != null }.toString(),
                    modifier = Modifier.weight(1f), color = ProfitGreen)
                StatChip(label = "BOT READY", value = strategies.count { it.botExecutionEligible }.toString(),
                    modifier = Modifier.weight(1f), color = ElectricCyan)
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("CURRENT ANALYZER RULESET", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Text(selected?.name ?: "No registered strategy selected", fontSize = 18.sp,
                        color = TextPrimary, fontWeight = FontWeight.Black)
                    if (selected != null) {
                        Text(selected.id, modifier = Modifier.testTag("strategy_selected_id"),
                            fontSize = 9.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                    }
                    Text(selected?.description ?: "Choose a Kotlin-implemented ruleset below.", fontSize = 11.sp,
                        color = TextSecondary, lineHeight = 17.sp)
                    if (selected != null) {
                        Text(selected.rulesSummary, fontSize = 10.sp, color = ElectricCyan,
                            fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                        CapabilityLine("KOTLIN", capabilityLabel(selected.kotlinStatus, selected.kotlinFinalValidation), selected.kotlinStatus == com.destinedfx.core.strategy.StrategyCapabilityStatus.IMPLEMENTED && selected.kotlinFinalValidation)
                        CapabilityLine("PYTHON", if (selected.pythonVersion == null) "NOT IMPLEMENTED" else "CANDIDATE ONLY · ${selected.pythonVersion}", false)
                        CapabilityLine("BOT ROUTING", if (selected.botExecutionEligible) "ELIGIBLE · FINAL VALIDATION REQUIRED" else "NOT ELIGIBLE", selected.botExecutionEligible)
                        Text("REQUIRED EVIDENCE", fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, letterSpacing = .7.sp)
                        selected.requiredEvidence.forEach { evidence ->
                            Text("• $evidence", fontSize = 10.sp, color = TextPrimary, lineHeight = 15.sp)
                        }
                        Text("COVERAGE: ${selected.testCoverage.joinToString(" · ")}", fontSize = 9.sp,
                            color = TextMuted, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                    }
                    Button(onClick = onOpenAnalyzer,
                        modifier = Modifier.fillMaxWidth().height(42.dp).testTag("strategy_open_analyzer"), enabled = selected?.isImplemented == true,
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)) {
                        Text(if (selected?.isImplemented == true) "OPEN ANALYZER" else "KOTLIN PATH REQUIRED", fontSize = 10.sp,
                            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().testTag("strategy_search"),
                singleLine = true,
                label = { Text("Find a strategy") },
                placeholder = { Text("Name, rule ID or evidence") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = CardBorder,
                    focusedLabelColor = ElectricCyan,
                    cursorColor = ElectricCyan
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "BOT READY", "PYTHON CANDIDATES").forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        modifier = Modifier.weight(1f).testTag("strategy_filter_${option.lowercase().replace(' ', '_')}"),
                        label = { Text(option, fontSize = 8.sp, maxLines = 1) }
                    )
                }
            }
            Text("${visible.size} OF ${strategies.size} RULESETS", fontSize = 9.sp, color = TextSecondary,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = .8.sp)
        }
        items(visible, key = { it.id }) { strat ->
            val active = strat.id == selectedStrategyId
            val ready = strat.kotlinStatus == com.destinedfx.core.strategy.StrategyCapabilityStatus.IMPLEMENTED && strat.kotlinFinalValidation
            Card(modifier = Modifier.fillMaxWidth().testTag("strategy_card_${strat.id}")
                .clickable(enabled = ready) { onSelect(strat.id) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = if (active) DarkSurfaceVariant else DarkSurface),
                border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) ElectricCyan else CardBorder)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(strat.name, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                            Text(strat.id, fontSize = 9.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                        }
                        if (active) Text("ANALYZER", fontSize = 8.sp, color = ElectricCyan,
                            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    }
                    Text(strat.description, fontSize = 10.sp, color = TextSecondary, lineHeight = 15.sp)
                    Text(strat.rulesSummary, fontSize = 9.sp, color = if (ready) ElectricCyan else TextMuted,
                        fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                    CapabilityLine("KOTLIN", capabilityLabel(strat.kotlinStatus, strat.kotlinFinalValidation), ready)
                    CapabilityLine("PYTHON", if (strat.pythonVersion == null) "NOT IMPLEMENTED" else "CANDIDATE ONLY", false)
                    CapabilityLine("BOT", if (strat.botExecutionEligible) "ELIGIBLE · VALIDATION REQUIRED" else "NOT ELIGIBLE", strat.botExecutionEligible)
                    Text("EVIDENCE: ${strat.requiredEvidence.joinToString(" · ")}", fontSize = 9.sp,
                        color = TextSecondary, lineHeight = 14.sp)
                }
            }
        }
        if (visible.isEmpty()) item { Text("No strategy matches this search/filter.", color = TextSecondary, fontSize = 11.sp) }
        item { backtestContent() }
    }
}

@Composable
private fun CapabilityLine(label: String, value: String, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, modifier = Modifier.width(66.dp), fontSize = 8.sp, color = TextSecondary,
            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text(value, modifier = Modifier.weight(1f), fontSize = 8.sp,
            color = if (enabled) ProfitGreen else WarningAmber,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, lineHeight = 12.sp)
    }
}

private fun capabilityLabel(status: com.destinedfx.core.strategy.StrategyCapabilityStatus, finalValidation: Boolean): String = when {
    status == com.destinedfx.core.strategy.StrategyCapabilityStatus.IMPLEMENTED && finalValidation -> "IMPLEMENTED · FINAL VALIDATION PATH"
    status == com.destinedfx.core.strategy.StrategyCapabilityStatus.CANDIDATE_ONLY -> "CANDIDATE ONLY · NOT EXECUTABLE"
    else -> "NOT IMPLEMENTED · NOT EXECUTABLE"
}

@Composable
fun RiskManagementTab(viewModel: DestinedFXViewModel) {
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val riskPercent by viewModel.riskPercent.collectAsStateWithLifecycle()

    LazyColumn(modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TerminalPageHeader(
                eyebrow = "ACCOUNT DEFENCE",
                title = "Risk control",
                subtitle = "Sizing and plan validation remain subject to RiskEngine."
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface), border = BorderStroke(1.dp, CardBorder)) {
                Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("CURRENT CONFIGURATION", fontSize = 9.sp, color = TextSecondary,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            Text("${riskPercent.coerceAtMost(1.0)}% risk configured", fontSize = 18.sp,
                                color = if (riskPercent <= 1.0) ProfitGreen else LossRed, fontWeight = FontWeight.Black)
                        }
                        Box(Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (riskPercent <= 1.0) ProfitGreen.copy(alpha = 0.12f) else LossRed.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 7.dp)) {
                            Text(if (riskPercent <= 1.0) "WITHIN CAP" else "BREACH", fontSize = 9.sp,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                                color = if (riskPercent <= 1.0) ProfitGreen else LossRed)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(5.dp)).background(DarkSurfaceVariant)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(riskPercent.coerceIn(0.0, 1.0).toFloat())
                            .clip(RoundedCornerShape(5.dp)).background(if (riskPercent <= 1.0) ElectricCyan else LossRed))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StatChip(label = "MAX PER TRADE", value = "1.0%", modifier = Modifier.weight(1f), color = WarningAmber)
                        StatChip(label = "MIN PLAN RR", value = "2.0R", modifier = Modifier.weight(1f), color = ProfitGreen)
                    }
                    HorizontalDivider(color = CardBorder)
                    Text("Hard limits", fontSize = 12.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                    Text("• Position sizing is calculated against configured equity.\n• Stops are not narrowed to force a valid trade.\n• Plans below 2.0R must remain WAIT / non-executable.\n• An invalidated setup cannot remain actionable.",
                        fontSize = 11.sp, color = TextSecondary, lineHeight = 18.sp)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.25f))) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("POSITION SIZING CALCULATOR", fontSize = 10.sp, color = ElectricCyan,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Text("Instrument: $activeSymbol", fontSize = 11.sp, color = TextSecondary)
                    RiskRewardCalculatorComponent(viewModel = viewModel, symbol = activeSymbol, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun SettingsTab(viewModel: DestinedFXViewModel) {

    var savedMessage by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                            .background(ElectricCyan.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(24.dp))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("TERMINAL CONTROL", fontSize = 9.sp, color = ElectricCyan,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.1.sp)
                            Text("Settings", fontSize = 25.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                            Text("Feed status and notifications", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                    HorizontalDivider(color = CardBorder)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(DarkSurfaceVariant).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("CHART ACCESS", fontSize = 8.sp, color = TextSecondary,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            Text("TradingView", fontSize = 12.sp, color = ProfitGreen, fontWeight = FontWeight.Black)
                            Text("No market-data key needed", fontSize = 9.sp, color = TextSecondary)
                        }
                    }
                    HorizontalDivider(color = CardBorder, modifier = Modifier.padding(vertical = 4.dp))

                    val scanningActive by viewModel.isBackgroundScanningActive.collectAsStateWithLifecycle()
                    val scanStatus by viewModel.backgroundScanStatus.collectAsStateWithLifecycle()

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.QueryStats, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("BACKGROUND MARKET SCANNER", fontSize = 10.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp)
                            Text("Multi-pair scan using TradingView feeds.", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("24/7 Background Market Scanner", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(if (scanningActive) "ACTIVE" else "PAUSED",
                                    fontSize = 9.sp, fontWeight = FontWeight.Black,
                                    color = if (scanningActive) ProfitGreen else WarningAmber,
                                    fontFamily = FontFamily.Monospace)
                            }
                            Text(scanStatus, fontSize = 10.sp, color = TextSecondary)
                            HorizontalDivider(color = CardBorder)
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("High-Confluence Alert Threshold", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("85/100 SCORE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = ElectricCyan, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider(color = TabBorder)
            Spacer(modifier = Modifier.height(8.dp))
            Text("TELEGRAM ALERTS", fontWeight = FontWeight.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = CyanPrimary)
            Text("Connection, delivery test and alert controls.", fontSize = 10.sp, color = TextSecondary)
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
                                    "Connected" -> ProfitGreen
                                    "Connection failed" -> LossRed
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
                        singleLine = true,
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    viewModel.autoDetectTelegramChatId(botTokenInput) { detected ->
                                        if (detected != null) {
                                            chatIdInput = detected
                                        }
                                    }
                                },
                                enabled = !isTelegramLoading && botTokenInput.isNotBlank()
                            ) {
                                Text("Auto-Detect", fontSize = 11.sp, color = CyanPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                    Text(
                        text = "Tip: Send /start to your bot in Telegram, then tap Auto-Detect to fetch your numeric Chat ID instantly without third-party lookup tools.",
                        fontSize = 10.sp,
                        color = TextSecondary,
                        lineHeight = 14.sp
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
                            colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen),
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
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = LossRed),
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
                        Text(res, fontSize = 11.sp, color = if (telegramStatus == "Connected") ProfitGreen else LossRed)
                    }
                }
            }
        }
        
        // Telegram Settings ... (will include here if needed, keeping it minimal as requested)
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
                        Text("${String.format(java.util.Locale.US, "%.1f", currentReport.winRate)}%", fontWeight = FontWeight.Bold, color = ProfitGreen)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Profit Factor", fontSize = 10.sp, color = TextSecondary)
                        Text(String.format(java.util.Locale.US, "%.2f", currentReport.profitFactor), fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Max Drawdown", fontSize = 10.sp, color = TextSecondary)
                        Text("${String.format(java.util.Locale.US, "%.1f", currentReport.maxDrawdown)}%", fontWeight = FontWeight.Bold, color = LossRed)
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Net Profit:", fontSize = 11.sp, color = TextSecondary)
                    Text("${String.format(java.util.Locale.US, "%.2f", currentReport.netProfitPercent)}%", fontWeight = FontWeight.Black, color = ProfitGreen, fontFamily = FontFamily.Monospace)
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
    val context = LocalContext.current
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
                    onClick = {
                        (context as? com.example.MainActivity)?.showBiometricPrompt()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Unlock Terminal", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


@Composable
private fun WatchlistTab(viewModel: DestinedFXViewModel) {
    val tickerMap by viewModel.tickers.collectAsStateWithLifecycle()
    val rows = remember(tickerMap) { tickerMap.values.sortedByDescending { it.lastUpdateMillis } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("MARKET TRACKER", fontSize = 9.sp, color = ElectricCyan,
                    fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Text("Watchlist", fontSize = 26.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                Text("Live quotes received by this device. Tap an instrument to open its chart and analysis.",
                    fontSize = 11.sp, color = TextSecondary)
            }
        }
        if (rows.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, CardBorder),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuoteFreshnessIndicator(null, connecting = true)
                        Text("Waiting for market quotes", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text("No live ticker is available yet. The list populates when the public feed provides valid prices.",
                            color = TextSecondary, fontSize = 11.sp)
                        Button(onClick = { viewModel.selectScreen(DestinedFXScreen.Analyzer) }) {
                            Text("OPEN ANALYZER")
                        }
                    }
                }
            }
        } else {
            items(rows, key = { it.symbol }) { ticker ->
                val change = ticker.changePercent
                val movementColor = if ((change ?: 0.0) >= 0.0) ProfitGreen else LossRed
                val isFresh = com.example.data.market.classifyQuoteFreshness(
                    ticker, System.currentTimeMillis()
                ) == com.example.data.market.QuoteFreshness.LIVE
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.selectTicker(ticker.symbol)
                        viewModel.selectScreen(DestinedFXScreen.Analyzer)
                    },
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, if (isFresh) CardBorder else WarningAmber.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                .background(ElectricCyan.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ShowChart, contentDescription = null,
                                tint = ElectricCyan, modifier = Modifier.size(21.dp))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(ticker.symbol, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            QuoteFreshnessIndicator(ticker, compact = true)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                if (ticker.price.isFinite() && ticker.price > 0.0) formatPrice(ticker.price, ticker.symbol) else "—",
                                color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp
                            )
                            Text(
                                change?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "CHANGE —",
                                color = movementColor, fontWeight = FontWeight.Bold, fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreTab(viewModel: DestinedFXViewModel) {
    val routes = DestinedFXScreen.moreScreens
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DESTINEDFX TOOLS", fontSize = 9.sp, color = ElectricCyan,
                    fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Text("More", fontSize = 26.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                Text("Secondary workspaces and terminal settings.", fontSize = 11.sp, color = TextSecondary)
            }
        }
        items(routes, key = { it.tag }) { screen ->
            val description = when (screen) {
                DestinedFXScreen.ImageAnalyzer -> "Inspect chart screenshots with on-device image evidence."
                DestinedFXScreen.Bot -> "Configure a broker connection, risk gates and automated scanning."
                DestinedFXScreen.Watchlist -> "Follow available market quotes and jump into analysis."
                DestinedFXScreen.Risk -> "Review account sizing, stop-loss and exposure constraints."
                DestinedFXScreen.Settings -> "Manage account, broker and notification preferences."
                DestinedFXScreen.About -> "Learn how analysis works and review the full risk disclosure."
                DestinedFXScreen.Contact -> "Contact the support address published on the website."
                else -> "Open this DestinedFX workspace."
            }
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.selectScreen(screen) }
                    .testTag(screen.tag),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, CardBorder),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                            .background(ElectricCyan.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(screen.icon, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(22.dp))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(screen.title, fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text(description, fontSize = 10.sp, color = TextSecondary, lineHeight = 14.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = "Open ${screen.title}",
                        tint = TextSecondary, modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}


@Composable
private fun PythonQuantitativeAnalysisPanel(result: com.example.domain.model.PythonAnalysisReport?) {
    InsightCard("ON-DEVICE PYTHON QUANTITATIVE ANALYSIS") {
        Text("Execution state: ANALYSIS ONLY — Python output is never execution approval.",
            fontSize = 10.sp, color = WarningAmber, lineHeight = 15.sp)
        val outcome = when {
            result == null -> "PYTHON RUNTIME UNAVAILABLE"
            result.conflict || result.state == "KOTLIN_PYTHON_CONFLICT" -> "KOTLIN/PYTHON CONFLICT"
            result.state == "CANDIDATE" -> "PYTHON CANDIDATE"
            result.state == "WAITING" -> "PYTHON WAITING"
            result.state == "DATA_UNAVAILABLE" -> "PYTHON DATA UNAVAILABLE"
            !result.runtimeAvailable -> "PYTHON RUNTIME UNAVAILABLE"
            else -> "PYTHON RUNTIME UNAVAILABLE"
        }
        Text(outcome, fontSize = 11.sp,
            color = if (outcome.contains("UNAVAILABLE") || outcome.contains("CONFLICT")) LossRed else ElectricCyan,
            fontWeight = FontWeight.Black)
        if (result == null) {
            Text("No Python result was returned for this scan. The Kotlin SignalEngine is the identified analysis engine.",
                fontSize = 10.sp, color = TextSecondary, lineHeight = 15.sp)
            return@InsightCard
        }
        InsightLine("Runtime availability", when {
            result.runtimeAvailable -> "Chaquopy result received"
            result.state == "DATA_UNAVAILABLE" -> "Not invoked or no valid Python response; data/provenance blocked analysis"
            else -> "Unavailable; no Python result used"
        })
        InsightLine("Strategy", result.strategyId)
        InsightLine("Ruleset version", result.strategyVersion)
        InsightLine("Python state / direction", "${result.state} / ${result.direction}")
        InsightLine("Provider source", result.source.ifBlank { "UNKNOWN" })
        InsightLine("Closed-candle timestamp", if (result.candleTimestampMs > 0L)
            java.time.Instant.ofEpochMilli(result.candleTimestampMs).toString() else "Unavailable")
        InsightLine("Snapshot retrieved at", if (result.retrievedAtMs > 0L)
            java.time.Instant.ofEpochMilli(result.retrievedAtMs).toString() else "Unavailable")
        Text("EVIDENCE", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black)
        Text(result.evidence.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "No supporting Python evidence returned.",
            fontSize = 10.sp, color = TextPrimary, lineHeight = 15.sp)
        if (result.scoreBasis.isNotEmpty()) InsightLine("Score basis", result.scoreBasis.joinToString(" · "))
        Text("REJECTION / WAITING REASONS", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black)
        Text(result.rejectionReasons.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: result.reason.ifBlank { "None returned." },
            fontSize = 10.sp, color = TextSecondary, lineHeight = 15.sp)
        if (result.state == "CANDIDATE" && result.entry != null && result.stopLoss != null && result.takeProfit != null) {
            HorizontalDivider(color = CardBorder)
            Text("RESEARCH CANDIDATE ONLY — NOT AN APPROVED TRADE", fontSize = 9.sp, color = WarningAmber, fontWeight = FontWeight.Black)
            InsightLine("Entry / stop / target", "${formatPrice(result.entry, result.symbol)} / ${formatPrice(result.stopLoss, result.symbol)} / ${formatPrice(result.takeProfit, result.symbol)}")
            InsightLine("Reward-to-risk proposal", result.rewardToRisk?.let { "${"%.2f".format(java.util.Locale.US, it)}R" } ?: "Not supplied")
        }
        if (result.conflict) Text("Automated execution is blocked until the configured engine-agreement policy resolves this conflict.",
            fontSize = 10.sp, color = LossRed, lineHeight = 15.sp)
    }
}
