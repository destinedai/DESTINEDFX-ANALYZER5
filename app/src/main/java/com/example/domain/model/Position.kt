package com.example.domain.model

enum class PositionStatus { OPEN, CLOSED, PENDING }

data class Position(
    val id: String,
    val signalId: String?,
    val symbol: String,
    val direction: SignalDirection,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val lotSize: Double,
    val status: PositionStatus,
    val currentProfit: Double,
    val openedAt: Long,
    val closedAt: Long? = null
)
