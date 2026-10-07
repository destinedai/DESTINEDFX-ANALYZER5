package com.example.presentation.state

data class AnalysisState(
    val isLoading: Boolean = false,
    val selectedSymbol: String = "XAU/USD",
    val selectedTimeframe: String = "H1",
    val selectedStrategy: String = "Smart Money Concepts (SMC)",
    val analysisResult: String? = null,
    val imageUri: String? = null,
    val error: String? = null
)
