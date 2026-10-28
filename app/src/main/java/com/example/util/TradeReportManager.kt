package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Generates only from authenticated broker ledger records; missing values remain UNAVAILABLE. */
object TradeReportManager {
    data class SyncResult(val generated: Int, val failed: Int, val paths: List<String>)
    fun sync(context: Context, records: List<JSONObject>): SyncResult {
        var generated=0; var failed=0; val paths=mutableListOf<String>()
        records.forEach { record ->
            val status=record.optString("status","UNKNOWN").uppercase(); if(status !in setOf("OPEN","CLOSED")) return@forEach
            val payload=record.optJSONObject("payload")?:JSONObject(); val account=payload.optJSONObject("account")?:JSONObject(); val mode=record.optString("account_mode","UNVERIFIED").uppercase(); val kind=if(status=="CLOSED") "FINAL_TRADE_REPORT" else "OPEN_TRADE"; val identity=safe(record.optString("identity","unknown")); val fileName="${kind}_${safe(mode)}_${accountHash(account)}_${identity}.pdf"
            try { paths += writePdf(context,record,fileName,mode,status); generated++ } catch(_:Exception) { failed++ }
        }; return SyncResult(generated,failed,paths)
    }
    private fun writePdf(context:Context,record:JSONObject,fileName:String,mode:String,status:String):String {
        val relative="${Environment.DIRECTORY_DOWNLOADS}/DestinedFX/TradeReports/$mode"; val registry=context.getSharedPreferences("destinedfx_trade_reports",Context.MODE_PRIVATE); val fingerprint=record.toString().hashCode().toString(); if(registry.getString(fileName,null)==fingerprint) return "$relative/$fileName"
        val document=PdfDocument(); var pageNo=1; var page=document.startPage(PdfDocument.PageInfo.Builder(612,792,pageNo).create()); var canvas=page.canvas; canvas.drawColor(Color.WHITE); val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=12f}; var y=36f
        fun newPage(){document.finishPage(page);pageNo++;page=document.startPage(PdfDocument.PageInfo.Builder(612,792,pageNo).create());canvas=page.canvas;canvas.drawColor(Color.WHITE);y=36f}
        fun line(label:String,value:String){"$label: $value".chunked(88).forEachIndexed{i,text->if(y>758f)newPage();canvas.drawText(if(i==0)text else "  $text",32f,y,paint);y+=18f}}
        val payload=record.optJSONObject("payload")?:JSONObject(); val account=payload.optJSONObject("account")?:JSONObject(); val signal=payload.optJSONObject("signal")?:JSONObject(); val deals=payload.optJSONArray("deals")?.let{a->(0 until a.length()).mapNotNull{a.optJSONObject(it)}}?:emptyList(); val deal=payload.optJSONObject("deal")?:deals.firstOrNull()?:JSONObject()
        line("DESTINEDFX ANALYZER",if(status=="CLOSED")"FINAL TRADE REPORT" else "OPEN TRADE"); line("Trade/report identity",record.optString("identity","UNAVAILABLE")); line("Account mode",mode.ifBlank{"UNAVAILABLE"}); line("Broker/company","${account.optString("company","UNAVAILABLE")} / ${account.optString("server","UNAVAILABLE")}"); line("Currency",account.optString("currency","UNAVAILABLE")); line("Account login",maskLogin(account.optString("login","UNAVAILABLE"))); line("Balance / equity","${account.optString("balance","UNAVAILABLE")} / ${account.optString("equity","UNAVAILABLE")} ${account.optString("currency","UNAVAILABLE")}"); line("Used/free margin","${account.optString("margin","UNAVAILABLE")} / ${account.optString("free_margin","UNAVAILABLE")}"); line("Margin level / leverage","${account.optString("margin_level","UNAVAILABLE")} / ${account.optString("leverage","UNAVAILABLE")}")
        line("Symbol / direction","${record.optString("symbol",signal.optString("symbol","UNAVAILABLE"))} / ${record.optString("direction",signal.optString("direction","UNAVAILABLE"))}"); line("Strategy / timeframe","${signal.optString("strategy","UNAVAILABLE")} / ${signal.optString("timeframe","UNAVAILABLE")}"); line("Order ticket",record.optString("order_ticket","UNAVAILABLE")); line("Deal ticket(s)",deals.joinToString(", "){it.optString("ticket","UNAVAILABLE")}); line("Position ID",record.optString("position_id","UNAVAILABLE")); line("Requested / executed volume","${signal.optString("requested_volume","UNAVAILABLE")} / ${signal.optString("executed_volume","UNAVAILABLE")}"); line("Order submitted (UTC)",signal.optString("submitted_at","UNAVAILABLE")); line("Actual broker fill time",signal.optString("opened_at",deal.optString("opened_at","UNAVAILABLE"))); line("Exit volume",sumField(deals,"volume")); line("Weighted average exit price",weightedAverage(deals,"price","volume")); line("Close time",record.optString("closed_at",deals.lastOrNull()?.optString("closed_at","UNAVAILABLE") ?: "UNAVAILABLE")); line("Signal entry",signal.optString("entry","UNAVAILABLE")); val actualEntry=signal.optString("actual_entry",deal.optString("entry_price","UNAVAILABLE")).takeIf{it.toDoubleOrNull()?.let{price->price.isFinite()&&price>0}==true} ?: "UNAVAILABLE"; line("Actual broker entry price",actualEntry); line("Stop loss / take profit","${signal.optString("stop_loss","UNAVAILABLE")} / ${signal.optString("take_profit","UNAVAILABLE")}"); line("Execution status",signal.optString("execution_state",status)); line("News verdict",signal.optString("news_detail","UNAVAILABLE"))
        if(status=="OPEN"){ val positions=payload.optJSONArray("positions"); val wanted=record.optString("position_id",signal.optString("position","")); val openPosition=positions?.let{a->(0 until a.length()).mapNotNull{a.optJSONObject(it)}.firstOrNull{it.optString("position_id","")==wanted}}; line("Current broker position volume",openPosition?.optString("volume","UNAVAILABLE") ?: "UNAVAILABLE"); line("Actual broker open price",openPosition?.optString("open_price","UNAVAILABLE") ?: signal.optString("actual_entry","UNAVAILABLE")); line("Floating/unrealized P/L",openPosition?.optString("floating_profit","UNAVAILABLE") ?: "UNAVAILABLE"); line("Open swap",openPosition?.optString("swap","UNAVAILABLE") ?: "UNAVAILABLE") } else { line("Gross realized P/L",sumField(deals,"profit")); line("Commission",sumField(deals,"commission")); line("Swap",sumField(deals,"swap")); line("Separate fees",sumField(deals,"fees")); line("Net realized P/L",net(deals)) }; line("Generated UTC",utcNow()); line("Unavailable values","UNAVAILABLE means the broker did not supply the field in the reconciled record.")
        document.finishPage(page); val result=save(context,document,fileName,mode,relative); document.close(); registry.edit().putString(fileName,fingerprint).apply(); return result
    }
    private fun save(context:Context,document:PdfDocument,fileName:String,mode:String,relative:String):String { if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){val r=context.contentResolver; val existing=r.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.MediaColumns._ID),"${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",arrayOf(fileName,"$relative/"),null)?.use{if(it.moveToFirst())Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,it.getLong(0).toString()) else null}; val uri=existing?:r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,fileName);put(MediaStore.MediaColumns.MIME_TYPE,"application/pdf");put(MediaStore.MediaColumns.RELATIVE_PATH,relative)})?:error("PDF storage unavailable"); r.openOutputStream(uri,"wt")?.use{document.writeTo(it)}?:error("PDF output unavailable"); return "$relative/$fileName"}; val dir=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"DestinedFX/TradeReports/$mode");if(!dir.exists())dir.mkdirs();val file=File(dir,fileName);FileOutputStream(file).use{document.writeTo(it)};return file.absolutePath }
    private fun sumField(deals:List<JSONObject>,key:String)=deals.map{it.optDouble(key,Double.NaN)}.takeIf{it.isNotEmpty()&&it.all(Double::isFinite)}?.sum()?.toString() ?:  "UNAVAILABLE"
    private fun weightedAverage(deals:List<JSONObject>,valueKey:String,weightKey:String):String{if(deals.isEmpty())return "UNAVAILABLE";val rows=deals.map{it.optDouble(valueKey,Double.NaN) to it.optDouble(weightKey,Double.NaN)};if(rows.any{!it.first.isFinite()||!it.second.isFinite()||it.second<=0})return "UNAVAILABLE";val weight=rows.sumOf{it.second};return if(weight>0)(rows.sumOf{it.first*it.second}/weight).toString() else "UNAVAILABLE"}
    private fun net(deals:List<JSONObject>):String{val p=sumField(deals,"profit");val c=sumField(deals,"commission");val s=sumField(deals,"swap");val f=sumField(deals,"fees");return if(listOf(p,c,s,f).all{it!="UNAVAILABLE"})(p.toDouble()+c.toDouble()+s.toDouble()+f.toDouble()).toString() else "UNAVAILABLE"}
    private fun accountHash(a:JSONObject)=MessageDigest.getInstance("SHA-256").digest(listOf("login","server","company","currency").joinToString("|"){a.optString(it,"UNAVAILABLE")}.toByteArray()).joinToString(""){ "%02x".format(it)}.take(12)
    private fun maskLogin(v:String)=if(v.length>4)"*".repeat(v.length-4)+v.takeLast(4) else if(v.isBlank())"UNAVAILABLE" else "****"
    private fun safe(v:String)=v.replace(Regex("[^A-Za-z0-9_-]"),"_").take(80).ifBlank{"unknown"}
    private fun utcNow()=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date())
}
