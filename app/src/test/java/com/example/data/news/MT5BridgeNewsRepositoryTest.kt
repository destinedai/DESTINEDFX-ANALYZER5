package com.example.data.news

import com.destinedfx.core.news.CalendarResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MT5BridgeNewsRepositoryTest {
    @Test
    fun acceptsFreshBridgeClearVerdictWithCompleteCoverage() {
        val now = 1_800_000_000_000L
        val verdict = JSONObject()
            .put("status", "CLEAR")
            .put("source", "Verified MT5 Bridge calendar")
            .put("retrieved_at_ms", now - 1_000L)
            .put("coverage_from_ms", now - 60_000L)
            .put("coverage_to_ms", now + 86_400_000L)
            .put("affected_currencies", org.json.JSONArray(listOf("EUR", "USD")))
        val result = MT5BridgeNewsRepository.bridgeCalendarResult(verdict, now)
        assertTrue(result is CalendarResult.Ok)
        assertEquals(emptyList<Any>(), (result as CalendarResult.Ok).events)
    }

    @Test
    fun blocksOnBridgeHighImpactVerdictAndKeepsRealEventTime() {
        val now = 1_800_000_000_000L
        val eventTime = now + 5 * 60_000L
        val verdict = JSONObject()
            .put("status", "HIGH_IMPACT_WINDOW")
            .put("source", "Verified MT5 Bridge calendar")
            .put("retrieved_at_ms", now - 1_000L)
            .put("coverage_from_ms", now - 60_000L)
            .put("coverage_to_ms", now + 86_400_000L)
            .put("event_time_ms", eventTime)
            .put("currency", "USD")
            .put("detail", "High-impact USD event")
        val result = MT5BridgeNewsRepository.bridgeCalendarResult(verdict, now)
        assertTrue(result is CalendarResult.Ok)
        val event = (result as CalendarResult.Ok).events.single()
        assertEquals(eventTime, event.timeMs)
        assertEquals("USD", event.currency)
    }

    @Test
    fun rejectsUnknownStaleAndUnrepresentableBridgeVerdicts() {
        val now = 1_800_000_000_000L
        fun base(status: String) = JSONObject()
            .put("status", status)
            .put("source", "Verified MT5 Bridge calendar")
            .put("retrieved_at_ms", now - 1_000L)
            .put("coverage_from_ms", now - 60_000L)
            .put("coverage_to_ms", now + 86_400_000L)
        assertTrue(MT5BridgeNewsRepository.bridgeCalendarResult(base("UNKNOWN"), now) is CalendarResult.Failed)
        assertTrue(MT5BridgeNewsRepository.bridgeCalendarResult(base("CLEAR").put("retrieved_at_ms", now - 16 * 60_000L), now) is CalendarResult.Failed)
        val tooWide = base("HIGH_IMPACT_WINDOW").put("event_time_ms", now + 2 * 60 * 60_000L).put("currency", "USD")
        assertTrue(MT5BridgeNewsRepository.bridgeCalendarResult(tooWide, now) is CalendarResult.Failed)
    }
}
