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
import com.example.domain.model.SignalDirection
import com.example.domain.model.TradeSignal
import com.example.security.SecretsManager
import com.example.ui.chart.TradingViewMapper
import com.example.ui.mapTradeSignalToUiModel
import com.example.ui.scanChartBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TradingViewAndMtfValidationTest {

    private fun getSecretsManager(): SecretsManager {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SecretsManager(context)
    }

    // --- 1. TRADINGVIEW MAPPING TESTS ---

    @Test
    fun testTradingViewSymbolMapping_SupportedAssets() {
        assertEquals("OANDA:EURUSD", TradingViewMapper.mapSymbol("EUR/USD"))
        assertEquals("OANDA:GBPUSD", TradingViewMapper.mapSymbol("GBP/USD"))
        assertEquals("OANDA:USDJPY", TradingViewMapper.mapSymbol("USD/JPY"))
        assertEquals("OANDA:XAUUSD", TradingViewMapper.mapSymbol("XAU/USD"))
        assertEquals("OANDA:XAGUSD", TradingViewMapper.mapSymbol("XAG/USD"))
        assertEquals("BINANCE:BTCUSDT", TradingViewMapper.mapSymbol("BTC/USD"))
        assertEquals("BINANCE:ETHUSDT", TradingViewMapper.mapSymbol("ETH/USD"))
        assertEquals("OANDA:SPX500USD", TradingViewMapper.mapSymbol("SPX500"))
        assertEquals("OANDA:NAS100USD", TradingViewMapper.mapSymbol("NAS100"))
        assertEquals("TVC:USOIL", TradingViewMapper.mapSymbol("USOIL"))
        assertEquals("OANDA:EURCHF", TradingViewMapper.mapSymbol("EUR/CHF"))
        assertEquals("OANDA:XPTUSD", TradingViewMapper.mapSymbol("XPT/USD"))
        assertEquals("OANDA:XPDUSD", TradingViewMapper.mapSymbol("XPD/USD"))
        assertEquals("OANDA:UK100GBP", TradingViewMapper.mapSymbol("FTSE100"))
        assertEquals("OANDA:DE30EUR", TradingViewMapper.mapSymbol("DAX40"))
        assertEquals("NYMEX:NG1!", TradingViewMapper.mapSymbol("NATGAS"))
    }

    @Test
    fun testTradingViewSymbolMapping_UnsupportedSyntheticRejection() {
        // Deriv synthetic indices are proprietary and have no TradingView feeds.
        // The mapper MUST return null rather than emitting invalid FX:R_100 symbols.
        assertNull("R_100 must not be mapped to TradingView", TradingViewMapper.mapSymbol("R_100"))
        assertNull("1HZ100V must not be mapped to TradingView", TradingViewMapper.mapSymbol("1HZ100V"))
        assertNull("CRASH500 must not be mapped to TradingView", TradingViewMapper.mapSymbol("CRASH500"))
        assertNull("BOOM1000 must not be mapped to TradingView", TradingViewMapper.mapSymbol("BOOM1000"))
        assertNull("Unknown garbage symbol must return null", TradingViewMapper.mapSymbol("UNKNOWN_COIN_XYZ_123"))
    }

    @Test
    fun testTradingViewAdvancedChartHtml_ContainsOfficialResponsiveWidget() {
        val html = TradingViewMapper.buildWidgetHtml("EUR/USD", "M15")
        assertNotNull(html)
        assertTrue(html!!.contains("https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js"))
        assertTrue(html.contains("\"symbol\":\"OANDA:EURUSD\""))
        assertTrue(html.contains("\"interval\":\"15\""))
        assertTrue(html.contains("height:100%"))
        assertTrue(html.contains("\"details\":true"))
        assertTrue(html.contains("\"calendar\":true"))
        assertTrue(html.contains("\"studies\":[\"STD;EMA\",\"STD;RSI\"]"))
        assertTrue(html.contains("#171B27"))
        assertNull(TradingViewMapper.buildWidgetHtml("R_100", "M15"))
        assertNull(TradingViewMapper.buildWidgetHtml("EUR/USD", "M2"))
    }

    @Test
    fun testTradingViewEmbedUrl_UsesSupportedSymbolAndInterval() {
        val url = TradingViewMapper.buildWidgetUrl("EUR/USD", "M15")
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://s.tradingview.com/widgetembed/?"))
        assertTrue(url.contains("symbol=OANDA%3AEURUSD"))
        assertTrue(url.contains("interval=15"))
        assertTrue(url.contains("hidesidetoolbar=0"))
        assertTrue(url.contains("theme=dark"))
        assertTrue(url.contains("calendar=1"))
        assertNull("Unsupported synthetic instruments must not get a TradingView URL", TradingViewMapper.buildWidgetUrl("R_100", "M15"))
        assertNull("Unsupported timeframes must not get a TradingView URL", TradingViewMapper.buildWidgetUrl("EUR/USD", "M2"))
    }

    @Test
    fun testTradingViewIntervalMapping_AllSupportedIntervals() {
        assertEquals("1", TradingViewMapper.mapInterval("M1"))
        assertEquals("5", TradingViewMapper.mapInterval("M5"))
        assertEquals("15", TradingViewMapper.mapInterval("M15"))
        assertEquals("30", TradingViewMapper.mapInterval("M30"))
        assertEquals("60", TradingViewMapper.mapInterval("H1"))
        assertEquals("240", TradingViewMapper.mapInterval("H4"))
        assertEquals("D", TradingViewMapper.mapInterval("D1"))
        assertEquals("W", TradingViewMapper.mapInterval("W1"))

        // Unsupported intervals
        assertNull(TradingViewMapper.mapInterval("M2"))
        assertNull(TradingViewMapper.mapInterval("H2"))
        assertNull(TradingViewMapper.mapInterval("INVALID"))
    }

    @Test
    fun testPublicDerivFeedMapUsesCanonicalForexAndMetalSymbols() {
        val mapping = buildDerivCanonicalSymbolMap()

        assertEquals("EUR/USD", mapping["frxEURUSD"])
        assertEquals("GBP/USD", mapping["frxGBPUSD"])
        assertEquals("XAU/USD", mapping["frxXAUUSD"])
        assertEquals("XAG/USD", mapping["frxXAGUSD"])
        // Crypto is already served by the public Binance stream, so Deriv aliases must not
        // override the canonical crypto tickers.
        assertFalse(mapping.keys.any { it.startsWith("cry") })
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
