package com.example.data.market

import android.util.Log
import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import com.example.domain.model.Tick
import com.example.data.trade.MT5RemoteBridgeClient
import com.example.data.trade.VerifiedBrokerQuote
import com.example.data.trade.VerifiedBrokerCandle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.destinedfx.core.marketdata.ExponentialBackoff
import com.destinedfx.core.marketdata.MarketQuote
import com.destinedfx.core.marketdata.OandaPricingStream
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
 * Map confirmed Deriv public symbols back to canonical analyzer symbols.
 * The active-symbol directory is checked again before a request is sent.
 */
internal const val DERIV_ACTIVE_SYMBOLS_TIMEOUT_MS = 30_000L
internal const val DERIV_SYMBOL_RESOLUTION_TIMEOUT_MS = 32_000L
internal const val DERIV_CANDLE_HISTORY_TIMEOUT_MS = 30_000L

internal fun buildDerivCanonicalSymbolMap(
    symbols: List<String> = SymbolRegistry.getBackgroundSymbols()
): Map<String, String> =
    symbols.asSequence()
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
        candle.startsWith("DERIV") -> quote.contains("DERIV")
        candle.startsWith("OANDA") -> quote.contains("OANDA")
        else -> false
    }
}

/** Explicit-feed detector; the Analyzer defaults to the unauthenticated public Deriv feed. */
internal fun isExplicitDerivPublicSource(source: String): Boolean =
    source.trim().uppercase(java.util.Locale.ROOT) == "DERIV_PUBLIC"

/** Reject stale, zero, or future-dated feed timestamps instead of counting a negative age as fresh. */
internal fun isTimestampFresh(timestampMs: Long, nowMs: Long, maxAgeMillis: Long): Boolean {
    if (timestampMs <= 0L || nowMs <= 0L || maxAgeMillis <= 0L) return false
    // Reject every future timestamp before subtraction; only a non-negative age is fresh.
    if (timestampMs > nowMs) return false
    return nowMs - timestampMs < maxAgeMillis
}

