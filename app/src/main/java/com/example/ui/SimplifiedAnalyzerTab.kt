package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.trade.MT5AnalyzerMarketFeedClient
import com.example.data.trade.MT5RemoteBridgeClient
import com.example.data.trade.VerifiedBrokerCandle
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@Composable
fun SimplifiedAnalyzerTab(viewModel: DestinedFXViewModel, onExportAnalysis: () -> Unit = {}) {
    val secrets = viewModel.app.secretsManager
    val selectedMt5Mode by viewModel.selectedMt5Mode.collectAsStateWithLifecycle()
    val selectedMt5BrokerBrand by viewModel.selectedMt5BrokerBrand.collectAsStateWithLifecycle()
    val selectedMt5Profile = "${selectedMt5BrokerBrand}_${selectedMt5Mode}"
    val expectedAccountLogin = remember(secrets, selectedMt5Profile) {
        secrets.getManagedMT5AccountLogin(selectedMt5Profile)
    }
    val expectedAccountServer = remember(secrets, selectedMt5Profile) {
        secrets.getManagedMT5AccountServer(selectedMt5Profile)
    }
    val serviceOrigin = remember(secrets) {
        MT5RemoteBridgeClient.initialServiceUrl(
            secrets.getManagedMT5ServiceUrl(),
            com.example.BuildConfig.MT5_MANAGED_SERVICE_URL,
            com.example.BuildConfig.DESTINEDFX_ENDPOINTS_LOCKED,
            com.example.BuildConfig.DESTINEDFX_CODESPACES_HOST_DISCOVERY
        )
    }
    val accessId = remember(secrets) { secrets.getCloudflareAccessClientId() }
    val accessSecret = remember(secrets) { secrets.getCloudflareAccessClientSecret() }
    val feedClient = remember(serviceOrigin, accessId, accessSecret, expectedAccountLogin, expectedAccountServer, selectedMt5Mode) {
        if (serviceOrigin.isBlank()) null else runCatching {
            MT5AnalyzerMarketFeedClient(
                serviceOrigin, accessId, accessSecret,
                expectedAccountLogin, expectedAccountServer, selectedMt5Mode
            )
        }.getOrNull()
    }
    var snapshot by remember(feedClient) {
        mutableStateOf<MT5AnalyzerMarketFeedClient.Snapshot?>(null)
    }
    var connectionError by remember(feedClient) {
        mutableStateOf(if (feedClient == null) "MT5 not connected" else "")
    }
    var selectedSymbol by remember(feedClient) { mutableStateOf("") }
    var selectedTimeframe by remember(feedClient) { mutableStateOf("M15") }
    var symbolMenuExpanded by remember { mutableStateOf(false) }
    var timeframeMenuExpanded by remember { mutableStateOf(false) }

    // The standalone Analyzer feed is read-only and does not start or inspect Bot pairing.
    LaunchedEffect(feedClient) {
        if (feedClient == null) {
            snapshot = null
            connectionError = "MT5 not connected"
            return@LaunchedEffect
        }
        while (true) {
            try {
                val fresh = feedClient.state()
                snapshot = fresh
                connectionError = fresh.errorMessage
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshot = null
                connectionError = "MT5 not connected"
            }
            delay(1_000L)
        }
    }

    val symbols = snapshot?.symbols.orEmpty()
    val symbol = selectedSymbol.takeIf { it.isNotBlank() }
        ?: snapshot?.activeChartSymbol?.takeIf { it in symbols }
        ?: symbols.firstOrNull()
        ?: ""
    val timeframe = selectedTimeframe

    LaunchedEffect(feedClient, symbol, timeframe) {
        if (feedClient != null && symbol.isNotBlank()) {
            runCatching { feedClient.select(symbol, timeframe) }
        }
    }

    val connected = snapshot?.connected == true && snapshot?.heartbeatFresh == true
    val quote = if (connected && symbol.isNotBlank()) {
        snapshot?.brokerQuotes?.singleOrNull { it.symbol.equals(symbol, true) }
    } else null
    val allSymbolCandles = if (connected && symbol.isNotBlank()) {
        snapshot?.brokerCandles.orEmpty().filter {
            it.symbol.equals(symbol, true) && it.source == "MT5_TERMINAL" && it.complete
        }
    } else emptyList()
    val candles = allSymbolCandles.filter { it.timeframe.equals(timeframe, true) }
        .sortedBy { it.timestampMs }
    val price = quote?.bid?.takeIf { it.isFinite() && it > 0.0 }
    val priceQuoteFresh = connected && quote != null && quote.ageMs in 0L..15_000L &&
        quote.timestampMs > 0L
    val insight = remember(quote, allSymbolCandles, timeframe) {
        MT5AnalyzerMath.analyze(quote, allSymbolCandles, timeframe)
    }
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
    val showPrice = priceQuoteFresh

    Column(
        Modifier.fillMaxSize().background(DarkBackground).verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        Text(symbol.ifBlank { "Select pair" }, color = TextPrimary, fontSize = 21.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                            modifier = Modifier.clickable { symbolMenuExpanded = true })
                        DropdownMenu(expanded = symbolMenuExpanded, onDismissRequest = { symbolMenuExpanded = false }) {
                            symbols.forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = {
                                    selectedSymbol = option
                                    symbolMenuExpanded = false
                                })
                            }
                        }
                    }
                    Text(timeframe, color = ElectricCyan, fontSize = 13.sp,
                        modifier = Modifier.clickable { timeframeMenuExpanded = true })
                    DropdownMenu(expanded = timeframeMenuExpanded, onDismissRequest = { timeframeMenuExpanded = false }) {
                        listOf("M1", "M5", "M15", "M30", "H1", "H4", "D1", "W1").forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = {
                                selectedTimeframe = option
                                timeframeMenuExpanded = false
                            })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(9.dp).background(if (showPrice) ProfitGreen else Color.Gray, CircleShape))
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("BID", color = TextSecondary, fontSize = 9.sp)
                        Text(
                            if (showPrice) formatAnalyzerTerminalPrice(quote!!.bid, quote!!.digits) else "--",
                            color = TextPrimary, fontSize = 27.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("ASK", color = TextSecondary, fontSize = 9.sp)
                        Text(
                            if (showPrice) formatAnalyzerTerminalPrice(quote!!.ask, quote!!.digits) else "--",
                            color = TextPrimary, fontSize = 19.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
                if (!connected) {
                    Text("MT5 not connected", color = TextSecondary, fontSize = 10.sp)
                } else if (!showPrice) {
                    Text(
                        connectionError.ifBlank { "Waiting for live MT5 quote" },
                        color = TextSecondary, fontSize = 10.sp
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            AnalyzerBrokerCandlestickChart(candles, symbol, timeframe, Modifier.fillMaxWidth().height(270.dp))
        }

        if (showPrice) {
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
                    Text("${insight.higherTimeframe}  ·  ${insight.lowerTimeframe}",
                        color = TextPrimary, fontSize = 11.sp)
                    Text("${insight.structure}  ·  ${insight.marketState}",
                        color = TextSecondary, fontSize = 11.sp)
                }
            }
            if (analysisValidated) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("LEVELS", color = ElectricCyan, fontSize = 11.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                        AnalyzerLevelValue("Support", support, symbol)
                        AnalyzerLevelValue("Resistance", resistance, symbol)
                        AnalyzerLevelValue("Swing low", insight.swingLow, symbol)
                        AnalyzerLevelValue("Swing high", insight.swingHigh, symbol)
                        AnalyzerLevelValue("SL suggestion", stopLoss, symbol)
                        AnalyzerLevelValue("TP suggestion", takeProfit, symbol)
                    }
                }
            }
        }

        if (symbol.isNotBlank()) EconomicCalendarCard(symbol)
    }
}

