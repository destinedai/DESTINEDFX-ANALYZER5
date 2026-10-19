package com.example.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class DestinedFXScreen(val title: String, val icon: ImageVector, val tag: String) {
    object Strategies : DestinedFXScreen("Strategies", Icons.Default.Architecture, "nav_tab_strategies")
    object Analyzer : DestinedFXScreen("Analyzer", Icons.Default.ShowChart, "nav_tab_chart")
    object Calendar : DestinedFXScreen("Calendar", Icons.Default.CalendarMonth, "nav_tab_calendar")
    object Bot : DestinedFXScreen("Bot", Icons.Default.SmartToy, "nav_tab_bot")
    object Risk : DestinedFXScreen("Risk", Icons.Default.Calculate, "nav_tab_risk")
    object Settings : DestinedFXScreen("Settings", Icons.Default.Settings, "nav_tab_settings")

    companion object {
        val allScreens: List<DestinedFXScreen>
            get() = listOf(Analyzer, Strategies, Calendar, Bot, Risk, Settings)
    }
}
