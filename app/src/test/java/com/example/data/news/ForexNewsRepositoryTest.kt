package com.example.data.news

import com.example.data.news.ForexNewsRepository
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.time.OffsetDateTime

class ForexNewsRepositoryTest {
    private val repository = ForexNewsRepository()
    private val now = OffsetDateTime.parse("2026-11-01T12:00:00Z").toInstant().toEpochMilli()

    @Test fun parsesUpcomingEventWithExplicitCurrencyAndImpact() {
        val payload = """[{"title":"CPI","country":"USD","date":"2026-11-02T12:30:00Z","impact":"High","forecast":"2.1","previous":"2.2"}]"""
        val event = repository.parseFeedPayload(payload, now).single()
        assertEquals("CPI", event.title)
        assertEquals("USD", event.currency)
        assertEquals("High", event.impact)
        assertEquals("2.1", event.forecast)
        assertEquals("2.2", event.previous)
        assertEquals(OffsetDateTime.parse("2026-11-02T12:30:00Z").toInstant().toEpochMilli(), event.dateTimeUtcMs)
    }

    @Test fun unknownImpactAndEmptyFeedAreNotReportedAsClear() {
        val unknownImpact = """[{"title":"CPI","country":"USD","date":"2026-11-02T12:30:00Z","impact":"unexpected"}]"""
        assertParseFails(unknownImpact)
        assertParseFails("[]")
    }

    @Test fun malformedTimestampOrMissingCurrencyFailClosed() {
        assertParseFails("""[{"title":"CPI","country":"USD","date":"not-a-date","impact":"High"}]""")
        assertParseFails("""[{"title":"CPI","date":"2026-11-02T12:30:00Z","impact":"High"}]""")
    }

    private fun assertParseFails(payload: String) {
        try {
            repository.parseFeedPayload(payload, now)
            fail("Expected malformed or incomplete provider data to be rejected")
        } catch (_: JSONException) {
            // Fail-closed parsing is the expected result.
        }
    }
}
