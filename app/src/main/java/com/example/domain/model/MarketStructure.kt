package com.example.domain.model

enum class StructureType { HH, HL, LH, LL, BOS, CHOCH, MSS }

data class MarketStructure(
    val type: StructureType,
    val timestamp: Long,
    val priceLevel: Double,
    val isConfirmed: Boolean
)
