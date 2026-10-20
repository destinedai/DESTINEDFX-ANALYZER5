package com.example.ui.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import com.example.ui.DynamicAnalysisInfo
import com.example.ui.formatPrice
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Locale

/** Exports the current analysis model. No chart prices or trade levels are manufactured. */
object RealAnalysisExporter {
    internal fun buildReportText(
        analysis: DynamicAnalysisInfo,
        timeframe: String,
        strategy: String,
        generatedAtUtc: String = Instant.now().toString()
    ): String = buildString {
        appendLine("DESTINEDFX ANALYZER")
        appendLine("Deterministic market-analysis report")
        appendLine("Generated UTC: $generatedAtUtc")
        appendLine()
        appendLine("MARKET SNAPSHOT")
        appendLine("Instrument: ${analysis.symbol}")
        appendLine("Timeframe: ${timeframe.ifBlank { "Not selected" }}")
        appendLine("Strategy: ${strategy.ifBlank { analysis.strategyVersion.ifBlank { "Not reported" } }}")
        appendLine("Direction: ${analysis.direction.ifBlank { "WAIT" }}")
        appendLine("Signal state: ${analysis.signalState.ifBlank { "UNKNOWN" }}")
        appendLine("Confidence: ${analysis.confidencePct.coerceIn(0, 100)}%")
        appendLine("Data source: ${analysis.dataSource.takeIf { it.isNotBlank() && it != "UNKNOWN" } ?: "Unavailable"}")
        appendLine("Strategy version: ${analysis.strategyVersion.ifBlank { "Not reported" }}")
        appendLine()
        appendLine("TRADE PLAN")
        val recognizedDirection = analysis.signalState.uppercase(Locale.US) in setOf(
            "STRONG_BUY", "BUY", "WEAK_BUY", "STRONG_SELL", "SELL", "WEAK_SELL"
        )
        val planIsValid = recognizedDirection &&
            analysis.entry.isFinite() && analysis.entry > 0.0 &&
            analysis.stopLoss.isFinite() && analysis.stopLoss > 0.0 &&
            analysis.tp1.isFinite() && analysis.tp1 > 0.0
        if (planIsValid) {
            appendLine("Entry: ${formatPrice(analysis.entry, analysis.symbol)}")
            appendLine("Stop loss: ${formatPrice(analysis.stopLoss, analysis.symbol)}")
            appendLine("Take profit: ${formatPrice(analysis.tp1, analysis.symbol)}")
            appendLine("Reward/risk: ${analysis.tp1RR.ifBlank { "Not reported" }}")
            appendLine("Entry rationale: ${analysis.entryReason.ifBlank { "Not reported" }}")
            appendLine("Stop rationale: ${analysis.slReason.ifBlank { "Not reported" }}")
            appendLine("Target rationale: ${analysis.tpReason.ifBlank { "Not reported" }}")
        } else {
            appendLine("Plan status: NO VALIDATED SETUP")
            appendLine("Entry: Not published")
            appendLine("Stop loss: Not published")
            appendLine("Take profit: Not published")
        }
        appendLine("Risk note: ${analysis.riskNote.ifBlank { "Not reported" }}")
        appendLine()
        appendLine("STRUCTURE & RATIONALE")
        appendLine(analysis.smcAnalysis.ifBlank { analysis.marketStructureNote.ifBlank { "No structural rationale was returned." } })
        appendLine("Order blocks:")
        if (analysis.orderBlocks.isEmpty()) appendLine("- None reported by the analysis engine")
        else analysis.orderBlocks.forEach { appendLine("- ${it.type} ${it.zone} • ${it.strength}") }
        appendLine("Key levels:")
        if (analysis.keyLevels.isEmpty()) appendLine("- None reported by the analysis engine")
        else analysis.keyLevels.forEach { appendLine("- $it") }
        appendLine()
        appendLine("MULTI-TIMEFRAME")
        appendLine("Status: ${analysis.mtfStatus.ifBlank { "UNKNOWN" }}")
        appendLine("Summary: ${analysis.mtfSummary.ifBlank { "No multi-timeframe verdict was returned." }}")
        listOf("M1", "M5", "M15", "M30", "H1", "H4", "D1", "W1").forEach { tf ->
            appendLine("$tf: ${analysis.getTfBias(tf).ifBlank { "N/A" }}")
        }
        appendLine()
        appendLine("STRATEGY EVIDENCE")
        if (analysis.strategyScores.isEmpty()) appendLine("No strategy scores were returned.")
        else analysis.strategyScores.toSortedMap().forEach { (name, score) ->
            appendLine("- $name: ${score.coerceIn(0, 100)}")
        }
        appendLine()
        appendLine("BLOCKERS / DATA LIMITATIONS")
        if (analysis.blockReasons.isNotEmpty()) analysis.blockReasons.forEach { appendLine("- $it") }
        else if (!planIsValid) appendLine("The engine has not published a validated trade plan.")
        else appendLine("No blockers were reported by the analysis engine.")
        appendLine()
        appendLine("Research support only. This report does not guarantee performance or authorize broker execution.")
    }

