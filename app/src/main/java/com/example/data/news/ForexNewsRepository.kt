package com.example.data.news

import android.util.Log
import com.example.domain.repository.NewsEvent
import com.example.domain.repository.NewsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.time.OffsetDateTime
import java.util.*
import java.text.ParsePosition
import java.util.concurrent.TimeUnit

class ForexNewsRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheTtlMs: Long = 30 * 60 * 1000L
) : NewsRepository {

    private var cache: List<NewsEvent> = emptyList()
    private var lastFetch = 0L
    private var fetchFailed = false
    @Volatile private var lastCalendar: com.destinedfx.core.news.CalendarResult? = null
    override suspend fun calendar(): com.destinedfx.core.news.CalendarResult? { fetchUpcomingEvents(); return lastCalendar }

    override suspend fun fetchUpcomingEvents(): List<NewsEvent> = withContext(Dispatchers.IO) {
        val requestStartedAt = clock()
        if (lastFetch > 0L && requestStartedAt >= lastFetch && requestStartedAt - lastFetch < cacheTtlMs && !fetchFailed) {
            return@withContext cache
        }
        try {
            client.newCall(
                Request.Builder()
                    .url("https://nfs.faireconomy.media/ff_calendar_thisweek.json")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    fetchFailed = true
                    lastCalendar = com.destinedfx.core.news.CalendarResult.Failed("Calendar HTTP ${resp.code}")
                    return@withContext cache
                }
                val body = resp.body?.string()
                    ?: return@withContext failCalendar("Empty calendar response")
                val now = clock()
                val evts = parseFeedPayload(body, now)
                cache = evts
                lastCalendar = com.destinedfx.core.news.CalendarResult.Ok(evts.map {
                    com.destinedfx.core.news.EconomicEvent(it.title, it.currency, it.currency,
                        when (it.impact) {
                            "High" -> com.destinedfx.core.news.NewsImpact.HIGH
                            "Medium" -> com.destinedfx.core.news.NewsImpact.MEDIUM
                            "Low" -> com.destinedfx.core.news.NewsImpact.LOW
                            else -> throw JSONException("Unknown impact")
                        }, it.dateTimeUtcMs, null, it.forecast, it.previous,
                        "ForexFactory weekly feed (faireconomy)", now)
                }, now, now - 3_600_000L, now + 6 * 86_400_000L, CALENDAR_SOURCE)
                lastFetch = now
                fetchFailed = false
                cache
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("ForexNews", "Failed to fetch calendar: ${e.javaClass.simpleName}")
            fetchFailed = true
            lastCalendar = com.destinedfx.core.news.CalendarResult.Failed("Network/parse error: ${e.javaClass.simpleName}")
            cache
        }
    }

    private fun failCalendar(reason: String): List<NewsEvent> {
        fetchFailed = true
        lastCalendar = com.destinedfx.core.news.CalendarResult.Failed(reason)
        return cache
    }

    internal fun parseFeedPayload(body: String, now: Long): List<NewsEvent> {
        val arr = JSONArray(body)
        val out = mutableListOf<NewsEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw JSONException("Malformed calendar event at index $i")
            val dateStr = required(o.optString("date"), "timestamp")
            val ms = parseDate(dateStr)
            if (ms <= 0L) throw JSONException("Invalid calendar timestamp")
            if (ms < now - 3_600_000L) continue
            val title = required(o.optString("title"), "event title")
            val currency = required(o.optString("country"), "event currency").uppercase(Locale.ROOT)
            val impact = when (o.optString("impact").trim().lowercase(Locale.ROOT)) {
                "high" -> "High"
                "medium" -> "Medium"
                "low" -> "Low"
                else -> throw JSONException("Unknown calendar impact")
            }
            out += NewsEvent(title, currency, impact, ms,
                o.optString("forecast").takeUnless { it.isBlank() || it == "null" },
                o.optString("previous").takeUnless { it.isBlank() || it == "null" })
        }
        return out
    }

    private fun required(value: String, label: String): String =
        value.takeUnless { it.isBlank() || it == "null" } ?: throw JSONException("Calendar $label is missing")

    override suspend fun isHighImpactWindowActive(currencies: List<String>, bufferMinutes: Int): Boolean {
        val now = clock()
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
        val now = clock()
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
                    isLenient = false
                }
                val position = ParsePosition(0)
                val parsed = format.parse(dateStr, position)
                parsed?.time?.takeIf { position.index == dateStr.length } ?: -1L
            } catch (e: java.text.ParseException) {
                -1L   // unparseable time: the event is skipped, never assigned "now"
            }
        }
    }

    private companion object {
        const val CALENDAR_SOURCE = "ForexFactory weekly feed (faireconomy)"
    }
}
