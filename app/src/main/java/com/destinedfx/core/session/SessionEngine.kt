package com.destinedfx.core.session

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class Session { SYDNEY, TOKYO, LONDON, NEW_YORK }
enum class Killzone { LONDON, NEW_YORK }

/** Local-time windows in real IANA zones, so daylight-saving shifts are handled by the tz database, not by UTC constants. */
data class SessionDef(val zone: ZoneId, val open: LocalTime, val close: LocalTime)

data class SessionState(val active: Set<Session>, val killzones: Set<Killzone>) {
    val label: String get() = if (active.isEmpty()) "OFF-HOURS" else active.joinToString("+") { it.name }
}

object SessionEngine {
    val defs: Map<Session, SessionDef> = mapOf(
        Session.SYDNEY to SessionDef(ZoneId.of("Australia/Sydney"), LocalTime.of(7, 0), LocalTime.of(16, 0)),
        Session.TOKYO to SessionDef(ZoneId.of("Asia/Tokyo"), LocalTime.of(9, 0), LocalTime.of(18, 0)),
        Session.LONDON to SessionDef(ZoneId.of("Europe/London"), LocalTime.of(8, 0), LocalTime.of(17, 0)),
        Session.NEW_YORK to SessionDef(ZoneId.of("America/New_York"), LocalTime.of(8, 0), LocalTime.of(17, 0))
    )
    /** Killzones expressed in New York local time (London 02:00-05:00, NY 07:00-10:00); override via parameter if your method differs. */
    val killzoneDefs: Map<Killzone, SessionDef> = mapOf(
        Killzone.LONDON to SessionDef(ZoneId.of("America/New_York"), LocalTime.of(2, 0), LocalTime.of(5, 0)),
        Killzone.NEW_YORK to SessionDef(ZoneId.of("America/New_York"), LocalTime.of(7, 0), LocalTime.of(10, 0))
    )
    private fun inWindow(nowMs: Long, d: SessionDef): Boolean {
        val dt = Instant.ofEpochMilli(nowMs).atZone(d.zone)
        if (dt.dayOfWeek.value >= 6) return false         // weekend in that local zone
        val t = dt.toLocalTime()
        return if (d.open <= d.close) t >= d.open && t < d.close else t >= d.open || t < d.close
    }
    fun state(nowMs: Long): SessionState = SessionState(
        Session.values().filter { inWindow(nowMs, defs.getValue(it)) }.toSet(),
        Killzone.values().filter { inWindow(nowMs, killzoneDefs.getValue(it)) }.toSet())

    /** Session start (epoch ms) of the most recent open of [s] at or before [nowMs] - used for session high/low liquidity. */
    fun lastOpenMs(s: Session, nowMs: Long): Long {
        val d = defs.getValue(s); var z = Instant.ofEpochMilli(nowMs).atZone(d.zone)
        var open = z.toLocalDate().atTime(d.open).atZone(d.zone)
        if (open.isAfter(z)) open = open.minusDays(1)
        return open.toInstant().toEpochMilli()
    }
}
