package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MyApplication
import com.example.domain.model.FairValueGap
import com.example.domain.model.FVGType
import com.example.domain.model.TradeSignal
import com.example.domain.model.SignalDirection
import kotlin.math.abs
import com.example.ui.mapTradeSignalToUiModel

/**
 * Market Structure Widget displaying real-time Order Block (OB)
 * and Fair Value Gap (FVG) levels directly on the dashboard.
 */
@Composable
fun MarketStructureWidget(
    activeSymbol: String,
    currentSpotPrice: Double,
    isLive: Boolean,
    viewModel: DestinedFXViewModel, // Added to access candles
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as MyApplication
    val candles = viewModel.candlestickHistory[activeSymbol] ?: emptyList()

    val signalState = produceState<TradeSignal?>(
        initialValue = null,
        key1 = activeSymbol,
        key2 = currentSpotPrice,
        key3 = candles.size
    ) {
        if (candles.size >= 10) {
            value = app.signalGenerationUseCase.generateSignal(activeSymbol, "60")
        } else {
            value = null
        }
    }

    val currentSignal = signalState.value
    val analysis = currentSignal?.let { mapTradeSignalToUiModel(it) } ?: DynamicAnalysisInfo(
        symbol = activeSymbol,
        direction = "WAIT",
        confidence = "NONE",
        confidencePct = 0,
        entry = currentSpotPrice,
        stopLoss = 0.0,
        tp1 = 0.0, tp2 = 0.0, tp3 = 0.0,
        smcAnalysis = "",
        orderBlocks = emptyList(),
        wyckoffPhase = "N/A",
        elliottWave = "N/A",
        marketStructureNote = "",
        keyLevels = emptyList(),
        riskNote = "",
        sessionBias = "",
        mtf1M = "N/A", mtf5M = "N/A", mtf15M = "N/A", mtf1H = "N/A", mtf4H = "N/A", mtf1D = "N/A",
        strategyScores = emptyMap()
    )

    val isBuy = analysis.direction == "BUY"
    val dirColor = if (isBuy) ProfitGreen else LossRed
    val dirBg = if (isBuy) Color(0xFF132A20) else Color(0xFF2E131C)

    val unmitigatedFvgs = currentSignal?.fvgs?.filter { !it.mitigated } ?: emptyList()
    val nearestUnmitigatedFvg = unmitigatedFvgs.minByOrNull { fvg ->
        val mid = (fvg.topPrice + fvg.bottomPrice) / 2.0
        abs(mid - currentSpotPrice)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "SMC Market Structure Matrix ($activeSymbol)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(dirBg)
                        .border(1.dp, dirColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    if (currentSignal != null && currentSignal.signalState != "DATA_UNAVAILABLE") {
                        val mtfSuffix = if (currentSignal.mtfSummary.contains("PARTIAL")) {
                            " [PARTIAL]"
                        } else ""
                        Text(
                            text = "BIAS: ${analysis.direction} (Score: ${analysis.confidencePct}/100)$mtfSuffix",
                            color = dirColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.dp, color = dirColor)
                    }
                }
            }

            Text(
                text = "Real-time broker liquidity mapping: Unmitigated Order Blocks and Fair Value Gaps (FVG).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )

            if (currentSignal != null && currentSignal.signalState != "DATA_UNAVAILABLE") {
                // 1. Order Blocks (OB) Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🧱 ORDER BLOCKS (OB)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("UNMITIGATED", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    val obList = analysis.orderBlocks
                    if (obList.isNotEmpty()) {
                        for (ob in obList) {
                            val isBullOb = ob.type == "BULLISH"
                            val obCol = if (isBullOb) ProfitGreen else LossRed
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.background)
                                    .border(1.dp, obCol.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("${ob.type} ORDER BLOCK", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = obCol)
                                    Text("Zone: ${ob.zone}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(obCol.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(ob.strength, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = obCol)
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "No unmitigated order blocks in current price range.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 2. Fair Value Gap (FVG) Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("⚡ FAIR VALUE GAP (FVG & 50% CE)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ElectricCyan)
                    }

                    if (nearestUnmitigatedFvg != null) {
                        val fvgCe = (nearestUnmitigatedFvg.topPrice + nearestUnmitigatedFvg.bottomPrice) / 2.0
                        val fvgCol = if (nearestUnmitigatedFvg.type == FVGType.BULLISH) ProfitGreen else LossRed
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .border(1.dp, ElectricCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${nearestUnmitigatedFvg.type.name} FVG (${nearestUnmitigatedFvg.strength.name})",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = fvgCol
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(ElectricCyan.copy(alpha = 0.12f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text("UNMITIGATED", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = ElectricCyan)
                                    }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Imbalance Zone:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "${formatPrice(nearestUnmitigatedFvg.bottomPrice, activeSymbol)} - ${formatPrice(nearestUnmitigatedFvg.topPrice, activeSymbol)}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ElectricCyan
                                    )
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("50% Consequent Encroachment (CE):", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatPrice(fvgCe, activeSymbol), fontSize = 10.sp, fontWeight = FontWeight.Black, color = ProfitGreen)
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "No unmitigated fair value gaps in current price range.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val waiting = currentSignal == null && candles.size < 10
                        if (waiting) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = when {
                                waiting -> "Loading candles for $activeSymbol..."
                                else -> currentSignal?.blockReasons?.firstOrNull() ?: currentSignal?.rationale
                                    ?: "WAITING FOR LIVE DATA: no live quote for $activeSymbol"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
