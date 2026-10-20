package com.example

import com.example.ui.DynamicAnalysisInfo
import com.example.ui.export.RealAnalysisExporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisExportPayloadTest {
    @Test
    fun unavailableAnalysisDoesNotInventTradePrices() {
        val report = RealAnalysisExporter.buildReportText(
            analysis = DynamicAnalysisInfo(
                symbol = "EUR/USD",
                direction = "WAIT",
                signalState = "DATA_UNAVAILABLE",
                dataSource = "UNKNOWN",
                blockReasons = listOf("WAITING FOR LIVE DATA")
            ),
            timeframe = "M15",
            strategy = "smc-confluence-1.0.0",
            generatedAtUtc = "2026-10-20T00:00:00Z"
        )
        assertTrue(report.contains("NO VALIDATED SETUP"))
        assertTrue(report.contains("Entry: Not published"))
        assertTrue(report.contains("Stop loss: Not published"))
        assertTrue(report.contains("WAITING FOR LIVE DATA"))
        assertFalse(report.contains("Entry: 0.00000"))
    }

    @Test
    fun validAnalysisExportsActualTradePlanAndMtfValues() {
        val report = RealAnalysisExporter.buildReportText(
            analysis = DynamicAnalysisInfo(
                symbol = "EUR/USD",
                direction = "BUY",
                signalState = "BUY",
                confidencePct = 74,
                entry = 1.08234,
                stopLoss = 1.08000,
                tp1 = 1.08702,
                tp1RR = "1:2.00",
                dataSource = "Deriv WebSocket",
                strategyVersion = "smc-confluence-1.0.0",
                mtfStatus = "FULL_BULLISH",
                mtfSummary = "3/3 aligned",
                mtfPerTimeframe = mapOf("M15" to "BULLISH")
            ),
            timeframe = "M15",
            strategy = "smc-confluence-1.0.0",
            generatedAtUtc = "2026-10-20T00:00:00Z"
        )
        assertTrue(report.contains("Entry: 1.08234"))
        assertTrue(report.contains("Stop loss: 1.08000"))
        assertTrue(report.contains("Take profit: 1.08702"))
        assertTrue(report.contains("M15: BULLISH"))
        assertTrue(report.contains("Deriv WebSocket"))
    }
}
