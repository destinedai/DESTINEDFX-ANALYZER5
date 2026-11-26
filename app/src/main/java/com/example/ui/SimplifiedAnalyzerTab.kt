package com.example.ui

import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LossRed
import com.example.ui.theme.ProfitGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@Composable
fun SimplifiedAnalyzerTab(viewModel: DestinedFXViewModel, onExportAnalysis: () -> Unit = {}) {
    val snapshot by viewModel.managedMt5BridgeSnapshot.collectAsStateWithLifecycle()
    val mode by viewModel.selectedMt5Mode.collectAsStateWithLifecycle()
    val brand by viewModel.selectedMt5BrokerBrand.collectAsStateWithLifecycle()
    val analyzerState: AnalyzerViewModel = composeViewModel()

    LaunchedEffect(brand, mode) { viewModel.startSelectedMt5AnalyzerBridgeMonitor() }

    val connected = snapshot?.let {
        it.connected && it.terminalConnected && it.terminalOnline && it.heartbeatFresh &&
            it.accountVerified && it.brokerIdentityVerified && it.actualAccountMode == mode
    } == true
    val symbol = snapshot?.activeChartSymbol?.takeIf { it.isNotBlank() }
        ?: snapshot?.brokerQuotes?.firstOrNull()?.symbol?.takeIf { it.isNotBlank() }
        ?: "EURUSD"
    val timeframe = snapshot?.activeChartTimeframe?.takeIf { it.isNotBlank() } ?: "M15"

    val quote = if (connected) snapshot?.brokerQuotes?.firstOrNull {
        it.symbol.equals(symbol, true) && it.source == "MT5_TERMINAL" &&
            it.bid.isFinite() && it.ask.isFinite() && it.bid > 0.0 && it.ask >= it.bid
    } else null
    val candles = if (connected) snapshot?.brokerCandles.orEmpty().filter {
        it.symbol.equals(symbol, true) && it.timeframe.equals(timeframe, true) &&
            it.source == "MT5_TERMINAL" && it.complete
    } else emptyList()

    val price = quote?.let { ((it.bid + it.ask) / 2.0).takeIf { value -> value.isFinite() && value > 0.0 } }
    val spread = quote?.let { (it.ask - it.bid).takeIf { value -> value.isFinite() && value >= 0.0 } }
    val priceQuoteAvailable = price != null
    val priceQuoteFresh = priceQuoteAvailable && connected && snapshot?.heartbeatFresh == true
    LaunchedEffect(priceQuoteAvailable, priceQuoteFresh) {
        analyzerState.updatePriceState(priceQuoteAvailable, priceQuoteFresh)
    }

    val insight = remember(quote, candles) { MT5AnalyzerMath.analyze(quote, candles) }
    val support = insight.support
    val resistance = insight.resistance
    val stopLoss = insight.stopLoss
    val takeProfit = insight.takeProfit
    val trendValidated = when (insight.trend) {
        "BUY" -> insight.reason.startsWith("Price above MA20")
        "SELL" -> insight.reason.startsWith("Price below MA20")
        else -> false
    }
    val levelsValidated = support?.let { it.isFinite() && it > 0.0 } == true &&
        resistance?.let { it.isFinite() && it >= (support ?: Double.POSITIVE_INFINITY) } == true &&
        stopLoss?.let { it.isFinite() && it > 0.0 } == true &&
        takeProfit?.let { it.isFinite() && it > 0.0 } == true &&
        when (insight.trend) {
            "BUY" -> stopLoss!! < (price ?: 0.0) && takeProfit!! > (price ?: 0.0)
            "SELL" -> stopLoss!! > (price ?: 0.0) && takeProfit!! < (price ?: 0.0)
            else -> false
        }
    val analysisValidated = trendValidated && levelsValidated
    val showPrice = priceQuoteAvailable && priceQuoteFresh &&
        analyzerState.priceAvailable && analyzerState.priceFresh
    val showAnalysis = showPrice && analysisValidated
    val chartTicker = analyzerTradingViewTicker(symbol) ?: "FX:EURUSD"

    Column(
        Modifier.fillMaxSize().background(DarkBackground).verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(symbol, color = TextPrimary, fontSize = 21.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    Box(Modifier.size(10.dp).background(if (connected) ProfitGreen else Color.Gray, CircleShape))
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("PRICE", color = TextSecondary, fontSize = 9.sp)
                        Text(if (showPrice) {
                            price?.let { formatPrice(it, symbol) } ?: "--"
                        } else "--", color = TextPrimary, fontSize = 29.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("SPREAD", color = TextSecondary, fontSize = 9.sp)
                        Text(if (showPrice) {
                            spread?.let { formatPrice(it, symbol) } ?: "--"
                        } else "--", color = TextPrimary, fontSize = 15.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            AnalyzerTradingViewChart(chartTicker, timeframe, analyzerState,
                Modifier.fillMaxWidth().height(270.dp))
        }

        if (showAnalysis) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("SIGNAL", color = ElectricCyan, fontSize = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(insight.trend,
                            color = when (insight.trend) { "BUY" -> ProfitGreen; "SELL" -> LossRed; else -> WarningAmber },
                            fontSize = 27.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                        Spacer(Modifier.weight(1f))
                        Text("${insight.confidence}%", color = TextPrimary, fontSize = 21.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Text(insight.reason, color = TextSecondary, fontSize = 12.sp)
                }
            }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("LEVELS", color = ElectricCyan, fontSize = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    AnalyzerLevelValue("Support", support, symbol)
                    AnalyzerLevelValue("Resistance", resistance, symbol)
                    AnalyzerLevelValue("SL suggestion", stopLoss, symbol)
                    AnalyzerLevelValue("TP suggestion", takeProfit, symbol)
                }
            }
        }

        EconomicCalendarCard(symbol)
    }
}

@Composable
private fun AnalyzerLevelValue(label: String, value: Double?, symbol: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(value?.let { formatPrice(it, symbol) } ?: "—", color = TextPrimary, fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

private val analyzerCalendarCurrencies = setOf(
    "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "SEK", "NOK",
    "DKK", "SGD", "HKD", "CNH", "CNY", "MXN", "ZAR", "TRY", "PLN", "HUF", "CZK"
)

private fun analyzerTradingViewTicker(mt5Symbol: String): String? {
    val raw = mt5Symbol.trim().uppercase(Locale.ROOT).substringAfterLast(':').substringBefore('.')
    val compact = raw.filter { it in 'A'..'Z' }
    if (compact.length < 6) return null
    val pair = compact.take(6)
    if (pair.startsWith("XAU") && pair.endsWith("USD")) return "TVC:GOLD"
    if (pair.startsWith("XAG") && pair.endsWith("USD")) return "TVC:SILVER"
    val base = pair.substring(0, 3)
    val quote = pair.substring(3, 6)
    return if (base in analyzerCalendarCurrencies && quote in analyzerCalendarCurrencies) "FX:$pair" else null
}

private fun analyzerTradingViewInterval(timeframe: String): String = when (timeframe.trim().uppercase(Locale.ROOT)) {
    "M1" -> "1"
    "M5" -> "5"
    "M15" -> "15"
    "M30" -> "30"
    "H1" -> "60"
    "H4" -> "240"
    "D1" -> "1D"
    "W1" -> "1W"
    else -> "15"
}

@Composable
private fun AnalyzerTradingViewChart(
    ticker: String,
    timeframe: String,
    analyzerState: AnalyzerViewModel,
    modifier: Modifier = Modifier
) {
    val url = remember(ticker, timeframe) {
        "https://www.tradingview.com/widgetembed/?frameElementId=destinedfx_analyzer" +
            "&symbol=${URLEncoder.encode(ticker, "UTF-8")}" +
            "&interval=${analyzerTradingViewInterval(timeframe)}" +
            "&hidesidetoolbar=1&hide_top_toolbar=1&symboledit=0&saveimage=0" +
            "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=0&hideideas=1&locale=en"
    }

    Box(modifier.background(Color(0xFF171B27))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.blockNetworkLoads = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    setBackgroundColor(android.graphics.Color.rgb(23, 27, 39))
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, pageUrl, favicon)
                            if (pageUrl?.startsWith("https://www.tradingview.com/widgetembed/") == true) {
                                analyzerState.onChartPageStarted()
                            }
                        }

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            super.onPageFinished(view, pageUrl)
                            if (pageUrl?.startsWith("https://www.tradingview.com/widgetembed/") == true && view != null) {
                                analyzerState.onChartPageLoaded()
                                pollAnalyzerChartRendered(view, analyzerState)
                            }
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) analyzerState.onChartPageFailed()
                        }

                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceResponse?) {
                            super.onReceivedHttpError(view, request, error)
                            if (request?.isForMainFrame == true && (error?.statusCode ?: 200) >= 400) {
                                analyzerState.onChartPageFailed()
                            }
                        }
                    }
                }
            },
            update = { webView ->
                if (webView.tag != url) {
                    analyzerState.onChartPageStarted()
                    webView.tag = url
                    webView.stopLoading()
                    webView.loadUrl(url)
                }
            }
        )
    }
}