/** Keep the last accepted provider quote when a refresh candidate is rejected. */
internal fun canonicalTickerAfterRefresh(
    existing: MarketTicker?,
    fetched: MarketTicker?,
    accepted: Boolean
): MarketTicker? = if (accepted) fetched else existing

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


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught in repository scope: ${e.javaClass.simpleName}") })
    private data class CachedCandles(val candles: List<Candle>, val timestamp: Long)
    private val candleCache = mutableMapOf<String, CachedCandles>()
    private val dataSourceMap = mutableMapOf<String, String>()
    private val _candleSources = ConcurrentHashMap<String, String>()
    private val _candleRetrievedAtMs = ConcurrentHashMap<String, Long>()
    // Public Deriv symbol cache powers Analyzer prices and candles without broker login.
    private val dynamicCanonicalByDerivSymbol = ConcurrentHashMap<String, String>()
    override val candleSources: Map<String, String> get() = _candleSources
    override val candleRetrievedAtMs: Map<String, Long> get() = _candleRetrievedAtMs

    private val _tickers = MutableStateFlow<Map<String, MarketTicker>>(emptyMap())
    override val tickerFlow: StateFlow<Map<String, MarketTicker>> = _tickers.asStateFlow()
    private val _quoteDiagnostics = MutableStateFlow<Map<String, String>>(emptyMap())
    override val quoteDiagnostics: StateFlow<Map<String, String>> = _quoteDiagnostics.asStateFlow()
    @Volatile private var bridgeSnapshotFailure: String = "MT5_BRIDGE_ACCOUNT_SNAPSHOT_UNAVAILABLE"

    override val selectedApiSource = MutableStateFlow("DERIV_PUBLIC")

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
        val gateway = derivMarketDataGateway ?: return null
        val discovered = withTimeoutOrNull(DERIV_ACTIVE_SYMBOLS_TIMEOUT_MS) { gateway.fetchActiveSymbols() } ?: emptyList()
        if (discovered.isEmpty()) return null

        // A hard-coded mapping is only a search hint. Never send a market request until
        // the exact instrument code has also appeared in the provider's current directory.
        val mappedCode = com.destinedfx.core.broker.DerivInstrumentResolver.resolve(canonicalSymbol)
        val direct = mappedCode?.let { code -> discovered.firstOrNull { it.symbol.equals(code, ignoreCase = true) } }
        val match = direct ?: run {
            val names = derivLookupNames(canonicalSymbol)
            if (names.isEmpty()) return null
            fun comparableDerivCode(value: String): String {
                val normalizedCode = normalizeMarketSymbol(value)
                return when {
                    normalizedCode.startsWith("FRX") -> normalizedCode.removePrefix("FRX")
                    normalizedCode.startsWith("CRY") -> normalizedCode.removePrefix("CRY")
                    else -> normalizedCode
                }
            }
            discovered.firstOrNull { candidate ->
                normalizeMarketSymbol(candidate.displayName) in names ||
                    normalizeMarketSymbol(candidate.symbol) in names || comparableDerivCode(candidate.symbol) in names
            }
        } ?: return null

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
                source = "Deriv public WebSocket",
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

    private val bridgeSnapshotMutex = Mutex()
    @Volatile private var bridgeSnapshotCache: MT5RemoteBridgeClient.Snapshot? = null
    @Volatile private var bridgeSnapshotCacheAtMs: Long = 0L
    @Volatile private var bridgeSnapshotCacheProfile: String = ""

    /**
     * Analyzer price data must be bound to the currently selected, encrypted broker profile.
     * No Deriv, OANDA, MetaAPI, or chart-widget fallback is accepted on this path.
     */
    private suspend fun verifiedMt5Snapshot(forceRefresh: Boolean = false): MT5RemoteBridgeClient.Snapshot? =
        withContext(Dispatchers.IO) {
            bridgeSnapshotMutex.withLock {
                val brand = secrets.getSelectedMT5BrokerBrand().trim().uppercase()
                val mode = secrets.getManagedMT5SelectedProfile().trim().uppercase()
                val profileKey = "${brand}_${mode}"
                val login = secrets.getManagedMT5AccountLogin(profileKey).trim()
                val server = secrets.getManagedMT5AccountServer(profileKey).trim()
                if (brand.isBlank() || mode !in setOf("DEMO", "LIVE") ||
                    login.isBlank() || server.isBlank() ||
                    secrets.getMT5BridgeAccountAppToken(profileKey).isBlank()) {
                    bridgeSnapshotFailure = when {
                        brand.isBlank() -> "MT5_BRIDGE_BROKER_NOT_SELECTED"
                        mode !in setOf("DEMO", "LIVE") -> "MT5_BRIDGE_ACCOUNT_MODE_NOT_SELECTED"
                        login.isBlank() -> "MT5_BRIDGE_ACCOUNT_LOGIN_NOT_CONFIGURED"
                        server.isBlank() -> "MT5_BRIDGE_ACCOUNT_SERVER_NOT_CONFIGURED"
                        else -> "MT5_BRIDGE_PAIRING_TOKEN_MISSING"
                    }
                    bridgeSnapshotCache = null
                    bridgeSnapshotCacheProfile = profileKey
                    bridgeSnapshotCacheAtMs = 0L
                    return@withLock null
                }
                val now = clock()
                if (!forceRefresh && bridgeSnapshotCacheProfile == profileKey &&
                    now >= bridgeSnapshotCacheAtMs && now - bridgeSnapshotCacheAtMs < 1_000L) {
                    return@withLock bridgeSnapshotCache
                }
                val snapshot = try {
                    MT5RemoteBridgeClient(
                        secrets = secrets, profile = profileKey, accountMode = mode, brokerBrand = brand
                    ).state()
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    bridgeSnapshotFailure = "MT5_BRIDGE_STATE_REQUEST_FAILED: " +
                        (error.message?.take(160) ?: error.javaClass.simpleName)
                    null
                }
                val rejection = snapshot?.let {
                    when {
                        !it.terminalOnline -> "MT5_TERMINAL_OFFLINE"
                        !it.heartbeatFresh -> "MT5_TERMINAL_HEARTBEAT_STALE"
                        !it.terminalConnected -> "MT5_TERMINAL_NOT_CONNECTED"
                        !it.accountVerified -> "MT5_ACCOUNT_IDENTITY_UNVERIFIED"
                        !it.brokerIdentityVerified -> "MT5_BROKER_IDENTITY_UNVERIFIED"
                        it.actualCompany.isBlank() -> "MT5_BROKER_COMPANY_UNAVAILABLE"
                        it.actualAccountMode != mode -> "MT5_ACCOUNT_MODE_MISMATCH"
                        it.actualLogin != login -> "MT5_ACCOUNT_LOGIN_MISMATCH"
                        it.actualServer != server -> "MT5_ACCOUNT_SERVER_MISMATCH"
                        !it.connected -> it.connectionWarning.ifBlank { "MT5_ACCOUNT_CONNECTION_NOT_VERIFIED" }
                        else -> null
                    }
                }
                val verified = snapshot?.takeIf {
                    rejection == null && it.connected && it.terminalOnline && it.heartbeatFresh &&
                        it.terminalConnected && it.accountVerified && it.brokerIdentityVerified &&
                        it.actualCompany.isNotBlank() && it.actualAccountMode == mode &&
                        it.actualLogin == login && it.actualServer == server
                }
                bridgeSnapshotFailure = when {
                    rejection != null -> rejection
                    verified != null -> ""
                    snapshot == null && bridgeSnapshotFailure.isBlank() -> "MT5_BRIDGE_STATE_UNAVAILABLE"
                    else -> bridgeSnapshotFailure
                }
                bridgeSnapshotCache = verified
                bridgeSnapshotCacheProfile = profileKey
                bridgeSnapshotCacheAtMs = now
                verified
            }
        }

    private fun markMt5QuoteUnavailable(symbol: String, reason: String) {
        _tickers.update { current ->
            current[symbol]?.let { current + (symbol to it.copy(kind = "STALE", isLive = false)) } ?: current
        }
        _quoteDiagnostics.update { current -> current + (symbol to reason) }
    }

    private fun resolveMt5TerminalQuote(
        snapshot: MT5RemoteBridgeClient.Snapshot,
        canonicalSymbol: String
    ): VerifiedBrokerQuote? {
        val quotes = snapshot.brokerQuotes.orEmpty()
        val override = secrets.getBrokerSymbolOverrides()[canonicalSymbol]?.trim().orEmpty()
        if (override.isNotBlank()) {
            return quotes.singleOrNull { it.symbol.equals(override, ignoreCase = true) }
        }
        quotes.singleOrNull { it.symbol.equals(canonicalSymbol, ignoreCase = true) }?.let { return it }
        fun canonical(value: String): String = value.uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' }
        val key = canonical(canonicalSymbol)
        if (key.isBlank()) return null
        return quotes.filter { canonical(it.symbol) == key }.singleOrNull()
    }

    private fun mt5Ticker(snapshot: MT5RemoteBridgeClient.Snapshot, symbol: String): MarketTicker? {
        val quote = resolveMt5TerminalQuote(snapshot, symbol) ?: return null
        if (quote.ageMs !in 0L..15_000L || quote.bid <= 0.0 || quote.ask < quote.bid) return null
        val mid = (quote.bid + quote.ask) / 2.0
        val dailyReference = snapshot.brokerCandles.orEmpty()
            .asSequence()
            .filter { it.symbol.equals(quote.symbol, ignoreCase = true) &&
                it.timeframe.equals("D1", ignoreCase = true) && it.complete }
            .maxByOrNull { it.timestampMs }
        val dailyReferenceClose = dailyReference?.close?.takeIf { it.isFinite() && it > 0.0 }
        val verifiedChange = dailyReferenceClose?.let { ((mid - it) / it) * 100.0 }?.takeIf { it.isFinite() }
        val brand = secrets.getSelectedMT5BrokerBrand().trim().uppercase()
        val company = com.example.data.broker.BrokerDirectory.byBrandId(brand)?.name ?: brand
        return MarketTicker(
            symbol = symbol,
            price = mid,
            changePercent = verifiedChange ?: 0.0,
            high = dailyReference?.high?.takeIf { it.isFinite() && it > 0.0 } ?: quote.ask,
            low = dailyReference?.low?.takeIf { it.isFinite() && it > 0.0 } ?: quote.bid,
            spread = quote.ask - quote.bid,
            bias = "NEUTRAL",
            confidence = 0,
            lastUpdateMillis = quote.timestampMs,
            isLive = true,
            source = "${company} MT5 Bridge",
            bid = quote.bid,
            ask = quote.ask,
            hasRealSpread = true,
            kind = "STREAM",
            hasVerifiedChange = verifiedChange != null
        )
    }

    private suspend fun fetchMt5BridgeQuote(symbol: String): MarketTicker? {
        val snapshot = verifiedMt5Snapshot()
        if (snapshot == null) {
            markMt5QuoteUnavailable(symbol, bridgeSnapshotFailure.ifBlank { "MT5_BRIDGE_ACCOUNT_SNAPSHOT_UNAVAILABLE" })
            return null
        }
        val ticker = mt5Ticker(snapshot, symbol)
        if (ticker == null || !isTimestampFresh(ticker.lastUpdateMillis, clock(), 15_000L)) {
            markMt5QuoteUnavailable(symbol, "MT5_TERMINAL_QUOTE_UNAVAILABLE: no unique fresh bid/ask for $symbol was reported by the authenticated account")
            return null
        }
        return ticker
    }

    private suspend fun fetchLiveQuoteForSymbol(sym: String): MarketTicker? {
        val preferred = selectedApiSource.value
        if (preferred == "MT5_BRIDGE") return fetchMt5BridgeQuote(sym)
        if (isExplicitDerivPublicSource(preferred) && derivMarketDataGateway != null) {
            derivTickerForSymbol(sym, 15_000L, waitForFreshQuote = true)?.let { return it }
        }
        if (preferred == "OANDA" && secrets.getOandaApiKey().isNotBlank()) {
            withTimeoutOrNull(3_000L) { oandaProvider.getLiveQuote(sym)?.copy(source = "OANDA") }?.let { return it }
        }
        return null
    }

    private suspend fun fetchBackgroundQuoteForSymbol(sym: String): MarketTicker? {
        val preferred = selectedApiSource.value
        if (preferred == "MT5_BRIDGE") return fetchMt5BridgeQuote(sym)
        if (isExplicitDerivPublicSource(preferred) && derivMarketDataGateway != null) {
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
        // OANDA remains optional; Analyzer defaults to the unauthenticated Deriv public WebSocket feed.
        scope.launch {
            val oandaSymbols = listOf(
                "EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "AUD/USD", "USD/CAD", "NZD/USD",
                "EUR/GBP", "EUR/JPY", "GBP/JPY", "XAU/USD", "XAG/USD", "SPX500", "NAS100", "DJ30"
            )
            val backoff = ExponentialBackoff()
            while (true) {
                val apiKey = secrets.getOandaApiKey().trim()
                val accountId = secrets.getOandaAccountId().trim()
                if (selectedApiSource.value == "OANDA" && apiKey.isNotBlank() && accountId.isNotBlank()) {
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
                                if (ticker.price > 0.0 && shouldUpdateTicker(q.symbol, ticker)) {
                                    _tickers.update { current -> current + (q.symbol to ticker) }
                                    _quoteDiagnostics.update { current -> current - q.symbol }
                                }
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

        // Legacy Deriv quotes and candles are subscribed on demand only when an explicit legacy source is selected.
        // instrument codes are confirmed against active_symbols. They are not broker execution prices.
    }

    private suspend fun pollAllSymbolsParallel() {
        val symbols = SymbolRegistry.getBackgroundSymbols()
        val now = clock()
        // Refresh stale entries too: caching the first accepted quote must not freeze a watchlist.
        val needingRefresh = symbols.filter { symbol ->
            classifyQuoteFreshness(_tickers.value[symbol], now) != QuoteFreshness.LIVE
        }
        if (needingRefresh.isNotEmpty()) {
            processPollingItemsIndependently(
                items = needingRefresh,
                parallelism = 4,
                onError = { sym, error -> Log.w(TAG, "Quote poll failed symbol=$sym error=" + error.javaClass.simpleName) }
            ) { sym ->
                val ticker = fetchBackgroundQuoteForSymbol(sym)
                if (ticker != null && shouldUpdateTicker(sym, ticker)) {
                    _tickers.update { current -> current + (sym to ticker) }
                    _quoteDiagnostics.update { current -> current - sym }
                } else if (_tickers.value[sym] == null) {
                    _quoteDiagnostics.update { current -> current + (sym to "No fresh provider quote is currently available") }
                }
            }
        }
    }

    override suspend fun refreshSymbol(symbol: String): MarketTicker? {
        if (selectedApiSource.value == "MT5_BRIDGE") {
            val ticker = fetchMt5BridgeQuote(symbol)
            if (ticker != null && shouldUpdateTicker(symbol, ticker)) {
                _tickers.update { current -> current + (symbol to ticker) }
                _quoteDiagnostics.update { current -> current - symbol }
                return ticker
            }
            return null
        }
        val ticker = fetchLiveQuoteForSymbol(symbol)
        val accepted = ticker != null && shouldUpdateTicker(symbol, ticker)
        if (accepted && ticker != null) {
            _tickers.update { current ->
                current + (symbol to ticker)
            }
            _quoteDiagnostics.update { current -> current - symbol }
        } else if (ticker == null && _quoteDiagnostics.value[symbol].isNullOrBlank()) {
            _quoteDiagnostics.update { current ->
                current + (symbol to "NO FRESH QUOTE • no supported provider returned a current valid price")
            }
        }
        return canonicalTickerAfterRefresh(_tickers.value[symbol], ticker, accepted)
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
        if (selectedApiSource.value == "MT5_BRIDGE") {
            val snapshot = verifiedMt5Snapshot()
            val quote = snapshot?.let { resolveMt5TerminalQuote(it, symbol) }
            val terminalSymbol = quote?.symbol
            val rows = if (terminalSymbol == null) emptyList() else snapshot?.brokerCandles.orEmpty()
                .filter { it.symbol.equals(terminalSymbol, ignoreCase = true) &&
                    it.timeframe.equals(timeframe.trim(), ignoreCase = true) && it.complete }
                .sortedBy { it.timestampMs }
            val bridgeCandles = rows.map {
                Candle(it.timestampMs, it.open, it.high, it.low, it.close, it.volume, complete = true)
            }.takeLast(count.coerceAtLeast(0))
            if (snapshot != null && terminalSymbol != null && bridgeCandles.isNotEmpty()) {
                val brand = secrets.getSelectedMT5BrokerBrand().trim().uppercase()
                val brokerName = com.example.data.broker.BrokerDirectory.byBrandId(brand)?.name ?: brand
                val source = "${brokerName} MT5 Bridge"
                val retrievedAt = clock()
                dataSourceMap[key] = source
                _candleSources[key] = source
                _candleRetrievedAtMs[key] = retrievedAt
                candleCache[key] = CachedCandles(bridgeCandles, retrievedAt)
                _candleFetchErrors.update { it - key }
                return@withContext bridgeCandles
            }
            val detail = when {
                snapshot == null -> bridgeSnapshotFailure.ifBlank {
                    "MT5_BRIDGE_ACCOUNT_SNAPSHOT_UNAVAILABLE: no verified terminal state; no alternate feed will be used."
                }
                terminalSymbol == null -> "MT5_TERMINAL_SYMBOL_QUOTE_UNAVAILABLE: no unique broker quote resolved for $symbol."
                else -> "MT5_TERMINAL_CLOSED_OHLC_UNAVAILABLE: no validated closed candles for $symbol $timeframe from the same broker symbol."
            }
            _candleFetchErrors.update { it + (key to detail) }
            dataSourceMap.remove(key)
            _candleSources.remove(key)
            _candleRetrievedAtMs.remove(key)
            candleCache.remove(key)
            return@withContext emptyList()
        }
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

        var candles = emptyList<Candle>()
        var sourceUsed = ""
        var derivSymbol: String? = null

        val preferred = selectedApiSource.value

        // Only provider-returned OHLC bars may feed analysis. A live quote snapshot is
        // never expanded into fabricated historical candles.
        if (preferred == "OANDA" && secrets.getOandaApiKey().isNotBlank()) {
            candles = withTimeoutOrNull(4_000L) { oandaProvider.getCandles(symbol, timeframe, count) } ?: emptyList()
            if (candles.isNotEmpty()) sourceUsed = "OANDA"
        }

        // Public Deriv OHLC candles are used only after the exact code is found in active_symbols.
        if (candles.isEmpty() && isExplicitDerivPublicSource(preferred) && derivMarketDataGateway != null) {
            derivSymbol = withTimeoutOrNull(DERIV_SYMBOL_RESOLUTION_TIMEOUT_MS) { resolveDerivSymbol(symbol) }
            if (derivSymbol != null) {
                val weekly = tf == com.destinedfx.core.marketdata.Timeframe.W1
                val granSec = if (weekly) 86_400 else (tf?.minutes ?: 60) * 60
                val requestCount = if (weekly) (count * 7 + 14).coerceAtMost(1_800) else count
                val fetched = withTimeoutOrNull(DERIV_CANDLE_HISTORY_TIMEOUT_MS) {
                    derivMarketDataGateway.fetchCandles(derivSymbol, granSec, requestCount)
                } ?: emptyList()
                candles = if (weekly) aggregateDailyCandlesToWeekly(fetched).takeLast(count) else fetched
                if (candles.isNotEmpty()) {
                    sourceUsed = if (weekly) "Deriv public daily candles aggregated into UTC weeks" else "Deriv public OHLC"
                }
            }
        }

        val snapshotRetrievedAtMs = clock()
        candles = sanitizeOhlcCandles(candles, now)
        if (candles.isNotEmpty()) {
            val finalSource = sourceUsed
            
            val currentCandleOpen = tf?.let { currentCandleOpenFor(now, it) } ?: candles.last().timestamp
            candles = candles.map { c ->
                if (c.timestamp == currentCandleOpen) c.copy(complete = false)
                else if (c.timestamp < currentCandleOpen) c.copy(complete = true)
                else c
            }

            // Update the forming bar only with a quote from the same provider. Never mix
            // a quote from another venue into historical OHLC data.
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
            _candleRetrievedAtMs[key] = snapshotRetrievedAtMs
            candleCache[key] = CachedCandles(processedCandles, snapshotRetrievedAtMs)
            _candleFetchErrors.update { it - key }
            return@withContext processedCandles.takeLast(count)
        } else {
            val providerError = if (preferred == "DERIV_PUBLIC") {
                derivMarketDataGateway?.getLastCandleError(derivSymbol ?: symbol)
            } else null
            val errorMsg = when {
                !providerError.isNullOrBlank() -> "Deriv public feed could not verify or fetch " + symbol + " " + timeframe + ". Provider detail: " + providerError.take(240)
                derivSymbol == null && preferred == "DERIV_PUBLIC" ->
                    "The current provider directory has not verified an instrument for " + symbol + ". No substitute price series was used."
                preferred == "OANDA" && secrets.getOandaApiKey().isBlank() ->
                    "An OANDA market-data credential is not configured for " + symbol + "."
                else -> "No fresh OHLC history was returned for " + symbol + " " + timeframe + ". No substitute candles were generated."
            }
            _candleFetchErrors.update { it + (key to errorMsg) }
            dataSourceMap.remove(key)
            _candleSources.remove(key)
            _candleRetrievedAtMs.remove(key)
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
