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
    val marketDataStatus by viewModel.marketDataStatus.collectAsStateWithLifecycle()
    val currentTicker = tickers[activeSymbol]
    val hasFreshQuote = currentTicker?.price?.let { it.isFinite() && it > 0.0 } == true &&
        viewModel.isDataFresh(activeSymbol)
    val headerPrice = currentTicker?.price?.takeIf { it.isFinite() && it > 0.0 && hasFreshQuote }
    val priceChange = currentTicker?.changePercent
    val feedStatusColor = when {
        hasFreshQuote && marketDataStatus.startsWith("READY", ignoreCase = true) -> ProfitGreen
        marketDataStatus.startsWith("UNAVAILABLE", ignoreCase = true) -> LossRed
        else -> WarningAmber
    }
    val feedStatusLabel = when {
        hasFreshQuote && marketDataStatus.startsWith("READY", ignoreCase = true) -> "Live market data"
        marketDataStatus.startsWith("UNAVAILABLE", ignoreCase = true) -> "Feed unavailable"
        hasFreshQuote -> "Live quote • candles pending"
        marketDataStatus.startsWith("READY", ignoreCase = true) -> "Candles ready • quote missing"
        else -> "Checking live quote"
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
                        .padding(horizontal = 14.dp, vertical = 9.dp),
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
                                    text = "TRADINGVIEW",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = ElectricCyan,
                                    letterSpacing = 0.4.sp
                                )
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
            Surface(
                color = DarkSurface,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .border(BorderStroke(1.dp, CardBorder.copy(alpha = 0.8f)))
            ) {
                // Primary website navigation stays visible on mobile; secondary tools live under More.
                val moreDestinations = DestinedFXScreen.moreScreens
                val bottomSelectedScreen = if (
                    selectedScreen == DestinedFXScreen.More || selectedScreen in moreDestinations
                ) DestinedFXScreen.More else selectedScreen
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
                                .background(if (isSelected) ElectricCyan.copy(alpha = 0.13f) else Color.Transparent)
                                .clickable { viewModel.selectScreen(screen) }
                                .testTag(screen.tag)
                                .padding(horizontal = 2.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                screen.icon,
                                contentDescription = screen.title,
                                tint = if (isSelected) ElectricCyan else TextSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                            Text(
                                text = screen.title,
                                maxLines = 1,
                                fontSize = 9.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                                color = if (isSelected) ElectricCyan else TextSecondary
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
    val analysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
    val isScanning by viewModel.isSignalScanning.collectAsStateWithLifecycle()
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

    LaunchedEffect(selectedAssetCategory) {
        if (activeSymbol !in symbols) {
            symbols.firstOrNull()?.let { viewModel.selectTicker(it) }
        }
    }

    LaunchedEffect(activeSymbol, selectedTimeframe, selectedStrategyId, selectedMtfTimeframes) {
        viewModel.runSignalScan(activeSymbol, selectedTimeframe, selectedMtfTimeframes)

        // A cold start can complete the first scan before the public feed finishes connecting.
        // Recheck only source/data failures after the prior scan has ended; never loop on a
        // legitimate WAIT/NEWS/RISK result and never cancel a scan that is still running.
        var retries = 0
        while (retries < 3) {
            while (viewModel.isSignalScanning.value) delay(250L)
            val lastSignal = viewModel.currentTradeSignal.value
            val retryable = lastSignal == null ||
                lastSignal.signalState in setOf("DATA_UNAVAILABLE", "SOURCE_ERROR", "DISCONNECTED", "GAP_DETECTED")
            if (!retryable) break
            delay(5_000L)
            viewModel.runSignalScan(activeSymbol, selectedTimeframe, selectedMtfTimeframes)
            retries++
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("LIVE ALGORITHMIC ANALYZER", fontSize = 9.sp, color = ElectricCyan,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Text(activeSymbol, fontSize = 25.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                    Text("Chart display: TradingView • Analysis: algorithmic rules", fontSize = 10.sp, color = TextSecondary)
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
                            Text("ANALYZING $activeSymbol", fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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
                            Text("TRADINGVIEW", fontSize = 11.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
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
                        TextButton(
                            onClick = { viewModel.refreshMarketData(activeSymbol, selectedTimeframe) },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh market data",
                                tint = ElectricCyan, modifier = Modifier.size(15.dp))
                            Text("REFRESH", color = ElectricCyan, fontSize = 8.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    TradingChartContainer(
                        activeSymbol = activeSymbol,
                        selectedTimeframe = selectedTimeframe,
                        currentSpotPrice = ticker?.price ?: 0.0,
                        activeStrategy = selectedStrategyId,
                        dynamicAnalysis = analysis ?: DynamicAnalysisInfo(symbol = activeSymbol),
                        candles = viewModel.candlestickHistoryByTimeframe[activeSymbol + "-" + selectedTimeframe]
                            ?: viewModel.candlestickHistory[activeSymbol] ?: emptyList(),
                        isRealTradingViewEnabled = true,
                        onToggleChartType = {},
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
                StatChip(label = "CONFIDENCE",
                    value = analysis?.confidencePct?.takeIf { it > 0 }?.let { "$it%" } ?: "—",
                    modifier = Modifier.weight(1f), color = ElectricCyan)
            }
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
                    if (isScanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ElectricCyan)
                    val canDisplayTrade = result != null && result.entry != 0.0 &&
                        listOf("STRONG_BUY", "BUY", "WEAK_BUY", "STRONG_SELL", "SELL", "WEAK_SELL").contains(result.signalState)
                    if (canDisplayTrade && result != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlanLevel("ENTRY", formatPrice(result.entry, activeSymbol), TextPrimary, Modifier.weight(1f))
                            PlanLevel("STOP LOSS", formatPrice(result.stopLoss, activeSymbol), LossRed, Modifier.weight(1f))
                            PlanLevel("TP1", formatPrice(result.tp1, activeSymbol), ProfitGreen, Modifier.weight(1f))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatChip(label = "REWARD / RISK", value = result.tp1RR, modifier = Modifier.weight(1f), color = ProfitGreen)
                            StatChip(label = "STRATEGY", value = result.strategyVersion.ifBlank { selectedStrategyId },
                                modifier = Modifier.weight(1f), color = ElectricCyan)
                        }
                        HorizontalDivider(color = CardBorder)
                        Text("Stop rationale: ${result.slReason.ifBlank { "Not supplied" }}", fontSize = 10.sp, color = TextSecondary)
                        Text("Target rationale: ${result.tpReason.ifBlank { "Not supplied" }}", fontSize = 10.sp, color = TextSecondary)
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
        InsightLine("Confidence", result.confidencePct.takeIf { it > 0 }?.let { "${it}%" } ?: "Not scored")
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

private val selectedIdVersion = "smc-confluence-1.0.0"

@Composable
fun StrategiesTab(viewModel: DestinedFXViewModel) {
    val selectedStrategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    val strategies = StrategyRegistry.allStrategies
    val selected = strategies.firstOrNull { it.id == selectedStrategyId }

    LazyColumn(modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TerminalPageHeader(
                eyebrow = "SYSTEMATIC PLAYBOOKS / RULE-BASED",
                title = "Strategy desk",
                subtitle = "Choose a registered ruleset. Only implemented strategies can be activated."
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip(label = "REGISTERED", value = strategies.size.toString(), modifier = Modifier.weight(1f))
                StatChip(label = "IMPLEMENTED", value = strategies.count { it.isImplemented }.toString(),
                    modifier = Modifier.weight(1f), color = ProfitGreen)
                StatChip(label = "SELECTED", value = if (selected != null) "1" else "0",
                    modifier = Modifier.weight(1f), color = ElectricCyan)
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))) {
                Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ACTIVE RULESET", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Text(selected?.name ?: "No registered strategy selected", fontSize = 18.sp,
                        color = TextPrimary, fontWeight = FontWeight.Black)
                    Text(selected?.description ?: "Choose an implemented ruleset below.", fontSize = 11.sp,
                        color = TextSecondary, lineHeight = 17.sp)
                    if (selected != null) {
                        Text(selected.rulesSummary, fontSize = 10.sp, color = ElectricCyan,
                            fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (selected.isImplemented) ProfitGreen else WarningAmber))
                            Text(if (selected.isImplemented) "IMPLEMENTED · ${selected.version}" else "REGISTERED BUT UNAVAILABLE",
                                fontSize = 9.sp, color = if (selected.isImplemented) ProfitGreen else WarningAmber,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Button(onClick = { viewModel.selectScreen(DestinedFXScreen.Analyzer) },
                        modifier = Modifier.fillMaxWidth().height(42.dp), enabled = selected?.isImplemented == true,
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)) {
                        Text("OPEN ANALYZER WITH SELECTED RULESET", fontSize = 10.sp,
                            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            Text("AVAILABLE RULESETS", fontSize = 10.sp, color = TextSecondary,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        }
        items(strategies) { strat ->
            val active = strat.id == selectedStrategyId
            Card(modifier = Modifier.fillMaxWidth().testTag("strategy_card_${strat.id}")
                .clickable(enabled = strat.isImplemented) { viewModel.selectStrategy(strat.id) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = if (active) DarkSurfaceVariant else DarkSurface),
                border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) ElectricCyan else CardBorder)) {
                Row(modifier = Modifier.padding(13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top) {
                    Box(modifier = Modifier.width(4.dp).height(68.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (active) ElectricCyan else if (strat.isImplemented) ProfitGreen.copy(alpha = 0.75f) else WarningAmber))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strat.name, modifier = Modifier.weight(1f), fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                            if (active) Text("ACTIVE", fontSize = 8.sp, color = ElectricCyan,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                        Text(strat.description, fontSize = 10.sp, color = TextSecondary, lineHeight = 15.sp)
                        Text(strat.rulesSummary, fontSize = 9.sp, color = if (strat.isImplemented) ElectricCyan else TextMuted,
                            fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                        Text(if (strat.isImplemented) "AVAILABLE · ${strat.version}" else "NOT IMPLEMENTED",
                            fontSize = 8.sp, color = if (strat.isImplemented) ProfitGreen else WarningAmber,
                            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item { BacktestSection(viewModel) }
    }
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
                eyebrow = "ACCOUNT DEFENCE / POSITION CONTROL",
                title = "Risk control",
                subtitle = "RiskEngine remains the authority for sizing and trade-plan validation."
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
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val secureStorageAvailable by viewModel.secureStorageAvailable.collectAsStateWithLifecycle()
    var supabaseUrlInput by remember { mutableStateOf(viewModel.getSupabaseUrl()) }
    var supabaseKeyInput by remember { mutableStateOf(viewModel.getSupabaseAnonKey()) }
    var derivTokenInput by remember { mutableStateOf(viewModel.getDerivApiToken()) }
    var derivAppIdInput by remember { mutableStateOf(viewModel.getDerivAppId()) }
    var metaApiTokenInput by remember { mutableStateOf(viewModel.getMetaApiToken()) }
    var metaApiAccountIdInput by remember { mutableStateOf(viewModel.getMetaApiAccountId()) }
    var savedMessage by remember { mutableStateOf("") }
    var derivConnectStatus by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                            .background(ElectricCyan.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(24.dp))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("TERMINAL CONTROL", fontSize = 9.sp, color = ElectricCyan,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.1.sp)
                            Text("Settings", fontSize = 25.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                            Text("Accounts, execution and notifications", fontSize = 10.sp, color = TextSecondary)
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
                        Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(DarkSurfaceVariant).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("BROKER EXECUTION", fontSize = 8.sp, color = TextSecondary,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            Text("Optional", fontSize = 12.sp, color = WarningAmber, fontWeight = FontWeight.Black)
                            Text("Connect only to trade", fontSize = 9.sp, color = TextSecondary)
                        }
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("CLOUD ACCOUNT", fontSize = 11.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp)
                            Text("Optional Supabase sign-in and cloud account features.", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                    OutlinedTextField(
                        value = supabaseUrlInput,
                        onValueChange = { supabaseUrlInput = it },
                        label = { Text("Supabase project URL") },
                        placeholder = { Text("https://your-project.supabase.co") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = supabaseKeyInput,
                        onValueChange = { supabaseKeyInput = it },
                        label = { Text("Supabase anon / publishable key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Text("Never put a Supabase service_role key in this application.", fontSize = 9.sp, color = TextMuted)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (viewModel.isPreview) DarkSurfaceVariant
                                else if (secureStorageAvailable) ProfitGreen.copy(alpha = 0.09f)
                                else LossRed.copy(alpha = 0.10f)
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Icon(
                            imageVector = if (viewModel.isPreview || secureStorageAvailable) Icons.Default.Lock else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (viewModel.isPreview) TextSecondary else if (secureStorageAvailable) ProfitGreen else LossRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = when {
                                    viewModel.isPreview -> "SECURE STORAGE: NOT CHECKED IN PREVIEW"
                                    secureStorageAvailable -> "SECURE STORAGE: AVAILABLE"
                                    else -> "SECURE STORAGE: UNAVAILABLE"
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = if (viewModel.isPreview) TextSecondary else if (secureStorageAvailable) ProfitGreen else LossRed
                            )
                            Text(
                                text = when {
                                    viewModel.isPreview -> "Preview does not verify device credential storage."
                                    secureStorageAvailable -> "Credentials use encrypted Android preferences."
                                    else -> "Credentials are memory-only during this run and may be lost when the app closes. Avoid reconnecting accounts until secure storage is restored."
                                },
                                fontSize = 9.sp,
                                color = TextSecondary,
                                lineHeight = 14.sp
                            )
                        }
                    }
                    HorizontalDivider(color = CardBorder)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("DERIV EXECUTION", fontSize = 11.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp)
                            Text("Broker credentials are for execution; they do not enable chart display.", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                    OutlinedTextField(
                        value = derivTokenInput,
                        onValueChange = { derivTokenInput = it },
                        label = { Text("Deriv API token") },
                        modifier = Modifier.fillMaxWidth().testTag("deriv_token_input"),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = derivAppIdInput,
                        onValueChange = { derivAppIdInput = it },
                        label = { Text("Registered Deriv API App ID") },
                        modifier = Modifier.fillMaxWidth().testTag("deriv_app_id_input"),
                        singleLine = true
                    )
                    HorizontalDivider(color = CardBorder)
                    Text("MT5 EXECUTION VIA METAAPI", fontSize = 10.sp, color = ElectricCyan,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = metaApiTokenInput, onValueChange = { metaApiTokenInput = it },
                        label = { Text("MetaAPI access token") },
                        modifier = Modifier.fillMaxWidth().testTag("settings_metaapi_token"),
                        singleLine = true, visualTransformation = PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = metaApiAccountIdInput, onValueChange = { metaApiAccountIdInput = it },
                        label = { Text("MetaAPI account ID") },
                        modifier = Modifier.fillMaxWidth().testTag("settings_metaapi_account_id"),
                        singleLine = true
                    )
                    Text("Recommended: use the Bot tab to enter MT5 Login, trading Password, Server and optional investor password. The MetaAPI bridge token is still required. Leave Account ID blank before linking a new MT5 account, or keep it for an already-linked account.",
                        fontSize = 9.sp, color = TextMuted, lineHeight = 14.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!viewModel.isPreview) {
                                    viewModel.app.secretsManager.saveSupabaseUrl(supabaseUrlInput.trim())
                                    viewModel.app.secretsManager.saveSupabaseAnonKey(supabaseKeyInput.trim())
                                    viewModel.app.secretsManager.saveDerivApiToken(derivTokenInput.trim())
                                    viewModel.app.secretsManager.saveDerivAppId(derivAppIdInput.trim())
                                    viewModel.app.secretsManager.saveMetaApiToken(metaApiTokenInput.trim())
                                    viewModel.app.secretsManager.saveMT5AccountNumber(metaApiAccountIdInput.trim())
                                }
                                savedMessage = when {
                                    viewModel.isPreview -> "Preview only — settings were not saved."
                                    secureStorageAvailable -> "Settings saved using encrypted local storage."
                                    else -> "WARNING: encrypted storage is unavailable. Values are memory-only and may be lost when the app closes."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground),
                            modifier = Modifier.weight(1f).testTag("save_credentials_button")
                        ) {
                            Text("SAVE SETTINGS", fontWeight = FontWeight.Black, fontSize = 10.sp)
                        }
                        Button(
                            onClick = {
                                viewModel.connectToDeriv(derivTokenInput, derivAppIdInput) { _, msg ->
                                    derivConnectStatus = msg
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen, contentColor = DarkBackground),
                            modifier = Modifier.weight(1f).testTag("connect_deriv_button")
                        ) {
                            Text("CONNECT DERIV", fontWeight = FontWeight.Black, fontSize = 10.sp)
                        }
                    }
                    if (savedMessage.isNotBlank()) Text(savedMessage, fontSize = 10.sp, color = ProfitGreen)
                    derivConnectStatus?.let { Text(it, fontSize = 10.sp, color = ElectricCyan) }
                }
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

        item {
            val lastCrash by viewModel.lastCrashText.collectAsStateWithLifecycle()
            InsightCard("SYSTEM DIAGNOSTICS & INCIDENT LOG") {
                if (!lastCrash.isNullOrBlank()) {
                    Text("Previous Incident Trace Recorded:", fontSize = 10.sp, color = LossRed, fontWeight = FontWeight.Bold)
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
                        colors = ButtonDefaults.buttonColors(containerColor = LossRed.copy(alpha = 0.2f))
                    ) {
                        Text("Sign Out", color = LossRed, fontWeight = FontWeight.Bold)
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
                    Text(errorMessage, fontSize = 11.sp, color = LossRed, textAlign = TextAlign.Center)
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
                val ageMs = (System.currentTimeMillis() - ticker.lastUpdateMillis).coerceAtLeast(0L)
                val isFresh = ageMs <= 15_000L
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
                            Text(
                                (ticker.source.ifBlank { "Market feed" }) + " • " + if (isFresh) "LIVE" else "STALE",
                                color = if (isFresh) ProfitGreen else WarningAmber, fontSize = 9.sp
                            )
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
