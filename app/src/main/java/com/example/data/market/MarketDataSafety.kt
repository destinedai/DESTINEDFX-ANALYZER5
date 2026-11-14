package com.example.data.market

import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal enum class QuoteFreshness { LIVE, STALE, CONNECTING, UNAVAILABLE }

/** Status is derived from the quote timestamp and its feed kind, never the sticky isLive flag. */
internal fun classifyQuoteFreshness(
    ticker: MarketTicker?,
    nowMs: Long,
    streamMaxAgeMs: Long = 5_000L,
    polledMaxAgeMs: Long = 15_000L,
    connecting: Boolean = false
): QuoteFreshness {
    if (ticker == null) return if (connecting) QuoteFreshness.CONNECTING else QuoteFreshness.UNAVAILABLE
    if (!ticker.price.isFinite() || ticker.price <= 0.0 || ticker.lastUpdateMillis <= 0L || nowMs <= 0L)
        return QuoteFreshness.UNAVAILABLE
    val age = nowMs - ticker.lastUpdateMillis
    if (age < 0L) return QuoteFreshness.STALE
    val limit = when (ticker.kind) {
        "STREAM" -> streamMaxAgeMs
        "POLLED_LAST", "POLLED_BIDASK" -> polledMaxAgeMs
        else -> return QuoteFreshness.STALE
    }
    return if (age < limit && limit > 0L) QuoteFreshness.LIVE else QuoteFreshness.STALE
}

/**
 * Checks both when OHLC was retrieved and the timestamp of its latest closed bar.
 * A recent download of old provider data is not considered a fresh market snapshot.
 */
internal fun isCandleSnapshotFresh(
    snapshotRetrievedAtMs: Long,
    latestClosedCandleAtMs: Long,
    nowMs: Long,
    timeframe: String,
    allowWeekendGap: Boolean = false
): Boolean {
    val tf = com.destinedfx.core.marketdata.Timeframe.parse(timeframe) ?: return false
    if (snapshotRetrievedAtMs <= 0L || latestClosedCandleAtMs <= 0L || nowMs <= 0L) return false

    val maxSnapshotAgeMs = when (tf) {
        com.destinedfx.core.marketdata.Timeframe.M1,
        com.destinedfx.core.marketdata.Timeframe.M5,
        com.destinedfx.core.marketdata.Timeframe.M15,
        com.destinedfx.core.marketdata.Timeframe.M30,
        com.destinedfx.core.marketdata.Timeframe.H1 -> 30_000L
        com.destinedfx.core.marketdata.Timeframe.H4 -> 60_000L
        com.destinedfx.core.marketdata.Timeframe.D1,
        com.destinedfx.core.marketdata.Timeframe.W1 -> 300_000L
    }

    val snapshotAgeMs = nowMs - snapshotRetrievedAtMs
    val candleAgeMs = nowMs - latestClosedCandleAtMs
    if (snapshotAgeMs !in 0 until maxSnapshotAgeMs || candleAgeMs < 0L) return false

    // FX/metals/indices can legitimately have a weekend gap. Crypto cannot.
    val weekendAllowanceMs = if (allowWeekendGap) 72L * 60L * 60L * 1000L else 0L
    val maxClosedBarAgeMs = 2L * tf.millis + weekendAllowanceMs + 60_000L
    return candleAgeMs <= maxClosedBarAgeMs
}

/** Reject malformed/future OHLC, sort chronologically and retain only the latest row per timestamp. */
internal fun sanitizeOhlcCandles(
    input: List<Candle>,
    nowMs: Long,
    futureToleranceMs: Long = 60_000L
): List<Candle> {
    if (nowMs <= 0L || futureToleranceMs < 0L) return emptyList()
    return input.asSequence()
        .filter { candle ->
            val values = listOf(candle.open, candle.high, candle.low, candle.close, candle.volume)
            candle.timestamp > 0L && candle.timestamp <= nowMs + futureToleranceMs &&
                values.all { it.isFinite() } &&
                candle.open > 0.0 && candle.high > 0.0 && candle.low > 0.0 && candle.close > 0.0 &&
                candle.volume >= 0.0 && candle.high >= maxOf(candle.open, candle.close, candle.low) &&
                candle.low <= minOf(candle.open, candle.close, candle.high)
        }
        .sortedBy { it.timestamp }
        .distinctBy { it.timestamp }
        .toList()
}

/** Run each polling item independently; a provider exception is reported without cancelling siblings. */
internal suspend fun <T> processPollingItemsIndependently(
    items: Iterable<T>,
    parallelism: Int = 4,
    onError: (T, Exception) -> Unit,
    process: suspend (T) -> Unit
) = supervisorScope {
    val semaphore = Semaphore(parallelism.coerceAtLeast(1))
    items.map { item ->
        async(Dispatchers.IO) {
            semaphore.withPermit {
                try {
                    process(item)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onError(item, error)
                }
            }
        }
    }.awaitAll()
}

/** Keep periodic polling alive after transient exceptions; cancellation remains cooperative. */
internal suspend fun resilientPollingLoop(
    intervalMs: Long,
    maxBackoffMs: Long = 300_000L,
    onError: (Exception) -> Unit,
    poll: suspend () -> Unit
) {
    require(intervalMs > 0L && maxBackoffMs >= intervalMs)
    var retryDelayMs = intervalMs
    while (currentCoroutineContext().isActive) {
        try {
            poll()
            retryDelayMs = intervalMs
            delay(intervalMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onError(error)
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2L).coerceAtMost(maxBackoffMs)
        }
    }
}