private fun formatAnalyzerTerminalPrice(value: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits.coerceIn(0, 12)}f", value)

@Composable
private fun AnalyzerBrokerCandlestickChart(
    candles: List<VerifiedBrokerCandle>,
    symbol: String,
    timeframe: String,
    modifier: Modifier = Modifier
) {
    val visible = remember(candles, symbol, timeframe) {
        candles.filter { it.symbol.equals(symbol, true) && it.timeframe.equals(timeframe, true) && it.source == "MT5_TERMINAL" && it.complete }
            .sortedBy { it.timestampMs }
            .takeLast(60)
    }
    Box(modifier.background(Color(0xFF171B27)), contentAlignment = Alignment.Center) {
        if (visible.size < 2) {
            Text("Waiting for broker candles", color = TextSecondary, fontSize = 12.sp)
        } else {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val rawLow = visible.minOf { it.low }
                val rawHigh = visible.maxOf { it.high }
                val rawRange = (rawHigh - rawLow).takeIf { it.isFinite() && it > 0.0 } ?: (rawHigh * 0.001)
                val low = rawLow - rawRange * 0.05
                val high = rawHigh + rawRange * 0.05
                val range = (high - low).coerceAtLeast(1e-12)
                fun y(priceValue: Double): Float = ((high - priceValue) / range * size.height.toDouble()).toFloat()
                val slot = size.width / visible.size.toFloat()
                val bodyWidth = (slot * 0.58f).coerceAtLeast(2f)
                val wickWidth = 1.dp.toPx()
                visible.forEachIndexed { index, candle ->
                    val x = (index + 0.5f) * slot
                    val wickColor = if (candle.close >= candle.open) ProfitGreen else LossRed
                    drawLine(wickColor, Offset(x, y(candle.high)), Offset(x, y(candle.low)), strokeWidth = wickWidth)
                    val bodyTop = minOf(y(candle.open), y(candle.close))
                    val bodyHeight = kotlin.math.abs(y(candle.open) - y(candle.close)).coerceAtLeast(2.dp.toPx())
                    drawRect(wickColor, Offset(x - bodyWidth / 2f, bodyTop), Size(bodyWidth, bodyHeight))
                }
            }
        }
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