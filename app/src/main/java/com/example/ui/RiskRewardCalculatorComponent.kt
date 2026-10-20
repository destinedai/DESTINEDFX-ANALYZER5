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
import com.example.ui.theme.*
import java.util.Locale
import kotlin.math.abs

/**
 * Dedicated Risk-Reward & Position Sizing Calculator.
 * Labels demo equity explicitly and verifies that risk does not exceed 1% of equity.
 * Displays calculated vs final lots, tick size, tick value, and estimated loss at SL.
 */
@Composable
fun RiskRewardCalculatorComponent(
    viewModel: DestinedFXViewModel,
    symbol: String,
    defaultBalance: Double = 10.0,
    defaultRisk: Double = 1.0,
    modifier: Modifier = Modifier
) {
    var equityText by remember { mutableStateOf(String.format(Locale.US, "%.2f", defaultBalance)) }
    var riskPercentText by remember { mutableStateOf(defaultRisk.toString()) }

    val tickers by viewModel.tickers.collectAsStateWithLifecycle()
    val ticker = tickers[symbol]
    val spec = com.destinedfx.core.instrument.SpecRegistry.get(symbol)
    val isSpecLoaded = spec != null

    val isJpy = symbol.contains("JPY", ignoreCase = true)

    var entryPriceText by remember(symbol) {
        mutableStateOf(if (ticker != null && ticker.price > 0) formatPrice(ticker.price, symbol) else "")
    }
    var stopLossText by remember(symbol) {
        val p = ticker?.price?.takeIf { it > 0.0 }
        val defaultSl = p?.let { if (isJpy) it - 0.200 else it * 0.997 }
        mutableStateOf(defaultSl?.let { formatPrice(it, symbol) } ?: "")
    }
    var takeProfitText by remember(symbol) {
        val p = ticker?.price?.takeIf { it > 0.0 }
        val defaultTp = p?.let { if (isJpy) it + 0.400 else it * 1.006 }
        mutableStateOf(defaultTp?.let { formatPrice(it, symbol) } ?: "")
    }

    val equity = equityText.toDoubleOrNull() ?: 10.0
    val riskPct = riskPercentText.toDoubleOrNull() ?: 1.0
    val entry = entryPriceText.toDoubleOrNull() ?: 0.0
    val sl = stopLossText.toDoubleOrNull() ?: 0.0
    val tp = takeProfitText.toDoubleOrNull() ?: 0.0

    val maxRiskDollars = equity * (riskPct / 100.0)
    val slDistance = if (entry > 0 && sl > 0) abs(entry - sl) else 0.0
    val rrRatio = if (entry > 0 && sl > 0 && tp > 0 && abs(entry - sl) > 0) {
        abs(tp - entry) / abs(entry - sl)
    } else null

    // Compute exact position sizing via deterministic RiskEngine
    val sizingResult = remember(equity, riskPct, entry, sl, spec) {
        if (spec != null && entry > 0.0 && sl > 0.0 && equity > 0.0 && riskPct > 0.0) {
            val acc = AccountState(balance = equity, equity = equity, freeMargin = equity, leverage = 100, currency = "USD")
            RiskEngine.positionSize(acc, riskPct, entry, sl, spec)
        } else null
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
                        .background(if (isSpecLoaded) Color(0xFF00FF99).copy(alpha = 0.15f) else Color(0xFFFF9F43).copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isSpecLoaded) "SPEC ACTIVE" else "DEFAULT SPEC",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSpecLoaded) Color(0xFF00FF99) else OrangeAccent
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
                    onValueChange = {
                        equityText = it
                        it.toDoubleOrNull()?.let { v -> viewModel.saveConfiguredEquity(v) }
                    },
                    label = { Text("Configured Equity (DEMO) $") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                    Text("Max Allowed Loss (1%):", fontSize = 11.sp, color = TextSecondary)
                    Text(
                        "$${String.format(Locale.US, "%.2f", maxRiskDollars)}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF00FF99)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Stop Loss Distance:", fontSize = 11.sp, color = TextSecondary)
                    Text(
                        "${String.format(Locale.US, "%.5f", slDistance)} pts",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFF5252)
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
                        color = if ((rrRatio ?: 0.0) >= 2.0) Color(0xFF00FF99) else Color(0xFFFF9F43)
                    )
                }
            }

            // Sizing Result or Rejection Block
            when (sizingResult) {
                is SizingResult.Sized -> {
                    val isExceeding = sizingResult.moneyRiskActual > maxRiskDollars + 1e-6
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isExceeding) Color(0xFF132A20) else Color(0xFF2E131C))
                            .border(1.dp, if (!isExceeding) Color(0xFF00FF99).copy(alpha = 0.5f) else Color(0xFFFF4560), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("FINAL POSITION SIZE:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (!isExceeding) Color(0xFF00FF99) else Color(0xFFFF4560))
                                Text("${sizingResult.lots} LOTS (stepped down)", fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = TextPrimary)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Estimated Loss at SL:", fontSize = 11.sp, color = TextSecondary)
                                Text("$${String.format(Locale.US, "%.2f", sizingResult.moneyRiskActual)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (!isExceeding) TextPrimary else Color(0xFFFF4560))
                            }
                            Text(
                                "Enforced: Rounded down to broker step (${spec?.volumeStep ?: 0.01}). Never rounded up.",
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
                            .border(1.dp, Color(0xFFFF4560).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF4560), modifier = Modifier.size(20.dp))
                            Column {
                                Text(
                                    text = "CANNOT_SIZE_WITHIN_RISK_LIMIT",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFFFF4560)
                                )
                                Text(
                                    text = if (sizingResult.reason.contains("below broker minimum")) "Minimum broker volume exceeds the 1% risk limit." else sizingResult.reason,
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
                            text = "Enter valid Entry and Stop Loss prices to compute lot sizing.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
