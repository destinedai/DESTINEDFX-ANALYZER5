package com.example.domain.repository

import com.example.domain.model.Position
import com.example.domain.model.TradeSignal

data class AccountMetrics(
    val balance: Double,
    val equity: Double,
    val margin: Double,
    val freeMargin: Double,
    val marginLevel: Double,
    val currency: String,
    val leverage: Int = 100
)

interface MT5Repository {
    suspend fun loadSymbolSpec(symbol: String): Result<com.destinedfx.core.instrument.InstrumentSpec>
    suspend fun executeTrade(signal: TradeSignal, lotSize: Double, requestId: String): Result<String>
    suspend fun closeTrade(ticketId: String): Result<Boolean>
    suspend fun modifyTrade(ticketId: String, newSl: Double, newTp: Double): Result<Boolean>
    suspend fun getOpenPositions(): Result<List<Position>>
    suspend fun getAccountBalance(): Result<Double>
    suspend fun getAccountMetrics(): Result<AccountMetrics>
    suspend fun getBrokerQuote(symbol: String): Result<com.destinedfx.core.marketdata.MarketQuote>
    suspend fun calculateRequiredMargin(symbol: String, lotSize: Double): Result<Double>
    fun isConnected(): Boolean
}
