package com.destinedfx.core.marketdata

import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.instrument.SpecRegistry
import kotlin.math.abs

data class QuoteView(
    val symbol: String,
    val price: Double,
    val bid: Double?,
    val ask: Double?,
    val source: String,
    val kind: String,
    val lastUpdateMillis: Long,
    val hasRealSpread: Boolean = false
)

data class DataCorrespondenceRow(
    val symbol: String,
    val analysisSource: String,
    val kind: String, // STREAM / POLLED / DELAYED
    val analysisAgeMs: Long,
    val candleSource: String,
    val resolvedBrokerSymbol: String,
    val brokerBid: Double?,
    val brokerAsk: Double?,
    val brokerAgeMs: Long?,
    val diffPips: Double?,
    val status: String, // OK / PRICE CONFLICT / STALE / WAITING FOR LIVE DATA / BROKER NOT CONNECTED
    val timingText: String? = null
)

object DataCorrespondence {
    fun rows(
        watchlist: List<String>,
        tickers: Map<String, QuoteView>,
        candleSources: Map<String, String>,
        resolver: (String) -> String?,
        brokerQuotes: Map<String, MarketQuote>,
        atrs: Map<String, Double> = emptyMap(),
        timings: Map<String, String> = emptyMap(),
        nowMs: Long = System.currentTimeMillis()
    ): List<DataCorrespondenceRow> {
        return watchlist.map { symbol ->
            val ticker = tickers[symbol]
            val candleSrc = candleSources[symbol] ?: "N/A"
            val resolvedBroker = resolver(symbol) ?: "N/A"
            val brokerQuote = brokerQuotes[symbol] ?: brokerQuotes[resolvedBroker]
            val spec = SpecRegistry.get(symbol) ?: SpecRegistry.get(resolvedBroker)
            val timing = timings[symbol]

            if (ticker == null || ticker.price <= 0.0) {
                return@map DataCorrespondenceRow(
                    symbol = symbol,
                    analysisSource = "N/A",
                    kind = "N/A",
                    analysisAgeMs = 0L,
                    candleSource = candleSrc,
                    resolvedBrokerSymbol = resolvedBroker,
                    brokerBid = brokerQuote?.bid,
                    brokerAsk = brokerQuote?.ask,
                    brokerAgeMs = brokerQuote?.let { nowMs - it.receivedAtMs },
                    diffPips = null,
                    status = "WAITING FOR LIVE DATA",
                    timingText = timing
                )
            }

            val analysisAge = (nowMs - ticker.lastUpdateMillis).coerceAtLeast(0L)
            val analysisQuote = MarketQuote(
                symbol = symbol,
                assetClass = spec?.assetClass ?: AssetClass.FOREX,
                bid = ticker.bid,
                ask = ticker.ask,
                last = ticker.price,
                timestampMs = ticker.lastUpdateMillis,
                receivedAtMs = ticker.lastUpdateMillis,
                source = ticker.source
            )

            val brokerAge = brokerQuote?.let { (nowMs - it.receivedAtMs).coerceAtLeast(0L) }
            val bMid = brokerQuote?.mid
            val aMid = analysisQuote.mid
            val diffRaw = if (bMid != null && aMid != null) {
                abs(aMid - bMid)
            } else null

            val diffPips = if (diffRaw != null && spec != null) {
                spec.priceToPips(diffRaw) ?: spec.priceToPoints(diffRaw)
            } else diffRaw

            val conflict = if (brokerQuote != null) {
                PriceConflictGuard.check(analysisQuote, brokerQuote, atrs[symbol], nowMs = nowMs)
            } else null

            val status = when {
                brokerQuote == null -> "BROKER NOT CONNECTED"
                conflict?.conflict == true -> "PRICE CONFLICT"
                analysisAge > 30_000L || (brokerAge != null && brokerAge > 30_000L) -> "STALE"
                else -> "OK"
            }

            DataCorrespondenceRow(
                symbol = symbol,
                analysisSource = ticker.source,
                kind = ticker.kind,
                analysisAgeMs = analysisAge,
                candleSource = candleSrc,
                resolvedBrokerSymbol = resolvedBroker,
                brokerBid = brokerQuote?.bid,
                brokerAsk = brokerQuote?.ask,
                brokerAgeMs = brokerAge,
                diffPips = diffPips,
                status = status,
                timingText = timing
            )
        }
    }
}
