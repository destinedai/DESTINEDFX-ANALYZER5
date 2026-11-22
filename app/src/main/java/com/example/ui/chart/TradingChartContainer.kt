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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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
        } else {
            // Lightweight Charts is a renderer only: bars and the live midpoint come from MT5 Bridge.
            TradingViewWebView(
                symbol = activeSymbol,
                timeframe = selectedTimeframe,
                fallbackCandles = candles,
                fallbackSource = candleSource,
                fallbackSpot = currentSpotPrice,
                fallbackStrategy = activeStrategy,
                fallbackAnalysis = dynamicAnalysis,
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
                    text = "FLOW: ${analysis.direction} (Confluence: ${analysis.confidencePct}/100)",
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
 * TradingView Lightweight Charts is a renderer only. It receives closed OHLC candles
 * from the selected account's authenticated MT5 Bridge and the same live midpoint as Quotes.
 * The last verified snapshot is frozen when the Bridge disconnects; no external price feed is used.
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

    val validCandles = remember(fallbackCandles) {
        fallbackCandles.filter { candle ->
            candle.complete && candle.timestamp > 0L &&
                candle.open.isFinite() && candle.high.isFinite() &&
                candle.low.isFinite() && candle.close.isFinite() &&
                candle.open > 0.0 && candle.high >= maxOf(candle.open, candle.close, candle.low) &&
                candle.low <= minOf(candle.open, candle.close, candle.high) && candle.close > 0.0
        }.sortedBy { it.timestamp }.distinctBy { it.timestamp }
    }
    val liveMid = fallbackSpot?.takeIf { it.isFinite() && it > 0.0 }
    val bridgeSource = fallbackSource.takeIf { it.contains("MT5 Bridge", ignoreCase = true) }
    val brokerName = bridgeSource?.substringBefore(" MT5 Bridge")?.ifBlank { "broker" } ?: "broker"
    val ready = bridgeSource != null && liveMid != null && validCandles.isNotEmpty()

    val barsJson = remember(validCandles) {
        org.json.JSONArray().apply {
            validCandles.forEach { candle ->
                put(org.json.JSONObject()
                    .put("time", candle.timestamp / 1_000L)
                    .put("open", candle.open)
                    .put("high", candle.high)
                    .put("low", candle.low)
                    .put("close", candle.close))
            }
        }.toString()
    }

    // Cache only fully validated MT5 Bridge snapshots. On disconnect these values do not move,
    // so the chart freezes on the last broker observation and receives a red DISCONNECTED overlay.
    var lastGoodBarsJson by remember(symbol, timeframe) { mutableStateOf<String?>(null) }
    var lastGoodMid by remember(symbol, timeframe) { mutableStateOf<Double?>(null) }
    var lastGoodBroker by remember(symbol, timeframe) { mutableStateOf<String?>(null) }
    LaunchedEffect(ready, barsJson, liveMid, brokerName) {
        if (ready && liveMid != null) {
            lastGoodBarsJson = barsJson
            lastGoodMid = liveMid
            lastGoodBroker = brokerName
        }
    }
    val displayBarsJson = if (ready) barsJson else lastGoodBarsJson
    val displayMid = if (ready) liveMid else lastGoodMid
    val displayBroker = if (ready) brokerName else lastGoodBroker
    if (displayBarsJson == null || displayMid == null) {
        Column(
            modifier = modifier.fillMaxSize().background(Color(0xFF171B27)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = LossRed, modifier = Modifier.size(30.dp))
            Text("DISCONNECTED", color = LossRed, fontWeight = FontWeight.Black, fontSize = 13.sp)
            Text(
                "Waiting for authenticated MT5 Bridge candles and the live broker quote. No other price feed is used.",
                color = TextSecondary, fontSize = 10.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return
    }

    val html = remember(symbol, timeframe, displayBarsJson, displayMid) {
        val midText = displayMid.toString()
        """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <style>
          html,body,#chart{margin:0;width:100%;height:100%;background:#171b27;overflow:hidden}
          #chart{position:absolute;inset:0}
          #status{position:absolute;left:8px;top:7px;z-index:2;color:#9aabbf;font:10px sans-serif;background:#121722dd;padding:4px 6px}
        </style>
        <script src="https://unpkg.com/lightweight-charts@5.3.0/dist/lightweight-charts.standalone.production.js"></script>
        </head><body><div id="chart"></div><div id="status">MT5 BRIDGE · LAST VERIFIED QUOTE</div>
        <script>
          (function(){
            const bars = __BARS__;
            const mid = __MID__;
            const root = document.getElementById('chart');
            if (!window.LightweightCharts || !Array.isArray(bars) || bars.length === 0 || !(mid > 0)) {
              document.getElementById('status').textContent = 'DISCONNECTED · MT5 BRIDGE DATA UNAVAILABLE';
              return;
            }
            const chart = LightweightCharts.createChart(root, {
              width: root.clientWidth, height: root.clientHeight,
              layout: { background: { color: '#171b27' }, textColor: '#c9d2df' },
              grid: { vertLines: { color: '#252d3a' }, horzLines: { color: '#252d3a' } },
              rightPriceScale: { borderColor: '#384252' },
              timeScale: { borderColor: '#384252', timeVisible: true, secondsVisible: false },
              crosshair: { mode: LightweightCharts.CrosshairMode.Normal }
            });
            const candles = chart.addSeries(LightweightCharts.CandlestickSeries, {
              upColor: '#16a879', downColor: '#e05260',
              borderUpColor: '#16a879', borderDownColor: '#e05260',
              wickUpColor: '#16a879', wickDownColor: '#e05260'
            });
            candles.setData(bars);
            candles.createPriceLine({
              price: mid, color: '#20c5e8', lineWidth: 2,
              lineStyle: LightweightCharts.LineStyle.Solid,
              axisLabelVisible: true, title: 'MT5 LIVE QUOTE'
            });
            chart.timeScale().fitContent();
            new ResizeObserver(function(entries) {
              const rect = entries[0].contentRect;
              if (rect.width > 0 && rect.height > 0) chart.applyOptions({width: rect.width, height: rect.height});
            }).observe(root);
          })();
        </script></body></html>
        """.replace("__BARS__", displayBarsJson).replace("__MID__", midText)
    }
    Column(modifier = modifier.fillMaxSize().background(Color(0xFF171B27))) {
        Text(
            if (ready) "Chart: TradingView style | Price: Live from ${displayBroker} via MT5 Bridge"
            else "Chart: TradingView style | Price: Frozen last snapshot from ${displayBroker} via MT5 Bridge",
            color = if (ready) ProfitGreen else LossRed, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setBackgroundColor(android.graphics.Color.rgb(23, 27, 39))
                        overScrollMode = View.OVER_SCROLL_NEVER
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        webViewClient = WebViewClient()
                        tag = html
                        loadDataWithBaseURL("https://unpkg.com/", html, "text/html", "UTF-8", null)
                    }
                },
                update = { view ->
                    if (view.tag != html) {
                        view.tag = html
                        view.loadDataWithBaseURL("https://unpkg.com/", html, "text/html", "UTF-8", null)
                    }
                },
                onRelease = { view ->
                    view.stopLoading()
                    view.webViewClient = WebViewClient()
                    view.removeAllViews()
                    view.destroy()
                },
                modifier = Modifier.fillMaxSize()
            )
            if (!ready) {
                Text(
                    "DISCONNECTED",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .background(LossRed, RoundedCornerShape(4.dp)).padding(horizontal = 9.dp, vertical = 5.dp)
                        .testTag("mt5_chart_disconnected")
                )
            }
        }
        Text(
            if (ready) "Chart data: authenticated MT5 Bridge closed candles and the same bid/ask midpoint shown in Quotes."
            else "Chart frozen at the last authenticated MT5 Bridge candles and quote. Reconnect to resume updates.",
            color = if (ready) TextSecondary else LossRed, fontSize = 9.sp,
            modifier = Modifier.fillMaxWidth().padding(6.dp)
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
            Text("TRADINGVIEW-STYLE LIGHTWEIGHT CHART", fontSize = 11.sp, color = ElectricCyan,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            Text("$symbol • $timeframe", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            Text("DESIGN PREVIEW ONLY — Compose previews cannot render the Lightweight Charts WebView.",
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

    var crosshair by remember(activeSymbol) { mutableStateOf<Offset?>(null) }
    var showEma20 by remember(activeSymbol) { mutableStateOf(false) }
    val ema20 = remember(chartCandles) { calculateEma(chartCandles, 20) }
    Box(modifier = modifier.background(Color(0xFF171B27))) {
        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(activeSymbol, chartCandles) {
                detectTapGestures { point -> crosshair = point }
            }
        ) {
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

            if (showEma20 && ema20.size == totalCandles) {
                for (i in 1 until totalCandles) {
                    val yPrevious = priceToY(ema20[i - 1])
                    val yCurrent = priceToY(ema20[i])
                    if (yPrevious.isFinite() && yCurrent.isFinite()) {
                        val xPrevious = (i - 1) * candleSpacing + candleSpacing / 2f
                        val xCurrent = i * candleSpacing + candleSpacing / 2f
                        drawLine(ElectricCyan, Offset(xPrevious, yPrevious), Offset(xCurrent, yCurrent), 2f)
                    }
                }
            }

            crosshair?.let { point ->
                if (point.x in 0f..w && point.y in 0f..h) {
                    drawLine(
                        TextSecondary.copy(alpha = 0.8f), Offset(point.x, 0f), Offset(point.x, h),
                        1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                    )
                    drawLine(
                        TextSecondary.copy(alpha = 0.8f), Offset(0f, point.y), Offset(w, point.y),
                        1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                    )
                }
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
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                .background(Color(0xCC101720), RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TextButton(onClick = { showEma20 = !showEma20 }) {
                Text(if (showEma20) "EMA 20 ON" else "INDICATORS · EMA 20", color = ElectricCyan, fontSize = 9.sp)
            }
            TextButton(onClick = { crosshair = null }) {
                Text(if (crosshair == null) "CROSSHAIR" else "CLEAR CROSSHAIR", color = TextPrimary, fontSize = 9.sp)
            }
        }
    }
}

private fun calculateEma(candles: List<Candle>, period: Int): List<Double> {
    if (candles.isEmpty() || period < 1) return emptyList()
    val alpha = 2.0 / (period + 1.0)
    val values = ArrayList<Double>(candles.size)
    var current = candles.first().close
    values += current
    candles.drop(1).forEach { candle ->
        current = candle.close * alpha + current * (1.0 - alpha)
        values += current
    }
    return values
}
