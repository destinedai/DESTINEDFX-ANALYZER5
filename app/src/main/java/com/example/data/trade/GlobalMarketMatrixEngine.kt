package com.example.data.trade

import com.example.data.market.SymbolRegistry
import com.example.domain.model.MarketTicker
import com.example.domain.repository.MarketDataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Data-surveillance orchestration only. This class never places orders and never manufactures a
 * price, candle, lot size, or trade plan. A caller must provide the application's existing
 * MarketDataRepository; a missing repository is an explicit configuration failure.
 */
class GlobalMarketMatrixEngine(
    private val uiListener: MatrixUiListener,
    private val marketDataRepository: MarketDataRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
    private val providerTimeoutMs: Long = DEFAULT_PROVIDER_TIMEOUT_MS,
    private val instrumentProvider: () -> List<String> = {
        SymbolRegistry.getAllCanonical().filter { symbol ->
            val info = SymbolRegistry.get(symbol)
            info.oandaInstrument.isNotBlank() || isCryptoSymbol(symbol)
        }
    }
) {
    interface MatrixUiListener {
        fun onEngineOnline()
        fun onLiveAssetTelemetry(symbol: String, assetType: String, price: Double, optimalLots: Double, cashRisk: Double, advice: String)
        fun onEngineFault(error: String)
    }

    enum class InstrumentState { WAITING, LIVE, STALE, UNSUPPORTED, TIMEOUT, ERROR }

    data class InstrumentSnapshot(
        val symbol: String,
        val state: InstrumentState,
        val source: String = "",
        val price: Double? = null,
        val quoteTimestampMs: Long? = null,
        val reason: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var scanJob: Job? = null
    private val snapshots = LinkedHashMap<String, InstrumentSnapshot>()
    private var accountEquity = 0.0
    private var configuredRiskPercent = 1.0
    private var configuredStopDistance = 0.0
    private var configuredTargetDistance = 0.0

    @Synchronized
    fun configureRiskParameters(investment: Double, risk: Double, rawSlDistance: Double, rawTpDistance: Double) {
        // Store only sane configuration. Position sizing remains unavailable until a real broker
        // specification is supplied to the authoritative RiskEngine elsewhere in the app.
        accountEquity = investment.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        configuredRiskPercent = risk.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0
        configuredStopDistance = rawSlDistance.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        configuredTargetDistance = rawTpDistance.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    }

    /** Starts one bounded polling loop; repeated starts do not create duplicate scanners. */
    @Synchronized
    fun startGlobalStream() {
        if (!running.compareAndSet(false, true)) return
        val repository = marketDataRepository
        if (repository == null) {
            running.set(false)
            uiListener.onEngineFault("SURVEILLANCE BLOCKED: no authorized market-data repository configured.")
            return
        }
        scanJob = scope.launch {
            var announcedOnline = false
            try {
                while (isActive && running.get()) {
                    val symbols = instrumentProvider().map(String::trim).filter(String::isNotBlank).distinct()
                    if (symbols.isEmpty()) {
                        uiListener.onEngineFault("SURVEILLANCE BLOCKED: no provider-supported instruments are configured.")
                        // Do not leave the engine marked as running after a failed empty-universe
                        // cycle; callers must be able to repair configuration and restart it.
                        running.set(false)
                        break
                    }
                    val cycle = coroutineScope {
                        symbols.chunked(MAX_CONCURRENT_SCANS).flatMap { batch ->
                            batch.map { symbol ->
                                async(Dispatchers.IO) { symbol to scanOne(repository, symbol) }
                            }.awaitAll()
                        }
                    }
                    var acceptedFreshQuote = false
                    for ((symbol, snapshot) in cycle) {
                        if (!isActive || !running.get()) break
                        synchronized(snapshots) { snapshots[symbol] = snapshot }
                        if (snapshot.state == InstrumentState.LIVE && snapshot.price != null) {
                            acceptedFreshQuote = true
                            // Lots and cash risk remain zero because this data-only layer has no
                            // broker contract specification and must not guess sizing.
                            uiListener.onLiveAssetTelemetry(
                                symbol,
                                assetType(symbol),
                                snapshot.price,
                                0.0,
                                0.0,
                                "LIVE DATA ONLY: ${snapshot.source}; no risk-gated trade plan emitted."
                            )
                        }
                    }
                    if (acceptedFreshQuote && !announcedOnline) {
                        announcedOnline = true
                        uiListener.onEngineOnline()
                    }
                    delay(scanIntervalMs.coerceAtLeast(1L))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                running.set(false)
                uiListener.onEngineFault("SURVEILLANCE ERROR: ${error.javaClass.simpleName}")
            }
        }
    }

    @Synchronized
    fun haltEngine() {
        running.set(false)
        scanJob?.cancel()
        scanJob = null
    }

    fun isRunning(): Boolean = running.get()

    fun latestSnapshots(): Map<String, InstrumentSnapshot> = synchronized(snapshots) { snapshots.toMap() }

    private suspend fun scanOne(repository: MarketDataRepository, symbol: String): InstrumentSnapshot {
        if (symbol !in SymbolRegistry.getAllCanonical()) {
            return InstrumentSnapshot(symbol, InstrumentState.UNSUPPORTED, reason = "Symbol is not in the authoritative registry")
        }
        val info = SymbolRegistry.get(symbol)
        if (info.oandaInstrument.isBlank() && !isCryptoSymbol(symbol)) {
            return InstrumentSnapshot(symbol, InstrumentState.UNSUPPORTED, reason = "No supported provider mapping")
        }
        val ticker = try {
            withTimeout(providerTimeoutMs.coerceAtLeast(1L)) { repository.refreshSymbol(symbol) }
        } catch (timeout: TimeoutCancellationException) {
            return InstrumentSnapshot(symbol, InstrumentState.TIMEOUT, reason = "Provider timeout")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return InstrumentSnapshot(symbol, InstrumentState.ERROR, reason = "Provider error: ${error.javaClass.simpleName}")
        }
        if (!isValidTicker(ticker, clock())) {
            val reason = if (ticker == null) "No quote returned" else "Quote is stale or invalid"
            return InstrumentSnapshot(symbol, if (ticker == null) InstrumentState.ERROR else InstrumentState.STALE, reason = reason)
        }
        return InstrumentSnapshot(
            symbol = symbol,
            state = InstrumentState.LIVE,
            source = ticker.source,
            price = ticker.price,
            quoteTimestampMs = ticker.lastUpdateMillis,
            reason = "Fresh validated quote"
        )
    }

    private fun isValidTicker(ticker: MarketTicker?, nowMs: Long): Boolean {
        if (ticker == null || !ticker.price.isFinite() || ticker.price <= 0.0) return false
        if (ticker.lastUpdateMillis <= 0L || nowMs < ticker.lastUpdateMillis) return false
        return nowMs - ticker.lastUpdateMillis <= MAX_QUOTE_AGE_MS && ticker.source.isNotBlank()
    }

    private fun isCrypto(symbol: String): Boolean = isCryptoSymbol(symbol)

    private fun isCryptoSymbol(symbol: String): Boolean =
        symbol.substringBefore('/').uppercase() in setOf("BTC", "ETH", "BNB", "SOL", "XRP")

    private fun assetType(symbol: String): String = when {
        isCrypto(symbol) -> "CRYPTO"
        symbol in setOf("XAU/USD", "XAG/USD", "XPT/USD", "XPD/USD") -> "METAL"
        symbol.contains("/") -> "FOREX"
        else -> "CFD"
    }

    companion object {
        const val DEFAULT_SCAN_INTERVAL_MS = 60_000L
        const val DEFAULT_PROVIDER_TIMEOUT_MS = 5_000L
        const val MAX_QUOTE_AGE_MS = 30_000L
        const val MAX_CONCURRENT_SCANS = 4
    }
}
