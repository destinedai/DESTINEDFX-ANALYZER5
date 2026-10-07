package com.example.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.net.URLEncoder

class NotificationService(private val okHttpClient: OkHttpClient) {

    suspend fun sendTelegramMessage(botToken: String, chatId: String, message: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if(botToken.isBlank() || chatId.isBlank()) return@withContext false
                
                val url = "https://api.telegram.org/bot$botToken/sendMessage"
                val json = JSONObject()
                json.put("chat_id", chatId)
                json.put("text", message)
                
                val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()
                
                okHttpClient.newCall(request).execute().use { response ->
                   val result = response.isSuccessful
                   if(result) {
                       Log.d("DestinedFX_Notification", "Telegram message sent successfully.")
                   } else {
                       Log.e("DestinedFX_Notification", "Telegram error: ${response.body?.string()}")
                   }
                   result
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DestinedFX_Notification", "Telegram API error: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    suspend fun sendWhatsAppMessage(apiKey: String, phoneNumber: String, message: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if(apiKey.isBlank() || phoneNumber.isBlank()) return@withContext false
                
                // Emulating CallMeBot or standard WhatsApp gateway structure
                val cleanPhone = phoneNumber.replace("+", "").replace(" ", "")
                val encodedMsg = URLEncoder.encode(message, "UTF-8")
                val url = "https://api.callmebot.com/whatsapp.php?phone=$cleanPhone&text=$encodedMsg&apikey=$apiKey"
                
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                
                okHttpClient.newCall(request).execute().use { response ->
                    val result = response.isSuccessful
                    if(result) {
                        Log.d("DestinedFX_Notification", "WhatsApp message sent successfully.")
                    } else {
                        Log.e("DestinedFX_Notification", "WhatsApp error: ${response.body?.string()}")
                    }
                    result
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DestinedFX_Notification", "WhatsApp API error: ${e.javaClass.simpleName}")
                false
            }
        }
    }
}
