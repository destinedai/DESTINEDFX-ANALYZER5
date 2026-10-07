package com.example.domain.model

enum class FVGType { BULLISH, BEARISH }
enum class Strength { WEAK, MEDIUM, STRONG }

data class FairValueGap(
    val type: FVGType,
    val topPrice: Double,
    val bottomPrice: Double,
    val strength: Strength,
    val mitigated: Boolean
)
