package com.example.data.trade

import android.util.Log
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MT5GatewayClient(private val callback: MT5DataListener) {

    interface MT5DataListener {
        fun onBalanceUpdate(balance: Double, equity: Double, margin: Double, freeMargin: Double, marginLevel: Double)
        fun onPriceUpdate(symbol: String, bid: Double, ask: Double)
        fun onError(message: String)
        fun onConnected()
        fun onDisconnected()
    }

    private val TAG = "MT5Gateway"
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val pending = com.destinedfx.core.execution.PendingRequests()

    /** No supported direct MT5 WebSocket authentication protocol is configured in this app. */
    fun connectToLiveMT5(gatewayUrl: String, accountId: String, passwordText: String, server: String) {
        callback.onError("CONFIGURATION_REQUIRED: direct MT5 WebSocket authentication is unsupported; configure the MetaAPI integration.")
    }
    private fun authenticateAccount(accountId: String, passwordText: String, server: String) {
        try {
            val authJson = JSONObject().apply {
                put("action", "CONNECT_LIVE")
                put("login", accountId)
                put("password", passwordText)
                put("server", server)
            }
            webSocket?.send(authJson.toString())   // password is sent once over WSS and never logged
        } catch (e: org.json.JSONException) { callback.onError("Auth payload error") }
    }

    private fun parseIncomingLivePayload(payload: String) {
        try {
            val data = JSONObject(payload)
            val type = data.optString("type")

            if ("ACCOUNT_UPDATE" == type) {
                val balance = data.optDouble("balance", 0.0)
                val equity = data.optDouble("equity", 0.0)
                val margin = data.optDouble("margin", 0.0)
                val freeMargin = data.optDouble("freeMargin", 0.0)
                val marginLevel = data.optDouble("marginLevel", 0.0)
                callback.onBalanceUpdate(balance, equity, margin, freeMargin, marginLevel)
            } else if ("TRADE_CONFIRMATION" == type || "ORDER_RESULT" == type) {
                val rid = data.optString("requestId", "")
                if (rid.isBlank()) return            // un-correlated reply: never applied to any order
                val success = data.has("success") && data.getBoolean("success")   // absent => NOT success
                val res = com.destinedfx.core.execution.ExecResult(rid, if (success) com.destinedfx.core.execution.ExecStatus.ACCEPTED else com.destinedfx.core.execution.ExecStatus.REJECTED,
                    orderId = data.optString("orderId").ifBlank { null }, positionId = data.optString("positionId").ifBlank { null }, error = data.optString("error").ifBlank { null })
                pending.complete(rid, res)
            }
        } catch (e: org.json.JSONException) { callback.onError("Malformed gateway message") }
    }

    /** Every order carries its own requestId; the broker must echo it. Concurrent orders cannot cross-complete. */
    fun executeMarketOrderWithConfirmation(requestId: String, symbol: String, action: String, volume: Double, stopLoss: Double, takeProfit: Double,
                                           onResult: (Boolean, String?, String?) -> Unit) {
        val deferred = try { pending.register(requestId) } catch (e: IllegalStateException) { onResult(false, null, "Duplicate requestId"); return }
        val order = JSONObject().apply { put("action", "TRADE"); put("requestId", requestId); put("symbol", symbol); put("orderType", action)
            put("volume", volume); put("stopLoss", stopLoss); put("takeProfit", takeProfit) }
        if (webSocket?.send(order.toString()) != true) { pending.complete(requestId, com.destinedfx.core.execution.ExecResult(requestId, com.destinedfx.core.execution.ExecStatus.DISCONNECTED, error = "WebSocket not connected"))
            onResult(false, null, "BROKER DISCONNECTED"); return }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val r = pending.await(requestId, deferred, 15_000)
            onResult(r.status == com.destinedfx.core.execution.ExecStatus.ACCEPTED, r.orderId, r.error ?: if (r.status != com.destinedfx.core.execution.ExecStatus.ACCEPTED) r.status.name else null)
        }
    }

    fun disconnect() {
        if (webSocket != null) {
            webSocket?.close(1000, "User terminated session")
            webSocket = null
        }
    }
}
