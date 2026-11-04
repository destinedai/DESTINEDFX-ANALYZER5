package com.example.ui.workstations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinedfx.core.instrument.AssetClassifier
import com.destinedfx.core.news.CalendarResult
import com.destinedfx.core.news.EconomicEvent
import com.destinedfx.core.news.NewsFilter
import com.destinedfx.core.news.NewsImpact
import com.destinedfx.core.news.NewsStatus
import com.example.ui.DestinedFXViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

private val utcZone: TimeZone get() = TimeZone.getTimeZone("UTC")
private fun dateKey(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utcZone }.format(Date(ms))
private fun dateLabel(ms: Long) = SimpleDateFormat("EEE, dd MMM", Locale.US).apply { timeZone = utcZone }.format(Date(ms))
private fun eventTime(ms: Long) = SimpleDateFormat("dd MMM HH:mm", Locale.US).apply { timeZone = utcZone }.format(Date(ms))

@Composable
fun CalendarTab(viewModel: DestinedFXViewModel) {
    val preview = LocalInspectionMode.current || viewModel.isPreview
    var events by remember { mutableStateOf<List<EconomicEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(!preview) }
    var newsStatus by remember { mutableStateOf(NewsStatus.UNKNOWN) }
    var detail by remember { mutableStateOf(if (preview) "Preview only — live calendar data is not loaded." else "Evaluating verified calendar coverage…") }
    var sourceRetrieved by remember { mutableStateOf<String?>(null) }
    var selectedDate by remember { mutableStateOf("All dates") }
    var selectedCurrency by remember { mutableStateOf("All") }
    var selectedImpact by remember { mutableStateOf("All") }
    var dateMenu by remember { mutableStateOf(false) }
    var currencyMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val symbol by viewModel.selectedTicker.collectAsStateWithLifecycle()
    val refreshGeneration = remember { AtomicLong(0L) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    fun refreshCalendar() {
        if (preview) return
        refreshJob?.cancel()
        val requestId = refreshGeneration.incrementAndGet()
        val requestedSymbol = symbol
        refreshJob = scope.launch {
            isLoading = true
            events = emptyList()
            sourceRetrieved = null
            newsStatus = NewsStatus.UNKNOWN
            detail = "Refreshing verified calendar coverage…"
            try {
                // One snapshot drives both the event rows and the execution-safety verdict.
                val result = withContext(Dispatchers.IO) { viewModel.app.newsRepository.calendar() }
                if (requestId != refreshGeneration.get()) return@launch
                val verdict = NewsFilter.evaluate(result, AssetClassifier.newsCurrencies(requestedSymbol), System.currentTimeMillis())
                if (requestId == refreshGeneration.get()) newsStatus = verdict.status
                if (result is CalendarResult.Ok) {
                    events = result.events.sortedBy { it.timeMs }
                    val retrieved = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = utcZone }.format(Date(result.retrievedAtMs))
                    sourceRetrieved = "Source: ${result.source} · Retrieved: $retrieved"
                } else {
                    events = emptyList()
                    sourceRetrieved = null
                }
                if (requestId == refreshGeneration.get()) detail = "Evaluated for $requestedSymbol · ${verdict.detail}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (requestId == refreshGeneration.get()) {
                    events = emptyList()
                    sourceRetrieved = null
                    newsStatus = NewsStatus.UNKNOWN
                    detail = "Calendar refresh cancelled. Automated execution remains blocked until a verified result is loaded."
                }
                throw e
            } catch (e: Exception) {
                if (requestId == refreshGeneration.get()) {
                    events = emptyList()
                    sourceRetrieved = null
                    newsStatus = NewsStatus.UNKNOWN
                    detail = "Calendar unavailable (${e.javaClass.simpleName}). Automated execution remains blocked until verified news data is available."
                }
            } finally {
                if (requestId == refreshGeneration.get()) {
                    isLoading = false
                    refreshJob = null
                }
            }
        }
    }
    LaunchedEffect(symbol) { if (!preview) refreshCalendar() }
    DisposableEffect(Unit) {
        onDispose {
            refreshGeneration.incrementAndGet()
            refreshJob?.cancel()
        }
    }

    val dates = remember(events) { listOf("All dates") + events.map { dateKey(it.timeMs) }.distinct().sorted() }
    val currencies = remember(events) { listOf("All") + events.map { it.currency }.distinct().sorted() }
    LaunchedEffect(dates, currencies) {
        if (selectedDate !in dates) selectedDate = "All dates"
        if (selectedCurrency !in currencies) selectedCurrency = "All"
    }
    val shown = remember(events, selectedDate, selectedCurrency, selectedImpact) {
        events.filter { event ->
            (selectedDate == "All dates" || dateKey(event.timeMs) == selectedDate) &&
                (selectedCurrency == "All" || event.currency.equals(selectedCurrency, true)) &&
                (selectedImpact == "All" || event.impact.name.equals(selectedImpact, true))
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(DarkBackground).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            val color = when (newsStatus) { NewsStatus.CLEAR -> ProfitGreen; NewsStatus.HIGH_IMPACT_WINDOW -> LossRed; NewsStatus.UNKNOWN -> WarningAmber }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), border = BorderStroke(1.dp, CardBorder)) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(36.dp).background(ElectricCyan.copy(alpha = .12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CalendarMonth, null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("EVENT RISK DESK", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                            Text("Economic calendar", fontSize = 20.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text(if (preview) "PREVIEW · NO LIVE DATA" else "Currency relevance: $symbol", fontSize = 10.sp, color = TextSecondary)
                        }
                        IconButton(onClick = { refreshCalendar() }, modifier = Modifier.testTag("refresh_calendar_button")) { Icon(Icons.Default.Refresh, "Refresh verified calendar", tint = ElectricCyan) }
                    }
                    HorizontalDivider(color = CardBorder)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(SimpleDateFormat("EEE, dd MMM yyyy", Locale.US).apply { timeZone = utcZone }.format(Date()), fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold); Text("All event times in UTC", fontSize = 9.sp, color = TextSecondary) }
                        Surface(color = color.copy(alpha = .13f), shape = RoundedCornerShape(6.dp)) {
                            Text(when (newsStatus) { NewsStatus.CLEAR -> "CLEAR"; NewsStatus.HIGH_IMPACT_WINDOW -> "HIGH IMPACT"; NewsStatus.UNKNOWN -> if (isLoading) "CHECKING" else "UNKNOWN" }, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 9.sp, color = color, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        item {
            val bg = when (newsStatus) { NewsStatus.CLEAR -> ProfitGreen.copy(alpha = .10f); NewsStatus.HIGH_IMPACT_WINDOW -> LossRed.copy(alpha = .13f); NewsStatus.UNKNOWN -> OrangeAccent.copy(alpha = .13f) }
            val border = when (newsStatus) { NewsStatus.CLEAR -> ProfitGreen; NewsStatus.HIGH_IMPACT_WINDOW -> LossRed; NewsStatus.UNKNOWN -> OrangeAccent }
            val title = when (newsStatus) { NewsStatus.CLEAR -> "NEWS WINDOW: CLEAR"; NewsStatus.HIGH_IMPACT_WINDOW -> "NEWS GATE: BLOCKED · HIGH IMPACT"; NewsStatus.UNKNOWN -> "NEWS GATE: UNKNOWN · AUTOMATION BLOCKED" }
            Card(colors = CardDefaults.cardColors(containerColor = bg), border = BorderStroke(1.dp, border), shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth().testTag("news_gate_banner")) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(if (newsStatus == NewsStatus.CLEAR) Icons.Default.CalendarMonth else Icons.Default.Warning, null, tint = border, modifier = Modifier.size(19.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = border)
                        Text(detail, fontSize = 10.sp, color = TextPrimary)
                        sourceRetrieved?.let { Text(it, fontSize = 9.sp, color = TextSecondary, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Stat("EVENTS", events.size.toString(), ElectricCyan, Modifier.weight(1f))
                Stat("HIGH", events.count { it.impact == NewsImpact.HIGH }.toString(), LossRed, Modifier.weight(1f))
                Stat("MEDIUM", events.count { it.impact == NewsImpact.MEDIUM }.toString(), OrangeAccent, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { dateMenu = true }, modifier = Modifier.fillMaxWidth().height(38.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (selectedDate == "All dates") "Date: All" else "Date: ${selectedDate.takeLast(5)}", fontSize = 10.sp, maxLines = 1) }
                    DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                        dates.forEach { value -> DropdownMenuItem(text = { Text(if (value == "All dates") value else dateLabel(events.firstOrNull { dateKey(it.timeMs) == value }?.timeMs ?: 0L)) }, onClick = { selectedDate = value; dateMenu = false }) }
                    }
                }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { currencyMenu = true }, modifier = Modifier.fillMaxWidth().height(38.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("CCY: $selectedCurrency", fontSize = 10.sp, maxLines = 1) }
                    DropdownMenu(expanded = currencyMenu, onDismissRequest = { currencyMenu = false }) { currencies.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { selectedCurrency = value; currencyMenu = false }) } }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("All", "High", "Medium", "Low").forEach { impact -> FilterChip(selected = selectedImpact.equals(impact, true), onClick = { selectedImpact = impact }, label = { Text(impact, fontSize = 10.sp) }, modifier = Modifier.height(31.dp)) }
            }
        }
        item { EventHeader() }
        if (isLoading) {
            item { Box(Modifier.fillMaxWidth().padding(26.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = CyanPrimary, modifier = Modifier.size(26.dp)) } }
        } else if (events.isEmpty()) {
            item { EmptyState(if (preview) "Preview only — live calendar data is not loaded." else "No verified calendar rows are currently available. This is not evidence of a clear market.") }
        } else if (shown.isEmpty()) {
            item { EmptyState("No verified events match these filters.") }
        } else {
            items(shown, key = { "${it.timeMs}-${it.currency}-${it.name}" }) { EconomicEventCard(it) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, CardBorder)) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) { Text(label, fontSize = 8.sp, color = TextSecondary, fontFamily = FontFamily.Monospace); Text(value, fontSize = 15.sp, color = color, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun EventHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("UTC", Modifier.width(56.dp), fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
        Text("CCY", Modifier.width(39.dp), fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
        Text("EVENT", Modifier.weight(1f), fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
        Text("IMPACT", Modifier.width(44.dp), fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyState(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Text(message, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(13.dp)) }
}

@Composable
fun EconomicEventCard(evt: EconomicEvent) {
    val impactColor = when (evt.impact) { NewsImpact.HIGH -> LossRed; NewsImpact.MEDIUM -> OrangeAccent; NewsImpact.LOW -> ProfitGreen }
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, TabBorder), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(eventTime(evt.timeMs), Modifier.width(56.dp), fontSize = 9.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                Text(evt.currency, Modifier.width(39.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CyanPrimary, fontFamily = FontFamily.Monospace)
                Text(evt.name, Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 2)
                Text(evt.impact.name, Modifier.width(44.dp), fontSize = 8.sp, fontWeight = FontWeight.Black, color = impactColor, fontFamily = FontFamily.Monospace)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Metric("ACTUAL", evt.actual, Modifier.weight(1f)); Metric("FORECAST", evt.forecast, Modifier.weight(1f)); Metric("PREVIOUS", evt.previous, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, fontSize = 7.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
        Text(value?.takeIf { it.isNotBlank() } ?: "—", fontSize = 9.sp, color = TextPrimary, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}
