package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinedfx.core.strategy.StrategyRegistry
import com.example.ui.components.StatChip
import com.example.ui.components.TerminalPageHeader
import com.example.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Deterministic screenshot survey. It measures visible chart-like colours and deliberately does
 * not claim to read price-axis labels or infer entry/SL/TP from pixels alone.
 */
data class ChartPixelScanReport(
    val sampledPixelCount: Int,
    val bullishTonePixels: Int,
    val bearishTonePixels: Int,
    val colourCoveragePercent: Double,
    val bullishToneSharePercent: Double,
    val bearishToneSharePercent: Double,
    val evidenceStatus: String,
    val visualBalance: String,
    val explanation: String
)

@Composable
fun ChartImageAnalyzerTab(viewModel: DestinedFXViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val activeStrategyId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    val strategies = StrategyRegistry.allStrategies

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pairHint by remember(activeSymbol) { mutableStateOf(activeSymbol) }
    var selectedStrategyId by remember(activeStrategyId) { mutableStateOf(activeStrategyId) }
    var note by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ChartPixelScanReport?>(null) }
    var loadingImage by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var strategyMenuExpanded by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            bitmap = null
            report = null
            errorMessage = null
            loadingImage = true
            scope.launch {
                try {
                    val decoded = withContext(Dispatchers.IO) { decodeChartBitmap(context, uri) }
                    bitmap = decoded
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    errorMessage = error.message ?: "The selected image could not be opened."
                } finally {
                    loadingImage = false
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TerminalPageHeader(
                eyebrow = "CHART VISION / DETERMINISTIC PIXEL SURVEY",
                title = "Chart Image Analyzer",
                subtitle = "Choose a TradingView, MT5 or broker-chart screenshot. The native scanner measures visible pixel evidence without AI services or invented price levels."
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Default.ImageSearch, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(23.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("CHART IMAGE", color = TextPrimary, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            Text("PNG · JPEG · WEBP · local processing", color = TextSecondary, fontSize = 10.sp)
                        }
                        if (bitmap != null) {
                            TextButton(onClick = {
                                selectedUri = null
                                bitmap = null
                                report = null
                                errorMessage = null
                            }) {
                                Text("Clear", color = ElectricCyan, fontSize = 10.sp)
                            }
                        }
                    }

                    if (bitmap == null) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(DarkBackground)
                                .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(13.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(33.dp))
                                Text("Select a chart screenshot", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("The image stays on this device for this scan.", color = TextSecondary, fontSize = 10.sp)
                            }
                        }
                    } else {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = "Selected chart screenshot",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                                .clip(RoundedCornerShape(11.dp)).background(DarkBackground),
                            contentScale = ContentScale.Fit
                        )
                        Text(
                            "${bitmap!!.width} × ${bitmap!!.height} pixels after safe downsampling",
                            color = TextSecondary, fontSize = 9.sp
                        )
                    }

                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        enabled = !loadingImage && !scanning,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricCyan),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (loadingImage) "OPENING IMAGE…" else "CHOOSE CHART IMAGE", fontWeight = FontWeight.Black, fontSize = 10.sp)
                    }

                    OutlinedTextField(
                        value = pairHint,
                        onValueChange = { if (it.length <= 50) pairHint = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Pair hint (not detected from pixels)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                    )

                    Box {
                        OutlinedButton(
                            onClick = { strategyMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, CardBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                        ) {
                            Text(
                                "Strategy lens: ${strategies.firstOrNull { it.id == selectedStrategyId }?.name ?: "Select strategy"}",
                                maxLines = 1,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        DropdownMenu(
                            expanded = strategyMenuExpanded,
                            onDismissRequest = { strategyMenuExpanded = false }
                        ) {
                            strategies.filter { it.isImplemented }.forEach { strategy ->
                                DropdownMenuItem(
                                    text = { Text(strategy.name) },
                                    onClick = {
                                        selectedStrategyId = strategy.id
                                        strategyMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Notes (optional — kept as your own reference)") },
                        placeholder = { Text("e.g. 4H screenshot after a liquidity sweep") },
                        minLines = 2,
                        maxLines = 3
                    )

                    Button(
                        onClick = {
                            val selectedBitmap = bitmap ?: return@Button
                            scanning = true
                            errorMessage = null
                            report = null
                            scope.launch {
                                try {
                                    report = withContext(Dispatchers.Default) { scanChartBitmap(selectedBitmap) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    errorMessage = error.message ?: "The image scan failed."
                                } finally {
                                    scanning = false
                                }
                            }
                        },
                        enabled = bitmap != null && !loadingImage && !scanning,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = DarkBackground,
                            disabledContainerColor = ElectricCyan.copy(alpha = 0.25f)
                        )
                    ) {
                        if (scanning) {
                            CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = DarkBackground)
                            Spacer(Modifier.width(8.dp))
                            Text("SCANNING IMAGE…", fontSize = 11.sp, fontWeight = FontWeight.Black)
                        } else {
                            Icon(Icons.Default.ImageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("RUN IMAGE STRUCTURE SCAN", fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                    }

                    Text(
                        "This feature does not use Gemini, a vision API or network AI service. It does not OCR the price axis, so it will not fabricate a trade entry, stop-loss, take-profit or trade confidence from a screenshot.",
                        color = TextMuted, fontSize = 9.sp, lineHeight = 14.sp
                    )
                }
            }
        }

        if (errorMessage != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = LossRed.copy(alpha = 0.09f)),
                    border = BorderStroke(1.dp, LossRed.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        errorMessage ?: "Image scan failed.",
                        modifier = Modifier.padding(13.dp),
                        color = LossRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        val result = report
        if (result != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("IMAGE SCAN REPORT", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                Text(result.evidenceStatus, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black)
                            }
                            Text(result.visualBalance, color = if (result.visualBalance.contains("GREEN/CYAN")) ProfitGreen
                                else if (result.visualBalance.contains("RED")) LossRed else WarningAmber,
                                fontSize = 9.sp, fontWeight = FontWeight.Black)
                        }
                        HorizontalDivider(color = CardBorder)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatChip("GREEN / CYAN PIXELS", result.bullishTonePixels.toString(), Modifier.weight(1f), ProfitGreen)
                            StatChip("RED PIXELS", result.bearishTonePixels.toString(), Modifier.weight(1f), LossRed)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatChip("COLOR COVERAGE", "${formatPercent(result.colourCoveragePercent)}%", Modifier.weight(1f), ElectricCyan)
                            StatChip("SAMPLED PIXELS", result.sampledPixelCount.toString(), Modifier.weight(1f), TextPrimary)
                        }
                        Text("Relative visible-color share", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                        PixelShareBar("Green/cyan pixels", result.bullishToneSharePercent, ProfitGreen)
                        PixelShareBar("Red pixels", result.bearishToneSharePercent, LossRed)
                        Text(result.explanation, color = TextPrimary, fontSize = 11.sp, lineHeight = 17.sp)
                        HorizontalDivider(color = CardBorder)
                        Text("NO TRADE PLAN GENERATED FROM IMAGE PIXELS", color = WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        Text(
                            "Pair hint: ${pairHint.ifBlank { "Not supplied" }} · Strategy note: ${strategies.firstOrNull { it.id == selectedStrategyId }?.name ?: "Not selected"}",
                            color = TextSecondary, fontSize = 10.sp, lineHeight = 15.sp
                        )
                        if (note.isNotBlank()) Text("Your note: $note", color = TextSecondary, fontSize = 10.sp, lineHeight = 15.sp)
                        TextButton(
                            onClick = {
                                val requested = pairHint.trim().uppercase().replace(" ", "")
                                val supported = com.example.data.market.SymbolRegistry.getAllCanonical()
                                val resolvedPair = supported.firstOrNull {
                                    it.equals(requested, ignoreCase = true) ||
                                        it.replace("/", "").equals(requested, ignoreCase = true)
                                }
                                if (resolvedPair == null) {
                                    errorMessage = "Pair '$pairHint' was not found in the supported instrument registry. Correct the pair hint before opening price-based analysis."
                                } else {
                                    errorMessage = null
                                    viewModel.selectTicker(resolvedPair)
                                    // The image analyzer has its own strategy lens; carry it into the live
                                    // deterministic scan instead of silently reverting to the global strategy.
                                    viewModel.selectStrategy(selectedStrategyId)
                                    viewModel.selectScreen(DestinedFXScreen.Analyzer)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("OPEN LIVE ANALYZER FOR PRICE-BASED VALIDATION →", color = ElectricCyan, fontWeight = FontWeight.Black, fontSize = 10.sp)
                        }
                    }
                }
            }
        } else if (bitmap == null && !loadingImage) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, CardBorder),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text(
                        "Your report will appear here after you select a screenshot and run the scan. No placeholder prices or sample trades are displayed.",
                        modifier = Modifier.padding(15.dp), color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PixelShareBar(label: String, share: Double, color: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextSecondary, fontSize = 10.sp)
            Text("${formatPercent(share)}%", color = color, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)).background(DarkSurfaceVariant)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth((share / 100.0).toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight().background(color)
            )
        }
    }
}

private fun decodeChartBitmap(context: Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Could not open the selected image.")
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("The selected file is not a readable image.")

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 1400 || bounds.outHeight / sampleSize > 1400) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IOException("Image decoding failed.")
    val maxDimension = max(decoded.width, decoded.height)
    if (maxDimension <= 1200) return decoded
    val scale = 1200.0 / maxDimension
    return Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).roundToInt().coerceAtLeast(1),
        (decoded.height * scale).roundToInt().coerceAtLeast(1),
        true
    ).also { if (it !== decoded) decoded.recycle() }
}

