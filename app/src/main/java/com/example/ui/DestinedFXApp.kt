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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinedFXApp(viewModel: DestinedFXViewModel) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isShowingSplash by viewModel.isShowingSplash.collectAsStateWithLifecycle()
    val isAppLocked by viewModel.isAppLocked.collectAsStateWithLifecycle()
    val showRegisterScreen by viewModel.showRegisterScreen.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()

    if (currentUser == null) {
        if (showRegisterScreen) {
            RegisterScreen(viewModel)
        } else {
            LoginScreen(viewModel)
        }
        return
    }

    if (isShowingSplash) {
        SplashScreen(
            onFinished = { viewModel.isShowingSplash.value = false },
            username = currentUser?.username ?: "Trader"
        )
        return
    }

    if (isAppLocked) {
        BiometricLockScreen(
            viewModel = viewModel,
            onUnlocked = { viewModel.isAppLocked.value = false }
        )
        return
    }

    var showExportDialog by remember { mutableStateOf(false) }
    val dynamicAnalysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(CyanPrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TrendingUp,
                                contentDescription = null,
                                tint = DarkBackground,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "DESTINEDFX",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                            Text(
                                text = "Institutional Trading & SMC Engine",
                                fontSize = 9.sp,
                                color = TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showExportDialog = true }, modifier = Modifier.testTag("export_button")) {
                        Icon(Icons.Default.Share, contentDescription = "Export Analysis", tint = CyanPrimary)
                    }
                    IconButton(onClick = { viewModel.isAppLocked.value = true }, modifier = Modifier.testTag("lock_button")) {
                        Icon(Icons.Default.Lock, contentDescription = "Lock App", tint = OrangeAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { viewModel.selectedTab.value = 0 },
                    icon = { Icon(Icons.Default.ShowChart, contentDescription = null) },
                    label = { Text("Chart & Signals", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = CyanPrimary.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("nav_tab_chart")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { viewModel.selectedTab.value = 1 },
                    icon = { Icon(Icons.Default.Architecture, contentDescription = null) },
                    label = { Text("Strategies", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = CyanPrimary.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("nav_tab_strategies")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { viewModel.selectedTab.value = 2 },
                    icon = { Icon(Icons.Default.Calculate, contentDescription = null) },
                    label = { Text("Risk & Calc", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = CyanPrimary.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("nav_tab_risk")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { viewModel.selectedTab.value = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = CyanPrimary.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("nav_tab_settings")
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                0 -> SignalsAndChartTab(viewModel)
                1 -> StrategiesTab(viewModel)
                2 -> RiskManagementTab(viewModel)
                3 -> SettingsTab(viewModel)
            }
        }

        if (showExportDialog && dynamicAnalysis != null) {
            AnalysisExportSelectionDialog(
                analysis = dynamicAnalysis!!,
                onDismiss = { showExportDialog = false },
                onExportPdf = {
                    viewModel.exportPdfDossier(dynamicAnalysis!!)
                    showExportDialog = false
                },
                onExportImage = {
                    viewModel.exportImageSnapshot(dynamicAnalysis!!)
                    showExportDialog = false
                },
                isExporting = false
            )
        }
    }
}

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
    val timeframes = listOf("M1", "M5", "M15", "H1", "H4", "D1")

    LaunchedEffect(activeSymbol, selectedTimeframe, selectedStrategyId) {
        viewModel.runSignalScan(activeSymbol, selectedTimeframe)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Symbol & Timeframe Selectors
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(symbols) { sym ->
                        FilterChip(
                            selected = activeSymbol == sym,
                            onClick = { viewModel.selectTicker(sym) },
                            label = { Text(sym, fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary,
                                selectedLabelColor = DarkBackground,
                                containerColor = DarkSurface,
                                labelColor = TextPrimary
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
                            label = { Text(tf, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary,
                                selectedLabelColor = DarkBackground,
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            ),
                            modifier = Modifier.testTag("tf_chip_$tf")
                        )
                    }
                }
            }
        }

        // TradingView Chart WebView Container
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, TabBorder),
                modifier = Modifier.fillMaxWidth().height(320.dp)
            ) {
                TradingChartContainer(
                    activeSymbol = activeSymbol,
                    selectedTimeframe = selectedTimeframe,
                    currentSpotPrice = spotPrice,
                    activeStrategy = selectedStrategyId,
                    dynamicAnalysis = analysis ?: DynamicAnalysisInfo(symbol = activeSymbol),
                    candles = emptyList(),
                    isRealTradingViewEnabled = isRealTradingViewEnabled,
                    onToggleChartType = { isRealTradingViewEnabled = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Native Trade Plan Card
        item {
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Assessment, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                            Text("INSTITUTIONAL TRADE PLAN", fontWeight = FontWeight.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = CyanPrimary)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when (analysis?.signalState) {
                                        "STRONG_BUY", "BUY", "WEAK_BUY" -> Color(0xFF00FF99).copy(alpha = 0.2f)
                                        "STRONG_SELL", "SELL", "WEAK_SELL" -> Color(0xFFFF4560).copy(alpha = 0.2f)
                                        "INVALIDATED" -> Color(0xFFFF4560).copy(alpha = 0.3f)
                                        else -> Color(0xFFFFB300).copy(alpha = 0.2f)
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = analysis?.signalState ?: "WAIT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (analysis?.signalState) {
                                    "STRONG_BUY", "BUY", "WEAK_BUY" -> Color(0xFF00FF99)
                                    "STRONG_SELL", "SELL", "WEAK_SELL" -> Color(0xFFFF4560)
                                    "INVALIDATED" -> Color(0xFFFF4560)
                                    else -> Color(0xFFFFB300)
                                }
                            )
                        }
                    }

                    if (isScanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CyanPrimary)
                    }

                    val isTradeValid = analysis != null && analysis?.entry != 0.0 && (analysis?.signalState == "STRONG_BUY" || analysis?.signalState == "BUY" || analysis?.signalState == "WEAK_BUY" || analysis?.signalState == "STRONG_SELL" || analysis?.signalState == "SELL" || analysis?.signalState == "WEAK_SELL")
                    if (isTradeValid && analysis != null) {
                        val a = analysis!!
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Direction:", fontSize = 10.sp, color = TextSecondary)
                                Text(a.direction, fontWeight = FontWeight.Bold, color = if (a.direction == "BUY") Color(0xFF00FF99) else Color(0xFFFF4560))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Reward-to-Risk:", fontSize = 10.sp, color = TextSecondary)
                                Text(a.tp1RR, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = Color(0xFF00FF99))
                            }
                        }

                        Divider(color = TabBorder)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Entry Price:", fontSize = 10.sp, color = TextSecondary)
                                Text(formatPrice(a.entry, activeSymbol), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TextPrimary)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Stop Loss (SL):", fontSize = 10.sp, color = TextSecondary)
                                Text(formatPrice(a.stopLoss, activeSymbol), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color(0xFFFF5252))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Take Profit (TP):", fontSize = 10.sp, color = TextSecondary)
                                Text(formatPrice(a.tp1, activeSymbol), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color(0xFF00FF99))
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))
                        Text("SL Reason: ${a.slReason}", fontSize = 10.sp, color = TextSecondary)
                        Text("TP Reason: ${a.tpReason}", fontSize = 10.sp, color = TextSecondary)
                        Text("Strategy: ${a.strategyVersion}", fontSize = 10.sp, color = TextSecondary)
                    } else {
                        Text(
                            text = analysis?.blockReasons?.firstOrNull() ?: analysis?.smcAnalysis ?: "WAIT: Gathering confluence & structural validation...",
                            fontSize = 11.sp,
                            color = Color(0xFFFFB300)
                        )
                    }
                }
            }
        }
    }
}

