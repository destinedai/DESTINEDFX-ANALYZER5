package com.example.ui.workstations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinedfx.core.instrument.AssetClassifier
import com.destinedfx.core.news.NewsFilter
import com.destinedfx.core.news.NewsStatus
import com.example.domain.repository.NewsEvent
import com.example.ui.DestinedFXViewModel
import com.example.ui.theme.*
import com.example.ui.components.TerminalPageHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalInspectionMode
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CalendarTab(viewModel: DestinedFXViewModel) {
    val isPreviewMode = LocalInspectionMode.current || viewModel.isPreview
    var events by remember { mutableStateOf<List<NewsEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(!isPreviewMode) }
    var newsStatus by remember { mutableStateOf(NewsStatus.UNKNOWN) }
    var statusDetail by remember {
        mutableStateOf(
            if (isPreviewMode) "Preview only — live calendar data is not loaded in preview mode."
            else "Evaluating economic calendar news filter and event windows..."
        )
    }

    val coroutineScope = rememberCoroutineScope()
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()

    fun refreshCalendar() {
        if (isPreviewMode) return
        coroutineScope.launch {
            isLoading = true
            try {
                val fetchedEvents = withContext(Dispatchers.IO) { viewModel.app.newsRepository.fetchUpcomingEvents() }
                events = fetchedEvents
                val result = withContext(Dispatchers.IO) { viewModel.app.newsRepository.calendar() }
                val verdict = NewsFilter.evaluate(result, AssetClassifier.newsCurrencies(activeSymbol), System.currentTimeMillis())
                newsStatus = verdict.status
                statusDetail = verdict.detail
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                events = emptyList()
                newsStatus = NewsStatus.UNKNOWN
                statusDetail = "Calendar unavailable (${e.javaClass.simpleName}). Automated execution remains blocked until verified news data is available."
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(activeSymbol) {
        if (!isPreviewMode) {
            refreshCalendar()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val statusColor = when (newsStatus) {
                NewsStatus.CLEAR -> ProfitGreen
                NewsStatus.HIGH_IMPACT_WINDOW -> LossRed
                NewsStatus.UNKNOWN -> WarningAmber
            }
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111A28)), border = BorderStroke(1.dp, CardBorder)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(ElectricCyan.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(23.dp))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("EVENT RISK DESK", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                            Text("Economic calendar", fontSize = 22.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                            Text(if (isPreviewMode) "PREVIEW · NO LIVE CALENDAR DATA" else "Currency relevance: $activeSymbol",
                                fontSize = 10.sp, color = TextSecondary)
                        }
                        IconButton(onClick = { refreshCalendar() }, modifier = Modifier.testTag("refresh_calendar_button")) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh verified calendar", tint = ElectricCyan)
                        }
                    }
                    HorizontalDivider(color = CardBorder)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(SimpleDateFormat("EEE, dd MMM yyyy", Locale.US).format(Date()),
                                fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text("Times shown in UTC", fontSize = 9.sp, color = TextSecondary)
                        }
                        Box(Modifier.clip(RoundedCornerShape(7.dp)).background(statusColor.copy(alpha = 0.13f))
                            .padding(horizontal = 9.dp, vertical = 6.dp)) {
                            Text(when (newsStatus) {
                                NewsStatus.CLEAR -> "CLEAR"
                                NewsStatus.HIGH_IMPACT_WINDOW -> "HIGH IMPACT"
                                NewsStatus.UNKNOWN -> if (isLoading) "CHECKING" else "UNKNOWN"
                            }, fontSize = 9.sp, color = statusColor, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.example.ui.components.StatChip(
                    label = "EVENTS LOADED",
                    value = events.size.toString(),
                    modifier = Modifier.weight(1f),
                    color = ElectricCyan
                )
                com.example.ui.components.StatChip(
                    label = "HIGH IMPACT",
                    value = events.count { it.impact.equals("high", ignoreCase = true) }.toString(),
                    modifier = Modifier.weight(1f),
                    color = Color(0xFFFF4560)
                )
                com.example.ui.components.StatChip(
                    label = "MEDIUM",
                    value = events.count { it.impact.equals("medium", ignoreCase = true) }.toString(),
                    modifier = Modifier.weight(1f),
                    color = OrangeAccent
                )
            }
        }

        // Live News Gate Status Banner
        item {
            val bannerBg = when (newsStatus) {
                NewsStatus.CLEAR -> Color(0xFF00FF99).copy(alpha = 0.12f)
                NewsStatus.HIGH_IMPACT_WINDOW -> Color(0xFFFF4560).copy(alpha = 0.15f)
                NewsStatus.UNKNOWN -> OrangeAccent.copy(alpha = 0.15f)
            }
            val bannerBorder = when (newsStatus) {
                NewsStatus.CLEAR -> Color(0xFF00FF99)
                NewsStatus.HIGH_IMPACT_WINDOW -> Color(0xFFFF4560)
                NewsStatus.UNKNOWN -> OrangeAccent
            }
            val bannerTitle = when (newsStatus) {
                NewsStatus.CLEAR -> "NEWS GATE: CLEAR (TRADING ALLOWED)"
                NewsStatus.HIGH_IMPACT_WINDOW -> "NEWS GATE: BLOCKED (HIGH IMPACT EVENT ACTIVE)"
                NewsStatus.UNKNOWN -> "NEWS GATE: UNKNOWN (AUTOMATION BLOCKED)"
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = bannerBg),
                border = BorderStroke(1.dp, bannerBorder),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("news_gate_banner")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (newsStatus == NewsStatus.CLEAR) Icons.Default.CalendarMonth else Icons.Default.Warning,
                        contentDescription = null,
                        tint = bannerBorder,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = bannerTitle,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = bannerBorder
                        )
                        Text(
                            text = statusDetail,
                            fontSize = 10.sp,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = CyanPrimary, modifier = Modifier.size(28.dp))
                }
            }
        } else if (events.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No verified events are available for this window. Calendar status remains unknown; this is not confirmation of a clear market.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(events) { evt ->
                EconomicEventCard(evt)
            }
        }
    }
}

@Composable
fun EconomicEventCard(evt: NewsEvent) {
    val impactColor = when (evt.impact.lowercase()) {
        "high" -> Color(0xFFFF4560)
        "medium" -> OrangeAccent
        else -> Color(0xFF00FF99)
    }

    val dateFmt = remember { SimpleDateFormat("EEE, MMM dd HH:mm 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    val formattedTime = dateFmt.format(Date(evt.dateTimeUtcMs))

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, TabBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(CyanPrimary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = evt.currency,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(impactColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = evt.impact.uppercase(),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = impactColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Text(
                    text = formattedTime,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = evt.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Forecast: ${evt.forecast ?: "N/A"}",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Previous: ${evt.previous ?: "N/A"}",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
