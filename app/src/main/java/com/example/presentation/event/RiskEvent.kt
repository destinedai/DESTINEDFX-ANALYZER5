package com.example.presentation.event

sealed class RiskEvent {
    data class MaxDrawdownChanged(val value: Double) : RiskEvent()
    data class RiskPerTradeChanged(val value: Double) : RiskEvent()
    object ToggleNfpShield : RiskEvent()
    data class ThemeChanged(val theme: String) : RiskEvent()
    data class TerminalModeChanged(val mode: String) : RiskEvent()
    data class BrokerServerChanged(val server: String) : RiskEvent()
    object SaveSettings : RiskEvent()
}
