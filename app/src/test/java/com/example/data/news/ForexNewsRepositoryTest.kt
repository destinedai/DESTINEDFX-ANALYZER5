package com.example.data.news

import com.destinedfx.core.news.CalendarResult
import com.destinedfx.core.news.NewsFilter
import com.destinedfx.core.news.NewsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ForexNewsRepositoryTest {
    private val now = OffsetDateTime.parse("2026-11-01T12:00:00Z").toInstant().toEpochMilli()

    @Test
    fun parsesUpcomingEventWithExplicitCurrencyAndImpact() {
        val repository = ForexNewsRepository()
        val payload = """[{"title":"CPI","country":"USD","date":"2026-11-02T12:30:00Z","impact":"High","forecast":"2.1","previous":"2.2"}]"""
        val event = repository.parseFeedPayload(payload, now).single()
        assertEquals("CPI", event.title)
        assertEquals("USD", event.currency)
        assertEquals("High", event.impact)
        assertEquals("2.1", event.forecast)
        assertEquals("2.2", event.previous)
        assertEquals(OffsetDateTime.parse("2026-11-02T12:30:00Z").toInstant().toEpochMilli(), event.dateTimeUtcMs)
    }

    @Test
    fun emptyArrayIsAValidEmptySnapshotButMalformedRowsStillFail() {
        val repository = ForexNewsRepository()
        assertEquals(emptyList<Any>(), repository.parseFeedPayload("[]", now))
        assertParseFails(repository, """[{"title":"CPI","country":"USD","date":"2026-11-02T12:30:00Z","impact":"unexpected"}]""")
        assertParseFails(repository, """[{"title":"CPI","country":"USD","date":"not-a-date","impact":"High"}]""")
        assertParseFails(repository, """[{"title":"CPI","date":"2026-11-02T12:30:00Z","impact":"High"}]""")
    }

    @Test
    fun validEmptyProviderResponseClearsOldRowsAndRetainsCoverageMetadata() = runBlocking {
        val current = AtomicLong(now)
        val requests = AtomicInteger(0)
        val client = queuedClient(requests, listOf(
            200 to """[{"title":"CPI","country":"USD","date":"2026-11-01T13:00:00Z","impact":"High"}]""",
            200 to "[]"
        ))
        val repository = ForexNewsRepository(client, current::get, 30 * 60 * 1000L)

        val initial = repository.calendar() as CalendarResult.Ok
        assertEquals(1, initial.events.size)
        assertEquals("ForexFactory weekly feed (faireconomy)", initial.source)

        current.addAndGet(31 * 60 * 1000L)
        val refreshed = repository.calendar() as CalendarResult.Ok
        assertEquals(emptyList<Any>(), refreshed.events)
        assertEquals(current.get(), refreshed.retrievedAtMs)
        assertEquals("ForexFactory weekly feed (faireconomy)", refreshed.source)
        assertEquals(current.get() - 3_600_000L, refreshed.coverageFromMs)
        assertEquals(current.get() + 6 * 86_400_000L, refreshed.coverageToMs)
        val verdict = NewsFilter.evaluate(refreshed, setOf("USD"), current.get())
        assertEquals(NewsStatus.CLEAR, verdict.status)
        assertTrueContains(verdict.detail, "ForexFactory weekly feed")

        // Empty successful snapshots are cached too; no second provider hit is needed.
        assertEquals(emptyList<Any>(), repository.fetchUpcomingEvents())
        assertEquals(2, requests.get())
    }

    @Test
    fun providerHttpErrorIsUnknownAndNotEquivalentToSuccessfulEmptyCalendar() = runBlocking {
        val client = queuedClient(AtomicInteger(), listOf(503 to "maintenance"))
        val repository = ForexNewsRepository(client, { now }, 0L)
        val result = repository.calendar()
        assertEquals(CalendarResult.Failed("Calendar HTTP 503"), result)
        assertEquals(NewsStatus.UNKNOWN, NewsFilter.evaluate(result, setOf("USD"), now).status)
    }

    @Test
    fun cancellationIsPropagatedRatherThanConvertedToAnEmptyOrFailedSnapshot() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { throw CancellationException("test cancellation") }).build()
        val repository = ForexNewsRepository(client, { now })
        try {
            repository.calendar()
            fail("Cancellation must propagate to the caller")
        } catch (_: CancellationException) {
            // Expected: cancellation must not be transformed into a provider result.
        }
    }

    private fun queuedClient(requests: AtomicInteger, responses: List<Pair<Int, String>>): OkHttpClient {
        return OkHttpClient.Builder().addInterceptor { chain ->
            val index = requests.getAndIncrement()
            val (code, body) = responses.getOrElse(index) { 500 to "unexpected request" }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Failure")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
    }

    private fun assertParseFails(repository: ForexNewsRepository, payload: String) {
        try {
            repository.parseFeedPayload(payload, now)
            fail("Expected malformed or incomplete provider data to be rejected")
        } catch (_: JSONException) {
            // Fail-closed parsing is expected.
        }
    }

    private fun assertTrueContains(actual: String, expected: String) {
        if (!actual.contains(expected)) fail("Expected <$actual> to contain <$expected>")
    }
}
