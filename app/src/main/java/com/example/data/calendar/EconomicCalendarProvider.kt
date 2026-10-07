package com.example.data.calendar

import com.destinedfx.core.news.CalendarResult
import com.destinedfx.core.news.EconomicEvent as VerifiedEvent
import com.destinedfx.core.news.NewsImpact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Verified provider data only (Financial Modeling Prep). The old calendar "projection" that fabricated NFP/CPI/FOMC dates is deleted.
 * Any failure is returned as [CalendarResult.Failed] so callers report NEWS STATUS = UNKNOWN instead of "no news".
 */
class EconomicCalendarProvider(private val client: OkHttpClient, private val fmpApiKeyProvider: () -> String) {

    suspend fun fetch(nowMs: Long = System.currentTimeMillis()): CalendarResult = withContext(Dispatchers.IO) {
        val key = fmpApiKeyProvider().trim()
        if (key.isBlank() || key.startsWith("YOUR_")) return@withContext CalendarResult.Failed("FMP API key not configured")
        val from = fmt(nowMs - 86_400_000L); val to = fmt(nowMs + 7 * 86_400_000L)
        try {
            val req = Request.Builder().url("https://financialmodelingprep.com/api/v3/economic_calendar?from=$from&to=$to&apikey=$key").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext CalendarResult.Failed("Calendar HTTP ${resp.code}")
                val arr = JSONArray(resp.body?.string() ?: return@withContext CalendarResult.Failed("Empty calendar body"))
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val out = ArrayList<VerifiedEvent>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val t = sdf.parse(o.optString("date"))?.time ?: continue     // unparseable time => skip, never "now"
                    out += VerifiedEvent(o.optString("event"), o.optString("country"), o.optString("currency").uppercase(),
                        when (o.optString("impact").lowercase()) { "high" -> NewsImpact.HIGH; "medium" -> NewsImpact.MEDIUM; else -> NewsImpact.LOW },
                        t, o.optString("actual").takeUnless { it.isBlank() || it == "null" }, o.optString("estimate").takeUnless { it.isBlank() || it == "null" },
                        o.optString("previous").takeUnless { it.isBlank() || it == "null" }, "FinancialModelingPrep", nowMs)
                }
                if (out.isEmpty()) CalendarResult.Failed("Calendar returned no events (cannot confirm absence of news)")
                else CalendarResult.Ok(out, nowMs, nowMs - 86_400_000L, nowMs + 7 * 86_400_000L)
            }
        } catch (e: IOException) { CalendarResult.Failed("Network error: ${e.javaClass.simpleName}") }
        catch (e: JSONException) { CalendarResult.Failed("Malformed calendar response") }
    }
    private fun fmt(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)
}
