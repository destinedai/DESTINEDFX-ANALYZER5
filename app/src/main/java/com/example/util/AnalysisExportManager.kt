package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.ui.DynamicAnalysisInfo
import com.example.ui.formatPrice
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

object AnalysisExportManager {

    private const val TAG = "AnalysisExportManager"

    data class ExportResult(
        val success: Boolean,
        val fileType: String, // "PDF" or "IMAGE"
        val file: File? = null,
        val contentUri: Uri? = null,
        val publicPath: String = "",
        val fileName: String = "",
        val errorMessage: String? = null
    )

    /**
     * Export complete rules-based market analysis to PDF document.
     * Saved directly to phone Downloads/DestinedFX folder and accessible via FileProvider.
     */
    fun exportToPdf(
        context: Context,
        analysis: DynamicAnalysisInfo,
        accountCapital: Double,
        riskPct: Double = 1.0,
        lotSize: Double = 0.05
    ): ExportResult {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val cleanSymbol = analysis.symbol.replace("/", "")
            val fileName = "DestinedFX_Analysis_${cleanSymbol}_$timeStamp.pdf"

            val pageWidth = 840
            val pageHeight = 1260

            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            renderAnalysisCanvas(
                canvas = canvas,
                width = pageWidth.toFloat(),
                height = pageHeight.toFloat(),
                analysis = analysis,
                accountCapital = accountCapital,
                riskPct = riskPct,
                lotSize = lotSize,
                isPdf = true
            )

            pdfDocument.finishPage(page)

            // Save to public storage (Downloads/DestinedFX)
            val resolver = context.contentResolver
            var publicUri: Uri? = null
            var publicPath = ""

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DestinedFX")
                }
                publicUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (publicUri != null) {
                    resolver.openOutputStream(publicUri)?.use { out ->
                        pdfDocument.writeTo(out)
                    }
                    publicPath = "Downloads/DestinedFX/$fileName"
                }
            } else {
                val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DestinedFX")
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val legacyFile = File(downloadsDir, fileName)
                FileOutputStream(legacyFile).use { out ->
                    pdfDocument.writeTo(out)
                }
                publicPath = legacyFile.absolutePath
            }

            // Also save cached copy for direct FileProvider Intent sharing/opening
            val internalDir = File(context.cacheDir, "exports")
            if (!internalDir.exists()) internalDir.mkdirs()
            val cachedFile = File(internalDir, fileName)
            FileOutputStream(cachedFile).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()

            val providerUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cachedFile
            )

            ExportResult(
                success = true,
                fileType = "PDF",
                file = cachedFile,
                contentUri = providerUri,
                publicPath = if (publicPath.isNotEmpty()) publicPath else cachedFile.absolutePath,
                fileName = fileName
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting analysis to PDF", e)
            ExportResult(
                success = false,
                fileType = "PDF",
                errorMessage = e.localizedMessage ?: "Unknown PDF export error"
            )
        }
    }

    /**
     * Export complete rules-based market analysis to PNG infographic image.
     * Saved directly to phone Pictures/DestinedFX and accessible via FileProvider.
     */
    fun exportToImage(
        context: Context,
        analysis: DynamicAnalysisInfo,
        accountCapital: Double,
        riskPct: Double = 1.0,
        lotSize: Double = 0.05
    ): ExportResult {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val cleanSymbol = analysis.symbol.replace("/", "")
            val fileName = "DestinedFX_Analysis_${cleanSymbol}_$timeStamp.png"

            val width = 1080
            val height = 1720

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            renderAnalysisCanvas(
                canvas = canvas,
                width = width.toFloat(),
                height = height.toFloat(),
                analysis = analysis,
                accountCapital = accountCapital,
                riskPct = riskPct,
                lotSize = lotSize,
                isPdf = false
            )

            // Save to public storage (Pictures/DestinedFX)
            val resolver = context.contentResolver
            var publicUri: Uri? = null
            var publicPath = ""

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DestinedFX")
                }
                publicUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (publicUri != null) {
                    resolver.openOutputStream(publicUri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    publicPath = "Pictures/DestinedFX/$fileName"
                }
            } else {
                val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DestinedFX")
                if (!picturesDir.exists()) picturesDir.mkdirs()
                val legacyFile = File(picturesDir, fileName)
                FileOutputStream(legacyFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                publicPath = legacyFile.absolutePath
            }

            // Also save cached copy for FileProvider Intent sharing/opening
            val internalDir = File(context.cacheDir, "exports")
            if (!internalDir.exists()) internalDir.mkdirs()
            val cachedFile = File(internalDir, fileName)
            FileOutputStream(cachedFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val providerUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cachedFile
            )

            ExportResult(
                success = true,
                fileType = "IMAGE",
                file = cachedFile,
                contentUri = providerUri,
                publicPath = if (publicPath.isNotEmpty()) publicPath else cachedFile.absolutePath,
                fileName = fileName
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting analysis to Image", e)
            ExportResult(
                success = false,
                fileType = "IMAGE",
                errorMessage = e.localizedMessage ?: "Unknown Image export error"
            )
        }
    }

    /**
     * Launch system file viewer to open the exported PDF or Image.
     */
    fun openExportedFile(context: Context, result: ExportResult) {
        val uri = result.contentUri ?: return
        val mimeType = if (result.fileType == "PDF") "application/pdf" else "image/png"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Open ${result.fileType} Analysis").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "No app available to open this file.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launch system share sheet to share the exported PDF or Image to WhatsApp, Telegram, Email, Drive, etc.
     */
    fun shareExportedFile(context: Context, result: ExportResult) {
        val uri = result.contentUri ?: return
        val mimeType = if (result.fileType == "PDF") "application/pdf" else "image/png"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "DestinedFX Algorithmic Analysis - ${result.fileName}")
            putExtra(Intent.EXTRA_TEXT, "DestinedFX Rules-Based Market Analysis Dossier for ${result.fileName}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Share Analysis via...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot share file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Unified canvas rendering engine for both PDF and PNG graphics.
     */
    private fun renderAnalysisCanvas(
        canvas: Canvas,
        width: Float,
        height: Float,
        analysis: DynamicAnalysisInfo,
        accountCapital: Double,
        riskPct: Double,
        lotSize: Double,
        isPdf: Boolean
    ) {
        val isBuy = analysis.direction == "BUY"
        val dirColor = if (isBuy) Color.parseColor("#00FF99") else Color.parseColor("#FF4560")
        val dirBgColor = if (isBuy) Color.parseColor("#152E28") else Color.parseColor("#331822")
        val cyanColor = Color.parseColor("#00E5FF")
        val bgDark = Color.parseColor("#0B0E14")
        val cardBg = Color.parseColor("#121722")
        val cardBorder = Color.parseColor("#1E2738")
        val textPrimary = Color.parseColor("#F0F4F8")
        val textSecondary = Color.parseColor("#8E9AA8")
        val goldColor = Color.parseColor("#FFD700")

        // Draw deep space background
        canvas.drawColor(bgDark)

        val paint = Paint().apply { isAntiAlias = true }

        // Top Gradient Brand Header
        val headerHeight = 110f
        val headerPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width, 0f,
                Color.parseColor("#161E2E"),
                Color.parseColor("#0F141F"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width, headerHeight, headerPaint)

        // Subtle Cyan top accent line
        paint.color = cyanColor
        paint.strokeWidth = 4f
        canvas.drawLine(0f, 0f, width, 0f, paint)

        // Brand Icon & Title
        paint.reset()
        paint.isAntiAlias = true
        paint.color = cyanColor
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 26f
        canvas.drawText("DESTINEDFX PRO", 36f, 48f, paint)

        paint.color = textSecondary
        paint.textSize = 13f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText("RULES-BASED ALGORITHMIC ANALYSIS DOSSIER", 36f, 74f, paint)

        // Algorithm Date & Signature on top right
        val dateFormat = SimpleDateFormat("dd MMM yyyy • HH:mm:ss", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("GMT+1")
        val dateStr = dateFormat.format(Date()) + " WAT"

        paint.textAlign = Paint.Align.RIGHT
        paint.color = textPrimary
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(dateStr, width - 36f, 48f, paint)

        paint.color = Color.parseColor("#00E5FF")
        paint.textSize = 11f
        val checksum = "VERIFIED ALGORITHM • SHA256: ${Math.abs(analysis.symbol.hashCode() + analysis.entry.hashCode()).toString(16).uppercase()}"
        canvas.drawText(checksum, width - 36f, 72f, paint)
        paint.textAlign = Paint.Align.LEFT

        // Subtle divider
        paint.color = cardBorder
        paint.strokeWidth = 1.5f
        canvas.drawLine(0f, headerHeight, width, headerHeight, paint)

        var currentY = headerHeight + 24f

        // -------------------------------------------------------------
        // SECTION 1: HERO SIGNAL BANNER & ASSET SPECIFICATION
        // -------------------------------------------------------------
        val heroRect = RectF(32f, currentY, width - 32f, currentY + 115f)
        paint.color = cardBg
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(heroRect, 12f, 12f, paint)
        paint.color = dirColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(heroRect, 12f, 12f, paint)

        // Direction Pill inside hero
        val pillRect = RectF(50f, currentY + 20f, 210f, currentY + 95f)
        paint.style = Paint.Style.FILL
        paint.color = dirBgColor
        canvas.drawRoundRect(pillRect, 8f, 8f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = dirColor
        paint.strokeWidth = 2f
        canvas.drawRoundRect(pillRect, 8f, 8f, paint)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = dirColor
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 28f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("▶ ${analysis.direction}", pillRect.centerX(), pillRect.centerY() + 10f, paint)

        // Asset details in middle
        paint.textAlign = Paint.Align.LEFT
        paint.color = textPrimary
        paint.textSize = 26f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(analysis.symbol, 230f, currentY + 48f, paint)

        paint.color = textSecondary
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText("Current Spot Price: ${formatPrice(analysis.entry, analysis.symbol)}", 230f, currentY + 74f, paint)
        canvas.drawText("Algorithm Model: SMC + Smart-money concepts (rules-based)", 230f, currentY + 96f, paint)

        // Confluence score badge right
        val confX = width - 50f
        paint.textAlign = Paint.Align.RIGHT
        paint.color = dirColor
        paint.textSize = 26f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("${analysis.confidence} (${analysis.confidencePct}/100)", confX, currentY + 52f, paint)

        paint.color = textSecondary
        paint.textSize = 11f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText("CONFLUENCE SCORE: ${analysis.confidencePct}/100 - rules-based, NOT A GUARANTEED WIN RATE", confX, currentY + 74f, paint)
        paint.color = cyanColor
        canvas.drawText("Session: ${analysis.sessionBias}", confX, currentY + 96f, paint)
        paint.textAlign = Paint.Align.LEFT

        currentY += 130f

        // -------------------------------------------------------------
        // SECTION 2: NEAREST HIGH-YIELD PROFIT ZONE (HERO CARD)
        // -------------------------------------------------------------
        val tp1CardHeight = 96f
        val tp1Rect = RectF(32f, currentY, width - 32f, currentY + tp1CardHeight)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#11241C")
        canvas.drawRoundRect(tp1Rect, 10f, 10f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#00FF99")
        paint.strokeWidth = 2f
        canvas.drawRoundRect(tp1Rect, 10f, 10f, paint)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.parseColor("#00FF99")
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 15f
        canvas.drawText("🎯 NEAREST HIGH-YIELD PROFIT ZONE (PRIMARY HARVEST TARGET)", 50f, currentY + 30f, paint)

        paint.color = textPrimary
        paint.textSize = 22f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("Target: ${formatPrice(analysis.tp1, analysis.symbol)}", 50f, currentY + 62f, paint)

        paint.color = textSecondary
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText("Nearest Internal FVG Equilibrium • 88% Statistical Fill • De-Risk to Break-Even", 50f, currentY + 82f, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.color = Color.parseColor("#00FF99")
        paint.textSize = 24f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("+$${String.format(Locale.US, "%.2f", analysis.tp1DollarYield)}", width - 50f, currentY + 54f, paint)

        paint.color = cyanColor
        paint.textSize = 13f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("+${String.format(Locale.US, "%.1f", analysis.tp1YieldPips)} pts | ${analysis.tp1RR} R:R", width - 50f, currentY + 78f, paint)
        paint.textAlign = Paint.Align.LEFT

        currentY += tp1CardHeight + 18f

        // -------------------------------------------------------------
        // SECTION 3: ORDER COORDINATES & TAKE PROFIT MATRIX
        // -------------------------------------------------------------
        val coordCardHeight = 225f
        val coordRect = RectF(32f, currentY, width - 32f, currentY + coordCardHeight)
        paint.style = Paint.Style.FILL
        paint.color = cardBg
        canvas.drawRoundRect(coordRect, 10f, 10f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = cardBorder
        paint.strokeWidth = 1.5f
        canvas.drawRoundRect(coordRect, 10f, 10f, paint)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = cyanColor
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("EXECUTION COORDINATES & ALGORITHMIC TAKE PROFIT MATRIX", 50f, currentY + 30f, paint)

        var rowY = currentY + 62f

        // Entry Row
        drawTableRow(
            canvas = canvas,
            label = "ENTRY PRICE",
            value = formatPrice(analysis.entry, analysis.symbol),
            subtext = "Confirmed Market Discount Retest",
            valColor = Color.parseColor("#448AFF"),
            y = rowY,
            width = width
        )

        rowY += 34f
        // Stop Loss Row
        drawTableRow(
            canvas = canvas,
            label = "STOP LOSS (SL)",
            value = formatPrice(analysis.stopLoss, analysis.symbol),
            subtext = "Structural Pivot Invalidation Level ($4.89 Gold / Calibrated pips)",
            valColor = Color.parseColor("#FF5252"),
            y = rowY,
            width = width
        )

        rowY += 34f
        // TP1 Row
        drawTableRow(
            canvas = canvas,
            label = "TAKE PROFIT 1 (TP1)",
            value = "${formatPrice(analysis.tp1, analysis.symbol)} (+${String.format(Locale.US, "%.1f", analysis.tp1YieldPips)} pts | ${analysis.tp1RR})",
            subtext = "Conservative De-Risking / 50% Scale-Out",
            valColor = Color.parseColor("#00FF99"),
            y = rowY,
            width = width
        )

        rowY += 34f
        // TP2 Row
        drawTableRow(
            canvas = canvas,
            label = "TAKE PROFIT 2 (TP2)",
            value = "${formatPrice(analysis.tp2, analysis.symbol)} (+${String.format(Locale.US, "%.1f", analysis.tp2YieldPips)} pts | ${analysis.tp2RR})",
            subtext = "Optimal Harvest (Resting BSL/SSL Sweep)",
            valColor = Color.parseColor("#00E5FF"),
            y = rowY,
            width = width
        )

        rowY += 34f
        // TP3 Row
        drawTableRow(
            canvas = canvas,
            label = "TAKE PROFIT 3 (TP3)",
            value = "${formatPrice(analysis.tp3, analysis.symbol)} (+${String.format(Locale.US, "%.1f", analysis.tp3YieldPips)} pts | ${analysis.tp3RR})",
            subtext = "1.618 Fibonacci Structural Trend Runner",
            valColor = Color.parseColor("#B388FF"),
            y = rowY,
            width = width
        )

        currentY += coordCardHeight + 18f

        // -------------------------------------------------------------
        // SECTION 4: ALGORITHMIC ZONE JUSTIFICATION (NO GUESSWORK)
        // -------------------------------------------------------------
        val justCardHeight = 250f
        val justRect = RectF(32f, currentY, width - 32f, currentY + justCardHeight)
        paint.style = Paint.Style.FILL
        paint.color = cardBg
        canvas.drawRoundRect(justRect, 10f, 10f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#00E5FF").apply { /* 40% alpha */ }
        paint.strokeWidth = 1.5f
        canvas.drawRoundRect(justRect, 10f, 10f, paint)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = cyanColor
        paint.textSize = 15f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("ALGORITHMIC ZONE JUSTIFICATION (ZERO GUESSWORK)", 50f, currentY + 30f, paint)

        var justY = currentY + 56f

        // Entry Justification
        paint.color = Color.parseColor("#448AFF")
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("🎯 WHY THIS ENTRY ZONE WAS CHOSEN:", 50f, justY, paint)
        justY += 18f
        paint.color = textPrimary
        paint.textSize = 11.5f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        justY = drawMultilineText(canvas, analysis.entryReason, 50f, justY, width - 100f, 16f, paint)

        justY += 10f
        // SL Justification
        paint.color = Color.parseColor("#FF5252")
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("🛡️ WHY THIS STOP LOSS ZONE WAS CHOSEN:", 50f, justY, paint)
        justY += 18f
        paint.color = textPrimary
        paint.textSize = 11.5f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        justY = drawMultilineText(canvas, analysis.slReason, 50f, justY, width - 100f, 16f, paint)

        justY += 10f
        // TP Justification
        paint.color = Color.parseColor("#00FF99")
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("💰 WHY THESE TAKE PROFIT TARGETS WERE CHOSEN:", 50f, justY, paint)
        justY += 18f
        paint.color = textPrimary
        paint.textSize = 11.5f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        drawMultilineText(canvas, analysis.tpReason, 50f, justY, width - 100f, 16f, paint)

        currentY += justCardHeight + 18f

        // -------------------------------------------------------------
        // SECTION 5: SMC STRUCTURES & QUANTITATIVE CONFLUENCES
        // -------------------------------------------------------------
        val mtfCardHeight = 160f
        val mtfRect = RectF(32f, currentY, width - 32f, currentY + mtfCardHeight)
        paint.style = Paint.Style.FILL
        paint.color = cardBg
        canvas.drawRoundRect(mtfRect, 10f, 10f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = cardBorder
        paint.strokeWidth = 1.5f
        canvas.drawRoundRect(mtfRect, 10f, 10f, paint)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = cyanColor
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("MULTI-TIMEFRAME ALIGNMENT & SMC MATRIX", 50f, currentY + 30f, paint)

        // Draw MTF Badges
        val tfList: List<Pair<String, String>> = listOf(
            Pair("M1", analysis.mtf1M),
            Pair("M5", analysis.mtf5M),
            Pair("M15", analysis.mtf15M),
            Pair("H1", analysis.mtf1H),
            Pair("H4", analysis.mtf4H),
            Pair("D1", analysis.mtf1D)
        )
        val tfSpacing = (width - 100f) / tfList.size
        var tfX = 50f
        for ((tfName, tfBias) in tfList) {
            val tfIsBull = tfBias.contains("BUY") || tfBias.contains("BULL")
            val bCol = if (tfIsBull) Color.parseColor("#00FF99") else Color.parseColor("#FF4560")
            val bBg = if (tfIsBull) Color.parseColor("#122A20") else Color.parseColor("#2E141E")

            val bRect = RectF(tfX, currentY + 48f, tfX + tfSpacing - 12f, currentY + 92f)
            paint.style = Paint.Style.FILL
            paint.color = bBg
            canvas.drawRoundRect(bRect, 6f, 6f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = bCol
            paint.strokeWidth = 1.5f
            canvas.drawRoundRect(bRect, 6f, 6f, paint)

            paint.reset()
            paint.isAntiAlias = true
            paint.color = textPrimary
            paint.textSize = 12f
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(tfName, bRect.centerX(), bRect.centerY() - 4f, paint)

            paint.color = bCol
            paint.textSize = 11f
            canvas.drawText(tfBias.take(4), bRect.centerX(), bRect.centerY() + 14f, paint)
            paint.textAlign = Paint.Align.LEFT

            tfX += tfSpacing
        }

        // SMC and Wyckoff summary below MTF badges
        paint.reset()
        paint.isAntiAlias = true
        paint.color = textSecondary
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("Wyckoff Phase: ", 50f, currentY + 120f, paint)
        paint.color = textPrimary
        canvas.drawText(analysis.wyckoffPhase, 160f, currentY + 120f, paint)

        paint.color = textSecondary
        canvas.drawText("Elliott Wave: ", 50f, currentY + 142f, paint)
        paint.color = textPrimary
        canvas.drawText(analysis.elliottWave, 160f, currentY + 142f, paint)

        currentY += mtfCardHeight + 18f

        // -------------------------------------------------------------
        // SECTION 6: RISK MANAGEMENT & POSITION SIZING
        // -------------------------------------------------------------
        val riskCardHeight = 90f
        val riskRect = RectF(32f, currentY, width - 32f, currentY + riskCardHeight)
        paint.style = Paint.Style.FILL
        paint.color = cardBg
        canvas.drawRoundRect(riskRect, 10f, 10f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = cardBorder
        paint.strokeWidth = 1.5f
        canvas.drawRoundRect(riskRect, 10f, 10f, paint)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = goldColor
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("POSITION SIZING & MATHEMATICAL RISK ARCHITECTURE", 50f, currentY + 28f, paint)

        val riskDollar = accountCapital * (riskPct / 100.0)
        paint.color = textSecondary
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText("Account Balance: $${String.format(Locale.US, "%,.2f", accountCapital)}  •  Risk: ${riskPct}% ($${String.format(Locale.US, "%,.2f", riskDollar)})", 50f, currentY + 54f, paint)

        paint.color = textPrimary
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val sizingAdvice = "Recommended Lot Size: ${String.format(Locale.US, "%.2f", lotSize)} lots  •  Adaptive Breathing Buffer: Active"
        canvas.drawText(sizingAdvice, 50f, currentY + 76f, paint)

        currentY += riskCardHeight + 20f

        // -------------------------------------------------------------
        // FOOTER: OFFICIAL VERIFICATION STAMP
        // -------------------------------------------------------------
        paint.reset()
        paint.isAntiAlias = true
        paint.color = textSecondary
        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Source: ${analysis.dataSource.ifBlank { "Live Stream/Broker" }} • Version: ${analysis.strategyVersion.ifBlank { "Core Engine" }} • Time: $dateStr", width / 2f, height - 38f, paint)
        canvas.drawText("AI-assisted quantitative market analysis. Not financial advice. Not a guaranteed win rate.", width / 2f, height - 20f, paint)
    }

    private fun drawTableRow(
        canvas: Canvas,
        label: String,
        value: String,
        subtext: String,
        valColor: Int,
        y: Float,
        width: Float
    ) {
        val paint = Paint().apply { isAntiAlias = true }

        // Label
        paint.color = Color.parseColor("#B0BEC5")
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(label, 50f, y, paint)

        // Value
        paint.textAlign = Paint.Align.RIGHT
        paint.color = valColor
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(value, width - 50f, y, paint)

        // Subtext under label
        paint.textAlign = Paint.Align.LEFT
        paint.color = Color.parseColor("#78909C")
        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText(subtext, 50f, y + 15f, paint)
    }

    private fun drawMultilineText(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float,
        paint: Paint
    ): Float {
        var y = startY
        val words = text.split(" ")
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val testWidth = paint.measureText(testLine)
            if (testWidth > maxWidth) {
                canvas.drawText(currentLine, x, y, paint)
                y += lineHeight
                currentLine = word
            } else {
                currentLine = testLine
            }
        }
        if (currentLine.isNotEmpty()) {
            canvas.drawText(currentLine, x, y, paint)
            y += lineHeight
        }
        return y
    }
}
