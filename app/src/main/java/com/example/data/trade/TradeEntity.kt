package com.example.data.trade

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trade_journal")
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val ticketId: String? = null,
    val symbol: String,
    val positionType: String, // "BUY" or "SELL"
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val rationale: String,
    val status: String, // "PENDING", "OPEN", "CLOSED"
    val pipsProfit: Int,
    val plAmount: Double,
    val lotSize: Double,
    val setupType: String = "SMC", // e.g. "SMC", "FVG", "OB"
    val timestamp: Long = System.currentTimeMillis(),
    val closeTimestamp: Long? = null
)
