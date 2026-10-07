package com.example.domain.model

enum class LiquidityType { EQH, EQL, BSL, SSL }

data class LiquidityZone(
    val type: LiquidityType,
    val priceLevel: Double,
    val isSwept: Boolean,
    val sweptTimestamp: Long? = null
)
