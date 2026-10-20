package com.example.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.destinedfx.core.instrument.InstrumentSpec
import com.destinedfx.core.instrument.SpecRegistry
import com.destinedfx.core.marketdata.AssetClass
import com.example.domain.model.Candle
import com.example.domain.model.MarketTicker
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DestinedFXTheme
import com.example.ui.workstations.BotWorkstationTab
import com.example.ui.workstations.CalendarTab

/**
 * Creates an authoritative preview ViewModel instance populated with realistic sample data:
 * - EUR/USD M15
 * - Institutional SMC Trade Plan (STRONG_BUY, Entry 1.08750, SL 1.08500, TP 1.09250, 1:2.0R, 50.0 pips)
 * - 15 realistic M15 candlesticks
 * - Demo $10 equity with 1% risk ($0.10 max loss)
 * - Zero network, Supabase, Deriv, MetaApi, or SecretsManager calls
 */
fun createPreviewViewModel(app: Application): DestinedFXViewModel {
    val vm = DestinedFXViewModel(app, isPreview = true)

    vm.selectTicker("EUR/USD")
    vm.selectTimeframe("M15")
    vm.setRiskPercent(1.0)
    vm.configuredEquity.value = 10.0
    vm.selectScreen(DestinedFXScreen.Analyzer)

    // High-fidelity sample candles for EUR/USD M15
    val now = System.currentTimeMillis()
    val candleStep = 15 * 60 * 1000L
    val sampleCandles = listOf(
        Candle(now - 14 * candleStep, 1.0820, 1.0838, 1.0815, 1.0832, 1200.0),
        Candle(now - 13 * candleStep, 1.0832, 1.0846, 1.0828, 1.0842, 1400.0),
        Candle(now - 12 * candleStep, 1.0842, 1.0853, 1.0837, 1.0847, 1100.0),
        Candle(now - 11 * candleStep, 1.0847, 1.0862, 1.0841, 1.0856, 1800.0),
        Candle(now - 10 * candleStep, 1.0856, 1.0867, 1.0851, 1.0862, 1300.0),
        Candle(now - 9 * candleStep, 1.0862, 1.0872, 1.0854, 1.0860, 1500.0),
        Candle(now - 8 * candleStep, 1.0860, 1.0864, 1.0848, 1.0850, 1250.0),
        Candle(now - 7 * candleStep, 1.0850, 1.0858, 1.0842, 1.0854, 1150.0),
        Candle(now - 6 * candleStep, 1.0854, 1.0869, 1.0851, 1.0866, 1600.0),
        Candle(now - 5 * candleStep, 1.0866, 1.0877, 1.0862, 1.0872, 1750.0),
        Candle(now - 4 * candleStep, 1.0872, 1.0882, 1.0867, 1.0879, 1900.0),
        Candle(now - 3 * candleStep, 1.0879, 1.0887, 1.0874, 1.0884, 1650.0),
        Candle(now - 2 * candleStep, 1.0884, 1.0892, 1.0880, 1.0887, 1450.0),
        Candle(now - 1 * candleStep, 1.0887, 1.0894, 1.0882, 1.0890, 1300.0),
        Candle(now, 1.0890, 1.0897, 1.0886, 1.0892, 1550.0)
    )
    vm.candlestickHistory["EUR/USD"] = sampleCandles

    // Sample market ticker for EUR/USD
    vm.tickers.value = mapOf(
        "EUR/USD" to MarketTicker(
            symbol = "EUR/USD",
            price = 1.0892,
            changePercent = 0.35,
            high = 1.0897,
            low = 1.0815,
            spread = 0.00012,
            bias = "BULLISH",
            confidence = 88,
            lastUpdateMillis = now,
            isLive = true,
            source = "Deriv WebSocket"
        )
    )

    // Authoritative SMC Dynamic Analysis Trade Plan
    vm.currentDynamicAnalysis.value = DynamicAnalysisInfo(
        symbol = "EUR/USD",
        signalState = "STRONG_BUY",
        direction = "BUY",
        confidence = "HIGH",
        confidencePct = 92,
        entry = 1.08750,
        stopLoss = 1.08500,
        tp1 = 1.09250,
        tp2 = 1.09650,
        tp3 = 1.10200,
        tp1RR = "1:2.0R",
        tp1YieldPips = 50.0,
        slReason = "Discount Bullish Order Block invalidation at 1.08500",
        tpReason = "External range liquidity harvest at 1.09250",
        strategyVersion = "smc-confluence-1.0.0",
        dataSource = "Deriv WebSocket",
        mtfSummary = "FULL BULLISH ALIGNMENT",
        mtfStatus = "FULL_BULLISH",
        smcAnalysis = "Bullish structure confirmed. Clean BOS with unmitigated M15 Order Block and FVG CE at discount.",
        orderBlocks = listOf(
            OrderBlockUiModel(type = "BULLISH", zone = "1.08650 - 1.08720", strength = "STRONG", topPrice = 1.08720, bottomPrice = 1.08650),
            OrderBlockUiModel(type = "BEARISH", zone = "1.09450 - 1.09520", strength = "MODERATE", topPrice = 1.09520, bottomPrice = 1.09450)
        ),
        keyLevels = listOf("1.08500 (SL Support)", "1.08750 (Optimal Entry)", "1.09250 (TP1 Liquidity)", "1.09650 (TP2 Swing High)"),
        blockReasons = emptyList(),
        mtfPerTimeframe = mapOf(
            "M5" to "BULLISH",
            "M15" to "BULLISH",
            "M30" to "BULLISH",
            "H1" to "BULLISH",
            "H4" to "BULLISH",
            "D1" to "BULLISH",
            "W1" to "NEUTRAL"
        )
    )

    // Pre-populate broker spec for EUR/USD to enable position sizing
    SpecRegistry.put(
        InstrumentSpec(
            symbol = "EUR/USD",
            assetClass = AssetClass.FOREX,
            digits = 5,
            tickSize = 0.00001,
            tickValue = 1.0,
            contractSize = 100000.0,
            volumeMin = 0.01,
            volumeMax = 100.0,
            volumeStep = 0.01,
            stopsLevelPoints = 0,
            profitCurrency = "USD",
            baseCurrency = "EUR",
            source = "Broker Demo"
        )
    )

    return vm
}

