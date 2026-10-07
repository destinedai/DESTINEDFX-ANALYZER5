package com.example.domain.engine

import com.destinedfx.core.session.Killzone as CoreKillzone
import com.destinedfx.core.session.Session
import com.destinedfx.core.session.SessionEngine as CoreSessionEngine

enum class MarketSession { SYDNEY, TOKYO, LONDON, NEW_YORK, NONE }
enum class Killzone { LONDON_KILLZONE, NEW_YORK_KILLZONE, NONE }
data class SessionContext(val activeSessions: List<MarketSession>, val activeKillzone: Killzone, val confidenceModifier: Double)

/** Delegates to the single timezone-aware core SessionEngine (DST handled by IANA zones). The old UTC-hour tables and score "modifier" are gone. */
class SessionEngine {
    companion object {
        fun evaluateSessionContext(timestampMs: Long): SessionContext {
            val s = CoreSessionEngine.state(timestampMs)
            val sessions = s.active.map { MarketSession.valueOf(it.name) }
            val kz = when { CoreKillzone.LONDON in s.killzones -> Killzone.LONDON_KILLZONE; CoreKillzone.NEW_YORK in s.killzones -> Killzone.NEW_YORK_KILLZONE; else -> Killzone.NONE }
            return SessionContext(sessions, kz, 1.0)   // sessions are a validation gate, never a hidden score multiplier
        }
        fun getActiveSessions(timestampMs: Long) = evaluateSessionContext(timestampMs).activeSessions
        fun getActiveKillzone(timestampMs: Long) = evaluateSessionContext(timestampMs).activeKillzone
        @Deprecated("Sessions no longer scale scores") fun getSessionConfidenceModifier(timestampMs: Long) = 1.0
    }
}
