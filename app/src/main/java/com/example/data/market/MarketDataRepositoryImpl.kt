package com.example.data.market

import android.util.Log
import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import com.example.domain.model.Tick
import com.destinedfx.core.marketdata.BinanceBookTickerStream
import com.destinedfx.core.marketdata.ExponentialBackoff
import com.destinedfx.core.marketdata.MarketQuote
import com.destinedfx.core.marketdata.OandaPricingStream
import com.destinedfx.core.marketdata.StreamEvent
import com.example.domain.repository.MarketDataRepository
import com.example.security.SecretsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Public Deriv quote subscriptions must be translated back to the app's canonical symbols.
 * Crypto tickers are supplied by the public Binance stream instead.
 */
internal fun buildDerivCanonicalSymbolMap(
    symbols: List<String> = SymbolRegistry.getBackgroundSymbols()
): Map<String, String> =
    symbols.asSequence()
        .filter { com.destinedfx.core.instrument.AssetClassifier.of(it) != com.destinedfx.core.marketdata.AssetClass.CRYPTO }
        .mapNotNull { canonical ->
            com.destinedfx.core.broker.DerivInstrumentResolver.resolve(canonical)?.let { it to canonical }
        }
        .toMap()

/**
 * A tiny quote lookup (for example, 2 bars to display a reference price) must never satisfy
 * a later multi-hundred-bar analysis request through the symbol/timeframe cache.
 * 60 closed bars is the minimum useful cache size for the deterministic analysis pipeline.
 */
internal fun shouldReuseCandleCache(cachedCount: Int, requestedCount: Int, minimumUsefulCandles: Int = 60): Boolean {
    if (cachedCount <= 0 || requestedCount <= 0 || minimumUsefulCandles <= 0) return false
    return cachedCount >= minOf(requestedCount, minimumUsefulCandles)
}

internal fun candleSourceKey(symbol: String, timeframe: String): String = "$symbol-${timeframe.trim().uppercase()}"

/** Never merge quote snapshots from one venue into candle history fetched from another venue. */
internal fun canMergeQuoteIntoCandle(candleSource: String, quoteSource: String): Boolean {
    val candle = candleSource.trim().uppercase(java.util.Locale.ROOT)
    val quote = quoteSource.trim().uppercase(java.util.Locale.ROOT)
    if (candle.isBlank() || quote.isBlank()) return false
    return when {
        candle.startsWith("BINANCE") -> quote.contains("BINANCE")
        candle.startsWith("DERIV") -> quote.contains("DERIV")
        candle.startsWith("OANDA") -> quote.contains("OANDA")
        else -> false
    }
}

/** Reject stale, zero, or future-dated feed timestamps instead of counting a negative age as fresh. */
internal fun isTimestampFresh(timestampMs: Long, nowMs: Long, maxAgeMillis: Long): Boolean =
    timestampMs > 0L && nowMs > 0L && maxAgeMillis > 0L &&
        (nowMs - timestampMs).let { it >= 0L && it < maxAgeMillis }

