package com.example.domain.model

data class Candle(
    val timestamp: Long, // Epoch milliseconds in UTC
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val complete: Boolean = true
)