private fun pollAnalyzerChartRendered(
    webView: WebView,
    analyzerState: AnalyzerViewModel,
    attempt: Int = 0
) {
    val script = "(function(){var nodes=document.querySelectorAll('iframe,canvas,svg');" +
        "for(var i=0;i<nodes.length;i++){var r=nodes[i].getBoundingClientRect();" +
        "if(r.width>0&&r.height>0)return true;}return false;})();"
    webView.evaluateJavascript(script) { result ->
        val rendered = result == "true"
        analyzerState.onChartRendered(rendered)
        if (!rendered && attempt < 40) {
            webView.postDelayed({
                if (webView.isAttachedToWindow) pollAnalyzerChartRendered(webView, analyzerState, attempt + 1)
            }, 500L)
        }
    }
}

private data class AnalyzerEconomicEvent(
    val title: String,
    val currency: String,
    val impact: String,
    val timeMillis: Long
)

private const val ANALYZER_CALENDAR_URL = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"
private const val ANALYZER_CALENDAR_PREFS = "destinedfx_economic_calendar"
private const val ANALYZER_CALENDAR_PAYLOAD = "weekly_payload"
private const val ANALYZER_CALENDAR_UPDATED = "last_updated"
private const val ANALYZER_CALENDAR_REFRESH_MS = 15 * 60 * 1000L