class MarketDataRepositoryImpl(
    private val secrets: SecretsManager,
    private val derivMarketDataGateway: com.example.data.deriv.DerivMarketDataGateway? = null,
    private val clock: () -> Long = System::currentTimeMillis
) : MarketDataRepository {

    companion object {
        private const val TAG = "MarketDataRepo"
    }





    private val oandaProvider = OandaProvider(
        apiKeyProvider = { secrets.getOandaApiKey() },
        accountIdProvider = { secrets.getOandaAccountId() },
        isPracticeModeProvider = { !secrets.getOandaAccountId().trim().startsWith("001-") }
    )

    private val binanceProvider = BinanceProvider()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught in repository scope: ${e.javaClass.simpleName}") })
    private data class CachedCandles(val candles: List<Candle>, val timestamp: Long)
    private val candleCache = mutableMapOf<String, CachedCandles>()
    private val dataSourceMap = mutableMapOf<String, String>()
    private val _candleSources = ConcurrentHashMap<String, String>()
    // Canonical app symbols discovered from Deriv's live active_symbols directory.
    private val dynamicCanonicalByDerivSymbol = ConcurrentHashMap<String, String>()
    override val candleSources: Map<String, String> get() = _candleSources

    private val _tickers = MutableStateFlow<Map<String, MarketTicker>>(emptyMap())
    override val tickerFlow: StateFlow<Map<String, MarketTicker>> = _tickers.asStateFlow()
    private val _quoteDiagnostics = MutableStateFlow<Map<String, String>>(emptyMap())
    override val quoteDiagnostics: StateFlow<Map<String, String>> = _quoteDiagnostics.asStateFlow()

    override val selectedApiSource = MutableStateFlow("TRADINGVIEW")

    private val activeSource = ConcurrentHashMap<String, String>()
    private val lastSourceLog = ConcurrentHashMap<String, Long>()




    private val _candleFetchErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    override val candleFetchErrors: StateFlow<Map<String, String>> = _candleFetchErrors.asStateFlow()

    fun getCandleError(symbol: String, timeframe: String): String? = _candleFetchErrors.value["$symbol-$timeframe"]

    init {
        startLivePolling()
        startLiveStreams()
        
        // Expired streams remain visibly stale; they are never relabelled as fresh polled quotes.
        scope.launch {
            while (true) {
                delay(1000L)
                val now = clock()
                _tickers.update { current ->
                    current.mapValues { (sym, ticker) ->
                        if (ticker.kind == "STREAM" && !isTimestampFresh(ticker.lastUpdateMillis, now, 5_000L)) {
                            ticker.copy(kind = "STALE", isLive = false)
                        } else {
                            ticker
                        }
                    }
                }
            }
        }
    }

    /**
     * Cancels polling and streaming workers if dependency initialization fails after construction.
     * MyApplication calls this before exposing a retry path to avoid orphaned network loops.
     */
    fun close() {
        scope.cancel()
    }

    private fun getTtlForTimeframe(timeframe: String): Long {
        val tf = com.destinedfx.core.marketdata.Timeframe.parse(timeframe) ?: return 300_000L
        return when (tf) {
            com.destinedfx.core.marketdata.Timeframe.M1, 
            com.destinedfx.core.marketdata.Timeframe.M5, 
            com.destinedfx.core.marketdata.Timeframe.M15, 
            com.destinedfx.core.marketdata.Timeframe.M30, 
            com.destinedfx.core.marketdata.Timeframe.H1 -> 30_000L
            com.destinedfx.core.marketdata.Timeframe.H4 -> 60_000L
            com.destinedfx.core.marketdata.Timeframe.D1, 
            com.destinedfx.core.marketdata.Timeframe.W1 -> 300_000L
        }
    }

    private fun normalizeMarketSymbol(value: String): String =
        value.trim().uppercase().filter { it.isLetterOrDigit() }

    /**
     * Resolve only against the fixed, audited resolver or Deriv's real active-symbol directory.
     * Never construct a synthetic feed code from a display label.
     */
    private fun derivLookupNames(canonicalSymbol: String): Set<String> {
        val normalized = normalizeMarketSymbol(canonicalSymbol)
        val aliases = when (normalized) {
            "SPX500" -> setOf("SPX500", "US500", "SP500", "SPX", "SP500INDEX")
            "NAS100" -> setOf("NAS100", "US100", "USTECH100", "NASDAQ100", "USTEC")
            "DJ30" -> setOf("DJ30", "US30", "WALLSTREET30", "DOWJONES30")
            "FTSE100" -> setOf("FTSE100", "UK100", "UKFTSE100")
            "DAX40", "GER40", "GER30" -> setOf("DAX40", "GER40", "GER30", "GERMANY40", "DE40")
            "NATGAS" -> setOf("NATGAS", "NATURALGAS", "GAS")
            "XPTUSD" -> setOf("XPTUSD", "PLATINUM", "PLATINUMUSD")
            "XPDUSD" -> setOf("XPDUSD", "PALLADIUM", "PALLADIUMUSD")
            "UKOIL" -> setOf("UKOIL", "BRENT", "UKBRENT", "OILBRENT")
            else -> emptySet()
        }
        return aliases + normalized
    }

    private suspend fun resolveDerivSymbol(canonicalSymbol: String): String? {
        com.destinedfx.core.broker.DerivInstrumentResolver.resolve(canonicalSymbol)?.let { return it }
        val gateway = derivMarketDataGateway ?: return null
        val names = derivLookupNames(canonicalSymbol)
        if (names.all { it.isBlank() }) return null
        val discovered = withTimeoutOrNull(18_000L) { gateway.fetchActiveSymbols() } ?: emptyList()
        fun comparableDerivCode(value: String): String {
            val normalizedCode = normalizeMarketSymbol(value)
            return when {
                normalizedCode.startsWith("FRX") -> normalizedCode.removePrefix("FRX")
                normalizedCode.startsWith("CRY") -> normalizedCode.removePrefix("CRY")
                else -> normalizedCode
            }
        }
        val match = discovered.firstOrNull { candidate ->
            normalizeMarketSymbol(candidate.displayName) in names ||
                normalizeMarketSymbol(candidate.symbol) in names ||
                comparableDerivCode(candidate.symbol) in names
        } ?: return null

        // Register only codes that actually appeared in Deriv's active symbol directory.
        com.destinedfx.core.broker.DerivInstrumentResolver.registerAlias(canonicalSymbol, match.symbol)
        dynamicCanonicalByDerivSymbol[match.symbol] = canonicalSymbol
        return match.symbol
    }

    private suspend fun derivTickerForSymbol(
        sym: String,
        maxQuoteAgeMs: Long,
        waitForFreshQuote: Boolean = false
    ): MarketTicker? {
        val gateway = derivMarketDataGateway ?: return null
        val derivSymbol = resolveDerivSymbol(sym) ?: return null
        val requestStartedAt = clock()

        fun liveTickerIfFresh(quote: MarketQuote?): MarketTicker? {
            quote ?: return null
            val ageMs = clock() - quote.receivedAtMs
            val price = quote.mid ?: quote.last ?: 0.0
            if (ageMs < 0L || ageMs > maxQuoteAgeMs || !price.isFinite() || price <= 0.0) return null
            return MarketTicker(
                symbol = sym, price = price, changePercent = 0.0, high = 0.0, low = 0.0,
                spread = quote.spread ?: 0.0, bias = "NEUTRAL", confidence = 0,
                lastUpdateMillis = quote.receivedAtMs, isLive = true,
                source = "Deriv WebSocket",
                bid = quote.bid, ask = quote.ask, hasRealSpread = quote.spread != null, kind = "STREAM"
            )
        }

        liveTickerIfFresh(gateway.latestQuotes.value[derivSymbol])?.let { return it }

        // Register the selected symbol on demand, not just the startup watchlist.
        gateway.subscribeTicks(derivSymbol) { _ -> }
        if (waitForFreshQuote) {
            withTimeoutOrNull(5_000L) {
                gateway.latestQuotes.first { quotes ->
                    val quote = quotes[derivSymbol]
                    quote != null && quote.receivedAtMs >= requestStartedAt &&
                        ((quote.mid ?: quote.last ?: 0.0) > 0.0)
                }
            }
        }
        // Historical candle closes are never exposed as if they were a real-time quote.
        return liveTickerIfFresh(gateway.latestQuotes.value[derivSymbol])
    }

    private suspend fun fetchLiveQuoteForSymbol(sym: String): MarketTicker? {
        withTimeoutOrNull(3_000L) { PublicRestMarketFeed.getLiveQuote(sym) }?.let { return it }
        val preferred = selectedApiSource.value
        if (preferred == "DERIV" && derivMarketDataGateway != null) {
            derivTickerForSymbol(sym, 15_000L, waitForFreshQuote = true)?.let { return it }
        }
        if (preferred == "OANDA" && secrets.getOandaApiKey().isNotBlank()) {
            withTimeoutOrNull(3_000L) { oandaProvider.getLiveQuote(sym)?.copy(source = "OANDA") }?.let { return it }
        }
        return null
    }

    private suspend fun fetchBackgroundQuoteForSymbol(sym: String): MarketTicker? {
        withTimeoutOrNull(2_000L) { PublicRestMarketFeed.getLiveQuote(sym) }?.let { return it }
        val preferred = selectedApiSource.value
        if (preferred == "DERIV" && derivMarketDataGateway != null) {
            derivTickerForSymbol(sym, 20_000L, waitForFreshQuote = false)?.let { return it }
        }
        if (preferred == "OANDA" && secrets.getOandaApiKey().isNotBlank()) {
            withTimeoutOrNull(2_000L) { oandaProvider.getLiveQuote(sym)?.copy(source = "OANDA") }?.let { return it }
        }
        return null
    }

    private fun toQuoteView(t: MarketTicker) = com.destinedfx.core.marketdata.QuoteView(
        symbol = t.symbol,
        price = t.price,
        bid = t.bid,
        ask = t.ask,
        source = t.source,
        kind = t.kind,
        lastUpdateMillis = t.lastUpdateMillis,
        hasRealSpread = t.hasRealSpread
    )

    override fun updateLiveQuoteFromFeed(symbol: String, price: Double) {
        // Unverified chart values are display-only and cannot enter the canonical
        // quote flow. Verified adapters must submit a fully formed provider quote
        // through the normal arbiter path instead of this legacy scalar API.
    }

    private fun shouldUpdateTicker(symbol: String, newTicker: MarketTicker): Boolean {
        if (classifyQuoteFreshness(newTicker, clock()) != QuoteFreshness.LIVE) {
            Log.d(TAG, "Rejecting non-fresh quote for $symbol source=${newTicker.source} kind=${newTicker.kind}")
            return false
        }
        val existing = _tickers.value[symbol]?.let { toQuoteView(it) }
        val incoming = toQuoteView(newTicker)
        val active = activeSource[symbol]
        val lastUpdate = lastSourceUpdate[symbol] ?: 0L
        val decision = com.destinedfx.core.marketdata.QuoteArbiter.accept(
            existing, incoming, active, lastUpdate, 2_500L, clock()
        )
        
        return when (decision) {
            is com.destinedfx.core.marketdata.Decision.Accept -> {
                // Anchor the arbiter to the first accepted source. A genuine stream may
                // take over from polling; subsequent polls then respect stream freshness.
                if (active == null || active == newTicker.source || newTicker.kind == "STREAM") {
                    if (active != null && active != newTicker.source) {
                        Log.i(TAG, "Switching active source for $symbol to ${newTicker.source}")
                    }
                    activeSource[symbol] = newTicker.source
                    lastSourceUpdate[symbol] = clock()
                }
                true
            }
            is com.destinedfx.core.marketdata.Decision.SwitchSource -> {
                Log.i(TAG, "Switching active source for $symbol: ${decision.reason}")
                activeSource[symbol] = newTicker.source
                lastSourceUpdate[symbol] = clock()
                true
            }
            is com.destinedfx.core.marketdata.Decision.Reject -> {
                // Reject is authoritative. Never accept a malformed, stale, or wrong-source
                // quote merely because its timestamp is newer than the currently displayed one.
                Log.d(TAG, "Rejecting ticker for $symbol: ${decision.reason}")
                false
            }
        }
    }

    private val lastSourceUpdate = ConcurrentHashMap<String, Long>()

    private fun startLiveStreams() {
        // TradingView scanner handles all crypto, forex, commodities and indices live polling every 2.5s.
        // 1. OANDA Pricing Stream for Forex & Metals if user has configured an OANDA API key
        scope.launch {
            val oandaSymbols = listOf(
                "EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "AUD/USD", "USD/CAD", "NZD/USD",
                "EUR/GBP", "EUR/JPY", "GBP/JPY", "XAU/USD", "XAG/USD", "SPX500", "NAS100", "DJ30"
            )
            val backoff = ExponentialBackoff()
            while (true) {
                val apiKey = secrets.getOandaApiKey().trim()
                val accountId = secrets.getOandaAccountId().trim()
                if (apiKey.isNotBlank() && accountId.isNotBlank()) {
                    try {
                        val isPractice = !accountId.startsWith("001-")
                        val stream = OandaPricingStream(
                            accountId = accountId,
                            tokenProvider = { secrets.getOandaApiKey() },
                            practice = isPractice
                        )
                        stream.run(
                            symbols = oandaSymbols,
                            onQuote = { q ->
                                val ticker = MarketTicker(
                                    symbol = q.symbol,
                                    price = q.mid ?: 0.0,
                                    changePercent = 0.0,
                                    high = 0.0,
                                    low = 0.0,
                                    spread = q.spread ?: 0.0,
                                    bias = "NEUTRAL",
                                    confidence = 0,
                                    lastUpdateMillis = q.receivedAtMs,
                                    isLive = true,
                                    source = q.source,
                                    bid = q.bid,
                                    ask = q.ask,
                                    hasRealSpread = q.spread != null,
                                    kind = "STREAM"
                                )
                                _tickers.update { it + (q.symbol to ticker) }
                            },
                            onHeartbeat = {}
                        )
                        backoff.reset()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "OANDA stream reconnecting: ${e.javaClass.simpleName}")
                    }
                }
                delay(backoff.nextDelayMs())
            }
        }

        // 3. Public Deriv stream. Subscribe explicitly and translate broker symbols back to
        // canonical app symbols (e.g. frxEURUSD -> EUR/USD), because the signal engine looks up
        // tickers by the canonical UI symbol. A WebSocket connection without subscriptions emits
        // no quote stream and leaves the Analyzer permanently in WAIT / DATA_UNAVAILABLE.
        derivMarketDataGateway?.let { derivGw ->
            val canonicalByDerivSymbol = buildDerivCanonicalSymbolMap()

            canonicalByDerivSymbol.keys.forEach { derivSymbol ->
                // The latestQuotes flow below publishes the full, two-sided MarketQuote. This
                // callback is deliberately side-effect free; registering it creates the
                // subscription and lets the gateway restore it after reconnects.
                derivGw.subscribeTicks(derivSymbol) { _ -> }
            }

            scope.launch {
                derivGw.tickDiagnostics.collect { diagnostics ->
                    val mapped = diagnostics.mapNotNull { (derivSymbol, message) ->
                        val canonical = canonicalByDerivSymbol[derivSymbol]
                            ?: dynamicCanonicalByDerivSymbol[derivSymbol]
                            ?: return@mapNotNull null
                        canonical to message
                    }.toMap()
                    _quoteDiagnostics.update { current -> current + mapped }
                }
            }

            scope.launch {
                derivGw.latestQuotes.collect { quotes ->
                    val updated = quotes.mapNotNull { (derivSymbol, q) ->
                        val canonicalSymbol = canonicalByDerivSymbol[derivSymbol]
                            ?: dynamicCanonicalByDerivSymbol[derivSymbol]
                            ?: return@mapNotNull null
                        val price = (q.mid ?: q.last ?: 0.0)
                        if (price <= 0.0) return@mapNotNull null

                        canonicalSymbol to MarketTicker(
                            symbol = canonicalSymbol,
                            price = price,
                            changePercent = 0.0,
                            high = 0.0,
                            low = 0.0,
                            spread = q.spread ?: 0.0,
                            bias = "NEUTRAL",
                            confidence = 0,
                            lastUpdateMillis = q.receivedAtMs,
                            isLive = true,
                            source = "Deriv WebSocket",
                            bid = q.bid,
                            ask = q.ask,
                            hasRealSpread = q.spread != null,
                            kind = "STREAM"
                        )
                    }.toMap()

                    updated.forEach { (canonicalSymbol, ticker) ->
                        if (shouldUpdateTicker(canonicalSymbol, ticker)) {
                            _tickers.update { current -> current + (canonicalSymbol to ticker) }
                            _quoteDiagnostics.update { current -> current - canonicalSymbol }
                        }
                    }
                }
            }
        }
    }

    private suspend fun pollAllSymbolsParallel() {
        val symbols = SymbolRegistry.getBackgroundSymbols()
        val tvQuotes = withTimeoutOrNull(4_000L) { PublicRestMarketFeed.fetchAllLiveQuotes(symbols) } ?: emptyMap()
        // Every polled quote must pass freshness, source-arbitration, and validation gates.
        // Updating the StateFlow map directly bypasses all three protections.
        tvQuotes.forEach { (sym, ticker) ->
            if (shouldUpdateTicker(sym, ticker)) {
                _tickers.update { current -> current + (sym to ticker) }
                _quoteDiagnostics.update { current -> current - sym }
            }
        }

        val missing = symbols.filter { sym -> _tickers.value[sym] == null }
        if (missing.isNotEmpty()) {
            processPollingItemsIndependently(
                items = missing,
                parallelism = 4,
                onError = { sym, error ->
                    Log.w(TAG, "Quote poll failed symbol=$sym error=${error.javaClass.simpleName}")
                }
            ) { sym ->
                val ticker = fetchBackgroundQuoteForSymbol(sym)
                if (ticker != null && shouldUpdateTicker(sym, ticker)) {
                    _tickers.update { current -> current + (sym to ticker) }
                }
            }
        }
    }

    override suspend fun refreshSymbol(symbol: String): MarketTicker? {
        val ticker = fetchLiveQuoteForSymbol(symbol)
        
        if (ticker != null && shouldUpdateTicker(symbol, ticker)) {
            _tickers.update { current ->
                current + (symbol to ticker)
            }
            _quoteDiagnostics.update { current -> current - symbol }
        } else if (ticker == null && _quoteDiagnostics.value[symbol].isNullOrBlank()) {
            _quoteDiagnostics.update { current ->
                current + (symbol to "NO FRESH QUOTE • no supported provider returned a current valid price")
            }
        }
        return ticker
    }

    private fun startLivePolling() {
        scope.launch {
            resilientPollingLoop(
                intervalMs = 2_500L,
                onError = { error -> Log.w(TAG, "Live polling pass failed: ${error.javaClass.simpleName}") },
                poll = { pollAllSymbolsParallel() }
            )
        }
    }

    private fun aggregateDailyCandlesToWeekly(source: List<Candle>): List<Candle> {
        val dayMs = 86_400_000L
        val weekMs = dayMs * 7L
        // 1970-01-05 was a Monday. Group real daily bars by Monday 00:00 UTC.
        val mondayOffsetMs = dayMs * 4L
        return source.filter { it.timestamp > 0L }
            .sortedBy { it.timestamp }
            .groupBy { Math.floorDiv(it.timestamp - mondayOffsetMs, weekMs) }
            .toSortedMap()
            .map { (weekIndex, bars) ->
                val ordered = bars.sortedBy { it.timestamp }
                val first = ordered.first()
                val last = ordered.last()
                Candle(
                    timestamp = weekIndex * weekMs + mondayOffsetMs,
                    open = first.open,
                    high = ordered.maxOf { it.high },
                    low = ordered.minOf { it.low },
                    close = last.close,
                    volume = ordered.sumOf { it.volume },
                    complete = ordered.all { it.complete }
                )
            }
    }

    private fun currentCandleOpenFor(nowMs: Long, timeframe: com.destinedfx.core.marketdata.Timeframe): Long {
        if (timeframe == com.destinedfx.core.marketdata.Timeframe.W1) {
            val weekMs = 7L * 86_400_000L
            val mondayOffsetMs = 4L * 86_400_000L
            return Math.floorDiv(nowMs - mondayOffsetMs, weekMs) * weekMs + mondayOffsetMs
        }
        return (nowMs / timeframe.millis) * timeframe.millis
    }

    override suspend fun getCandles(symbol: String, timeframe: String, count: Int): List<Candle> = withContext(Dispatchers.IO) {
        val key = candleSourceKey(symbol, timeframe)
        val now = clock()
        val cached = candleCache[key]
        val ttl = getTtlForTimeframe(timeframe)

        // P4: Cache TTL capping and open time rollover check
        val tf = com.destinedfx.core.marketdata.Timeframe.parse(timeframe)
        val isExpired = if (cached != null && tf != null && cached.candles.isNotEmpty()) {
            val lastCandleTime = cached.candles.last().timestamp
            val currentCandleOpen = currentCandleOpenFor(now, tf)
            (now - cached.timestamp) >= ttl || currentCandleOpen > lastCandleTime
        } else true

        if (cached != null && !isExpired &&
            shouldReuseCandleCache(cached.candles.size, count)) {
            return@withContext cached.candles.takeLast(count)
        }

        val aClass = com.destinedfx.core.instrument.AssetClassifier.of(symbol)
        var candles = emptyList<Candle>()
        var sourceUsed = ""
        var isDelayed = false
        var derivFetchAttempted = false
        var derivSymbol: String? = null

        val preferred = selectedApiSource.value

        // Only provider-returned OHLC bars may feed analysis. A live quote snapshot is
        // never expanded into fabricated historical candles.
        if (preferred == "OANDA" && secrets.getOandaApiKey().isNotBlank()) {
            candles = withTimeoutOrNull(4_000L) { oandaProvider.getCandles(symbol, timeframe, count) } ?: emptyList()
            if (candles.isNotEmpty()) sourceUsed = "OANDA"
        }

        // Public Binance klines provide genuine OHLC bars for supported crypto symbols and
        // do not require an API key. Quotes alone are not an OHLC fallback.
        if (candles.isEmpty() && BinanceSymbols.toBinance(symbol) != null) {
            candles = withTimeoutOrNull(7_000L) { binanceProvider.getCandles(symbol, timeframe, count) } ?: emptyList()
            if (candles.isNotEmpty()) sourceUsed = "Binance Spot OHLC candles"
        }

        // Deriv WebSocket candles are a real OHLC fallback only when providers above return no bars.
        if (candles.isEmpty() && derivMarketDataGateway != null) {
            derivSymbol = withTimeoutOrNull(5_000L) { resolveDerivSymbol(symbol) }
            if (derivSymbol != null) {
                derivFetchAttempted = true
                val weekly = tf == com.destinedfx.core.marketdata.Timeframe.W1
                val granSec = if (weekly) 86_400 else (tf?.minutes ?: 60) * 60
                val requestCount = if (weekly) (count * 7 + 14).coerceAtMost(1_800) else count
                val fetched = withTimeoutOrNull(10_000L) {
                    derivMarketDataGateway.fetchCandles(derivSymbol, granSec, requestCount)
                } ?: emptyList()
                candles = if (weekly) aggregateDailyCandlesToWeekly(fetched).takeLast(count) else fetched
                if (candles.isNotEmpty()) {
                    sourceUsed = if (weekly) "Deriv daily candles aggregated into UTC weeks" else "Deriv WebSocket"
                    isDelayed = false
                }
            }
        }

        candles = sanitizeOhlcCandles(candles, now)
        if (candles.isNotEmpty()) {
            val finalSource = if (isDelayed) "$sourceUsed | DELAYED - analysis only" else sourceUsed
            
            val currentCandleOpen = tf?.let { currentCandleOpenFor(now, it) } ?: candles.last().timestamp
            candles = candles.map { c ->
                if (c.timestamp == currentCandleOpen) c.copy(complete = false)
                else if (c.timestamp < currentCandleOpen) c.copy(complete = true)
                else c
            }

            // Update the forming bar only with a quote from the same provider. Never mix
            // TradingView scanner prices into Deriv/OANDA/Binance OHLC history.
            val ticker = _tickers.value[symbol]
            val canMergeQuote = ticker != null && canMergeQuoteIntoCandle(finalSource, ticker.source)
            val processedCandles = if (canMergeQuote && ticker != null && ticker.price > 0 && candles.isNotEmpty()) {
                val last = candles.last()
                if (last.timestamp == currentCandleOpen && !last.complete) {
                    val updatedLast = last.copy(
                        close = ticker.price,
                        high = maxOf(last.high, ticker.price),
                        low = minOf(last.low, ticker.price),
                        complete = false
                    )
                    candles.dropLast(1) + updatedLast
                } else candles
            } else candles

            dataSourceMap[key] = finalSource
            _candleSources[key] = finalSource
            candleCache[key] = CachedCandles(processedCandles, now)
            _candleFetchErrors.update { it - key }
            return@withContext processedCandles.takeLast(count)
        } else {
            val providerError = if (derivFetchAttempted && derivSymbol != null) {
                derivMarketDataGateway?.getLastCandleError(derivSymbol)
            } else null
            val errorMsg = when {
                !providerError.isNullOrBlank() -> "Deriv feed: " + providerError
                derivSymbol == null && BinanceSymbols.toBinance(symbol) == null && secrets.getOandaApiKey().isBlank() ->
                    "No configured keyless candle feed supports " + symbol +
                        ". Select a supported public Deriv market or a Binance-listed crypto pair."
                else -> "No valid OHLC candles received for " + symbol + " " + timeframe +
                    ". Check network access and the upstream feed response."
            }
            _candleFetchErrors.update { it + (key to errorMsg) }
            dataSourceMap.remove(key)
            _candleSources.remove(key)
            return@withContext emptyList()
        }
    }

    override suspend fun getLatestTick(symbol: String): Tick? = withContext(Dispatchers.IO) {
        val ticker = _tickers.value[symbol]
        if (ticker != null && ticker.price.isFinite() && ticker.price > 0.0 && ticker.lastUpdateMillis > 0L) {
            Tick(ticker.lastUpdateMillis, ticker.price, ticker.spread)
        } else {
            null
        }
    }

    override fun isFresh(symbol: String, maxAgeMillis: Long): Boolean {
        val ticker = _tickers.value[symbol] ?: return false
        return classifyQuoteFreshness(
            ticker, clock(), streamMaxAgeMs = minOf(maxAgeMillis, 5_000L),
            polledMaxAgeMs = minOf(maxAgeMillis, 15_000L)
        ) == QuoteFreshness.LIVE
    }

    fun getDataSource(symbol: String, timeframe: String): String {
        return dataSourceMap[candleSourceKey(symbol, timeframe)]
            ?: "UNAVAILABLE • No validated OHLC source is cached for $symbol $timeframe"
    }
}
