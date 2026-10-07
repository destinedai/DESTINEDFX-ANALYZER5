package com.example.domain.model

enum class OBType { BULLISH, BEARISH }

data class OrderBlock(
    val type: OBType,
    val topPrice: Double,
    val bottomPrice: Double,
    val validityScore: Int, // 0 to 100
    val mitigated: Boolean
)