private fun downloadAnalyzerCalendar(): String {
    val connection = (URL(ANALYZER_CALENDAR_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 12_000
        readTimeout = 12_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "DestinedFX-Android")
    }
    return try {
        val status = connection.responseCode
        if (status !in 200..299) throw IllegalStateException("Calendar HTTP $status")
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun parseAnalyzerCalendar(raw: String): List<AnalyzerEconomicEvent> {
    val json = JSONArray(raw)
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { isLenient = false }
    return buildList {
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            val title = item.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val currency = item.optString("country").uppercase(Locale.ROOT)
            val impact = item.optString("impact").ifBlank { "Low" }
            val dateText = item.optString("date")
            val time = runCatching { parser.parse(dateText)?.time }.getOrNull() ?: continue
            add(AnalyzerEconomicEvent(title, currency, impact, time))
        }
    }.sortedBy { it.timeMillis }
}

private fun analyzerCurrenciesForSymbol(symbol: String): Set<String> {
    val compact = symbol.trim().uppercase(Locale.ROOT).substringAfterLast(':').substringBefore('.')
        .filter { it in 'A'..'Z' }
    if (compact.length < 6) return emptySet()
    val base = compact.take(3)
    val quote = compact.drop(3).take(3)
    if (base == "XAU" || base == "XAG") return setOf(quote).filter { it in analyzerCalendarCurrencies }.toSet()
    return setOf(base, quote).filter { it in analyzerCalendarCurrencies }.toSet()
}

private fun analyzerLocalDateKey(timeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(timeMillis))

private fun analyzerLocalTime(timeMillis: Long, pattern: String = "HH:mm"): String =
    SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = TimeZone.getDefault() }.format(Date(timeMillis))

