package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinedfx.core.risk.AccountState
import com.destinedfx.core.risk.RiskEngine
import com.destinedfx.core.risk.SizingResult
import com.example.data.broker.BrokerAccountSnapshot
import com.example.ui.theme.*
import java.util.Locale

internal fun manualPlanRewardToRisk(entry: Double, stopLoss: Double, takeProfit: Double): Double? {
    if (!entry.isFinite() || !stopLoss.isFinite() || !takeProfit.isFinite() || entry <= 0.0 || stopLoss <= 0.0 || takeProfit <= 0.0) return null
    if (stopLoss == entry) return null
    return RiskEngine.rewardToRisk(entry, stopLoss, takeProfit, isLong = stopLoss < entry)
}

/**
 * Hypothetical risk/reward calculator. Manual equity is a scenario input only:
 * it is never persisted as account balance and cannot authorize an execution.
 */
@Composable
fun RiskRewardCalculatorComponent(
    viewModel: DestinedFXViewModel,
    symbol: String,
    defaultBalance: Double = 0.0,
    defaultRisk: Double = 1.0,
    verifiedAccount: BrokerAccountSnapshot? = null,
    modifier: Modifier = Modifier
) {
    val liveAccount = verifiedAccount?.takeIf { it.isDisplayable() }
    val liveEquity = liveAccount?.equity
    var equityText by remember(symbol, liveAccount?.retrievedAtMs) {
        mutableStateOf((liveEquity ?: defaultBalance).takeIf { it > 0.0 }
            ?.let { String.format(Locale.US, "%.2f", it) }.orEmpty())
    }
    var riskPercentText by remember { mutableStateOf(defaultRisk.toString()) }

    val tickers by viewModel.tickers.collectAsStateWithLifecycle()
    val ticker = tickers[symbol]
    val analysis by viewModel.currentDynamicAnalysis.collectAsStateWithLifecycle()
    val spec = com.destinedfx.core.instrument.SpecRegistry.get(symbol)
    val isSpecLoaded = spec != null

    val isJpy = symbol.contains("JPY", ignoreCase = true)

    var entryPriceText by remember(symbol, analysis?.entry) {
        mutableStateOf(
            analysis?.entry?.takeIf { it > 0.0 }?.let { formatPrice(it, symbol) }
                ?: ticker?.price?.takeIf { it > 0.0 }?.let { formatPrice(it, symbol) }
                ?: ""
        )
    }
    var stopLossText by remember(symbol, analysis?.stopLoss) {
        mutableStateOf(analysis?.stopLoss?.takeIf { it > 0.0 }?.let { formatPrice(it, symbol) } ?: "")
    }
    var takeProfitText by remember(symbol, analysis?.tp1) {
        mutableStateOf(analysis?.tp1?.takeIf { it > 0.0 }?.let { formatPrice(it, symbol) } ?: "")
    }
    val equity = equityText.toDoubleOrNull() ?: 0.0
    val riskPct = riskPercentText.toDoubleOrNull() ?: 0.0
    val riskIsValid = riskPct.isFinite() && riskPct in 0.1..1.0
    val entry = entryPriceText.toDoubleOrNull() ?: 0.0
    val sl = stopLossText.toDoubleOrNull() ?: 0.0
    val tp = takeProfitText.toDoubleOrNull() ?: 0.0
    val accountCurrency = liveAccount?.currency?.takeIf { it.isNotBlank() } ?: "USD"
    val riskBase = liveAccount?.let { account ->
        listOfNotNull(account.balance, account.equity).minOrNull() ?: 0.0
    } ?: equity
    val maxRiskDollars = if (riskBase > 0.0 && riskIsValid) riskBase * (riskPct / 100.0) else 0.0
    val slDistance = if (entry > 0 && sl > 0) kotlin.math.abs(entry - sl) else 0.0
    val rrRatio = manualPlanRewardToRisk(entry, sl, tp)

    val sizingResult = run {
        if (spec == null || entry <= 0.0 || sl <= 0.0 || equity <= 0.0 || !riskIsValid) {
            null
        } else {
            val accountState = if (liveAccount != null) {
                val balance = liveAccount.balance
                val equityValue = liveAccount.equity
                val freeMargin = liveAccount.freeMargin
                val leverage = liveAccount.leverage
                val currency = liveAccount.currency
                if (balance == null || equityValue == null || freeMargin == null ||
                    leverage == null || leverage <= 0 || currency.isNullOrBlank() ||
                    equity != equityValue ||
                    listOf(balance, equityValue, freeMargin).any { !it.isFinite() || it < 0.0 }
                ) null else AccountState(
                    balance = balance, equity = equityValue, freeMargin = freeMargin,
                    leverage = leverage, currency = currency
                )
            } else {
                // Explicitly hypothetical: never substitute assumed margin or leverage as broker data.
                AccountState(balance = equity, equity = equity, freeMargin = null, leverage = null, currency = "USD")
            }
            accountState?.let { RiskEngine.positionSize(it, riskPct, entry, sl, spec) }
        }
    }
    val gatedSizingResult = when {
        sizingResult is SizingResult.Sized && (rrRatio ?: 0.0) < RiskEngine.MINIMUM_REWARD_TO_RISK ->
            SizingResult.Rejected("Sizing withheld: target must be on the reward side of entry and the plan must meet at least 2.0R.")
        else -> sizingResult
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TabBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Default.Calculate,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "RISK ARCHITECTURE & SIZING",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = CyanPrimary
                        )
                        Text(
                            text = "$symbol • Max 1% Risk Enforcement",
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSpecLoaded) ProfitGreen.copy(alpha = 0.15f) else WarningAmber.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isSpecLoaded) "SPEC ACTIVE" else "SPEC UNAVAILABLE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSpecLoaded) ProfitGreen else OrangeAccent
                    )
                }
            }

            // Equity & Risk % Inputs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = equityText,
                    onValueChange = { equityText = it },
                    label = { Text(if (liveAccount != null) "Verified equity · $accountCurrency" else "Scenario equity (USD)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = liveAccount == null,
                    modifier = Modifier.weight(1.2f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = riskPercentText,
                    onValueChange = { riskPercentText = it },
                    label = { Text("Risk Limit %") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f),
                    singleLine = true
                )
            }

            Text(
                if (liveAccount != null) {
                    "VERIFIED BROKER SNAPSHOT · Balance, equity, currency, free margin and leverage come from the authenticated broker account. This remains a sizing estimate, not an execution authorization."
                } else {
                    "SCENARIO ONLY · This manually entered equity is not a broker balance, is not synced to the Bot, and cannot authorize an order."
                },
                fontSize = 10.sp,
                color = WarningAmber,
                lineHeight = 14.sp
            )

            // Price Inputs: Entry, SL, TP
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = entryPriceText,
                    onValueChange = { entryPriceText = it },
                    label = { Text("Entry Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = stopLossText,
                    onValueChange = { stopLossText = it },
                    label = { Text("Stop Loss (SL)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = takeProfitText,
                    onValueChange = { takeProfitText = it },
                    label = { Text("Take Profit (TP)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Divider(color = TabBorder)

            // Primary Calculations Readout
            Text("MATHEMATICAL RISK READOUT", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = TextSecondary)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(if (liveAccount != null) "Max broker risk budget (1%):" else "Scenario max loss (1%):", fontSize = 11.sp, color = TextSecondary)
                    Text(
                        "$accountCurrency ${String.format(Locale.US, "%.2f", maxRiskDollars)}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = ProfitGreen
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Stop Loss Distance:", fontSize = 11.sp, color = TextSecondary)
                    Text(
                        "${String.format(Locale.US, "%.5f", slDistance)} pts",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = LossRed
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Tick Size / Value:", fontSize = 11.sp, color = TextSecondary)
                    Text(
                        "${spec?.tickSize ?: 0.00001} / $${spec?.tickValue ?: 1.0}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Reward-to-Risk (R:R):", fontSize = 11.sp, color = TextSecondary)
                    Text(
                        rrRatio?.let { String.format(Locale.US, "1:%.2f", it) } ?: "N/A",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if ((rrRatio ?: 0.0) >= 2.0) ProfitGreen else WarningAmber
                    )
                }
            }

            // Sizing Result or Rejection Block
            when (gatedSizingResult) {
                is SizingResult.Sized -> {
                    val isExceeding = gatedSizingResult.moneyRiskActual > maxRiskDollars + 1e-6
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isExceeding) Color(0xFF132A20) else Color(0xFF2E131C))
                            .border(1.dp, if (!isExceeding) ProfitGreen.copy(alpha = 0.5f) else LossRed, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (liveAccount != null) "BROKER-BASED SIZE ESTIMATE:" else "SCENARIO LOT ESTIMATE:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (!isExceeding) ProfitGreen else LossRed)
                                Text("${gatedSizingResult.lots} LOTS (stepped down)", fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = TextPrimary)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (liveAccount != null) "Estimated broker loss at SL:" else "Scenario loss at SL:", fontSize = 11.sp, color = TextSecondary)
                                Text("$accountCurrency ${String.format(Locale.US, "%.2f", gatedSizingResult.moneyRiskActual)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (!isExceeding) TextPrimary else LossRed)
                            }
                            Text(
                                if (liveAccount != null) "Sizing uses verified broker metrics; the terminal still enforces actual margin, stops, costs and execution."
                                else "Scenario only; broker lot step and actual account conditions have not been verified.",
                                fontSize = 9.5.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
                is SizingResult.Rejected -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2E131C))
                            .border(1.dp, LossRed.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = LossRed, modifier = Modifier.size(20.dp))
                            Column {
                                Text(
                                    text = "CANNOT_SIZE_WITHIN_RISK_LIMIT",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = LossRed
                                )
                                Text(
                                    text = if (gatedSizingResult.reason.contains("below broker minimum")) "Minimum broker volume exceeds the 1% risk limit." else gatedSizingResult.reason,
                                    fontSize = 10.sp,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
                null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurfaceVariant)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Enter scenario equity, Entry and Stop Loss to calculate a hypothetical size. Broker account conditions are not used.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
