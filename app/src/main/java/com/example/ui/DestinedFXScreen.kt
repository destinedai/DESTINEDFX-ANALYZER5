package com.example.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Native destinations mirroring the website's primary navigation, while retaining app-only
 * Risk and Bot workspaces and consolidating web account/help routes into Settings.
 */
sealed class DestinedFXScreen(val title: String, val icon: ImageVector, val tag: String) {
    object Home : DestinedFXScreen("Home", Icons.Default.Home, "nav_tab_home")
    object Analyzer : DestinedFXScreen("Analyzer", Icons.Default.ShowChart, "nav_tab_chart")
    object ImageAnalyzer : DestinedFXScreen("Image Analyzer", Icons.Default.Image, "nav_tab_image_analyzer")
    object Strategies : DestinedFXScreen("Strategies", Icons.Default.Architecture, "nav_tab_strategies")
    object Calendar : DestinedFXScreen("Calendar", Icons.Default.CalendarMonth, "nav_tab_calendar")
    object Bot : DestinedFXScreen("Bot", Icons.Default.SmartToy, "nav_tab_bot")
    object Risk : DestinedFXScreen("Risk", Icons.Default.Calculate, "nav_tab_risk")
    object Settings : DestinedFXScreen("Settings", Icons.Default.Settings, "nav_tab_settings")

    companion object {
        val allScreens: List<DestinedFXScreen>
            get() = listOf(Home, Analyzer, ImageAnalyzer, Strategies, Calendar, Bot, Risk, Settings)
    }
}
