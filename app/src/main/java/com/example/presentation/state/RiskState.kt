package com.example.presentation.state

data class RiskState(
    val isLoading: Boolean = false,
    val maxDrawdownLimit: Double = 5.0,
    val currentDrawdown: Double = 0.0,
    val fixedRiskPerTrade: Double = 1.0,
    val isNfpShieldActive: Boolean = false,
    val themeMode: String = "System",
    val brokerServer: String = "FTMO-Live2",
    val terminalMode: String = "MT5 Gateway",
    val error: String? = null
)
