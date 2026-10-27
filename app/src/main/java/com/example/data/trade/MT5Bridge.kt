package com.example.data.trade

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

class MT5Bridge {

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _walletBalance = MutableStateFlow(0.0)
    val walletBalance: StateFlow<Double> = _walletBalance

    private val _equity = MutableStateFlow(0.0)
    val equity: StateFlow<Double> = _equity

    private val _margin = MutableStateFlow(0.0)
    val margin: StateFlow<Double> = _margin

    private val _freeMargin = MutableStateFlow(0.0)
    val freeMargin: StateFlow<Double> = _freeMargin

    private val _marginLevel = MutableStateFlow(0.0)
    val marginLevel: StateFlow<Double> = _marginLevel

    private var gatewayClient: MT5GatewayClient? = null
    private var connectionDeferred: CompletableDeferred<Boolean>? = null
    private var currentGatewayUrl: String = ""

    suspend fun connect(
        broker: String,
        loginId: String,
        pass: String,
        gatewayUrl: String = ""
    ): Boolean {
        currentGatewayUrl = gatewayUrl.trim()
        _isConnected.value = false
        _walletBalance.value = 0.0
        _equity.value = 0.0
        _margin.value = 0.0
        _freeMargin.value = 0.0
        _marginLevel.value = 0.0
        if (currentGatewayUrl.isBlank()) {
            _isConnected.value = false
            return false
        }
        connectionDeferred = CompletableDeferred()
        gatewayClient?.disconnect()
        
        val client = MT5GatewayClient(object : MT5GatewayClient.MT5DataListener {
            override fun onBalanceUpdate(balance: Double, equity: Double, margin: Double, freeMargin: Double, marginLevel: Double) {
                _walletBalance.value = balance
                _equity.value = equity
                _margin.value = margin
                _freeMargin.value = freeMargin
                _marginLevel.value = marginLevel
            }

            override fun onPriceUpdate(symbol: String, bid: Double, ask: Double) {}

            override fun onError(message: String) {
                Log.e("MT5Bridge", "MT5 gateway error: $message")
                connectionDeferred?.complete(false)
                _isConnected.value = false
            }

            override fun onConnected() {
                connectionDeferred?.complete(true)
                _isConnected.value = true
            }

            override fun onDisconnected() {
                _isConnected.value = false
            }
        })

        gatewayClient = client
        client.connectToLiveMT5(gatewayUrl, loginId, pass, broker)

        return try {
            withTimeout(6000) {
                connectionDeferred?.await() ?: false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun disconnect() {
        gatewayClient?.disconnect()
        gatewayClient = null
        currentGatewayUrl = ""
        _isConnected.value = false
    }

    suspend fun executeTrade(symbol: String, lotSize: Double, type: String, stopLoss: Double, takeProfit: Double, requestId: String = "REQ-" + java.util.UUID.randomUUID()): Result<String> {
        if (!_isConnected.value) return Result.failure(Exception("Broker not connected"))
        
        if (currentGatewayUrl.isEmpty()) {
            return Result.failure(Exception("Broker not connected"))
        }
        
        val mappedAction = if (type.equals("Long", ignoreCase = true) || type.equals("BUY", ignoreCase = true)) "BUY" else "SELL"
        
        val confirmationDeferred = CompletableDeferred<Result<String>>()
        
        gatewayClient?.executeMarketOrderWithConfirmation(requestId, symbol, mappedAction, lotSize, stopLoss, takeProfit) { success, orderId, error ->
            if (success) {
                confirmationDeferred.complete(if (orderId != null) Result.success(orderId) else Result.failure(Exception("Broker accepted but returned no order id: state unknown, reconcile")))
            } else {
                confirmationDeferred.complete(Result.failure(Exception(error ?: "Broker rejected execution")))
            }
        }

        return try {
            withTimeout(10000) {
                confirmationDeferred.await()
            }
        } catch (e: Exception) {
            Result.failure(Exception("Broker execution confirmation timeout"))
        }
    }
}
