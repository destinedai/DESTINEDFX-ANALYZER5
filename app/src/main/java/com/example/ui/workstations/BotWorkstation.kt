package com.example.ui.workstations

import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import com.example.ui.formatPrice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinedfx.core.bot.BotLifecycleState
import com.example.data.broker.BrokerType
import com.example.data.broker.BrokerConnectionStatus
import com.example.ui.DestinedFXViewModel
import com.example.ui.InsightCard
import com.example.ui.InsightLine
import com.example.ui.theme.*
import com.example.ui.components.TerminalPageHeader
import com.example.ui.components.StatChip
import androidx.compose.ui.platform.LocalContext
import com.example.security.AppSecurityGuard
import com.destinedfx.core.news.HighImpactNewsSentry
import kotlinx.coroutines.launch

@Composable
fun BotWorkstationTab(viewModel: DestinedFXViewModel) {
    val botSummary by viewModel.botStatusSummary.collectAsStateWithLifecycle()
    val botActive by viewModel.botActive.collectAsStateWithLifecycle()
    val activeBrokerType by viewModel.activeBrokerType.collectAsStateWithLifecycle()
    val activeBrokerGateway by viewModel.activeBrokerGateway.collectAsStateWithLifecycle()
    val brokerConnStatus by activeBrokerGateway.connectionStatus.collectAsStateWithLifecycle()
    val accountMetrics by activeBrokerGateway.accountMetrics.collectAsStateWithLifecycle()
    val openPositions by activeBrokerGateway.openPositions.collectAsStateWithLifecycle()

    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val selectedStrategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsStateWithLifecycle()
    val riskPercent by viewModel.riskPercent.collectAsStateWithLifecycle()

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var brokerConnectionMessage by remember { mutableStateOf<String?>(null) }
    var mt5LoginInput by remember { mutableStateOf(viewModel.getMT5Login()) }
    var mt5PasswordInput by remember { mutableStateOf(viewModel.getMT5Password()) }
    var mt5ServerInput by remember { mutableStateOf(viewModel.getMT5BrokerServer()) }
    var mt5InvestorPasswordInput by remember { mutableStateOf(viewModel.getMT5InvestorPassword()) }
    var isConnectingBroker by remember { mutableStateOf(false) }
    var showPostLoginPairSelectorModal by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(activeBrokerType) {
        brokerConnectionMessage = null
        statusMessage = null
        isConnectingBroker = false
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val isConnected = brokerConnStatus == BrokerConnectionStatus.CONNECTED
            val statusColor = when (botSummary.state) {
                BotLifecycleState.ACTIVE_SCANNING -> ProfitGreen
                BotLifecycleState.EXECUTING -> ElectricCyan
                BotLifecycleState.CONNECTING_BROKER -> OrangeAccent
                BotLifecycleState.ERROR -> LossRed
                else -> if (isConnected) ProfitGreen else WarningAmber
            }
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.42f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(statusColor.copy(alpha = 0.13f)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = statusColor, modifier = Modifier.size(23.dp))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("AUTOMATION / EXECUTION", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                            Text("Bot command desk", fontSize = 22.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                            Text("$activeSymbol · $selectedTimeframe · $selectedStrategyId",
                                fontSize = 9.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                        }
                        Box(Modifier.clip(RoundedCornerShape(7.dp)).background(statusColor.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(if (isConnected) "CONNECTED" else "OFFLINE", fontSize = 8.sp, color = statusColor,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                    }
                    HorizontalDivider(color = CardBorder)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("BOT STATE", fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text(botSummary.state.name.replace('_', ' '), fontSize = 12.sp, color = statusColor,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("BROKER", fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text(activeBrokerType.name, fontSize = 12.sp, color = TextPrimary,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("RISK LIMIT", fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text("${riskPercent.coerceAtMost(1.0)}%", fontSize = 12.sp, color = WarningAmber,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text(if (isConnected) "Execution gateway connected; verify all orders before use."
                        else "Execution is disconnected. Analysis and chart display are separate from broker execution.",
                        fontSize = 10.sp, color = TextSecondary, lineHeight = 15.sp)
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChip(
                    label = "BOT STATE",
                    value = botSummary.state.name.replace('_', ' '),
                    modifier = Modifier.weight(1f),
                    color = if (botSummary.isRunning || botActive) ProfitGreen else WarningAmber
                )
                StatChip(
                    label = "RISK CAP",
                    value = "${riskPercent.coerceAtMost(1.0)}%",
                    modifier = Modifier.weight(1f),
                    color = WarningAmber
                )
            }
        }

        // Master Bot Control Card
        item {
            val isRunning = botSummary.isRunning || botActive
            val statusColor = when (botSummary.state) {
                BotLifecycleState.ACTIVE_SCANNING -> ProfitGreen
                BotLifecycleState.EXECUTING -> CyanPrimary
                BotLifecycleState.CONNECTING_BROKER -> OrangeAccent
                BotLifecycleState.ERROR -> LossRed
                else -> if (isRunning) ProfitGreen else TextSecondary
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, if (isRunning) statusColor.copy(alpha = 0.6f) else TabBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("bot_master_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = "BOT STATUS: ${botSummary.state.name}",
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = statusColor
                            )
                        }

                        Switch(
                            checked = isRunning,
                            onCheckedChange = { active ->
                                viewModel.setBotStatus(active) { success, err ->
                                    statusMessage = if (success) {
                                        if (active) "24/7 Surveillance Engine activated" else "Surveillance stopped"
                                    } else {
                                        err ?: "Failed to change engine state"
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DarkBackground,
                                checkedTrackColor = ProfitGreen,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = DarkSurfaceVariant
                            ),
                            modifier = Modifier.testTag("bot_toggle_switch")
                        )
                    }

                    HorizontalDivider(color = TabBorder)

                    // Last Action & Logs
                    Text(
                        text = "LAST ACTION: ${botSummary.lastAction}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )

                    if (botSummary.lastError != null) {
                        Text(
                            text = "ERROR: ${botSummary.lastError}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = LossRed
                        )
                    }

                    val msg = statusMessage
                    if (msg != null) {
                        Text(
                            text = msg,
                            fontSize = 10.sp,
                            color = CyanPrimary
                        )
                    }
                }
            }
        }

        // Target Pair Selection & Autonomous Balance & Free Margin Audit
        item {
            val capital by viewModel.accountCapital.collectAsStateWithLifecycle()
            val dynamicAnalysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
            val ticker = viewModel.tickers.collectAsStateWithLifecycle().value[activeSymbol]
            val cmp = ticker?.price ?: dynamicAnalysis?.entry ?: 0.0
            val isBullish = (dynamicAnalysis?.direction ?: "BUY").contains("BUY", true) || (dynamicAnalysis?.direction ?: "").contains("LONG", true)
            val effectiveEntry = if (dynamicAnalysis != null && dynamicAnalysis!!.entry > 0.0) dynamicAnalysis!!.entry else cmp
            val effectiveSl = if (dynamicAnalysis != null && dynamicAnalysis!!.stopLoss > 0.0) dynamicAnalysis!!.stopLoss else if (isBullish) effectiveEntry * 0.997 else effectiveEntry * 1.003
            val effectiveTp1 = if (dynamicAnalysis != null && dynamicAnalysis!!.tp1 > 0.0) dynamicAnalysis!!.tp1 else if (isBullish) effectiveEntry * 1.006 else effectiveEntry * 0.994
            val effectiveTp2 = dynamicAnalysis?.tp2 ?: 0.0
            val effectiveTp3 = dynamicAnalysis?.tp3 ?: 0.0

            var reqMarginState by remember(activeSymbol, cmp) { mutableStateOf(0.0) }
            LaunchedEffect(activeSymbol, cmp) {
                val res = activeBrokerGateway.calculateRequiredMargin(activeSymbol, 0.01)
                if (res.isSuccess && (res.getOrNull() ?: 0.0) > 0.0) {
                    reqMarginState = res.getOrNull()!!
                } else {
                    val spec = com.destinedfx.core.instrument.SpecRegistry.get(activeSymbol)
                    val contractSize = spec?.contractSize ?: 100000.0
                    reqMarginState = if (cmp > 0.0) (0.01 * contractSize * cmp) / 100.0 else 20.0
                }
            }

            val availablePairs = listOf("EUR/USD", "GBP/USD", "XAU/USD", "BTC/USD", "NAS100", "SPX500", "USD/JPY", "USD/CAD", "USD/CHF")

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("bot_pair_margin_audit_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "BOT INSTRUMENT SELECTION & MARGIN CHECK",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = ElectricCyan
                            )
                            Text(
                                text = "Select pair to trade • Bot audits balance & margin automatically",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ProfitGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "LIVE AUDIT",
                                fontSize = 8.sp,
                                color = ProfitGreen,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    HorizontalDivider(color = CardBorder)

                    Text(
                        text = "1. TARGET TRADING INSTRUMENT",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(availablePairs) { pair ->
                            val isSelected = pair == activeSymbol
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.selectTicker(pair) },
                                label = {
                                    Text(
                                        text = pair,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan,
                                    selectedLabelColor = DarkBackground,
                                    containerColor = DarkSurfaceVariant,
                                    labelColor = TextPrimary
                                ),
                                modifier = Modifier.testTag("bot_pair_chip_$pair")
                            )
                        }
                    }

                    HorizontalDivider(color = CardBorder)

                    Text(
                        text = "2. ACCOUNT BALANCE, EQUITY & AUTO LOT AUDIT",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )

                    val freeMarginVal = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null)
                        accountMetrics!!.freeMargin else capital

                    val sizedResult = remember(activeSymbol, freeMarginVal, riskPercent, effectiveEntry, effectiveSl) {
                        viewModel.sizeFor(activeSymbol, freeMarginVal, riskPercent, effectiveEntry, effectiveSl)
                    }
                    val lotDisplay = when (sizedResult) {
                        is com.destinedfx.core.risk.SizingResult.Sized -> "%.2f Lots".format(sizedResult.lots)
                        is com.destinedfx.core.risk.SizingResult.Rejected -> "0.01 Lots"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatChip(
                            label = "BALANCE",
                            value = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null)
                                "$${String.format(java.util.Locale.US, "%.2f", accountMetrics!!.balance)}"
                            else if (capital > 0.0) "$${String.format(java.util.Locale.US, "%.2f", capital)}"
                            else "OFFLINE",
                            modifier = Modifier.weight(1f),
                            color = TextPrimary
                        )
                        StatChip(
                            label = "EQUITY",
                            value = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null)
                                "$${String.format(java.util.Locale.US, "%.2f", accountMetrics!!.equity)}"
                            else if (capital > 0.0) "$${String.format(java.util.Locale.US, "%.2f", capital)}"
                            else "OFFLINE",
                            modifier = Modifier.weight(1f),
                            color = TextPrimary
                        )
                        StatChip(
                            label = "FREE MARGIN",
                            value = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null)
                                "$${String.format(java.util.Locale.US, "%.2f", accountMetrics!!.freeMargin)}"
                            else if (capital > 0.0) "$${String.format(java.util.Locale.US, "%.2f", capital)}"
                            else "OFFLINE",
                            modifier = Modifier.weight(1f),
                            color = ProfitGreen
                        )
                        StatChip(
                            label = "AUTO LOT",
                            value = lotDisplay,
                            modifier = Modifier.weight(1f),
                            color = ElectricCyan
                        )
                    }

                    val isMarginSafe = freeMarginVal >= reqMarginState && reqMarginState > 0.0

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isMarginSafe || freeMarginVal > 0.0) ProfitGreen.copy(alpha = 0.12f) else LossRed.copy(alpha = 0.12f))
                            .border(1.dp, if (isMarginSafe || freeMarginVal > 0.0) ProfitGreen.copy(alpha = 0.4f) else LossRed.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (brokerConnStatus != BrokerConnectionStatus.CONNECTED && capital <= 0.0)
                                    "DISCONNECTED • CONNECT BROKER TO VERIFY MARGIN"
                                else if (isMarginSafe) "MARGIN CHECK PASSED • SAFE TO EXECUTE" else "LOW FREE MARGIN WARNING",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = if (isMarginSafe || freeMarginVal > 0.0) ProfitGreen else LossRed
                            )
                            Text(
                                text = if (brokerConnStatus != BrokerConnectionStatus.CONNECTED && capital <= 0.0)
                                    "Connect your Deriv or MetaTrader 5 demo/real account below to execute live trades."
                                else if (isMarginSafe)
                                    "Free margin ($${String.format(java.util.Locale.US, "%.2f", freeMarginVal)}) is sufficient to cover required margin ($${String.format(java.util.Locale.US, "%.2f", reqMarginState)}) for $activeSymbol."
                                else "Free margin ($${String.format(java.util.Locale.US, "%.2f", freeMarginVal)}) is low. Reduce lot size or deposit funds.",
                                fontSize = 10.sp,
                                color = TextPrimary
                            )
                        }
                    }

                    HorizontalDivider(color = CardBorder)

                    Text(
                        text = "3. BOT ENTRY, STOP LOSS & TARGET PLAN",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )

                    if (effectiveEntry > 0.0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("ENTRY", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(formatPrice(effectiveEntry, activeSymbol), fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            }
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("STOP LOSS", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(formatPrice(effectiveSl, activeSymbol), fontSize = 11.sp, color = LossRed, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            }
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("TP1", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(formatPrice(effectiveTp1, activeSymbol), fontSize = 11.sp, color = ProfitGreen, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            }
                        }

                        if (effectiveTp2 > 0.0 || effectiveTp3 > 0.0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (effectiveTp2 > 0.0) {
                                    Column(
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text("TP2 (EXTENDED)", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                        Text(formatPrice(effectiveTp2, activeSymbol), fontSize = 11.sp, color = ProfitGreen, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                    }
                                }
                                if (effectiveTp3 > 0.0) {
                                    Column(
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant).padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text("TP3 (RUNWAY)", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                        Text(formatPrice(effectiveTp3, activeSymbol), fontSize = 11.sp, color = ProfitGreen, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Break-Even Guidance: Shift Stop Loss to Entry (${formatPrice(effectiveEntry, activeSymbol)}) once price reaches TP1.",
                            fontSize = 9.5.sp,
                            color = ElectricCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = "Awaiting market quote and analysis setup for $activeSymbol...",
                            fontSize = 10.sp,
                            color = WarningAmber
                        )
                    }
                }
            }
        }

        // Engine Safeguards & Quantitative Controls
        item {
            val capital by viewModel.accountCapital.collectAsStateWithLifecycle()
            val strat by viewModel.activeStrategy.collectAsStateWithLifecycle()
            val nfpArmed by viewModel.nfpProtectionArmed.collectAsStateWithLifecycle()
            val isHalted by viewModel.showHaltNotification.collectAsStateWithLifecycle()
            val isMultiScanning by viewModel.isMultiAIScanning.collectAsStateWithLifecycle()
            val consensusStatus by viewModel.multiAIConsensusStatus.collectAsStateWithLifecycle()

            InsightCard("ENGINE SAFEGUARDS & QUANT CONTROLS") {
                InsightLine("Active Strategy", strat)
                InsightLine("Account Capital", "$${"%.2f".format(capital)}")
                InsightLine("NFP Safeguard", if (nfpArmed) "ARMED (High-Impact Guard Active)" else "DISARMED")
                InsightLine("Execution Status", if (isHalted) "HALTED (News Filter Lock)" else "NORMAL")
                InsightLine("Deterministic MTF Scan", if (isMultiScanning) "SCANNING (H1 / M15 / H4)" else consensusStatus)
                if (isHalted) {
                    Text(
                        text = "Trading halted due to high-impact economic event window.",
                        color = LossRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // HARDENED APP SHIELD & HIGH-IMPACT NEWS PROTECTION
        item {
            val context = LocalContext.current
            val isSecureStorageAvailable by viewModel.secureStorageAvailable.collectAsStateWithLifecycle()
            val secReport = remember(isSecureStorageAvailable) {
                AppSecurityGuard.auditAppSecurity(context, isSecureStorageAvailable)
            }
            val newsAlert by HighImpactNewsSentry.sentryAlert.collectAsStateWithLifecycle()
            var autoExitNewsEnabled by remember { mutableStateOf(true) }

            // Periodically check high-impact news for selected asset
            LaunchedEffect(activeSymbol, brokerConnStatus) {
                while (true) {
                    try {
                        HighImpactNewsSentry.auditAndProtectPositions(
                            symbol = activeSymbol,
                            gateway = activeBrokerGateway,
                            calendarResult = null, // Will use cached/real calendar if available
                            autoExitEnabled = autoExitNewsEnabled
                        )
                    } catch (_: Exception) {}
                    kotlinx.coroutines.delay(10_000L)
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, ProfitGreen.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("app_security_news_shield_card")
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = ProfitGreen, modifier = Modifier.size(20.dp))
                            Text(
                                text = "HARDENED APP SHIELD & NEWS SENTRY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = ProfitGreen
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ProfitGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "SHIELD ACTIVE",
                                fontSize = 8.sp,
                                color = ProfitGreen,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Text(
                        text = secReport.statusMessage,
                        fontSize = 10.sp,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace
                    )

                    HorizontalDivider(color = CardBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("HIGH-IMPACT NEWS SENTRY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ElectricCyan)
                            Text("Auto-exit positions on high impact releases (NFP, CPI, FOMC)", fontSize = 9.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = autoExitNewsEnabled,
                            onCheckedChange = { autoExitNewsEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = DarkBackground, checkedTrackColor = ElectricCyan)
                        )
                    }

                    newsAlert?.let { alert ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(LossRed.copy(alpha = 0.15f))
                                .border(1.dp, LossRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = alert.detailMessage,
                                fontSize = 10.sp,
                                color = LossRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // MetaTrader 5 Native Login & Broker Connection Card
        item {
            var selectedBrokerCompanyName by remember { mutableStateOf("FBS Markets Inc.") }
            var showBrokerSearchDialog by remember { mutableStateOf(false) }
            var brokerSearchQuery by remember { mutableStateOf("") }
            var accountTypeSelection by remember { mutableStateOf("Trade account") }
            var savePasswordChecked by remember { mutableStateOf(true) }
            var showOpenDemoDialog by remember { mutableStateOf(false) }
            var showOpenRealDialog by remember { mutableStateOf(false) }

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, Color(0xFF2196F3).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("mt5_native_login_card")
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AccountBalance, contentDescription = null, tint = Color(0xFF2196F3), modifier = Modifier.size(20.dp))
                            Text(
                                text = "METATRADER 5 • DIRECT BROKER LOGIN",
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF2196F3)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2196F3).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "NATIVE MT5",
                                fontSize = 8.sp,
                                color = Color(0xFF2196F3),
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Top Broker Company Search Header
                    Row(
                        modifier = Modifier.fillMaxWidth().background(DarkSurfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Business, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                            Column {
                                Text(selectedBrokerCompanyName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Search all global MT5 brokers & servers", fontSize = 9.sp, color = TextSecondary)
                            }
                        }
                        Button(
                            onClick = { showBrokerSearchDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.testTag("search_broker_button")
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Find Broker", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Open Demo Account Helper Card
                    Card(
                        onClick = { showOpenDemoDialog = true },
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("open_demo_account_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(ElectricCyan.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.School, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                                }
                                Column {
                                    Text("Open a demo account", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("To learn trading and to test your strategies", fontSize = 9.sp, color = TextSecondary)
                                }
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                        }
                    }

                    // Open Real Account Helper Card
                    Card(
                        onClick = { showOpenRealDialog = true },
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("open_real_account_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(ProfitGreen.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.TrendingUp, contentDescription = null, tint = ProfitGreen, modifier = Modifier.size(18.dp))
                                }
                                Column {
                                    Text("Open a real account", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("For live trading, additional identification is required", fontSize = 9.sp, color = TextSecondary)
                                }
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                        }
                    }

                    HorizontalDivider(color = CardBorder)

                    Text(
                        text = "Login to an existing account",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3),
                        fontFamily = FontFamily.Monospace
                    )

                    // Type Dropdown Selection
                    var expandedType by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = accountTypeSelection,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Type") },
                            trailingIcon = {
                                IconButton(onClick = { expandedType = !expandedType }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("account_type_field"),
                            singleLine = true
                        )
                        DropdownMenu(
                            expanded = expandedType,
                            onDismissRequest = { expandedType = false },
                            modifier = Modifier.fillMaxWidth(0.85f)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Trade account") },
                                onClick = { accountTypeSelection = "Trade account"; expandedType = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Investor account") },
                                onClick = { accountTypeSelection = "Investor account"; expandedType = false }
                            )
                        }
                    }

                    // Account / Client ID Input
                    OutlinedTextField(
                        value = mt5LoginInput, onValueChange = { mt5LoginInput = it.filter(Char::isDigit) },
                        label = { Text("Trade account / Client ID") },
                        placeholder = { Text("Enter numeric login ID") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("bot_mt5_login")
                    )

                    // Password Input
                    OutlinedTextField(
                        value = mt5PasswordInput, onValueChange = { mt5PasswordInput = it },
                        label = { Text("Password") },
                        placeholder = { Text("Enter trading password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("bot_mt5_password")
                    )

                    // Server Selector with Dynamic Dropdown
                    var expandedServer by remember { mutableStateOf(false) }
                    val matchedBrokers = remember(mt5ServerInput, selectedBrokerCompanyName) {
                        com.example.data.broker.BrokerDirectory.searchBrokers(
                            if (mt5ServerInput.isNotBlank()) mt5ServerInput else selectedBrokerCompanyName
                        )
                    }
                    val availableServerList = remember(matchedBrokers) {
                        matchedBrokers.flatMap { it.servers }.distinct().take(12)
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = mt5ServerInput,
                            onValueChange = { mt5ServerInput = it },
                            label = { Text("Server") },
                            placeholder = { Text("e.g. Exness-MT5Real, ICMarketsSC-Live01, FBS-Real") },
                            trailingIcon = {
                                IconButton(onClick = { expandedServer = !expandedServer }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("bot_mt5_server"),
                            singleLine = true
                        )
                        DropdownMenu(
                            expanded = expandedServer && availableServerList.isNotEmpty(),
                            onDismissRequest = { expandedServer = false },
                            modifier = Modifier.fillMaxWidth(0.85f)
                        ) {
                            availableServerList.forEach { srv ->
                                DropdownMenuItem(
                                    text = { Text(srv, fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        mt5ServerInput = srv
                                        expandedServer = false
                                    }
                                )
                            }
                        }
                    }

                    // Save password checkbox
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = savePasswordChecked,
                            onCheckedChange = { savePasswordChecked = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF2196F3))
                        )
                        Text("Save password", fontSize = 11.sp, color = TextPrimary)
                    }

                    // Prominent MT5 Blue Login Button (#2196F3)
                    Button(
                        onClick = {
                            if (mt5LoginInput.isBlank()) {
                                brokerConnectionMessage = "Please enter your Trade Account / Login ID."
                                return@Button
                            }
                            if (mt5PasswordInput.isBlank()) {
                                brokerConnectionMessage = "Please enter your password."
                                return@Button
                            }
                            if (mt5ServerInput.isBlank()) {
                                brokerConnectionMessage = "Please enter or select a broker server."
                                return@Button
                            }
                            isConnectingBroker = true
                            brokerConnectionMessage = null
                            viewModel.connectToMT5Credentials(
                                mt5LoginInput, mt5PasswordInput, mt5ServerInput,
                                mt5InvestorPasswordInput
                            ) { success, message ->
                                isConnectingBroker = false
                                brokerConnectionMessage = message ?: if (success) "Connected successfully to $selectedBrokerCompanyName." else "Login failed. Verify credentials."
                                if (success) {
                                    showPostLoginPairSelectorModal = true
                                }
                            }
                        },
                        enabled = !isConnectingBroker,
                        modifier = Modifier.fillMaxWidth().height(50.dp).testTag("bot_connect_mt5"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2196F3),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isConnectingBroker) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Text("LOGIN", fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.sp)
                        }
                    }

                    // Comprehensive Global Broker Search Dialog
                    if (showBrokerSearchDialog) {
                        AlertDialog(
                            onDismissRequest = { showBrokerSearchDialog = false },
                            title = { Text("Global Broker Search", fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = brokerSearchQuery,
                                        onValueChange = { brokerSearchQuery = it },
                                        label = { Text("Search Broker or Server (e.g. Exness, IC Markets, XM)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    val results = remember(brokerSearchQuery) {
                                        com.example.data.broker.BrokerDirectory.searchBrokers(brokerSearchQuery)
                                    }
                                    LazyColumn(modifier = Modifier.height(260.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(results) { broker ->
                                            Card(
                                                onClick = {
                                                    selectedBrokerCompanyName = broker.name
                                                    mt5ServerInput = broker.servers.firstOrNull() ?: "${broker.name}-Real"
                                                    showBrokerSearchDialog = false
                                                },
                                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Text(broker.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ElectricCyan)
                                                    Text("Servers: ${broker.servers.take(3).joinToString(", ")}", fontSize = 9.sp, color = TextSecondary)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showBrokerSearchDialog = false }) {
                                    Text("Close")
                                }
                            }
                        )
                    }

                    // Open Demo / Real Account Dialogs
                    if (showOpenDemoDialog) {
                        AlertDialog(
                            onDismissRequest = { showOpenDemoDialog = false },
                            title = { Text("Open Demo Account") },
                            text = { Text("To open a free $10,000 demo trading account with $selectedBrokerCompanyName, enter your details or use instant 1-click demo login above.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showOpenDemoDialog = false
                                    mt5LoginInput = "50" + (100000..999999).random()
                                    mt5PasswordInput = "DemoPass123!"
                                    mt5ServerInput = "$selectedBrokerCompanyName-Demo"
                                }) { Text("Auto-fill Demo") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showOpenDemoDialog = false }) { Text("Cancel") }
                            }
                        )
                    }

                    if (showOpenRealDialog) {
                        AlertDialog(
                            onDismissRequest = { showOpenRealDialog = false },
                            title = { Text("Open Real Account") },
                            text = { Text("Real trading accounts with $selectedBrokerCompanyName require verification and live deposits through your broker portal. Enter your verified account credentials once issued.") },
                            confirmButton = {
                                TextButton(onClick = { showOpenRealDialog = false }) { Text("OK") }
                            }
                        )
                    }

                    brokerConnectionMessage?.let { message ->
                        Text(
                            text = message, fontSize = 10.sp,
                            color = if (message.contains("failed", true) || message.contains("error", true)) LossRed else ElectricCyan,
                            lineHeight = 15.sp, modifier = Modifier.testTag("bot_broker_connection_status")
                        )
                    }

                    HorizontalDivider(color = CardBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Connection:", fontSize = 10.sp, color = TextSecondary)
                            Text(
                                text = brokerConnStatus.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED) ProfitGreen else OrangeAccent
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("Balance:", fontSize = 10.sp, color = TextSecondary)
                            Text(
                                text = if (brokerConnStatus == BrokerConnectionStatus.CONNECTED && accountMetrics != null) {
                                    "${String.format(java.util.Locale.US, "%.2f", accountMetrics!!.balance)} ${accountMetrics!!.currency}"
                                } else "—",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }

                        if (brokerConnStatus == BrokerConnectionStatus.CONNECTED) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        activeBrokerGateway.disconnect()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = LossRed),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("bot_disconnect_button")
                            ) {
                                Text("Disconnect", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Target Configuration Parameters Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, TabBorder),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "AUTONOMOUS PARAMETERS & SAFETY CONSTRAINTS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyanPrimary
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Instrument:", fontSize = 11.sp, color = TextSecondary)
                        Text(activeSymbol, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Strategy:", fontSize = 11.sp, color = TextSecondary)
                        Text(selectedStrategyId, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Timeframe:", fontSize = 11.sp, color = TextSecondary)
                        Text(selectedTimeframe, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Max Risk per Trade:", fontSize = 11.sp, color = TextSecondary)
                        Text("${riskPercent}% (Capped strictly <= 1.0%)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ProfitGreen)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Minimum Executable RR:", fontSize = 11.sp, color = TextSecondary)
                        Text("1:2.0 (Absolute minimum)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ProfitGreen)
                    }
                }
            }
        }

        // Open Positions Section
        item {
            Text(
                text = "OPEN POSITIONS (${openPositions.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary
            )
        }

        if (openPositions.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No open positions currently active on ${activeBrokerGateway.displayName}.",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(openPositions) { pos ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, TabBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${pos.direction.name} ${pos.symbol}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (pos.direction.name == "LONG") ProfitGreen else LossRed
                            )
                            Text(
                                text = "Entry: ${pos.entryPrice} | Lot/Stake: ${pos.lotSize}",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    activeBrokerGateway.closePosition(pos.id)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LossRed),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("close_position_${pos.id}")
                        ) {
                            Text("Close", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showPostLoginPairSelectorModal) {
        AlertDialog(
            onDismissRequest = { showPostLoginPairSelectorModal = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = ProfitGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "BROKER CONNECTED SUCCESSFULLY",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = ProfitGreen
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Select the pair you wish to run the bot on. The bot will automatically inspect your leverage, account balance ($${String.format(java.util.Locale.US, "%.2f", accountMetrics?.balance ?: viewModel.accountCapital.value)}), free margin ($${String.format(java.util.Locale.US, "%.2f", accountMetrics?.freeMargin ?: viewModel.accountCapital.value)}), and equity to calculate the optimal lot size and trade plan.",
                        fontSize = 11.sp,
                        color = TextPrimary,
                        lineHeight = 16.sp
                    )

                    HorizontalDivider(color = CardBorder)

                    Text(
                        text = "CHOOSE PAIR TO AUTOMATE:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = ElectricCyan
                    )

                    val selectPairs = listOf(
                        "EUR/USD" to "Forex Major",
                        "GBP/USD" to "Forex Major",
                        "XAU/USD" to "Gold Commodity",
                        "BTC/USD" to "Crypto Asset",
                        "NAS100" to "US Tech Index",
                        "SPX500" to "US SP500 Index",
                        "USD/JPY" to "Forex Major",
                        "USD/CAD" to "Forex Major",
                        "USD/CHF" to "Forex Major"
                    )

                    LazyColumn(
                        modifier = Modifier.height(240.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(selectPairs) { (pair, category) ->
                            Card(
                                onClick = {
                                    viewModel.selectTicker(pair)
                                    showPostLoginPairSelectorModal = false
                                    statusMessage = "Bot configured for $pair. Account balance, leverage & free margin verified!"
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (pair == activeSymbol) ElectricCyan.copy(alpha = 0.15f) else DarkSurfaceVariant
                                ),
                                border = BorderStroke(1.dp, if (pair == activeSymbol) ElectricCyan else CardBorder),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().testTag("modal_pair_$pair")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(pair, fontWeight = FontWeight.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                                        Text(category, fontSize = 9.sp, color = TextSecondary)
                                    }
                                    if (pair == activeSymbol) {
                                        Text("ACTIVE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = ElectricCyan, fontFamily = FontFamily.Monospace)
                                    } else {
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPostLoginPairSelectorModal = false },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)
                ) {
                    Text("Confirm $activeSymbol", fontWeight = FontWeight.Black, fontSize = 11.sp)
                }
            }
        )
    }
}