@Composable
fun rememberPreviewViewModel(): DestinedFXViewModel {
    val context = LocalContext.current
    return remember {
        val app = (context.applicationContext as? Application) ?: (context as? Application) ?: Application()
        createPreviewViewModel(app)
    }
}

// -------------------------------------------------------------
// REAL COMPOSABLE PREVIEWS: One dedicated preview per tab
// -------------------------------------------------------------

@Preview(name = "1. Analyzer Tab", showBackground = true, widthDp = 380, heightDp = 800)
@Composable
fun PreviewAnalyzerTab() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            val vm = rememberPreviewViewModel()
            SignalsAndChartTab(viewModel = vm)
        }
    }
}

@Preview(name = "2. Strategies Tab", showBackground = true, widthDp = 380, heightDp = 800)
@Composable
fun PreviewStrategiesTab() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            val vm = rememberPreviewViewModel()
            StrategiesTab(viewModel = vm)
        }
    }
}

@Preview(name = "3. Calendar Tab", showBackground = true, widthDp = 380, heightDp = 800)
@Composable
fun PreviewCalendarTab() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            val vm = rememberPreviewViewModel()
            CalendarTab(viewModel = vm)
        }
    }
}

@Preview(name = "4. Bot Workstation Tab", showBackground = true, widthDp = 380, heightDp = 800)
@Composable
fun PreviewBotWorkstationTab() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            val vm = rememberPreviewViewModel()
            BotWorkstationTab(viewModel = vm)
        }
    }
}

@Preview(name = "5. Risk Management Tab", showBackground = true, widthDp = 380, heightDp = 800)
@Composable
fun PreviewRiskManagementTab() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            val vm = rememberPreviewViewModel()
            RiskManagementTab(viewModel = vm)
        }
    }
}

@Preview(name = "6. Settings Tab", showBackground = true, widthDp = 380, heightDp = 800)
@Composable
fun PreviewSettingsTab() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            val vm = rememberPreviewViewModel()
            SettingsTab(viewModel = vm)
        }
    }
}

@Preview(name = "7. Full DestinedFX App Terminal", showBackground = true, widthDp = 380, heightDp = 800)
@Composable
fun PreviewDestinedFXFullApp() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            val vm = rememberPreviewViewModel()
            DestinedFXApp(viewModel = vm)
        }
    }
}
