package com.example.data.news

import com.destinedfx.core.news.CalendarResult
import com.destinedfx.core.news.EconomicEvent
import com.destinedfx.core.news.NewsFilter
import com.destinedfx.core.news.NewsImpact
import com.example.domain.repository.NewsEvent
import com.example.domain.repository.NewsRepository
import com.example.security.SecretsManager
import com.example.data.trade.MT5RemoteBridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Analyzer's news gate is read from the selected, identity-verified MT5 Bridge snapshot.
 * This class intentionally has no direct HTTP client or third-party URL.
 */
class MT5BridgeNewsRepository(
    private val secrets: SecretsManager,
    private val clock: () -> Long = System::currentTimeMillis
) : NewsRepository {

    override suspend fun calendar(): CalendarResult = withContext(Dispatchers.IO) {
        try {
            val brand = secrets.getSelectedMT5BrokerBrand().trim().uppercase(Locale.ROOT)
            val mode = secrets.getManagedMT5SelectedProfile().trim().uppercase(Locale.ROOT)
            val profile = "${brand}_${mode}"
            val login = secrets.getManagedMT5AccountLogin(profile).trim()
            val server = secrets.getManagedMT5AccountServer(profile).trim()
            if (brand.isBlank() || mode !in setOf("DEMO", "LIVE") ||
                login.isBlank() || server.isBlank() ||
                secrets.getMT5BridgeAccountAppToken(profile).isBlank()
            ) return@withContext CalendarResult.Failed("No selected authenticated MT5 Bridge profile")

            val snapshot = MT5RemoteBridgeClient(
                secrets = secrets,
                profile = profile,
                accountMode = mode,
                brokerBrand = brand
            ).state()
            if (!snapshot.connected || !snapshot.terminalOnline || !snapshot.heartbeatFresh ||
                !snapshot.terminalConnected || !snapshot.accountVerified || !snapshot.brokerIdentityVerified ||
                snapshot.actualAccountMode != mode || snapshot.actualLogin != login || snapshot.actualServer != server
            ) return@withContext CalendarResult.Failed("Selected MT5 Bridge account/terminal identity is not fresh and verified")

            bridgeCalendarResult(snapshot.newsVerdict, clock())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CalendarResult.Failed("Authenticated MT5 Bridge news verdict unavailable")
        }
    }

    override suspend fun fetchUpcomingEvents(): List<NewsEvent> =
        (calendar() as? CalendarResult.Ok)?.events.orEmpty().map { event ->
            NewsEvent(event.name, event.currency, event.impact.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() },
                event.timeMs, event.forecast, event.previous)
        }

    override suspend fun isHighImpactWindowActive(currencies: List<String>, bufferMinutes: Int): Boolean {
        val verdict = NewsFilter.evaluate(calendar(), currencies.map { it.uppercase(Locale.ROOT) }.toSet(), clock(),
            beforeMs = bufferMinutes.coerceAtLeast(0) * 60_000L)
        return verdict.status != com.destinedfx.core.news.NewsStatus.CLEAR
    }

    override suspend fun getNextHighImpactEvent(currency: String): NewsEvent? =
        fetchUpcomingEvents().filter {
            it.impact == "High" && it.currency.equals(currency, ignoreCase = true) && it.dateTimeUtcMs > clock()
        }.minByOrNull { it.dateTimeUtcMs }

    companion object {
        internal fun bridgeCalendarResult(verdict: JSONObject?, nowMs: Long): CalendarResult {
            if (verdict == null || nowMs <= 0L) return CalendarResult.Failed("Bridge did not return a news verdict")
            val status = verdict.optString("status").uppercase(Locale.ROOT)
            val source = verdict.optString("source").trim()
            val retrievedAt = verdict.optLong("retrieved_at_ms", 0L)
            val coverageFrom = verdict.optLong("coverage_from_ms", 0L)
            val coverageTo = verdict.optLong("coverage_to_ms", 0L)
            if (source.isBlank() || retrievedAt <= 0L || coverageFrom <= 0L || coverageTo <= 0L ||
                retrievedAt > nowMs + 60_000L || nowMs - retrievedAt > 15 * 60_000L ||
                coverageFrom > nowMs || coverageTo < nowMs + 30 * 60_000L
            ) return CalendarResult.Failed("Bridge news verdict is stale or its coverage is incomplete")

            val events = when (status) {
                "CLEAR" -> emptyList()
                "HIGH_IMPACT_WINDOW" -> {
                    val eventTime = verdict.optLong("event_time_ms", 0L)
                    val currency = verdict.optString("currency").trim().uppercase(Locale.ROOT)
                    val delta = eventTime - nowMs
                    // The Android signal gate uses a 30m-before / 15m-after window.
                    // If Bridge's configured blackout is wider, fail closed rather than under-block.
                    if (eventTime <= 0L || currency.isBlank() || delta > 30 * 60_000L || delta < -15 * 60_000L) {
                        return CalendarResult.Failed("Bridge reports a high-impact blackout that cannot be safely represented")
                    }
                    listOf(EconomicEvent(
                        name = verdict.optString("detail").ifBlank { "High-impact event verified by Bridge" },
                        country = currency,
                        currency = currency,
                        impact = NewsImpact.HIGH,
                        timeMs = eventTime,
                        actual = null,
                        forecast = null,
                        previous = null,
                        source = source,
                        retrievedAtMs = retrievedAt
                    ))
                }
                else -> return CalendarResult.Failed("Bridge news status is UNKNOWN")
            }
            return CalendarResult.Ok(events, retrievedAt, coverageFrom, coverageTo, source)
        }
    }
}