/**
 * Samples a bounded grid of pixels and reports only measurable colour distribution.
 * It intentionally does not infer a market direction or numeric price from colour counts.
 */
internal fun scanChartBitmap(bitmap: Bitmap): ChartPixelScanReport {
    if (bitmap.width <= 0 || bitmap.height <= 0) {
        throw IllegalArgumentException("The selected image has no readable pixels.")
    }
    val stepX = max(1, bitmap.width / 360)
    val stepY = max(1, bitmap.height / 360)
    var sampled = 0
    var bullish = 0
    var bearish = 0
    val hsv = FloatArray(3)

    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            AndroidColor.RGBToHSV(AndroidColor.red(pixel), AndroidColor.green(pixel), AndroidColor.blue(pixel), hsv)
            sampled++
            val hue = hsv[0]
            val saturation = hsv[1]
            val value = hsv[2]
            if (saturation >= 0.32f && value >= 0.2f) {
                val red = AndroidColor.red(pixel)
                val green = AndroidColor.green(pixel)
                val blue = AndroidColor.blue(pixel)
                val greenCyan = hue in 72f..182f && green >= red * 1.12 && green >= blue * 0.88
                val redTone = (hue <= 18f || hue >= 342f) && red >= green * 1.15 && red >= blue * 1.08
                if (greenCyan) bullish++
                else if (redTone) bearish++
            }
            x += stepX
        }
        y += stepY
    }

    val coloured = bullish + bearish
    val coverage = if (sampled > 0) coloured * 100.0 / sampled else 0.0
    val bullishShare = if (coloured > 0) bullish * 100.0 / coloured else 0.0
    val bearishShare = if (coloured > 0) bearish * 100.0 / coloured else 0.0
    val minimumEvidence = max(30, sampled / 5000)
    val enoughEvidence = coloured >= minimumEvidence && coverage >= 0.15
    val visualBalance = when {
        !enoughEvidence -> "UNCLEAR"
        bullishShare - bearishShare >= 12.0 -> "GREEN/CYAN COLOUR MORE VISIBLE"
        bearishShare - bullishShare >= 12.0 -> "RED COLOUR MORE VISIBLE"
        else -> "MIXED COLOUR EVIDENCE"
    }
    val evidenceStatus = when {
        !enoughEvidence -> "LIMITED CHART-COLOUR EVIDENCE"
        coverage >= 2.5 -> "VISIBLE COLOUR EVIDENCE"
        else -> "LOW COLOUR COVERAGE"
    }
    val explanation = when {
        !enoughEvidence ->
            "The image contains too few clear green/cyan or red pixels for a useful colour survey. Try a full chart screenshot with candles visible."
        else ->
            "Measured pixel balance only: $bullish green/cyan-toned samples and $bearish red-toned samples. Candlesticks, indicators, annotations and chart themes can affect these counts; this is not bullish/bearish market confirmation."
    }
    return ChartPixelScanReport(
        sampledPixelCount = sampled,
        bullishTonePixels = bullish,
        bearishTonePixels = bearish,
        colourCoveragePercent = coverage,
        bullishToneSharePercent = bullishShare,
        bearishToneSharePercent = bearishShare,
        evidenceStatus = evidenceStatus,
        visualBalance = visualBalance,
        explanation = explanation
    )
}

private fun formatPercent(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
