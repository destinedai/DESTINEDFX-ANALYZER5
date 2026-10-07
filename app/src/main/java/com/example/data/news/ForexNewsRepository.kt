package com.example.data.news

import android.util.Log
import com.example.domain.repository.NewsEvent
import com.example.domain.repository.NewsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.TimeUnit

class ForexNewsRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
) : NewsRepository {

    private var cache: List<NewsEvent> = emptyList()
    private var lastFetch = 0L
    private val CACHE_TTL = 30 * 60 * 1000L
    private var fetchFailed = false
    @Volatile private var lastCalendar: com.destinedfx.core.news.CalendarResult? = null
    override suspend fun calendar(): com.destinedfx.core.news.CalendarResult? { fetchUpcomingEvents(); return lastCalendar }

    override suspend fun fetchUpcomingEvents(): List<NewsEvent> = withContext(Dispatchers.IO) {
        if (cache.isNotEmpty() && System.currentTimeMillis() - lastFetch < CACHE_TTL && !fetchFailed) {
            return@withContext cache
        }
        try {
            val resp = client.newCall(
                Request.Builder()
                    .url("https://nfs.faireconomy.media/ff_calendar_thisweek.json")
                    .build()
            ).execute()
            if (!resp.isSuccessful) {
                fetchFailed = true
                lastCalendar = com.destinedfx.core.news.CalendarResult.Failed("Calendar HTTP ${resp.code}")
                return@withContext cache
            }
            val body = resp.body?.string() ?: "[]"
            val arr = JSONArray(body)
            val now = System.currentTimeMillis()
            val evts = mutableListOf<NewsEvent>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val dateStr = o.optString("date", "")
                val ms = parseDate(dateStr)
                if (ms <= 0L || ms < now - 3_600_000) continue
                evts.add(
                    NewsEvent(
                        title = o.optString("title", "Event"),
                        currency = o.optString("country", "USD").uppercase(),
                        impact = when (o.optString("impact", "").lowercase()) {
                            "high" -> "High"
                            "medium" -> "Medium"
                            else -> "Low"
                        },
                        dateTimeUtcMs = ms,
                        forecast = o.optString("forecast").ifBlank { null },
                        previous = o.optString("previous").ifBlank { null }
                    )
                )
            }
            cache = evts
            lastCalendar = com.destinedfx.core.news.CalendarResult.Ok(evts.map {
                com.destinedfx.core.news.EconomicEvent(it.title, it.currency, it.currency, when (it.impact) { "High" -> com.destinedfx.core.news.NewsImpact.HIGH; "Medium" -> com.destinedfx.core.news.NewsImpact.MEDIUM; else -> com.destinedfx.core.news.NewsImpact.LOW },
                    it.dateTimeUtcMs, null, it.forecast, it.previous, "ForexFactory weekly feed (faireconomy)", now) }, now, now - 3_600_000L, now + 6 * 86_400_000L)
            lastFetch = now
            fetchFailed = false
            cache
        } catch (e: Exception) {
            Log.e("ForexNews", "Failed to fetch calendar: ${e.javaClass.simpleName}")
            fetchFailed = true
            lastCalendar = com.destinedfx.core.news.CalendarResult.Failed("Network/parse error: ${e.javaClass.simpleName}")
            cache
        }
    }

    override suspend fun isHighImpactWindowActive(currencies: List<String>, bufferMinutes: Int): Boolean {
        val now = System.currentTimeMillis()
        val sixHoursMs = 6 * 60 * 60 * 1000L
        
        val events = try {
            fetchUpcomingEvents()
        } catch (e: Exception) {
            fetchFailed = true
            cache
        }
        
        // If fetch failed and cache is empty or older than 6 hours, return true to block trading
        if (fetchFailed && (events.isEmpty() || now - lastFetch > sixHoursMs)) {
            return true
        }

        val buf = bufferMinutes * 60_000L
        return events.any {
            it.impact == "High" && it.currency in currencies && Math.abs(it.dateTimeUtcMs - now) <= buf
        }
    }

    override suspend fun getNextHighImpactEvent(currency: String): NewsEvent? {
        val now = System.currentTimeMillis()
        return fetchUpcomingEvents()
            .filter { it.impact == "High" && it.currency == currency && it.dateTimeUtcMs > now }
            .minByOrNull { it.dateTimeUtcMs }
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return -1L
        return try {
            OffsetDateTime.parse(dateStr).toInstant().toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            try {
                val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                format.parse(dateStr)?.time ?: -1L
            } catch (e: java.text.ParseException) {
                -1L   // unparseable time: the event is skipped, never assigned "now"
            }
        }
    }
}
