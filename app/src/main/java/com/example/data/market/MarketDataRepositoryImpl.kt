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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

class MarketDataRepositoryImpl(
    private val secrets: SecretsManager,
    private val derivMarketDataGateway: com.example.data.deriv.DerivMarketDataGateway? = null,
    private val clock: () -> Long = System::currentTimeMillis
) : MarketDataRepository {

    companion object {
        private const val TAG = "MarketDataRepo"
    }





    private val binanceProvider = BinanceProvider()

    private val oandaProvider = OandaProvider(
        apiKeyProvider = { secrets.getOandaApiKey() },
        accountIdProvider = { secrets.getOandaAccountId() },
        isPracticeModeProvider = { !secrets.getOandaAccountId().trim().startsWith("001-") }
    )

    private val pollingSemaphore = Semaphore(4)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught in repository scope: ${e.javaClass.simpleName}") })
    private data class CachedCandles(val candles: List<Candle>, val timestamp: Long)
    private val candleCache = mutableMapOf<String, CachedCandles>()
    private val dataSourceMap = mutableMapOf<String, String>()
    private val _candleSources = ConcurrentHashMap<String, String>()
    override val candleSources: Map<String, String> get() = _candleSources

    private val _tickers = MutableStateFlow<Map<String, MarketTicker>>(emptyMap())
    override val tickerFlow: StateFlow<Map<String, MarketTicker>> = _tickers.asStateFlow()

    override val selectedApiSource = MutableStateFlow("DERIV")

    private val activeSource = ConcurrentHashMap<String, String>()
    private val lastSourceLog = ConcurrentHashMap<String, Long>()




    private val _candleFetchErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    override val candleFetchErrors: StateFlow<Map<String, String>> = _candleFetchErrors.asStateFlow()

    fun getCandleError(symbol: String, timeframe: String): String? = _candleFetchErrors.value["$symbol-$timeframe"]

    init {
        startLivePolling()
        startLiveStreams()
        
        // P2: Mark stale streams as POLLED_LAST
        scope.launch {
            while (true) {
                delay(1000L)
                val now = clock()
                _tickers.update { current ->
                    current.mapValues { (sym, ticker) ->
                        if (ticker.kind == "STREAM" && (now - ticker.lastUpdateMillis) > 5000L) {
                            ticker.copy(kind = "POLLED_LAST", lastUpdateMillis = ticker.lastUpdateMillis)
                        } else {
                            ticker
                        }
                    }
                }
            }
        }
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

    private suspend fun fetchLiveQuoteForSymbol(sym: String): MarketTicker? {
        val aClass = com.destinedfx.core.instrument.AssetClassifier.of(sym)
        // 1. Priority for Crypto: Binance
        if (aClass == com.destinedfx.core.marketdata.AssetClass.CRYPTO) {
            val binance = withTimeoutOrNull(3000) { binanceProvider.getLiveQuote(sym) }
            if (binance != null) return binance.copy(source = "Binance Spot (USDT pairs)")
        }

        val preferred = selectedApiSource.value

        // 2. Deriv priority: when DERIV is preferred, gateway is present, query Deriv quote or fallback to candles
        if ((preferred == "DERIV" || preferred.isBlank()) && derivMarketDataGateway != null) {
            val derivSym = com.destinedfx.core.broker.DerivInstrumentResolver.resolve(sym)
            if (derivSym != null) {
                val q = derivMarketDataGateway.latestQuotes.value[derivSym]
                val price = if (q != null && System.currentTimeMillis() - q.receivedAtMs <= 15_000L) {
                    q.mid ?: q.last ?: 0.0
                } else {
                    val candles = derivMarketDataGateway.fetchCandles(derivSym, 60, 1)
                    candles.lastOrNull()?.close ?: 0.0
                }
                if (price > 0.0) {
                    val spread = q?.spread ?: 0.00010
                    return MarketTicker(
                        symbol = sym,
                        price = price,
                        changePercent = 0.0,
                        high = 0.0,
                        low = 0.0,
                        spread = spread,
                        bias = "NEUTRAL",
                        confidence = 0,
                        lastUpdateMillis = q?.receivedAtMs ?: System.currentTimeMillis(),
                        isLive = true,
                        source = if (q != null) "Deriv WebSocket" else "Deriv REST/WS",
                        bid = q?.bid ?: (price - 0.00005),
                        ask = q?.ask ?: (price + 0.00005),
                        hasRealSpread = q?.spread != null,
                        kind = if (q != null) "STREAM" else "POLLED_LAST"
                    )
                }
            }
        }

        // 3. OANDA if configured and preferred
        if (preferred == "OANDA" || secrets.getOandaApiKey().isNotBlank()) {
            val oanda = withTimeoutOrNull(3000) { oandaProvider.getLiveQuote(sym)?.copy(source = "OANDA") }
            if (oanda != null) return oanda
        }

        return null
    }

    private suspend fun fetchBackgroundQuoteForSymbol(sym: String): MarketTicker? {
        val aClass = com.destinedfx.core.instrument.AssetClassifier.of(sym)
        // 1. Crypto priority: Binance
        if (aClass == com.destinedfx.core.marketdata.AssetClass.CRYPTO) {
            val binance = withTimeoutOrNull(2000) { binanceProvider.getLiveQuote(sym) }
            if (binance != null) return binance.copy(source = "Binance Spot (USDT pairs)")
        }

        val preferred = selectedApiSource.value

        // 2. Deriv background quote check
        if ((preferred == "DERIV" || preferred.isBlank()) && derivMarketDataGateway != null) {
            val derivSym = com.destinedfx.core.broker.DerivInstrumentResolver.resolve(sym)
            if (derivSym != null) {
                val q = derivMarketDataGateway.latestQuotes.value[derivSym]
                val price = if (q != null && System.currentTimeMillis() - q.receivedAtMs <= 20_000L) {
                    q.mid ?: q.last ?: 0.0
                } else {
                    val candles = derivMarketDataGateway.fetchCandles(derivSym, 60, 1)
                    candles.lastOrNull()?.close ?: 0.0
                }
                if (price > 0.0) {
                    val spread = q?.spread ?: 0.00010
                    return MarketTicker(
                        symbol = sym,
                        price = price,
                        changePercent = 0.0,
                        high = 0.0,
                        low = 0.0,
                        spread = spread,
                        bias = "NEUTRAL",
                        confidence = 0,
                        lastUpdateMillis = q?.receivedAtMs ?: System.currentTimeMillis(),
                        isLive = true,
                        source = if (q != null) "Deriv WebSocket" else "Deriv REST/WS",
                        bid = q?.bid ?: (price - 0.00005),
                        ask = q?.ask ?: (price + 0.00005),
                        hasRealSpread = q?.spread != null,
                        kind = if (q != null) "STREAM" else "POLLED_LAST"
                    )
                }
            }
        }

        // 3. OANDA background quote if configured
        if (preferred == "OANDA" && secrets.getOandaApiKey().isNotBlank()) {
            return withTimeoutOrNull(2000) { oandaProvider.getLiveQuote(sym)?.copy(source = "OANDA") }
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

    private fun shouldUpdateTicker(symbol: String, newTicker: MarketTicker): Boolean {
        val existing = _tickers.value[symbol]?.let { toQuoteView(it) }
        val incoming = toQuoteView(newTicker)
        val active = activeSource[symbol]
        val lastUpdate = lastSourceUpdate[symbol] ?: 0L
        val decision = com.destinedfx.core.marketdata.QuoteArbiter.accept(
            existing, incoming, active, lastUpdate, 45_000L, clock()
        )
        
        return when (decision) {
            is com.destinedfx.core.marketdata.Decision.Accept -> true
            is com.destinedfx.core.marketdata.Decision.SwitchSource -> {
                Log.i(TAG, "Switching active source for $symbol: ${decision.reason}")
                activeSource[symbol] = newTicker.source
                lastSourceUpdate[symbol] = clock()
                true
            }
            is com.destinedfx.core.marketdata.Decision.Reject -> {
                Log.d(TAG, "Rejecting ticker for $symbol: ${decision.reason}")
                false
            }
        }
    }

    private val lastSourceUpdate = ConcurrentHashMap<String, Long>()

    private fun startLiveStreams() {
        // 1. Binance Crypto Stream
        scope.launch {
            val cryptoSymbols = listOf("BTC/USD", "ETH/USD", "BNB/USD", "SOL/USD", "XRP/USD")
            val binanceStream = BinanceBookTickerStream(
                symbols = cryptoSymbols,
                onEvent = { event ->
                    if (event is StreamEvent.Quote) {
                        val q = event.quote
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
                    }
                },
                scheduler = { delayMs, block ->
                    scope.launch {
                        delay(delayMs)
                        block()
                    }
                }
            )
            binanceStream.start()
        }

        // 2. OANDA Pricing Stream for Forex & Metals
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
                derivGw.latestQuotes.collect { quotes ->
                    val updated = quotes.mapNotNull { (derivSymbol, q) ->
                        val canonicalSymbol = canonicalByDerivSymbol[derivSymbol]
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
                        }
                    }
                }
            }
        }
    }

    private suspend fun pollAllSymbolsParallel() {
        val symbols = SymbolRegistry.getBackgroundSymbols()
        coroutineScope {
            symbols.map { sym ->
                async {
                    pollingSemaphore.withPermit {
                        val ticker = fetchBackgroundQuoteForSymbol(sym)
                        if (ticker != null && shouldUpdateTicker(sym, ticker)) {
                            _tickers.update { current ->
                                current + (sym to ticker)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    override suspend fun refreshSymbol(symbol: String): MarketTicker? {
        val ticker = fetchLiveQuoteForSymbol(symbol)
        
        if (ticker != null && shouldUpdateTicker(symbol, ticker)) {
            _tickers.update { current ->
                current + (symbol to ticker)
            }
        }
        return ticker
    }

    private fun startLivePolling() {
        scope.launch {
            try {
                // Background polling loop - reduced frequency to save user's data
                while (true) {
                    // Only poll everything every 45 seconds to keep dashboard alive without wasting data
                    pollAllSymbolsParallel()
                    delay(45_000L)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Live polling loop failed: ${e.javaClass.simpleName}")
            }
        }
    }

    override suspend fun getCandles(symbol: String, timeframe: String, count: Int): List<Candle> = withContext(Dispatchers.IO) {
        val key = "$symbol-$timeframe"
        val now = clock()
        val cached = candleCache[key]
        val ttl = getTtlForTimeframe(timeframe)

        // P4: Cache TTL capping and open time rollover check
        val tf = com.destinedfx.core.marketdata.Timeframe.parse(timeframe)
        val isExpired = if (cached != null && tf != null && cached.candles.isNotEmpty()) {
            val lastCandleTime = cached.candles.last().timestamp
            val currentCandleOpen = (now / tf.millis) * tf.millis
            (now - cached.timestamp) >= ttl || currentCandleOpen > lastCandleTime
        } else true

        if (cached != null && !isExpired && cached.candles.isNotEmpty()) {
            return@withContext cached.candles.takeLast(count)
        }

        val aClass = com.destinedfx.core.instrument.AssetClassifier.of(symbol)
        var candles = emptyList<Candle>()
        var sourceUsed = ""
        var isDelayed = false

        val preferred = selectedApiSource.value

        // 1. Deriv WebSocket priority: when DERIV is selected, default, or for Deriv symbols
        val derivSymbol = com.destinedfx.core.broker.DerivInstrumentResolver.resolve(symbol)
        if ((preferred == "DERIV" || preferred.isBlank() || derivSymbol?.startsWith("R_") == true || derivSymbol?.startsWith("1HZ") == true || derivSymbol?.startsWith("BOOM") == true || derivSymbol?.startsWith("CRASH") == true) && derivMarketDataGateway != null && derivSymbol != null) {
            val granSec = (tf?.minutes ?: 60) * 60
            candles = withTimeoutOrNull(6000) { derivMarketDataGateway.fetchCandles(derivSymbol, granSec, count) } ?: emptyList()
            if (candles.isNotEmpty()) {
                sourceUsed = "Deriv WebSocket"
                isDelayed = false
            }
        }

        // 2. Crypto priority: Binance Spot
        if (candles.isEmpty() && BinanceSymbols.toBinance(symbol) != null) {
            candles = withTimeoutOrNull(5000) { binanceProvider.getCandles(symbol, timeframe, count) } ?: emptyList()
            if (candles.isNotEmpty()) sourceUsed = "Binance Spot (USDT pairs)"
        } else if (candles.isEmpty() && (aClass == com.destinedfx.core.marketdata.AssetClass.FOREX || 
                   aClass == com.destinedfx.core.marketdata.AssetClass.METALS || 
                   aClass == com.destinedfx.core.marketdata.AssetClass.INDICES)) {
            
            // 3. OANDA if configured and selected
            if (preferred == "OANDA" || secrets.getOandaApiKey().isNotBlank()) {
                candles = withTimeoutOrNull(4000) { oandaProvider.getCandles(symbol, timeframe, count) } ?: emptyList()
                if (candles.isNotEmpty()) sourceUsed = "OANDA"
            }
            


        }

        if (candles.isNotEmpty()) {
            val finalSource = if (isDelayed) "$sourceUsed | DELAYED - analysis only" else sourceUsed
            
            val currentCandleOpen = tf?.let { (now / it.millis) * it.millis } ?: candles.last().timestamp
            candles = candles.map { c ->
                if (c.timestamp == currentCandleOpen) c.copy(complete = false)
                else if (c.timestamp < currentCandleOpen) c.copy(complete = true)
                else c
            }

            // P5: Update forming candle with live ticker
            val ticker = _tickers.value[symbol]
            val processedCandles = if (ticker != null && ticker.price > 0 && candles.isNotEmpty()) {
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
            _candleSources[symbol] = finalSource
            candleCache[key] = CachedCandles(processedCandles, now)
            _candleFetchErrors.update { it - key }
            return@withContext processedCandles.takeLast(count)
        } else {
            val errorMsg = "Data unavailable for $symbol $timeframe"
            _candleFetchErrors.update { it + (key to errorMsg) }
            return@withContext emptyList()
        }
    }

    override suspend fun getLatestTick(symbol: String): Tick = withContext(Dispatchers.IO) {
        val ticker = _tickers.value[symbol]
        if (ticker != null) {
            Tick(ticker.lastUpdateMillis, ticker.price, ticker.spread)
        } else {
            Tick(clock(), 0.0, 0.0)
        }
    }

    override fun isFresh(symbol: String, maxAgeMillis: Long): Boolean {
        val ticker = _tickers.value[symbol] ?: return false
        return (clock() - ticker.lastUpdateMillis) < maxAgeMillis
    }

    fun getDataSource(symbol: String, timeframe: String): String {
        return dataSourceMap["$symbol-$timeframe"] ?: "Deriv WebSocket"
    }
}