private fun analyzerCountdown(targetMillis: Long, nowMillis: Long): String {
    val remaining = (targetMillis - nowMillis).coerceAtLeast(0L)
    val hours = TimeUnit.MILLISECONDS.toHours(remaining)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
    return when {
        hours > 0 -> "in ${hours}h ${minutes}m"
        minutes > 0 -> "in ${minutes}m"
        else -> "in less than 1m"
    }
}

@Composable
private fun EconomicCalendarCard(symbol: String) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(ANALYZER_CALENDAR_PREFS, android.content.Context.MODE_PRIVATE)
    }
    var events by remember { mutableStateOf(emptyList<AnalyzerEconomicEvent>()) }
    var lastUpdated by remember { mutableStateOf(0L) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(preferences) {
        preferences.getString(ANALYZER_CALENDAR_PAYLOAD, null)?.let { cached ->
            events = runCatching { parseAnalyzerCalendar(cached) }.getOrDefault(emptyList())
            lastUpdated = preferences.getLong(ANALYZER_CALENDAR_UPDATED, 0L)
        }
        while (true) {
            try {
                val downloaded = withContext(Dispatchers.IO) {
                    val raw = downloadAnalyzerCalendar()
                    raw to parseAnalyzerCalendar(raw)
                }
                events = downloaded.second
                lastUpdated = System.currentTimeMillis()
                preferences.edit()
                    .putString(ANALYZER_CALENDAR_PAYLOAD, downloaded.first)
                    .putLong(ANALYZER_CALENDAR_UPDATED, lastUpdated)
                    .apply()
            } catch (_: Exception) {
                // Keep the last successful calendar in memory and in the local cache.
            }
            delay(ANALYZER_CALENDAR_REFRESH_MS)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    val currencies = remember(symbol) { analyzerCurrenciesForSymbol(symbol) }
    val todayKey = remember(nowMillis) { analyzerLocalDateKey(nowMillis) }
    val tomorrowKey = remember(nowMillis) {
        val tomorrow = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, 1)
        }
        analyzerLocalDateKey(tomorrow.timeInMillis)
    }
    val visibleEvents = remember(events, currencies, todayKey, tomorrowKey) {
        events.filter { event ->
            event.currency in currencies &&
                (analyzerLocalDateKey(event.timeMillis) == todayKey ||
                    analyzerLocalDateKey(event.timeMillis) == tomorrowKey)
        }.sortedBy { it.timeMillis }
    }
    val nextEvent = visibleEvents.firstOrNull { it.timeMillis > nowMillis }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ECONOMIC CALENDAR", color = ElectricCyan, fontSize = 11.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
            if (nextEvent != null) {
                Text("NEXT: ${nextEvent.title} · ${analyzerCountdown(nextEvent.timeMillis, nowMillis)}",
                    color = TextPrimary, fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            if (visibleEvents.isEmpty()) {
                Text(
                    if (lastUpdated > 0L) "No events scheduled for these currencies today or tomorrow."
                    else "Calendar data unavailable.",
                    color = TextSecondary, fontSize = 12.sp
                )
            } else {
                var lastDisplayedDate: String? = null
                visibleEvents.forEach { event ->
                    val eventDateKey = analyzerLocalDateKey(event.timeMillis)
                    if (eventDateKey != lastDisplayedDate) {
                        Text(if (eventDateKey == todayKey) "TODAY" else "TOMORROW",
                            color = ElectricCyan, fontSize = 9.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        lastDisplayedDate = eventDateKey
                    }
                    val impactColor = when (event.impact.uppercase(Locale.ROOT)) {
                        "HIGH" -> LossRed
                        "MEDIUM" -> WarningAmber
                        else -> TextSecondary
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(analyzerLocalTime(event.timeMillis), color = TextSecondary, fontSize = 10.sp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(event.title, color = TextPrimary, fontSize = 11.sp)
                            Text(event.currency, color = TextSecondary, fontSize = 9.sp)
                        }
                        Text(event.impact.uppercase(Locale.ROOT), color = impactColor, fontSize = 9.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
            }
            if (lastUpdated > 0L) {
                Text("Last updated: ${analyzerLocalTime(lastUpdated, "dd MMM, HH:mm")}",
                    color = TextSecondary, fontSize = 9.sp)
            }
        }
    }
}