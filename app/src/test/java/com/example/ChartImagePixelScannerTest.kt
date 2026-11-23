package com.example

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.example.ui.scanChartBitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartImagePixelScannerTest {
    @Test(timeout = 30_000)
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

    @Test(timeout = 30_000)
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
