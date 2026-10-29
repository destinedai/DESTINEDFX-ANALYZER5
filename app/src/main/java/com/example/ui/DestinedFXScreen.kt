package com.example.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The six core workspaces stay directly accessible in the bottom navigation.
 * Home and secondary utilities remain reachable from the header's More workspaces menu.
 */
sealed class DestinedFXScreen(val title: String, val icon: ImageVector, val tag: String) {
    object Home : DestinedFXScreen("Home", Icons.Default.Home, "nav_tab_home")
    object Analyzer : DestinedFXScreen("Analyzer", Icons.Default.ShowChart, "nav_tab_chart")
    object ImageAnalyzer : DestinedFXScreen("Image Analyzer", Icons.Default.Image, "nav_tab_image_analyzer")
    object Strategies : DestinedFXScreen("Strategies", Icons.Default.Architecture, "nav_tab_strategies")
    object Calendar : DestinedFXScreen("Calendar", Icons.Default.CalendarMonth, "nav_tab_calendar")
    object Bot : DestinedFXScreen("Bot", Icons.Default.SmartToy, "nav_tab_bot")
    object Watchlist : DestinedFXScreen("Watchlist", Icons.Default.BookmarkBorder, "nav_tab_watchlist")
    object Chat : DestinedFXScreen("Chat", Icons.Default.Chat, "nav_tab_chat")
    object More : DestinedFXScreen("More", Icons.Default.MoreHoriz, "nav_tab_more")
    object Risk : DestinedFXScreen("Risk", Icons.Default.Calculate, "nav_tab_risk")
    object Settings : DestinedFXScreen("Settings", Icons.Default.Settings, "nav_tab_settings")
    object About : DestinedFXScreen("About & Disclosure", Icons.Default.Info, "nav_tab_about")
    object Contact : DestinedFXScreen("Contact", Icons.Default.Email, "nav_tab_contact")

    companion object {
        val primaryScreens: List<DestinedFXScreen>
            get() = listOf(Analyzer, Strategies, Calendar, Bot, Risk, Settings)

        val moreScreens: List<DestinedFXScreen>
            get() = listOf(Home, ImageAnalyzer, Watchlist, Chat, About, Contact)

        val allScreens: List<DestinedFXScreen>
            get() = listOf(Home, Analyzer, Strategies, Calendar, More, Chat, ImageAnalyzer, Bot, Watchlist, Risk, Settings, About, Contact)
    }
}
