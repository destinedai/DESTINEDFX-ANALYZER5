package com.example.data.trade

import com.example.data.market.SymbolRegistry
import com.example.domain.model.MarketTicker
import com.example.domain.repository.MarketDataRepository
import com.example.domain.usecase.SignalGenerationUseCase
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

private fun isCryptoSymbol(symbol: String): Boolean =
    symbol.substringBefore('/').uppercase() in setOf("BTC", "ETH", "BNB", "SOL", "XRP")

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
    },
    private val signalGenerationUseCase: SignalGenerationUseCase? = null,
    private val signalTimeframe: String = "M15",
    private val signalStrategyId: String = "smc-confluence-1.0.0"
) {
    interface MatrixUiListener {
        fun onEngineOnline()
        fun onLiveAssetTelemetry(symbol: String, assetType: String, price: Double, optimalLots: Double, cashRisk: Double, advice: String)
        fun onEngineFault(error: String)

        /** Default no-op keeps existing UI listeners source-compatible. */
        fun onSignalEvaluation(symbol: String, signalState: String, executable: Boolean, detail: String) = Unit
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
                            evaluateSignal(symbol)
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

    /**
     * Runs the existing deterministic analysis adapter only after this scanner has accepted a
     * fresh provider quote. SignalGenerationUseCaseImpl performs candle validation, MTF checks,
     * SignalEngine analysis, and authoritative RiskEngine plan validation. This class only relays
     * its result; it never computes entry, stop, target, R:R, or position size.
     */
    private suspend fun evaluateSignal(symbol: String) {
        val generator = signalGenerationUseCase ?: return
        try {
            val signal = withTimeout(30_000L) {
                generator.generateSignal(symbol, signalTimeframe, signalStrategyId)
            }
            val detail = (signal.blockReasons + signal.reasons)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("; ")
                .ifBlank { signal.rationale }
            uiListener.onSignalEvaluation(symbol, signal.signalState, signal.executable, detail)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            uiListener.onSignalEvaluation(
                symbol,
                "DATA_UNAVAILABLE",
                false,
                "Signal generation failed: ${error.javaClass.simpleName}"
            )
        }
    }

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
        val acceptedTicker = ticker ?: return InstrumentSnapshot(
            symbol,
            InstrumentState.ERROR,
            reason = "No quote returned"
        )
        if (!isValidTicker(acceptedTicker, clock())) {
            return InstrumentSnapshot(symbol, InstrumentState.STALE, reason = "Quote is stale or invalid")
        }
        return InstrumentSnapshot(
            symbol = symbol,
            state = InstrumentState.LIVE,
            source = acceptedTicker.source,
            price = acceptedTicker.price,
            quoteTimestampMs = acceptedTicker.lastUpdateMillis,
            reason = "Fresh validated quote"
        )
    }

    private fun isValidTicker(ticker: MarketTicker?, nowMs: Long): Boolean {
        if (ticker == null || !ticker.price.isFinite() || ticker.price <= 0.0) return false
        if (ticker.lastUpdateMillis <= 0L || nowMs < ticker.lastUpdateMillis) return false
        return nowMs - ticker.lastUpdateMillis <= MAX_QUOTE_AGE_MS && ticker.source.isNotBlank()
    }

    private fun isCrypto(symbol: String): Boolean = isCryptoSymbol(symbol)

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
