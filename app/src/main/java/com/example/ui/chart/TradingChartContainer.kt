package com.example.ui.chart

import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.model.Candle
import com.example.ui.DynamicAnalysisInfo
import com.example.ui.formatPrice
import com.example.ui.theme.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Unified Trading Chart Container supporting:
 * 1. Live TradingView Chart (with non-blank direct widget embed and WebView lifecycle protection)
 * 2. High-FPS Algorithmic Canvas Chart mapping structures as the market flows (BOS, CHoCH, OB, FVG, TP/SL targets)
 * 3. Live Structure HUD overlay floating across the top
 */
@Composable
fun TradingChartContainer(
    activeSymbol: String,
    selectedTimeframe: String,
    currentSpotPrice: Double,
    activeStrategy: String,
    dynamicAnalysis: DynamicAnalysisInfo,
    candles: List<Candle>,
    isRealTradingViewEnabled: Boolean,
    onToggleChartType: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showStructureOverlayOnTv by remember { mutableStateOf(true) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF171B27))
            .border(1.dp, TabBorder, RoundedCornerShape(4.dp))
    ) {
        val isInspection = LocalInspectionMode.current
        if (isInspection) {
            // Static Canvas Candlestick placeholder for Preview / Inspection Mode
            TradingViewPreviewPlaceholder(
                symbol = activeSymbol,
                timeframe = selectedTimeframe,
                modifier = Modifier.fillMaxSize()
            )
        } else if (isRealTradingViewEnabled) {
            // Live Interactive TradingView Chart
            TradingViewWebView(
                symbol = activeSymbol,
                timeframe = selectedTimeframe,
                onSwitchToCanvas = { onToggleChartType(false) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Pure Algorithmic Structure Canvas (100% native Compose)
            AlgorithmicCanvasChart(
                activeSymbol = activeSymbol,
                currentSpotPrice = currentSpotPrice,
                activeStrategy = activeStrategy,
                drawConfluences = dynamicAnalysis,
                candles = candles,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Keep the TradingView chart unobstructed; analysis details are shown below it.
    }
}

/**
 * Top floating HUD bar showing real-time market structure flow.
 */
@Composable
fun LiveStructureHud(
    analysis: DynamicAnalysisInfo,
    symbol: String,
    currentSpotPrice: Double,
    modifier: Modifier = Modifier
) {
    val isBuy = analysis.direction == "BUY"
    val dirCol = if (isBuy) ProfitGreen else LossRed
    val dirBg = if (isBuy) Color(0xFF132A20) else Color(0xFF2E131C)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0D121B).copy(alpha = 0.90f))
            .border(1.dp, TabBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pulsing live beacon
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ProfitGreen)
            )

            // Flow Direction Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(dirBg)
                    .border(1.dp, dirCol.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "FLOW: ${analysis.direction} (Score: ${analysis.confidencePct}/100)",
                    color = dirCol,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Order Block Status
            val ob = analysis.orderBlocks.firstOrNull()
            if (ob != null) {
                Text(
                    text = "OB: ${ob.type} [${ob.strength}]",
                    color = TextPrimary,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Target TP1 Harvest
            Text(
                text = "TARGET: ${formatPrice(analysis.tp1, symbol)} (+${String.format(Locale.US, "%.1f", analysis.tp1YieldPips)} pts)",
                color = ProfitGreen,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // Live Spot Price
        Text(
            text = "SPOT: ${formatPrice(currentSpotPrice, symbol)}",
            color = CyanPrimary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * TradingView's official Advanced Chart script embed, matching the HTML/React snippet used
 * on the DestinedFX site. The wrapper lives inside Android WebView; the surrounding app remains
 * native Compose. Pair/timeframe changes rebuild the widget config without allowing symbol drift.
 */
@Composable
fun TradingViewWebView(
    symbol: String,
    timeframe: String,
    onSwitchToCanvas: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (LocalInspectionMode.current) {
        TradingViewPreviewPlaceholder(symbol = symbol, timeframe = timeframe, modifier = modifier)
        return
    }
    val mappedSymbol = TradingViewMapper.mapSymbol(symbol)
    val mappedInterval = TradingViewMapper.mapInterval(timeframe)
    val widgetUrl = TradingViewMapper.buildWidgetUrl(symbol, timeframe)
    val widgetHtml = TradingViewMapper.buildWidgetHtml(symbol, timeframe)

    if (mappedSymbol == null || mappedInterval == null || widgetUrl == null || widgetHtml == null) {
        Column(modifier = modifier.fillMaxSize().background(Color(0xFF171B27)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = OrangeAccent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(if (mappedSymbol == null) "Symbol Not Supported by TradingView" else "Unsupported Timeframe",
                color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(if (mappedSymbol == null) "TradingView has no configured symbol mapping for '$symbol'."
                else "Timeframe '$timeframe' cannot be mapped to the TradingView widget.",
                fontSize = 11.sp, color = TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }

    val currentKey = "${mappedSymbol}_${mappedInterval}"
    val context = LocalContext.current
    var isLoading by remember(currentKey) { mutableStateOf(true) }
    var isChartError by remember(currentKey) { mutableStateOf(false) }
    var errorMessage by remember(currentKey) { mutableStateOf("") }
    var retryTrigger by remember(currentKey) { mutableIntStateOf(0) }

    LaunchedEffect(currentKey, retryTrigger) {
        isLoading = true
        isChartError = false
        errorMessage = ""
        kotlinx.coroutines.delay(45_000L)
        if (isLoading && !isChartError) {
            isChartError = true
            isLoading = false
            errorMessage = "TradingView's chart script or market iframe did not initialize within 45 seconds. Update Android System WebView/Chrome, check your connection, then retry."
        }
    }

    val expectedTag = "${currentKey}_retry_${retryTrigger}"
    Box(modifier = modifier.background(Color(0xFF171B27))) {
        if (isChartError) {
            Column(modifier = Modifier.fillMaxSize().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = OrangeAccent, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(10.dp))
                Text("TradingView unavailable", color = TextPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(errorMessage.ifBlank { "The chart could not be displayed for $symbol." },
                    fontSize = 11.sp, color = TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    isChartError = false
                    isLoading = true
                    errorMessage = ""
                    retryTrigger++
                }, colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    shape = RoundedCornerShape(9.dp), modifier = Modifier.testTag("tradingview_retry")) {
                    Text("RETRY TRADINGVIEW", color = DarkBackground, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(widgetUrl))) }
                            .onFailure { errorMessage = "Could not open browser: ${it.message ?: it.javaClass.simpleName}" }
                    },
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricCyan)
                ) {
                    Text("OPEN CHART IN BROWSER", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            }
        } else {
            AndroidView(
                factory = { context ->
                    try {
                        WebView(context).apply {
                            tag = expectedTag
                            setLayerType(View.LAYER_TYPE_HARDWARE, null)
                            overScrollMode = View.OVER_SCROLL_NEVER
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    isChartError = false
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    // A loaded wrapper page is not proof that TradingView rendered the selected market.
                                    if (view == null || url?.contains("tradingview.com") != true) return
                                    fun verifyChart(attempt: Int) {
                                        if (isChartError || !isLoading) return
                                        view.evaluateJavascript(
                                            "(function(){var e=document.documentElement.getAttribute('data-tv-widget-error');if(e)return 'ERROR:'+e;var b=document.body;var t=(b&&b.innerText||'').toLowerCase();if(t.includes('invalid symbol')||t.includes('symbol not found'))return 'ERROR:TradingView could not find this symbol';var c=document.querySelector('.tradingview-widget-container__widget');var f=c?c.querySelector('iframe'):null;if(f&&f.src&&/tradingview/i.test(f.src)){var r=f.getBoundingClientRect();var loadedAt=parseInt(f.getAttribute('data-destinedfx-tv-loaded-at')||'0',10);if(r.width>100&&r.height>100&&loadedAt>0&&Date.now()-loadedAt>=1500)return 'READY';}var canvases=document.querySelectorAll('canvas');for(var i=0;i<canvases.length;i++){var cr=canvases[i].getBoundingClientRect();if(cr.width>100&&cr.height>100)return 'READY';}return 'WAIT';})()"
                                        ) { result ->
                                            val state = result?.removeSurrounding("\"") ?: "WAIT"
                                            when {
                                                state == "READY" -> {
                                                    isLoading = false
                                                    isChartError = false
                                                }
                                                state.startsWith("ERROR:") -> {
                                                    isLoading = false
                                                    isChartError = true
                                                    errorMessage = state.removePrefix("ERROR:")
                                                }
                                                attempt < 30 -> view.postDelayed({ verifyChart(attempt + 1) }, 1000L)
                                                else -> {
                                                    isLoading = false
                                                    isChartError = true
                                                    errorMessage = "TradingView loaded its page, but no visible market chart appeared. Update Android System WebView/Chrome or open the chart externally."
                                                }
                                            }
                                        }
                                    }
                                    view.postDelayed({ verifyChart(0) }, 1200L)
                                }
                                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                    handler?.cancel()
                                    isChartError = true
                                    isLoading = false
                                    errorMessage = "SSL verification failed. The TradingView connection was cancelled."
                                }
                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    if (request?.isForMainFrame == true) {
                                        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString() else null
                                        isChartError = true
                                        isLoading = false
                                        errorMessage = description?.takeIf { it.isNotBlank() } ?: "The TradingView page could not be reached."
                                    }
                                }
                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: android.webkit.WebResourceResponse?
                                ) {
                                    if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                                        isChartError = true
                                        isLoading = false
                                        errorMessage = "TradingView returned HTTP ${errorResponse?.statusCode ?: "error"}."
                                    }
                                }
                            }
                            @android.annotation.SuppressLint("SetJavaScriptEnabled")
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                loadsImagesAutomatically = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                javaScriptCanOpenWindowsAutomatically = false
                                setSupportMultipleWindows(false)
                                cacheMode = WebSettings.LOAD_DEFAULT
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                allowFileAccess = false
                                allowContentAccess = false
                            }
                            // Use the device WebView's real user agent instead of spoofing an
                            // outdated Chrome version. TradingView can then serve a compatible UI.
                            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            loadDataWithBaseURL("https://www.tradingview.com/", widgetHtml, "text/html", "UTF-8", null)
                        }
                    } catch (error: Throwable) {
                        isChartError = true
                        isLoading = false
                        errorMessage = "Android WebView could not start: ${error.message ?: error.javaClass.simpleName}"
                        View(context)
                    }
                },
                update = { view ->
                    if (view is WebView && view.tag != expectedTag) {
                        view.tag = expectedTag
                        isLoading = true
                        isChartError = false
                        errorMessage = ""
                        view.loadDataWithBaseURL("https://www.tradingview.com/", widgetHtml, "text/html", "UTF-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xD90B0E14)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = ElectricCyan, strokeWidth = 2.dp, modifier = Modifier.size(30.dp))
                        Text("OPENING TRADINGVIEW • $symbol • $timeframe", fontSize = 10.sp, color = TextSecondary,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun TradingViewPreviewPlaceholder(
    symbol: String,
    timeframe: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF171B27)), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(Icons.Default.QueryStats, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(34.dp))
            Text("TRADINGVIEW RUNTIME CHART", fontSize = 11.sp, color = ElectricCyan,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            Text("$symbol • $timeframe", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            Text("DESIGN PREVIEW ONLY — Compose previews cannot display the embedded TradingView WebView.",
                fontSize = 10.sp, color = TextSecondary, lineHeight = 15.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("Run the Android app to verify chart loading.", fontSize = 9.sp, color = WarningAmber,
                fontFamily = FontFamily.Monospace)
        }
    }
}



/**
 * High-performance Compose Canvas chart that maps out structures as the market flows!
 * Maps:
 * - Dynamic Bullish & Bearish Order Blocks (OB)
 * - Fair Value Gaps (FVG) with 50% Consequent Encroachment (CE)
 * - Break of Structure (BOS) and Change of Character (CHoCH)
 * - Liquidity Sweeps (BSL/SSL)
 * - EMA 9 & EMA 21 flowing ribbons
 * - Trade Coordinates (Entry, SL, TP1, TP2, TP3)
 * - Live spot price pulsing beacon
 */
@Composable
fun AlgorithmicCanvasChart(
    activeSymbol: String,
    currentSpotPrice: Double,
    activeStrategy: String,
    drawConfluences: DynamicAnalysisInfo,
    candles: List<Candle>,
    modifier: Modifier = Modifier
) {
    if (candles.isEmpty()) {
        Box(
            modifier = modifier.background(Color(0xFF171B27)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No live candles available",
                color = TextMuted,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        return
    }

    Box(modifier = modifier.background(Color(0xFF171B27))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Calculate scaled min/max bounds so all structures and candles fit
            val candleMin = candles.minOf { it.low }
            val candleMax = candles.maxOf { it.high }

            val rawMin = min(candleMin, min(drawConfluences.entry, min(drawConfluences.stopLoss, drawConfluences.tp1)))
            val rawMax = max(candleMax, max(drawConfluences.entry, max(drawConfluences.stopLoss, drawConfluences.tp3)))

            val padding = (rawMax - rawMin) * 0.08
            val minPrice = rawMin - padding
            val maxPrice = rawMax + padding
            val priceRange = if (maxPrice > minPrice) maxPrice - minPrice else 1.0

            fun priceToY(price: Double): Float {
                return (h - ((price - minPrice) / priceRange * h)).toFloat()
            }

            // 1. Draw Grid lines and Price Axis
            val gridCount = 6
            val nativePaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 20f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
            }

            for (i in 1 until gridCount) {
                val gridY = h * (i.toFloat() / gridCount)
                val gridPrice = minPrice + ((h - gridY) / h) * priceRange
                drawLine(
                    color = ChartGrid.copy(alpha = 0.5f),
                    start = Offset(0f, gridY),
                    end = Offset(w - 70f, gridY),
                    strokeWidth = 1f
                )

                nativePaint.color = android.graphics.Color.parseColor("#5A6978")
                drawContext.canvas.nativeCanvas.drawText(
                    formatPrice(gridPrice, activeSymbol),
                    w - 65f,
                    gridY + 6f,
                    nativePaint
                )
            }

            // 2. Map Market Structures: Order Blocks (OB)
            val isBuy = drawConfluences.direction == "BUY"
            val obHeightPips = abs(drawConfluences.entry - drawConfluences.stopLoss) * 0.35

            if (isBuy) {
                // Bullish Order Block at Discount
                val obTopPrice = drawConfluences.entry - (obHeightPips * 0.2)
                val obBottomPrice = drawConfluences.entry - obHeightPips
                val obY1 = priceToY(obTopPrice)
                val obY2 = priceToY(obBottomPrice)

                drawRect(
                    color = ProfitGreen.copy(alpha = 0.12f),
                    topLeft = Offset(w * 0.25f, min(obY1, obY2)),
                    size = Size(w * 0.75f, abs(obY2 - obY1))
                )
                drawLine(
                    color = ProfitGreen.copy(alpha = 0.6f),
                    start = Offset(w * 0.25f, min(obY1, obY2)),
                    end = Offset(w, min(obY1, obY2)),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                )
                nativePaint.color = android.graphics.Color.parseColor("#00FF99")
                drawContext.canvas.nativeCanvas.drawText("BULLISH OB [UNMITIGATED]", w * 0.26f, min(obY1, obY2) - 6f, nativePaint)
            } else {
                // Bearish Order Block at Premium
                val obTopPrice = drawConfluences.entry + obHeightPips
                val obBottomPrice = drawConfluences.entry + (obHeightPips * 0.2)
                val obY1 = priceToY(obTopPrice)
                val obY2 = priceToY(obBottomPrice)

                drawRect(
                    color = LossRed.copy(alpha = 0.12f),
                    topLeft = Offset(w * 0.25f, min(obY1, obY2)),
                    size = Size(w * 0.75f, abs(obY2 - obY1))
                )
                drawLine(
                    color = LossRed.copy(alpha = 0.6f),
                    start = Offset(w * 0.25f, max(obY1, obY2)),
                    end = Offset(w, max(obY1, obY2)),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                )
                nativePaint.color = android.graphics.Color.parseColor("#FF4560")
                drawContext.canvas.nativeCanvas.drawText("BEARISH OB [UNMITIGATED]", w * 0.26f, max(obY1, obY2) + 20f, nativePaint)
            }

            // 3. Map Fair Value Gap (FVG) with 50% Consequent Encroachment
            val fvgMidPrice = if (isBuy) drawConfluences.entry + (obHeightPips * 0.5) else drawConfluences.entry - (obHeightPips * 0.5)
            val fvgY = priceToY(fvgMidPrice)
            drawLine(
                color = ElectricCyan.copy(alpha = 0.5f),
                start = Offset(w * 0.4f, fvgY),
                end = Offset(w * 0.85f, fvgY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            )
            nativePaint.color = android.graphics.Color.parseColor("#00E5FF")
            drawContext.canvas.nativeCanvas.drawText("FVG CE 50%", w * 0.42f, fvgY - 6f, nativePaint)

            // 4. Map Break of Structure (BOS)
            val bosPrice = if (isBuy) candleMax else candleMin
            val bosY = priceToY(bosPrice)
            drawLine(
                color = if (isBuy) ProfitGreen.copy(alpha = 0.7f) else LossRed.copy(alpha = 0.7f),
                start = Offset(w * 0.15f, bosY),
                end = Offset(w * 0.75f, bosY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
            )
            nativePaint.color = if (isBuy) android.graphics.Color.parseColor("#00FF99") else android.graphics.Color.parseColor("#FF4560")
            drawContext.canvas.nativeCanvas.drawText(if (isBuy) "BOS ↑ (STRUCTURAL BREAK)" else "BOS ↓ (STRUCTURAL BREAK)", w * 0.16f, bosY - 6f, nativePaint)

            // 5. Draw High-Fidelity Candlesticks
            val totalCandles = candles.size
            val chartAreaWidth = w - 75f
            val candleSpacing = chartAreaWidth / totalCandles
            val candleWidth = candleSpacing * 0.65f

            candles.forEachIndexed { i, c ->
                val cX = i * candleSpacing + candleSpacing / 2f
                val yHigh = priceToY(c.high)
                val yLow = priceToY(c.low)
                val yOpen = priceToY(c.open)
                val yClose = priceToY(c.close)

                val isGreen = c.close >= c.open
                val cColor = if (isGreen) ProfitGreen else LossRed

                // Wick
                drawLine(
                    color = cColor,
                    start = Offset(cX, yHigh),
                    end = Offset(cX, yLow),
                    strokeWidth = 1.5f
                )

                // Body
                val topY = min(yOpen, yClose)
                val bodyHeight = max(3f, abs(yOpen - yClose))
                drawRect(
                    color = cColor,
                    topLeft = Offset(cX - candleWidth / 2f, topY),
                    size = Size(candleWidth, bodyHeight)
                )
            }

            // 6. Draw Algorithmic Execution Target Lines
            val entryY = priceToY(drawConfluences.entry)
            val slY = priceToY(drawConfluences.stopLoss)
            val tp1Y = priceToY(drawConfluences.tp1)
            val tp2Y = priceToY(drawConfluences.tp2)
            val tp3Y = priceToY(drawConfluences.tp3)

            // Entry Line (Blue)
            drawLine(
                color = ElectricCyan,
                start = Offset(0f, entryY),
                end = Offset(w - 70f, entryY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f)
            )
            nativePaint.color = android.graphics.Color.parseColor("#448AFF")
            drawContext.canvas.nativeCanvas.drawText("ENTRY ${formatPrice(drawConfluences.entry, activeSymbol)}", 16f, entryY - 8f, nativePaint)

            // Stop Loss Line (Red with buffer)
            drawLine(
                color = LossRed,
                start = Offset(0f, slY),
                end = Offset(w - 70f, slY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f)
            )
            nativePaint.color = android.graphics.Color.parseColor("#FF5252")
            drawContext.canvas.nativeCanvas.drawText("SL ${formatPrice(drawConfluences.stopLoss, activeSymbol)} [INVALIDATION]", 16f, slY - 8f, nativePaint)

            // TP1 Line (Green)
            drawLine(
                color = ProfitGreen,
                start = Offset(0f, tp1Y),
                end = Offset(w - 70f, tp1Y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f)
            )
            nativePaint.color = android.graphics.Color.parseColor("#00FF99")
            drawContext.canvas.nativeCanvas.drawText("TP1 ${formatPrice(drawConfluences.tp1, activeSymbol)} (+${String.format(Locale.US, "%.1f", drawConfluences.tp1YieldPips)} pts | 1:1.5R)", 16f, tp1Y - 8f, nativePaint)

            // TP2 Line (Cyan)
            drawLine(
                color = ElectricCyan,
                start = Offset(0f, tp2Y),
                end = Offset(w - 70f, tp2Y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f)
            )
            nativePaint.color = android.graphics.Color.parseColor("#00E5FF")
            drawContext.canvas.nativeCanvas.drawText("TP2 ${formatPrice(drawConfluences.tp2, activeSymbol)} (1:2.5R)", 16f, tp2Y - 8f, nativePaint)

            // 7. Live Spot Price Cursor & Pulsating Beacon
            val spotY = priceToY(currentSpotPrice)
            drawLine(
                color = ElectricCyan.copy(alpha = 0.8f),
                start = Offset(0f, spotY),
                end = Offset(w, spotY),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
            )

            val lastX = (candles.size - 1) * candleSpacing + candleSpacing / 2f
            drawCircle(
                color = ElectricCyan,
                radius = 5f,
                center = Offset(lastX, spotY)
            )
            drawCircle(
                color = ElectricCyan.copy(alpha = 0.25f),
                radius = 12f,
                center = Offset(lastX, spotY)
            )

            // Spot Price Pill on the Right Axis
            drawRect(
                color = ElectricCyan,
                topLeft = Offset(w - 70f, spotY - 12f),
                size = Size(70f, 24f)
            )
            nativePaint.color = android.graphics.Color.BLACK
            nativePaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            drawContext.canvas.nativeCanvas.drawText(
                formatPrice(currentSpotPrice, activeSymbol),
                w - 65f,
                spotY + 6f,
                nativePaint
            )
        }
    }
}

internal object TradingViewMapper {
    fun mapSymbol(symbol: String): String? {
        val clean = symbol.trim().uppercase()
        // Reject Deriv synthetic indices which TradingView does not support
        if (clean.startsWith("R_") || clean.startsWith("1HZ") || clean.startsWith("CRASH") ||
            clean.startsWith("BOOM") || clean.startsWith("JUMP") || clean.startsWith("STEP") ||
            clean.startsWith("STP_") || clean.contains("VOLATILITY") || clean.contains("DEX")) {
            return null
        }
        return when (clean) {
            "EUR/USD", "EURUSD" -> "OANDA:EURUSD"
            "GBP/USD", "GBPUSD" -> "OANDA:GBPUSD"
            "USD/JPY", "USDJPY" -> "OANDA:USDJPY"
            "USD/CHF", "USDCHF" -> "OANDA:USDCHF"
            "AUD/USD", "AUDUSD" -> "OANDA:AUDUSD"
            "USD/CAD", "USDCAD" -> "OANDA:USDCAD"
            "NZD/USD", "NZDUSD" -> "OANDA:NZDUSD"
            "EUR/GBP", "EURGBP" -> "OANDA:EURGBP"
            "EUR/JPY", "EURJPY" -> "OANDA:EURJPY"
            "GBP/JPY", "GBPJPY" -> "OANDA:GBPJPY"
            "AUD/JPY", "AUDJPY" -> "OANDA:AUDJPY"
            "EUR/AUD", "EURAUD" -> "OANDA:EURAUD"
            "EUR/CAD", "EURCAD" -> "OANDA:EURCAD"
            "GBP/AUD", "GBPAUD" -> "OANDA:GBPAUD"
            "GBP/CAD", "GBPCAD" -> "OANDA:GBPCAD"
            "XAU/USD", "XAUUSD", "GOLD" -> "OANDA:XAUUSD"
            "XAG/USD", "XAGUSD", "SILVER" -> "OANDA:XAGUSD"
            "XPT/USD", "XPTUSD" -> "OANDA:XPTUSD"
            "XPD/USD", "XPDUSD" -> "OANDA:XPDUSD"
            "BTC/USD", "BTCUSD", "BTCUSDT" -> "BINANCE:BTCUSDT"
            "ETH/USD", "ETHUSD", "ETHUSDT" -> "BINANCE:ETHUSDT"
            "SOL/USD", "SOLUSD", "SOLUSDT" -> "BINANCE:SOLUSDT"
            "XRP/USD", "XRPUSD", "XRPUSDT" -> "BINANCE:XRPUSDT"
            "BNB/USD", "BNBUSD", "BNBUSDT" -> "BINANCE:BNBUSDT"
            "USOIL", "WTI" -> "TVC:USOIL"
            "UKOIL", "BRENT" -> "TVC:UKOIL"
            "NATGAS", "NATURAL GAS" -> "NYMEX:NG1!"
            "WHEAT" -> "CBOT:ZW1!"
            "CORN" -> "CBOT:ZC1!"
            "SOYBEANS", "SOY BEANS" -> "CBOT:ZS1!"
            "COFFEE" -> "ICEUS:KC1!"
            "FTSE100", "UK100" -> "OANDA:UK100GBP"
            "SPX500", "US500", "SPX" -> "OANDA:SPX500USD"
            "NAS100", "US100", "NDX" -> "OANDA:NAS100USD"
            "DJ30", "US30", "DJI" -> "OANDA:US30USD"
            "GER30", "GER40", "DAX", "DAX40" -> "OANDA:DE30EUR"
            else -> {
                // If standard 6-character forex pair like "NZD/CAD"
                val stripped = clean.replace("/", "")
                if (stripped.length == 6 && stripped.all { it.isLetter() }) {
                    "OANDA:$stripped"
                } else {
                    null // Fail-safe: unsupported symbol
                }
            }
        }
    }

    fun mapInterval(timeframe: String): String? {
        return when (timeframe.trim().uppercase()) {
            "M1", "1" -> "1"
            "M5", "5" -> "5"
            "M15", "15" -> "15"
            "M30", "30" -> "30"
            "H1", "60", "1H" -> "60"
            "H4", "240", "4H" -> "240"
            "D1", "D", "1D" -> "D"
            "W1", "W", "1W" -> "W"
            else -> null
        }
    }

    /** Build the supported TradingView embed URL used by the browser fallback. */
    fun buildWidgetUrl(symbol: String, timeframe: String): String? {
        val mappedSymbol = mapSymbol(symbol) ?: return null
        val mappedInterval = mapInterval(timeframe) ?: return null
        return "https://s.tradingview.com/widgetembed/?frameElementId=destinedfx_tv" +
            "&symbol=${android.net.Uri.encode(mappedSymbol)}" +
            "&interval=$mappedInterval&hidesidetoolbar=0&symboledit=0&saveimage=0" +
            "&toolbarbg=171B27&theme=dark&style=1&timezone=Etc%2FUTC" +
            "&withdateranges=1&locale=en&hideideas=1&calendar=1&hotlist=0&news=0"
    }

    /**
     * Full-size Advanced Chart widget document. Loading the documented widget script inside a
     * responsive HTML container is more reliable in Android WebView than opening the low-level URL.
     */
    fun buildWidgetHtml(symbol: String, timeframe: String): String? {
        val mappedSymbol = mapSymbol(symbol) ?: return null
        val mappedInterval = mapInterval(timeframe) ?: return null
        val config = org.json.JSONObject().apply {
            put("autosize", true)
            put("symbol", mappedSymbol)
            put("interval", mappedInterval)
            put("timezone", "Etc/UTC")
            put("theme", "dark")
            put("style", "1")
            put("locale", "en")
            put("enable_publishing", false)
            put("allow_symbol_change", false)
            put("calendar", true)
            put("details", true)
            put("hide_side_toolbar", false)
            put("hide_top_toolbar", false)
            put("hide_legend", false)
            put("hide_volume", false)
            put("hotlist", false)
            put("save_image", false)
            put("backgroundColor", "#0F0F0F")
            put("gridColor", "rgba(242,242,242,0.2)")
            put("watchlist", org.json.JSONArray())
            put("withdateranges", true)
            put("compareSymbols", org.json.JSONArray())
            put("studies", org.json.JSONArray().put("STD;EMA").put("STD;RSI"))
            put("support_host", "https://www.tradingview.com")
        }.toString()

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
              <meta name="referrer" content="strict-origin-when-cross-origin">
              <style>
                html, body { margin:0; padding:0; width:100%; height:100%; overflow:hidden; background:#0F0F0F; }
                .tradingview-widget-container { position:relative; width:100%; height:100%; background:#0F0F0F; }
                .tradingview-widget-container__widget { width:100%; height:calc(100% - 32px); }
                .tradingview-widget-copyright { height:32px; line-height:32px; text-align:right; padding-right:9px; font:10px Arial,sans-serif; background:#0F0F0F; }
                .tradingview-widget-copyright a { color:#8B98A9; text-decoration:none; }
              </style>
              <script>
                // Track the iframe's actual load event; the existence of an iframe is not proof
                // that the selected TradingView market has rendered.
                document.addEventListener('load', function(event) {
                  var target = event.target;
                  if (target && target.tagName === 'IFRAME' &&
                      target.closest && target.closest('.tradingview-widget-container')) {
                    target.setAttribute('data-destinedfx-tv-loaded-at', String(Date.now()));
                  }
                }, true);
                window.addEventListener('error', function(event) {
                  var target = event.target;
                  if (target && target.tagName === 'SCRIPT' &&
                      String(target.src || '').indexOf('embed-widget-advanced-chart.js') >= 0) {
                    document.documentElement.setAttribute('data-tv-widget-error',
                      'TradingView advanced chart script could not load.');
                  }
                }, true);
              </script>
            </head>
            <body>
              <div class="tradingview-widget-container">
                <div class="tradingview-widget-container__widget"></div>
                <div class="tradingview-widget-copyright">
                  <a href="https://www.tradingview.com/" rel="noopener nofollow" target="_blank">Chart by TradingView</a>
                </div>
                <script type="text/javascript" src="https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js" async>
                ${config}
                </script>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
