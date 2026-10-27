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
import androidx.compose.ui.graphics.toArgb
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
 * Returns true only for the cross-origin TradingView chart document itself.
 * Resource failures for this iframe are actionable for chart fallback; failures for unrelated
 * images, fonts, or external resources must not change the chart loading state.
 */
internal fun isTradingViewChartDocumentUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val host = parsed.host?.lowercase(Locale.ROOT) ?: return false
    val path = parsed.path.orEmpty().lowercase(Locale.ROOT)
    val trustedTradingViewHost = isSupportedTradingViewHost(host)
    val chartDocumentPath = path == "/widgetembed" || path.startsWith("/widgetembed/") ||
        path.startsWith("/embed-widget/advanced-chart") || path.startsWith("/widget/advanced-chart")
    return trustedTradingViewHost && chartDocumentPath
}

internal fun isSupportedTradingViewHost(host: String?): Boolean {
    val normalized = host?.trim()?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
    return normalized == "tradingview.com" || normalized.endsWith(".tradingview.com") ||
        normalized == "tradingview-widget.com" || normalized.endsWith(".tradingview-widget.com")
}

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
    currentSpotPrice: Double?,
    activeStrategy: String,
    dynamicAnalysis: DynamicAnalysisInfo,
    candles: List<Candle>,
    candleSource: String = "",
    isRealTradingViewEnabled: Boolean,
    onToggleChartType: (Boolean) -> Unit,
    onPriceUpdate: ((String, Double) -> Unit)? = null,
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
                fallbackCandles = candles,
                fallbackSource = candleSource,
                fallbackSpot = currentSpotPrice,
                fallbackStrategy = activeStrategy,
                fallbackAnalysis = dynamicAnalysis,
                onSwitchToCanvas = { onToggleChartType(false) },
                onPriceUpdate = onPriceUpdate,
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
    currentSpotPrice: Double?,
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
            text = "SPOT: ${currentSpotPrice?.takeIf(::isValidSpotPrice)?.let { formatPrice(it, symbol) } ?: "—"}",
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
    fallbackCandles: List<Candle> = emptyList(),
    fallbackSource: String = "",
    fallbackSpot: Double? = null,
    fallbackStrategy: String = "",
    fallbackAnalysis: DynamicAnalysisInfo = DynamicAnalysisInfo(symbol = symbol),
    onSwitchToCanvas: (() -> Unit)? = null,
    onPriceUpdate: ((String, Double) -> Unit)? = null,
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
        Column(
            modifier = modifier.fillMaxSize().background(Color(0xFF171B27)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = OrangeAccent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                if (mappedSymbol == null) "Symbol Not Supported by TradingView" else "Unsupported Timeframe",
                color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (mappedSymbol == null) "TradingView does not support synthetic indices ($symbol). Please select a Forex, Crypto, Commodity or Index pair."
                else "Timeframe '$timeframe' cannot be mapped to the TradingView widget.",
                fontSize = 11.sp, color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return
    }

    val currentKey = "${mappedSymbol}_${mappedInterval}"

    Box(modifier = modifier.background(Color(0xFF171B27))) {
        AndroidView(
            factory = { viewContext ->
                WebView(viewContext).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.parseColor("#171B27"))
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                            val text = redactChartDiagnostic(message.message()).take(500)
                            android.util.Log.w(TRADING_VIEW_LOG_TAG, "console ${message.messageLevel()}: $text")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                            val host = error?.url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                            if (isSupportedTradingViewHost(host) || error?.url?.startsWith("https://s3.tradingview.com") == true) {
                                handler?.proceed()
                            } else {
                                handler?.cancel()
                            }
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString() else null
                            android.util.Log.e(TRADING_VIEW_LOG_TAG, "resource error code=${error?.errorCode} description=${redactChartDiagnostic(description.orEmpty()).take(240)}")
                        }
                    }

                    @Suppress("SetJavaScriptEnabled")
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadsImagesAutomatically = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(false)
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        allowFileAccess = false
                        allowContentAccess = false
                        val defaultUa = WebSettings.getDefaultUserAgent(viewContext)
                        userAgentString = defaultUa.replace("; wv", "")
                    }

                    setOnTouchListener { v, event ->
                        when (event.action) {
                            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false
                    }

                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    @Suppress("AddJavascriptInterface")
                    addJavascriptInterface(
                        TradingViewPriceBridge(symbol) { sym, p ->
                            onPriceUpdate?.invoke(sym, p)
                        },
                        "DestinedFXBridge"
                    )
                    onResume()
                    resumeTimers()
                    tag = currentKey
                    android.util.Log.i(TRADING_VIEW_LOG_TAG, "loading TradingView HTML for $symbol ($timeframe)")
                    post {
                        loadDataWithBaseURL("https://s3.tradingview.com/", widgetHtml, "text/html", "UTF-8", "https://s3.tradingview.com/")
                    }
                }
            },
            update = { view ->
                if (view is WebView && view.tag != currentKey) {
                    view.tag = currentKey
                    view.onResume()
                    view.resumeTimers()
                    android.util.Log.i(TRADING_VIEW_LOG_TAG, "updating active chart HTML for $symbol ($timeframe)")
                    view.post {
                        view.loadDataWithBaseURL("https://s3.tradingview.com/", widgetHtml, "text/html", "UTF-8", "https://s3.tradingview.com/")
                    }
                }
            },
            onRelease = { view ->
                if (view is WebView) {
                    view.onPause()
                    view.pauseTimers()
                    view.stopLoading()
                    view.webChromeClient = null
                    view.webViewClient = WebViewClient()
                    view.removeAllViews()
                    view.destroy()
                }
                android.util.Log.d(TRADING_VIEW_LOG_TAG, "released WebView key=$currentKey")
            },
            modifier = Modifier.fillMaxSize()
        )
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
 * Native fallback rendered from validated OHLC history. It may overlay symbol-matched order blocks
 * and actionable engine trade-plan levels; it does not synthesize BOS/FVG/EMA structure signals.
 */