private val selectedIdVersion = "smc-confluence-1.0.0"

@Composable
fun StrategiesTab(viewModel: DestinedFXViewModel) {
    val selectedStrategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    val strategies = StrategyRegistry.allStrategies

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "STRATEGY LIBRARY & ENGINE RULES",
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = CyanPrimary
            )
            Text(
                text = "Select active analytical ruleset. Only SMC Confluence is fully implemented.",
                fontSize = 11.sp,
                color = TextSecondary
            )
        }

        items(strategies) { strat ->
            val isSelected = strat.id == selectedStrategyId
            Card(
                colors = CardDefaults.cardColors(containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (isSelected) CyanPrimary else TabBorder),
                modifier = Modifier.fillMaxWidth().clickable(enabled = strat.isImplemented) {
                    viewModel.selectStrategy(strat.id)
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
    }
}

@Composable
fun RiskManagementTab(viewModel: DestinedFXViewModel) {
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "RISK ENGINE & POSITION SIZING",
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = CyanPrimary
        )
        RiskRewardCalculatorComponent(
            viewModel = viewModel,
            symbol = activeSymbol
        )
    }
}

@Composable
fun SettingsTab(viewModel: DestinedFXViewModel) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    var supabaseUrlInput by remember { mutableStateOf(viewModel.app.secretsManager.getSupabaseUrl()) }
    var supabaseKeyInput by remember { mutableStateOf(viewModel.app.secretsManager.getSupabaseAnonKey()) }
    var savedMessage by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("SUPABASE CLOUD & API CREDENTIALS", fontWeight = FontWeight.Black, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = CyanPrimary)
            Text("Enter your Supabase project URL and Anon Key. Never store service_role keys here.", fontSize = 11.sp, color = TextSecondary)
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
            Button(
                onClick = {
                    viewModel.app.secretsManager.saveSupabaseUrl(supabaseUrlInput.trim())
                    viewModel.app.secretsManager.saveSupabaseAnonKey(supabaseKeyInput.trim())
                    savedMessage = "Supabase credentials saved securely in Keystore."
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Credentials", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
            if (savedMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(savedMessage, fontSize = 11.sp, color = Color(0xFF00FF99))
            }
        }

        item {
            Divider(color = TabBorder)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Logged in as:", fontSize = 11.sp, color = TextSecondary)
                    Text(currentUser?.email ?: "Unknown", fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Button(
                    onClick = { viewModel.logoutUser() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4560).copy(alpha = 0.2f))
                ) {
                    Text("Sign Out", color = Color(0xFFFF4560), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun LoginScreen(viewModel: DestinedFXViewModel) {
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
                Text("DESTINEDFX LOGIN", fontWeight = FontWeight.Black, fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = CyanPrimary)
                Text("Institutional Trading Suite", fontSize = 11.sp, color = TextSecondary)

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
            }
        }
    }
}

@Composable
fun RegisterScreen(viewModel: DestinedFXViewModel) {
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
                Text("DESTINEDFX REGISTER", fontWeight = FontWeight.Black, fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = CyanPrimary)
                Text("Create Supabase Account", fontSize = 11.sp, color = TextSecondary)

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