    fun exportPdf(context: Context, analysis: DynamicAnalysisInfo, timeframe: String, strategy: String) {
        val document = PdfDocument()
        try {
            val lines = buildReportText(analysis, timeframe, strategy).lineSequence().toList()
            var index = 0
            var pageNumber = 1
            do {
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                val next = drawPdfPage(page.canvas, lines, index, pageNumber)
                document.finishPage(page)
                check(next > index || index >= lines.size) { "PDF pagination could not advance." }
                index = next
                pageNumber++
            } while (index < lines.size)
            val name = "DestinedFX_Analysis_${safeName(analysis.symbol)}_${System.currentTimeMillis()}.pdf"
            saveReport(context, name, "application/pdf", false) { document.writeTo(it) }
            showMessage(context, "Analysis PDF saved to Downloads/DestinedFX.")
        } catch (e: Exception) {
            android.util.Log.e("RealAnalysisExporter", "PDF export failed", e)
            showMessage(context, "PDF export failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            document.close()
        }
    }

    fun exportPng(context: Context, analysis: DynamicAnalysisInfo, timeframe: String, strategy: String) {
        var bitmap: Bitmap? = null
        try {
            val image = createReportBitmap(buildReportText(analysis, timeframe, strategy))
            bitmap = image
            val name = "DestinedFX_Analysis_${safeName(analysis.symbol)}_${System.currentTimeMillis()}.png"
            saveReport(context, name, "image/png", true) {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed." }
            }
            showMessage(context, "Analysis image saved to Pictures/DestinedFX.")
        } catch (e: Exception) {
            android.util.Log.e("RealAnalysisExporter", "PNG export failed", e)
            showMessage(context, "Image export failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            bitmap?.recycle()
        }
    }

    private fun drawPdfPage(canvas: Canvas, lines: List<String>, start: Int, pageNumber: Int): Int {
        canvas.drawColor(Color.rgb(9, 15, 27))
        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 229, 255); textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawRect(36f, 34f, 559f, 38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255) })
        canvas.drawText("DESTINEDFX ANALYZER", 36f, 68f, header)
        var y = 104f
        var index = start
        while (index < lines.size) {
            val line = lines[index]
            val isSection = line.isNotBlank() && line == line.uppercase(Locale.US) && line.length < 46
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (index == 0 || isSection) Color.rgb(0, 229, 255) else Color.rgb(229, 235, 244)
                textSize = if (index == 0) 15f else if (isSection) 11f else 9f
                typeface = if (index == 0 || isSection) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            }
            val wrapped = wrapText(line, paint, 520f)
            val lineHeight = if (index == 0 || isSection) 18f else 13f
            val required = wrapped.size * lineHeight + if (line.isBlank()) 5f else 0f
            if (y + required > 790f && index > start) break
            if (line.isBlank()) y += 6f
            else wrapped.forEach { canvas.drawText(it, 38f, y, paint); y += lineHeight }
            index++
            if (y > 790f) break
        }
        canvas.drawText(
            "Deterministic analysis • page $pageNumber • DestinedFX Analyzer", 36f, 820f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(132, 147, 169); textSize = 8f }
        )
        return index
    }

    private fun createReportBitmap(report: String): Bitmap {
        val width = 1240
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 235, 244); textSize = 23f }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 229, 255); textSize = 36f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 229, 255); textSize = 27f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val rows = report.lineSequence().flatMap { raw ->
            val paint = if (raw == "DESTINEDFX ANALYZER") title
                else if (raw.isNotBlank() && raw == raw.uppercase(Locale.US) && raw.length < 46) heading else body
            if (raw.isBlank()) sequenceOf(Triple("", body, 20f))
            else wrapText(raw, paint, (width - 96).toFloat()).asSequence()
                .map { Triple(it, paint, if (paint === body) 36f else 44f) }
        }.toList()
        val height = (150 + rows.sumOf { it.third.toDouble() }.toInt() + 65).coerceIn(1500, 7000)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(9, 15, 27))
        canvas.drawRect(0f, 0f, width.toFloat(), 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255) })
        var y = 76f
        rows.forEach { (line, paint, advance) ->
            if (y + advance < height - 50) { canvas.drawText(line, 48f, y, paint); y += advance }
        }
        canvas.drawText(
            "Deterministic market analysis • DestinedFX Analyzer", 48f, (height - 24).toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(132, 147, 169); textSize = 18f }
        )
        return bitmap
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val result = mutableListOf<String>()
        var current = ""
        text.split(Regex("\\s+")).forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) { result.add(current); current = word }
            else current = candidate
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "Market" }

    private fun saveReport(context: Context, name: String, mimeType: String, isImage: Boolean, writer: (OutputStream) -> Unit) {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/DestinedFX")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: error("Android could not create the export file.")
            try {
                val output = resolver.openOutputStream(uri) ?: error("Android could not open the export file.")
                output.use(writer)
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val folder = File(context.getExternalFilesDir(if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "DestinedFX")
            if (!folder.exists() && !folder.mkdirs()) error("Could not create the app export folder.")
            FileOutputStream(File(folder, name)).use(writer)
        }
    }

    private fun showMessage(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