@Composable
fun AlgorithmicCanvasChart(
    activeSymbol: String,
    currentSpotPrice: Double?,
    activeStrategy: String,
    drawConfluences: DynamicAnalysisInfo,
    candles: List<Candle>,
    modifier: Modifier = Modifier
) {
    val chartCandles = remember(candles) { validatedChartCandles(candles) }
    val chartScale = remember(chartCandles, activeSymbol) { buildNativeChartScale(chartCandles, activeSymbol) }
    if (chartCandles.isEmpty() || chartScale == null) {
        Box(
            modifier = modifier.background(Color(0xFF171B27)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No valid OHLC candles available",
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

            val minPrice = chartScale.minPrice
            val maxPrice = chartScale.maxPrice
            val priceRange = chartScale.range

            fun priceToY(price: Double): Float {
                val fraction = chartScale.priceToFraction(price) ?: return Float.NaN
                return (h - fraction * h).toFloat()
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
                val gridPrice = chartScale.fractionToPrice(((h - gridY) / h).toDouble()) ?: continue
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

            // Only actual, symbol-matched strategy outputs are eligible overlays. No synthetic
            // BOS/FVG is inferred from the candle extremes or entry/stop distance.
            drawConfluences.orderBlocks.asSequence()
                .filter { drawConfluences.symbol.equals(activeSymbol, ignoreCase = true) }
                .filter { it.type == "BUY" || it.type == "BULLISH" || it.type == "SELL" || it.type == "BEARISH" }
                .mapNotNull { block ->
                    val top = block.topPrice
                    val bottom = block.bottomPrice
                    if (top.isFinite() && bottom.isFinite() && bottom > 0.0 && top > bottom &&
                        top >= minPrice && bottom <= maxPrice) Triple(block, priceToY(top), priceToY(bottom)) else null
                }
                .take(2)
                .forEach { (block, yTop, yBottom) ->
                    val color = if (block.type == "BUY" || block.type == "BULLISH") ProfitGreen else LossRed
                    drawRect(color = color.copy(alpha = 0.10f),
                        topLeft = Offset(w * 0.25f, min(yTop, yBottom)),
                        size = Size(w * 0.65f, abs(yBottom - yTop)))
                }

            // 5. Draw High-Fidelity Candlesticks
            val totalCandles = chartCandles.size
            val chartAreaWidth = w - 75f
            val candleSpacing = chartAreaWidth / totalCandles
            val candleWidth = candleSpacing * 0.65f

            chartCandles.forEachIndexed { i, c ->
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

            // Trade levels are annotations only. They never affect the candle axis and are
            // shown only for a symbol-matched actionable engine plan with valid coordinates.
            val levels = validNativeChartTradeLevels(
                direction = drawConfluences.direction,
                analysisSymbol = drawConfluences.symbol,
                chartSymbol = activeSymbol,
                entry = drawConfluences.entry,
                stop = drawConfluences.stopLoss,
                target = drawConfluences.tp1
            )
            fun drawPlanLine(price: Double, color: Color, label: String) {
                val y = priceToY(price)
                if (!y.isFinite() || y !in 0f..h) return
                drawLine(color = color, start = Offset(0f, y), end = Offset(w - 70f, y),
                    strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f))
                nativePaint.color = color.toArgb()
                drawContext.canvas.nativeCanvas.drawText("$label ${formatPrice(price, activeSymbol)}", 12f,
                    (y - 5f).coerceAtLeast(nativePaint.textSize), nativePaint)
            }
            levels?.let { plan ->
                drawPlanLine(plan.entry, ElectricCyan, "ENTRY")
                drawPlanLine(plan.stop, LossRed, "SL")
                plan.target?.let { drawPlanLine(it, ProfitGreen, "TP") }
            }

            // 7. Live Spot Price Cursor & Pulsating Beacon
            val validSpot = currentSpotPrice?.takeIf(::isValidSpotPrice)
            val spotY = validSpot?.let(::priceToY)
            if (validSpot != null && spotY != null && spotY.isFinite() && spotY in 0f..h) {
            drawLine(
                color = ElectricCyan.copy(alpha = 0.8f),
                start = Offset(0f, spotY),
                end = Offset(w, spotY),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
            )

            val lastX = (chartCandles.size - 1) * candleSpacing + candleSpacing / 2f
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
                formatPrice(validSpot, activeSymbol),
                w - 65f,
                spotY + 6f,
                nativePaint
            )
            }
        }
    }
}

private const val TRADING_VIEW_LOG_TAG = "DestinedFX.TradingView"

private fun chartUrlHost(url: String?): String = runCatching {
    url?.let { android.net.Uri.parse(it).host }?.takeIf { it.isNotBlank() } ?: "inline-document"
}.getOrDefault("unknown-host")

private fun redactChartDiagnostic(message: String): String = message
    .replace(Regex("(?i)(access[_-]?token|authorization|api[_-]?key|token)(\\s*[:=]\\s*)[^\\s,;]+"), "$1$2[redacted]")
    .replace(Regex("(?i)([?&](?:access[_-]?token|authorization|api[_-]?key|token)=)[^&\\s]+"), "$1[redacted]")

internal class TradingViewPriceBridge(private val symbol: String, private val onPrice: (String, Double) -> Unit) {
    @android.webkit.JavascriptInterface
    fun onLivePrice(price: Double) {
        if (price > 0.0) {
            onPrice(symbol, price)
        }
    }
}

internal object TradingViewMapper {
    fun mapSymbol(symbol: String): String? {
        val raw = symbol.trim().uppercase()
        if (raw.startsWith("OANDA:") || raw.startsWith("FOREXCOM:") || raw.startsWith("CAPITALCOM:") || raw.startsWith("TVC:") || raw.startsWith("BINANCE:") || raw.startsWith("NASDAQ:") || raw.startsWith("BITSTAMP:") || raw.startsWith("COINBASE:") || raw.startsWith("NYMEX:") || raw.startsWith("CBOT:") || raw.startsWith("ICEUS:")) {
            return raw
        }
        val clean = raw.replace(" ", "").replace("-", "")
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
            "EUR/CHF", "EURCHF" -> "OANDA:EURCHF"
            "GBP/CHF", "GBPCHF" -> "OANDA:GBPCHF"
            "CAD/JPY", "CADJPY" -> "OANDA:CADJPY"
            "CHF/JPY", "CHFJPY" -> "OANDA:CHFJPY"
            "USD/TRY", "USDTRY" -> "OANDA:USDTRY"
            "USD/ZAR", "USDZAR" -> "OANDA:USDZAR"
            "USD/MXN", "USDMXN" -> "OANDA:USDMXN"
            "USD/SGD", "USDSGD" -> "OANDA:USDSGD"
            "EUR/TRY", "EURTRY" -> "OANDA:EURTRY"
            "GBP/NZD", "GBPNZD" -> "OANDA:GBPNZD"
            "GBP/AUD", "GBPAUD" -> "OANDA:GBPAUD"
            "GBP/CAD", "GBPCAD" -> "OANDA:GBPCAD"
            "AUD/CAD", "AUDCAD" -> "OANDA:AUDCAD"
            "AUD/CHF", "AUDCHF" -> "OANDA:AUDCHF"
            "AUD/NZD", "AUDNZD" -> "OANDA:AUDNZD"
            "CAD/CHF", "CADCHF" -> "OANDA:CADCHF"
            "EUR/NOK", "EURNOK" -> "OANDA:EURNOK"
            "EUR/NZD", "EURNZD" -> "OANDA:EURNZD"
            "EUR/SEK", "EURSEK" -> "OANDA:EURSEK"
            "NZD/CAD", "NZDCAD" -> "OANDA:NZDCAD"
            "NZD/CHF", "NZDCHF" -> "OANDA:NZDCHF"
            "NZD/JPY", "NZDJPY" -> "OANDA:NZDJPY"
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
            "NATGAS", "NATURALGAS", "NATURAL GAS" -> "NYMEX:NG1!"
            "WHEAT" -> "CBOT:ZW1!"
            "CORN" -> "CBOT:ZC1!"
            "SOYBEANS", "SOY BEANS" -> "CBOT:ZS1!"
            "COFFEE" -> "ICEUS:KC1!"
            "FTSE100", "UK100" -> "OANDA:UK100GBP"
            "SPX500", "US500", "SPX" -> "OANDA:SPX500USD"
            "NAS100", "US100", "NDX" -> "OANDA:NAS100USD"
            "DJ30", "US30", "DJI" -> "CAPITALCOM:US30"
            "GER30", "GER40", "DAX", "DAX40" -> "OANDA:DE30EUR"
            else -> null
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
        return "https://s.tradingview.com/widgetembed/?" +
            "symbol=${android.net.Uri.encode(mappedSymbol)}" +
            "&interval=$mappedInterval&hidesidetoolbar=0&symboledit=0&saveimage=0" +
            "&toolbarbg=171B27&theme=dark&style=1&timezone=Etc%2FUTC" +
            "&withdateranges=1&locale=en&hideideas=1&calendar=1&hotlist=0&news=0"
    }

    /**
     * Full-size Advanced Chart widget document using official TradingView embed API.
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
            put("withdateranges", true)
            put("hide_side_toolbar", false)
            put("allow_symbol_change", false)
            put("save_image", false)
            put("details", true)
            put("hotlist", false)
            put("calendar", true)
            put("studies", org.json.JSONArray().apply {
                put("STD;EMA")
                put("STD;RSI")
            })
            put("container_id", "destinedfx-tv-chart")
        }.toString()

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
              <style>
                html, body { margin:0; padding:0; width:100%; height:100%; min-height:0; overflow:hidden; background:#0F0F0F; }
                .tradingview-widget-container { width:100%; height:100%; min-height:0; background:#0F0F0F; }
                .tradingview-widget-container__widget { width:100%; height:100%; min-height:0; }
                #destinedfx-tv-chart { width:100%; height:100%; min-height:0; }
              </style>
              <script>
                function isTradingViewChartUrl(url) {
                  return typeof url === 'string' && (url.indexOf('tradingview.com') !== -1 || url.indexOf('tradingview-widget.com') !== -1);
                }
                window.addEventListener('message', function(event) {
                  try {
                    var d = event.data;
                    if (typeof d === 'string') { try { d = JSON.parse(d); } catch(_) {} }
                    if (d && (d.price || d.last || d.close || (d.data && d.data.price))) {
                      var val = Number(d.price || d.last || d.close || (d.data && d.data.price));
                      if (val > 0 && window.DestinedFXBridge) {
                        window.DestinedFXBridge.onLivePrice(val);
                      }
                    }
                  } catch(_) {}
                });
                window.addEventListener('load', function() {
                  document.body.setAttribute('data-tv-frame-loaded-at', Date.now());
                });
              </script>
            </head>
            <body>
              <div class="tradingview-widget-container">
                <div id="destinedfx-tv-chart" class="tradingview-widget-container__widget"></div>
                <script type="text/javascript" id="tvScript" src="https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js" async>
                $config
                </script>
                <script>
                  var tvScript = document.getElementById('tvScript');
                  if (tvScript) {
                    tvScript.onload = function() {
                      document.body.setAttribute('data-tv-script-loaded', 'true');
                      document.body.setAttribute('data-destinedfx-tv-loaded-at', Date.now());
                    };
                    tvScript.onerror = function() {
                      if (window.DestinedFXBridge && window.DestinedFXBridge.onChartFailed) {
                        window.DestinedFXBridge.onChartFailed('script_error');
                      }
                    };
                  }
                </script>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
