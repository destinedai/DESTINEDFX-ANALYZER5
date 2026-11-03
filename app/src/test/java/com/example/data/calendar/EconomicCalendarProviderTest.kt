package com.example.data.calendar

import com.destinedfx.core.news.CalendarResult
import com.destinedfx.core.news.NewsFilter
import com.destinedfx.core.news.NewsStatus
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EconomicCalendarProviderTest {
    private val now = 1_798_000_000_000L

    @Test
    fun explicitEmptyArrayIsSuccessfulCoveredResultWithSourceAndRetrievalTime() = runBlocking {
        val provider = EconomicCalendarProvider(client(200, "[]")) { "test-key" }
        val result = provider.fetch(now) as CalendarResult.Ok

        assertTrue(result.events.isEmpty())
        assertEquals(now, result.retrievedAtMs)
        assertEquals(now - 86_400_000L, result.coverageFromMs)
        assertEquals(now + 7 * 86_400_000L, result.coverageToMs)
        assertEquals("FinancialModelingPrep", result.source)
        val verdict = NewsFilter.evaluate(result, setOf("USD"), now)
        assertEquals(NewsStatus.CLEAR, verdict.status)
        assertTrue(verdict.detail.contains("FinancialModelingPrep"))
    }

    @Test
    fun malformedNonemptyPayloadAndHttpFailureRemainUnknown() = runBlocking {
        val malformed = EconomicCalendarProvider(client(200, "[{}]")) { "test-key" }.fetch(now)
        assertEquals(NewsStatus.UNKNOWN, NewsFilter.evaluate(malformed, setOf("USD"), now).status)

        val httpFailure = EconomicCalendarProvider(client(503, "maintenance")) { "test-key" }.fetch(now)
        assertEquals(NewsStatus.UNKNOWN, NewsFilter.evaluate(httpFailure, setOf("USD"), now).status)
    }

    private fun client(status: Int, body: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status in 200..299) "OK" else "Failure")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()
}
