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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalInspectionMode
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CalendarTab(viewModel: DestinedFXViewModel) {
    val isPreviewMode = LocalInspectionMode.current || viewModel.isPreview
    val sampleEvents = remember {
        listOf(
            NewsEvent(
                title = "Non-Farm Employment Change (USD)",
                currency = "USD",
                impact = "High",
                dateTimeUtcMs = System.currentTimeMillis() + 3600000,
                forecast = "180K",
                previous = "175K"
            ),
            NewsEvent(
                title = "ECB Main Refinancing Rate",
                currency = "EUR",
                impact = "High",
                dateTimeUtcMs = System.currentTimeMillis() + 7200000,
                forecast = "3.75%",
                previous = "3.75%"
            ),
            NewsEvent(
                title = "Core CPI m/m",
                currency = "USD",
                impact = "High",
                dateTimeUtcMs = System.currentTimeMillis() + 10800000,
                forecast = "0.3%",
                previous = "0.2%"
            ),
            NewsEvent(
                title = "German Prelim Flash Manufacturing PMI",
                currency = "EUR",
                impact = "Medium",
                dateTimeUtcMs = System.currentTimeMillis() + 14400000,
                forecast = "42.8",
                previous = "42.4"
            )
        )
    }

    var events by remember { mutableStateOf<List<NewsEvent>>(if (isPreviewMode) sampleEvents else emptyList()) }
    var isLoading by remember { mutableStateOf(!isPreviewMode) }
    var newsStatus by remember { mutableStateOf(if (isPreviewMode) NewsStatus.CLEAR else NewsStatus.UNKNOWN) }
    var statusDetail by remember {
        mutableStateOf(
            if (isPreviewMode) "No high-impact event window active. Automated trading permitted."
            else "Evaluating economic calendar news filter and event windows..."
        )
    }

    val coroutineScope = rememberCoroutineScope()
    val activeSymbol by viewModel.selectedTicker.collectAsStateWithLifecycle()

    fun refreshCalendar() {
        if (isPreviewMode) return
        coroutineScope.launch {
            isLoading = true
            val evts = withContext(Dispatchers.IO) {
                viewModel.app.newsRepository.fetchUpcomingEvents()
            }
            events = evts
            val cal = withContext(Dispatchers.IO) {
                viewModel.app.newsRepository.calendar()
            }
            val verdict = NewsFilter.evaluate(cal, AssetClassifier.newsCurrencies(activeSymbol), System.currentTimeMillis())
            newsStatus = verdict.status
            statusDetail = verdict.detail
            isLoading = false
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ECONOMIC CALENDAR",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )
                    Text(
                        text = "High-Impact News Safeguards (NFP, CPI, FOMC)",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
                IconButton(
                    onClick = { refreshCalendar() },
                    modifier = Modifier.testTag("refresh_calendar_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Calendar", tint = CyanPrimary)
                }
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
                        text = "No upcoming high-impact economic releases in current window.",
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
