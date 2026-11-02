package com.example.data.trade

import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import com.example.domain.model.SignalDirection
import com.example.domain.model.Tick
import com.example.domain.model.TradeSignal
import com.example.domain.repository.MarketDataRepository
import com.example.domain.usecase.SignalGenerationUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GlobalMarketMatrixEngineTest {
    private val now = 1_700_000_000_000L

    @Test
    fun freshStaleInvalidFutureMissingTimeoutAndProviderErrorAreDistinct() = runBlocking {
        val cases = listOf(
            "EUR/USD" to MarketTicker("EUR/USD", 1.1, 0.0, 1.2, 1.0, 0.0, "NEUTRAL", 0, now, true, "TEST"),
            "GBP/USD" to MarketTicker("GBP/USD", 1.2, 0.0, 1.3, 1.1, 0.0, "NEUTRAL", 0, now - 31_000L, true, "TEST"),
            "USD/JPY" to MarketTicker("USD/JPY", Double.NaN, 0.0, 1.0, 1.0, 0.0, "NEUTRAL", 0, now, true, "TEST"),
            "USD/CHF" to MarketTicker("USD/CHF", 0.9, 0.0, 1.0, 0.8, 0.0, "NEUTRAL", 0, now + 1_000L, true, "TEST")
        )
        val responses = cases.toMap()
        val repo = FakeRepository { symbol ->
            when (symbol) {
                "EUR/GBP" -> null
                "AUD/USD" -> throw IllegalStateException("provider rejected symbol")
                else -> responses[symbol]
            }
        }
        val engine = engine(repo, listOf("EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "EUR/GBP", "AUD/USD"))
        engine.startGlobalStream()
        await { engine.latestSnapshots().size == 6 }
        val snapshots = engine.latestSnapshots()
        assertEquals(GlobalMarketMatrixEngine.InstrumentState.LIVE, snapshots["EUR/USD"]?.state)
        assertEquals(GlobalMarketMatrixEngine.InstrumentState.STALE, snapshots["GBP/USD"]?.state)
        assertEquals(GlobalMarketMatrixEngine.InstrumentState.STALE, snapshots["USD/JPY"]?.state)
        assertEquals(GlobalMarketMatrixEngine.InstrumentState.STALE, snapshots["USD/CHF"]?.state)
        assertEquals("No quote returned", snapshots["EUR/GBP"]?.reason)
        assertEquals(GlobalMarketMatrixEngine.InstrumentState.ERROR, snapshots["AUD/USD"]?.state)
        assertTrue(snapshots["AUD/USD"]?.reason.orEmpty().contains("Provider error"))
        engine.haltEngine()
    }

    @Test
    fun timeoutIsNotReportedAsMissingQuote() = runBlocking {
        val repo = FakeRepository { delay(200); null }
        val engine = engine(repo, listOf("EUR/USD"), providerTimeoutMs = 20)
        engine.startGlobalStream()
        await { engine.latestSnapshots().containsKey("EUR/USD") }
        val snapshot = engine.latestSnapshots().getValue("EUR/USD")
        assertEquals(GlobalMarketMatrixEngine.InstrumentState.TIMEOUT, snapshot.state)
        assertEquals("Provider timeout", snapshot.reason)
        engine.haltEngine()
    }

    @Test
    fun unknownInstrumentIsUnsupportedAndNeverRequested() = runBlocking {
        val calls = AtomicInteger(0)
        val repo = FakeRepository { calls.incrementAndGet(); null }
        val engine = engine(repo, listOf("NOT/REGISTERED"))
        engine.startGlobalStream()
        await { engine.latestSnapshots().containsKey("NOT/REGISTERED") }
        assertEquals(GlobalMarketMatrixEngine.InstrumentState.UNSUPPORTED, engine.latestSnapshots().getValue("NOT/REGISTERED").state)
        assertEquals(0, calls.get())
        engine.haltEngine()
    }

    @Test
    fun duplicateStartsDoNotDuplicateRequestsAndEmptyUniverseCanRestart() = runBlocking {
        val symbols = AtomicReference<List<String>>(emptyList())
        val repo = FakeRepository { MarketTicker("EUR/USD", 1.1, 0.0, 1.2, 1.0, 0.0, "NEUTRAL", 0, now, true, "TEST") }
        val engine = engine(repo, symbolsProvider = { symbols.get() })
        engine.startGlobalStream()
        await { !engine.isRunning() }
        symbols.set(listOf("EUR/USD"))
        engine.startGlobalStream()
        engine.startGlobalStream()
        await { engine.latestSnapshots()["EUR/USD"]?.state == GlobalMarketMatrixEngine.InstrumentState.LIVE }
        assertTrue(repo.calls.get() >= 1)
        engine.haltEngine()
    }

    @Test
    fun scanConcurrencyIsBoundedAndHaltCancelsTheLoop() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val repo = FakeRepository { symbol ->
            val current = active.incrementAndGet()
            maximum.updateAndGet { old -> maxOf(old, current) }
            delay(20)
            active.decrementAndGet()
            MarketTicker(symbol, 1.0, 0.0, 1.1, 0.9, 0.0, "NEUTRAL", 0, now, true, "TEST")
        }
        val symbols = listOf("EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "AUD/USD", "USD/CAD", "NZD/USD", "EUR/GBP")
        val engine = engine(repo, symbols, scanIntervalMs = 1_000)
        engine.startGlobalStream()
        await { repo.calls.get() == symbols.size }
        assertTrue(maximum.get() <= GlobalMarketMatrixEngine.MAX_CONCURRENT_SCANS)
        engine.haltEngine()
        assertFalse(engine.isRunning())
    }

    @Test
    fun freshQuoteRelaysExistingDeterministicSignalResultWithoutApprovingIt() = runBlocking {
        val listener = RecordingListener()
        val calls = AtomicInteger(0)
        val signalUseCase = object : SignalGenerationUseCase {
            override suspend fun generateSignal(symbol: String, timeframe: String, strategyId: String): TradeSignal {
                calls.incrementAndGet()
                return TradeSignal(
                    id = "signal-1",
                    symbol = symbol,
                    timeframe = timeframe,
                    direction = SignalDirection.LONG,
                    entryPrice = 1.1,
                    stopLoss = 1.0,
                    takeProfit = 1.3,
                    confidenceScore = 70,
                    rationale = "deterministic test result",
                    timestamp = now,
                    signalState = "BUY",
                    executable = false,
                    blockReasons = listOf("RiskEngine rejected test plan")
                )
            }
        }
        val repo = FakeRepository {
            MarketTicker("EUR/USD", 1.1, 0.0, 1.2, 1.0, 0.0, "NEUTRAL", 0, now, true, "TEST")
        }
        val engine = engine(repo, listener = listener, signalUseCase = signalUseCase)
        engine.startGlobalStream()
        await { calls.get() == 1 }
        assertEquals("BUY", listener.signalState)
        assertFalse(listener.signalExecutable)
        assertTrue(listener.signalDetail.contains("RiskEngine rejected"))
        engine.haltEngine()
    }

    private fun engine(
        repo: FakeRepository,
        symbols: List<String> = listOf("EUR/USD"),
        scanIntervalMs: Long = 1_000,
        providerTimeoutMs: Long = 200,
        symbolsProvider: (() -> List<String>)? = null,
        listener: RecordingListener = RecordingListener(),
        signalUseCase: SignalGenerationUseCase? = null
    ) = GlobalMarketMatrixEngine(
        uiListener = listener,
        marketDataRepository = repo,
        clock = { now },
        scanIntervalMs = scanIntervalMs,
        providerTimeoutMs = providerTimeoutMs,
        instrumentProvider = symbolsProvider ?: { symbols },
        signalGenerationUseCase = signalUseCase
    )

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(3_000) {
            while (!condition()) delay(5)
        }
    }

    private class RecordingListener : GlobalMarketMatrixEngine.MatrixUiListener {
        var signalState: String = ""
        var signalExecutable: Boolean = false
        var signalDetail: String = ""
        override fun onEngineOnline() = Unit
        override fun onLiveAssetTelemetry(symbol: String, assetType: String, price: Double, optimalLots: Double, cashRisk: Double, advice: String) = Unit
        override fun onEngineFault(error: String) = Unit
        override fun onSignalEvaluation(symbol: String, signalState: String, executable: Boolean, detail: String) {
            this.signalState = signalState
            this.signalExecutable = executable
            this.signalDetail = detail
        }
    }

    private class FakeRepository(private val response: suspend (String) -> MarketTicker?) : MarketDataRepository {
        val calls = AtomicInteger(0)
        override val tickerFlow = MutableStateFlow<Map<String, MarketTicker>>(emptyMap())
        override val quoteDiagnostics = MutableStateFlow<Map<String, String>>(emptyMap())
        override val candleFetchErrors = MutableStateFlow<Map<String, String>>(emptyMap())
        override val selectedApiSource = MutableStateFlow("TEST")
        override val candleSources: Map<String, String> = emptyMap()
        override suspend fun getCandles(symbol: String, timeframe: String, count: Int): List<Candle> = emptyList()
        override suspend fun getLatestTick(symbol: String): Tick? = null
        override fun isFresh(symbol: String, maxAgeMillis: Long): Boolean = false
        override suspend fun refreshSymbol(symbol: String): MarketTicker? {
            calls.incrementAndGet()
            return response(symbol)
        }
    }
}
