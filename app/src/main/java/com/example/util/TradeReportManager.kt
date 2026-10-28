package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Generates only from authenticated broker ledger records; missing values remain UNAVAILABLE. */
object TradeReportManager {
    data class SyncResult(val generated: Int, val failed: Int, val paths: List<String>)

    fun sync(context: Context, records: List<JSONObject>): SyncResult {
        var generated = 0; var failed = 0; val paths = mutableListOf<String>()
        records.forEach { record ->
            val status = record.optString("status", "UNKNOWN").uppercase()
            if (status !in setOf("OPEN", "CLOSED")) return@forEach
            val identity = safe(record.optString("identity", "unknown"))
            val mode = record.optString("account_mode", "UNVERIFIED").uppercase()
            val kind = if (status == "CLOSED") "FINAL_TRADE_REPORT" else "OPEN_TRADE"
            val fileName = "${kind}_${safe(mode)}_${identity}_${dateKey()}.pdf"
            try {
                val saved = writePdf(context, record, fileName, mode, status)
                generated++; paths += saved
            } catch (_: Exception) { failed++ }
        }
        return SyncResult(generated, failed, paths)
    }

    private fun writePdf(context: Context, record: JSONObject, fileName: String, mode: String, status: String): String {
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/DestinedFX/TradeReports/$mode"
        val registry = context.getSharedPreferences("destinedfx_trade_reports", Context.MODE_PRIVATE)
        if (registry.getBoolean(fileName, false)) return "$relative/$fileName"
        val document = PdfDocument(); val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        val canvas = page.canvas; canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 12f }
        var y = 36f
        fun line(label: String, value: String) { canvas.drawText("$label: $value", 32f, y, paint); y += 18f }
        val payload = record.optJSONObject("payload") ?: JSONObject()
        val account = payload.optJSONObject("account") ?: JSONObject()
        val signal = payload.optJSONObject("signal") ?: JSONObject()
        val deal = payload.optJSONObject("deal") ?: JSONObject()
        val deals = payload.optJSONArray("deals")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } } ?: listOf(deal)
        canvas.drawText("DESTINEDFX ANALYZER", 32f, y, paint); y += 22f
        paint.textSize = 16f; canvas.drawText(if (status == "CLOSED") "FINAL TRADE REPORT" else "OPEN TRADE", 32f, y, paint); paint.textSize = 12f; y += 24f
        line("Trade/report identity", record.optString("identity", "UNAVAILABLE"))
        line("Account mode", mode.ifBlank { "UNAVAILABLE" })
        line("Broker/company", "${account.optString("company", "UNAVAILABLE")} / ${account.optString("server", "UNAVAILABLE")}")
        line("Currency", account.optString("currency", "UNAVAILABLE"))
        line("Account login", maskLogin(account.optString("login", "UNAVAILABLE")))
        line("Symbol / direction", "${record.optString("symbol", signal.optString("symbol", "UNAVAILABLE"))} / ${record.optString("direction", signal.optString("direction", "UNAVAILABLE"))}")
        line("Strategy / timeframe", "${signal.optString("strategy", "ict-silver-bullet")} / ${signal.optString("timeframe", "UNAVAILABLE")}")
        line("Order ticket", record.optString("order_ticket", "UNAVAILABLE")); line("Deal ticket(s)", deals.map { it.optString("ticket", "UNAVAILABLE") }.joinToString(", ")); line("Position ID", record.optString("position_id", "UNAVAILABLE"))
        line("Requested volume", signal.optString("requested_volume", "UNAVAILABLE")); line("Executed volume", signal.optString("executed_volume", "UNAVAILABLE"))
        line("Entry price", signal.optString("entry", deal.optString("price", "UNAVAILABLE"))); line("Stop loss / take profit", "${signal.optString("stop_loss", "UNAVAILABLE")} / ${signal.optString("take_profit", "UNAVAILABLE")}")
        line("Execution status", signal.optString("execution_state", status)); line("News verdict", signal.optString("news_detail", "UNAVAILABLE")); line("News source / retrieval", "${signal.optString("news_source", "UNAVAILABLE")} / ${signal.optString("news_retrieved_at_ms", "UNAVAILABLE")}")
        if (status == "OPEN") line("Floating/unrealized P/L", payload.optJSONArray("positions")?.toString() ?: "UNAVAILABLE")
        else { line("Gross realized P/L", sumField(deals, "profit")); line("Commission", sumField(deals, "commission")); line("Swap", sumField(deals, "swap")); line("Net realized P/L", net(deals)) }
        line("Generated UTC", utcNow())
        line("Unavailable values", "UNAVAILABLE means the broker did not supply the field in the reconciled record.")
        document.finishPage(page)
        val result = save(context, document, fileName, mode, relative)
        document.close(); registry.edit().putBoolean(fileName, true).apply(); return result
    }

    private fun save(context: Context, document: PdfDocument, fileName: String, mode: String, relative: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileName); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf"); put(MediaStore.MediaColumns.RELATIVE_PATH, relative) }
            val uri: Uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("PDF storage unavailable")
            context.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) } ?: error("PDF output unavailable")
            return "$relative/$fileName"
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DestinedFX/TradeReports/$mode"); if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName); FileOutputStream(file).use { document.writeTo(it) }; return file.absolutePath
    }
    private fun sumField(deals: List<JSONObject>, key: String): String { val values = deals.map { it.optDouble(key, Double.NaN) }; return if (values.isNotEmpty() && values.all { it.isFinite() }) values.sum().toString() else "UNAVAILABLE" }
    private fun net(deals: List<JSONObject>): String { val p=sumField(deals,"profit"); val c=sumField(deals,"commission"); val s=sumField(deals,"swap"); return if (p!="UNAVAILABLE"&&c!="UNAVAILABLE"&&s!="UNAVAILABLE") (p.toDouble()+c.toDouble()+s.toDouble()).toString() else "UNAVAILABLE" }
    private fun maskLogin(value: String) = if (value.length > 4) "*".repeat(value.length - 4) + value.takeLast(4) else if (value.isBlank()) "UNAVAILABLE" else "****"
    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifBlank { "unknown" }
    private fun dateKey() = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    private fun utcNow() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
