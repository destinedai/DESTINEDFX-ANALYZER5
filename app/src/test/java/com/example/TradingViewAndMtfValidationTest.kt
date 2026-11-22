package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.test.core.app.ApplicationProvider
import com.destinedfx.core.analysis.*
import com.destinedfx.core.marketdata.*
import com.example.data.market.MarketDataRepositoryImpl
import com.example.data.market.buildDerivCanonicalSymbolMap
import com.example.data.market.shouldReuseCandleCache
import com.example.data.market.isTimestampFresh
import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal
import com.example.security.SecretsManager
import com.example.ui.DestinedFXScreen
import com.example.ui.deriveMarketReadiness
import com.example.ui.mapTradeSignalToUiModel
import com.example.ui.scanChartBitmap
import com.example.ui.detectPairFromChartText
import com.example.ui.detectTimeframeFromChartText
import com.example.ui.extractVisibleNumericLabels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TradingViewAndMtfValidationTest {

    @Test
    fun chartReadinessNeverInfersRenderingFromCandlesOrWidgetPresence() {
        val unverified = deriveMarketReadiness(
            ticker = null,
            candleSource = "verified-provider",
            candlesAvailable = true,
            chartDisplayed = false,
            signal = null,
            nowMs = 3_610_000L,
candleRetrievedAtMs = 3_610_000L,
latestClosedCandleAtMs = 10_000L,
timeframe = "H1",
allowWeekendGap = false,
)
        assertTrue(unverified.candlesConfirmed)
        assertFalse(unverified.chartDisplayed)
        assertEquals("Chart candle rendering unverified", unverified.chartLabel)

        val nativeRendered = deriveMarketReadiness(
            ticker = null,
            candleSource = "verified-provider",
            candlesAvailable = true,
            chartDisplayed = true,
            signal = null,
            nowMs = 3_610_000L,
candleRetrievedAtMs = 3_610_000L,
latestClosedCandleAtMs = 10_000L,
timeframe = "H1",
allowWeekendGap = false,
)
        assertEquals("Native chart rendered", nativeRendered.chartLabel)
    }

    private fun getSecretsManager(): SecretsManager {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SecretsManager(context)
    }

    @Test
    fun testMobileNavigationHasFivePrimaryTabsAndSecondaryRoutes() {
        assertEquals(
            listOf("Home", "Analyzer", "Bot", "Watchlist", "More"),
            DestinedFXScreen.primaryScreens.map { it.title }
        )
        assertTrue(DestinedFXScreen.moreScreens.map { it.title }.containsAll(
            listOf("Image Analyzer", "Strategies", "Calendar", "Risk", "Settings", "Chat", "About & Disclosure", "Contact")
        ))
        assertEquals(
            DestinedFXScreen.allScreens.toSet(),
            (DestinedFXScreen.primaryScreens + DestinedFXScreen.moreScreens + DestinedFXScreen.More).toSet()
        )
        assertEquals(DestinedFXScreen.allScreens.size, DestinedFXScreen.allScreens.distinct().size)
        assertTrue(DestinedFXScreen.allScreens.contains(DestinedFXScreen.Watchlist))
        assertTrue(DestinedFXScreen.primaryScreens.contains(DestinedFXScreen.Watchlist))
        assertTrue(DestinedFXScreen.primaryScreens.containsAll(
            listOf(DestinedFXScreen.Home, DestinedFXScreen.Analyzer, DestinedFXScreen.Bot,
                DestinedFXScreen.Watchlist, DestinedFXScreen.More)
        ))
        assertFalse(DestinedFXScreen.moreScreens.any { it in DestinedFXScreen.primaryScreens })
        assertEquals("nav_tab_more", DestinedFXScreen.More.tag)
    }

    @Test
    fun testPublicDerivFeedMapUsesCanonicalForexAndMetalSymbols() {
        val mapping = buildDerivCanonicalSymbolMap()

        assertEquals("EUR/USD", mapping["frxEURUSD"])
        assertEquals("GBP/USD", mapping["frxGBPUSD"])
        assertEquals("XAU/USD", mapping["frxXAUUSD"])
        assertEquals("XAG/USD", mapping["frxXAGUSD"])
        // Only the two crypto instruments with explicit, audited Deriv resolver entries
        // may be mapped. Other analyzer crypto labels must remain unsupported, not guessed.
        assertEquals("BTC/USD", mapping["cryBTCUSD"])
        assertEquals("ETH/USD", mapping["cryETHUSD"])
        assertFalse(mapping.values.contains("BNB/USD"))
        assertFalse(mapping.values.contains("SOL/USD"))
        assertFalse(mapping.values.contains("XRP/USD"))
    }

    @Test
    fun isTimestampFresh_RejectsFutureStaleAndInvalidTimestamps() {
        assertTrue(isTimestampFresh(timestampMs = 9_950L, nowMs = 10_000L, maxAgeMillis = 100L))
        assertFalse("At the max-age boundary the tick is stale",
            isTimestampFresh(timestampMs = 9_900L, nowMs = 10_000L, maxAgeMillis = 100L))
        assertFalse("A future-dated quote must not count as fresh",
            isTimestampFresh(timestampMs = 10_001L, nowMs = 10_000L, maxAgeMillis = 100L))
        assertFalse(isTimestampFresh(timestampMs = 1L, nowMs = 10_000L, maxAgeMillis = 100L))
        assertFalse(isTimestampFresh(timestampMs = 0L, nowMs = 10_000L, maxAgeMillis = 100L))
        assertFalse(isTimestampFresh(timestampMs = 9_950L, nowMs = 10_000L, maxAgeMillis = 0L))
    }

    // --- 3. MULTI-TIMEFRAME ANALYSIS TESTS ---

    private fun generateCandleSeries(
        count: Int,
        timeframe: Timeframe,
        isBullish: Boolean = true,
        basePrice: Double = 1.0800,
        baseTime: Long = 1700000000000L
    ): List<Candle> {
        val candles = mutableListOf<Candle>()
        var price = basePrice
        for (i in 0 until count) {
            val waveStep = i % 10
            val delta = if (isBullish) {
                if (waveStep < 7) 0.0008 else -0.0004
            } else {
                if (waveStep < 7) -0.0008 else 0.0004
            }
            val open = price
            val close = price + delta
            val high = maxOf(open, close) + 0.0003
            val low = minOf(open, close) - 0.0003
            candles.add(
                Candle(
                    timeMs = baseTime + i * timeframe.millis,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = 1200.0,
                    volumeType = VolumeType.TICK,
                    complete = true
                )
            )
            price = close
        }
        return candles
    }

    @Test
    fun testMtfEngine_AllRequiredTimeframesAlignBullish() {
        val w1Candles = generateCandleSeries(50, Timeframe.W1, isBullish = true)
        val d1Candles = generateCandleSeries(50, Timeframe.D1, isBullish = true)
        val h4Candles = generateCandleSeries(50, Timeframe.H4, isBullish = true)

        val data = mapOf(
            Timeframe.W1 to w1Candles,
            Timeframe.D1 to d1Candles,
            Timeframe.H4 to h4Candles
        )

        val result = MtfEngine.analyse(data, MtfConfig(required = listOf(Timeframe.W1, Timeframe.D1, Timeframe.H4)))
        assertEquals(MtfState.FULL_BULLISH, result.state)
        assertEquals(Dir.BULL, result.dir)
        assertTrue(result.missing.isEmpty())
        assertEquals(3, result.aligned)
        assertEquals(3, result.required)
    }

    @Test
    fun testMtfEngine_MissingTimeframeProducesDataIncomplete() {
        val d1Candles = generateCandleSeries(50, Timeframe.D1, isBullish = true)
        // W1 is missing entirely, H4 has fewer than minCandles (only 10)
        val h4Candles = generateCandleSeries(10, Timeframe.H4, isBullish = true)

        val data = mapOf(
            Timeframe.D1 to d1Candles,
            Timeframe.H4 to h4Candles
        )

        val result = MtfEngine.analyse(data, MtfConfig(required = listOf(Timeframe.W1, Timeframe.D1, Timeframe.H4), minCandles = 40))
        assertEquals(MtfState.DATA_INCOMPLETE, result.state)
        assertTrue(result.missing.contains(Timeframe.W1))
        assertTrue(result.missing.contains(Timeframe.H4))
        assertTrue(result.summary.contains("MTF DATA INCOMPLETE"))
    }

    @Test
    fun testMtfEngine_ConflictingHigherTimeframesProduceConflict() {
        val w1Candles = generateCandleSeries(50, Timeframe.W1, isBullish = true)
        val d1Candles = generateCandleSeries(50, Timeframe.D1, isBullish = false) // Bearish conflict!
        val h4Candles = generateCandleSeries(50, Timeframe.H4, isBullish = true)

        val data = mapOf(
            Timeframe.W1 to w1Candles,
            Timeframe.D1 to d1Candles,
            Timeframe.H4 to h4Candles
        )

        val result = MtfEngine.analyse(data, MtfConfig(required = listOf(Timeframe.W1, Timeframe.D1, Timeframe.H4)))
        assertEquals(MtfState.CONFLICT, result.state)
        assertNull("Conflicting timeframes must have no agreed direction", result.dir)
    }

    @Test
    fun testDynamicAnalysisInfo_ExposesMtfBiasesCorrectly() {
        val signal = TradeSignal(
            id = "test-signal-1",
            symbol = "EUR/USD",
            timeframe = "M15",
            direction = SignalDirection.LONG,
            entryPrice = 1.0850,
            stopLoss = 1.0840,
            takeProfit = 1.0875,
            confidenceScore = 78,
            rationale = "MTF: 2/3 aligned",
            timestamp = System.currentTimeMillis(),
            mtfSummary = "2/3 aligned",
            mtfState = "PARTIAL_BULLISH",
            mtfPerTimeframe = mapOf(
                "H1" to "BULLISH",
                "H4" to "BULLISH",
                "D1" to "BEARISH",
                "W1" to "BULLISH"
            ),
            signalState = "BUY",
            executable = true
        )

        val uiModel = mapTradeSignalToUiModel(signal)
        assertEquals("BULLISH", uiModel.getTfBias("H1"))
        assertEquals("BULLISH", uiModel.getTfBias("H4"))
        assertEquals("BEARISH", uiModel.getTfBias("D1"))
        assertEquals("BULLISH", uiModel.getTfBias("W1"))
        assertEquals("N/A", uiModel.getTfBias("M1"))
        assertEquals("2/3 aligned", uiModel.mtfSummary)
        assertEquals("PARTIAL_BULLISH", uiModel.mtfStatus)
    }

    @Test
    fun testCandleCache_DoesNotLetTwoBarReferencePriceHideFullHistoryRequest() {
        assertTrue(shouldReuseCandleCache(cachedCount = 2, requestedCount = 2))
        assertFalse(shouldReuseCandleCache(cachedCount = 2, requestedCount = 250))
        assertFalse(shouldReuseCandleCache(cachedCount = 59, requestedCount = 250))
        assertTrue(shouldReuseCandleCache(cachedCount = 60, requestedCount = 250))
        assertTrue(shouldReuseCandleCache(cachedCount = 150, requestedCount = 250))
        assertFalse(shouldReuseCandleCache(cachedCount = 0, requestedCount = 1))
    }

    @Test
    fun testCandleValidator_RejectsOutOfOrderTimestamps() {
        val now = 1700000000000L
        val candle1 = Candle(now, 1.0800, 1.0820, 1.0790, 1.0810, 100.0, VolumeType.TICK, null, true)
        // Out of order: candle 2 timestamp is earlier than candle 1!
        val candle2 = Candle(now - 60_000L, 1.0810, 1.0830, 1.0805, 1.0825, 100.0, VolumeType.TICK, null, true)

        val check = CandleValidator.validate(listOf(candle1, candle2), Timeframe.M1, minCount = 2, tolerateWeekendGaps = false)
        assertFalse("Candle sequence with decreasing timestamp must be rejected", check.ok)
        assertTrue(check.problems.any { it.contains("Non-increasing timestamps") })
    }

    @Test
    fun testChartImagePixelScannerReportsOnlyMeasuredColourEvidence() {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(AndroidColor.rgb(18, 24, 36))
        for (y in 0 until 40) {
            for (x in 0 until 20) bitmap.setPixel(x, y, AndroidColor.rgb(30, 220, 100))
            for (x in 20 until 30) bitmap.setPixel(x, y, AndroidColor.rgb(238, 55, 65))
        }

        val report = scanChartBitmap(bitmap)
        assertEquals(1600, report.sampledPixelCount)
        assertEquals(800, report.bullishTonePixels)
        assertEquals(400, report.bearishTonePixels)
        assertEquals(75.00, report.colourCoveragePercent, 0.02)
        assertTrue(report.visualBalance.contains("GREEN/CYAN COLOUR"))
        assertTrue(report.explanation.contains("not bullish/bearish market confirmation"))
        bitmap.recycle()
    }

    @Test
    fun testChartImagePixelScannerDoesNotCallDarkImageASetup() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(AndroidColor.rgb(12, 16, 26))

        val report = scanChartBitmap(bitmap)
        assertEquals(0, report.bullishTonePixels)
        assertEquals(0, report.bearishTonePixels)
        assertEquals("UNCLEAR", report.visualBalance)
        assertTrue(report.evidenceStatus.contains("LIMITED"))
        bitmap.recycle()
    }

}
